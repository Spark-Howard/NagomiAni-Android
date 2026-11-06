package com.sparkhoward.nagomiani.core.watchparty

import com.sparkhoward.nagomiani.core.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 一起看房间连接（OkHttp WebSocket）。只管传输：连接/自动重连/收发 wire 消息/测 RTT，
 * 同步决策与播放器操作不在这里（PlayerViewModel 消费事件流做裁决）。
 * 协议层 pingInterval 保活 NAT；应用层 ping 顺带测 RTT（落位补偿用）。
 */
class RoomClient(private val scope: CoroutineScope) {

    enum class Role { HOST, GUEST }

    sealed interface Event {
        /** welcome：入房成功（自己 id、房主、成员表）。重连成功也会走到这里 */
        data class Welcome(val selfId: String, val hostId: String, val members: List<PartyMember>) : Event

        /** 成员表/房主变化 */
        data class Members(val hostId: String, val members: List<PartyMember>) : Event

        /** 房主播放状态（relay 不回显给发送者，只有 guest 会收到） */
        data class HostState(val state: PartyWire.State) : Event

        /** guest 请求对齐（房主消费：立刻广播一次状态） */
        data object SyncRequested : Event

        data class Chat(val nick: String, val text: String) : Event

        /** 连接中断（自动重连已排程） */
        data class Disconnected(val reason: String) : Event
    }

    enum class Phase { IDLE, CONNECTING, JOINED, RECONNECTING, FAILED }

    data class ConnectionState(
        val phase: Phase = Phase.IDLE,
        val roomCode: String = "",
        val selfId: String = "",
        val hostId: String = "",
        val members: List<PartyMember> = emptyList(),
        val error: String? = null,
    ) {
        val role: Role get() = if (selfId.isNotEmpty() && selfId == hostId) Role.HOST else Role.GUEST
        val nick: String get() = members.firstOrNull { it.id == selfId }?.nick ?: ""
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** 最近一次应用层 ping 往返毫秒（0=未知）；落位补偿用 */
    @Volatile
    var rttMs: Long = 0
        private set

    private var ws: WebSocket? = null
    private var baseUrl = ""
    private var nick = ""
    private var wantConnected = false
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var lastPingSentAt = 0L

    /** 独立 WS client + 15s 协议层心跳。强制直连（NO_PROXY）：一起看连的是局域网房主手机或
     *  用户自填的中继，走应用内代理会被隧道到代理机器上（目标端口在手机/局域网，代理侧必然
     *  连不通，真机复现为 unexpected end of stream）；继承 Http.client 是要保留其 DNS/超时基线 */
    private val wsClient: OkHttpClient = Http.client.newBuilder()
        .pingInterval(15, TimeUnit.SECONDS)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    /** 创建/加入房间：URL 允许带或不带 ws:// 前缀；同码重复调用会先干净断开旧连接 */
    fun connect(serverUrl: String, roomCode: String, nick: String) {
        close("reconnect")
        wantConnected = true
        this.nick = nick.trim().ifBlank { "路人" }
        attempts = 0
        val base = serverUrl.trim().trimEnd('/')
        baseUrl = if (base.startsWith("ws://") || base.startsWith("wss://")) base else "ws://$base"
        _state.value = ConnectionState(phase = Phase.CONNECTING, roomCode = roomCode)
        open()
    }

    fun sendState(msg: PartyWire.State) {
        send(msg)
    }

    fun sendChat(text: String) {
        send(PartyWire.Chat(nick = _state.value.nick.ifBlank { nick }, text = text.take(200)))
    }

    /** 入房/重连后向房主要一次最新状态 */
    fun requestSync() {
        send(PartyWire.Sync(at = System.currentTimeMillis()))
    }

    fun close(reason: String = "leave") {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        ws?.close(1000, reason)
        ws = null
        _state.value = ConnectionState(phase = Phase.IDLE)
    }

    private fun open() {
        val request = Request.Builder().url("$baseUrl/room/${_state.value.roomCode}").build()
        ws = wsClient.newWebSocket(request, listener)
    }

    private fun send(msg: PartyWire): Boolean {
        val socket = ws ?: return false
        return runCatching { socket.send(PartyWireCodec.encode(msg)) }.getOrDefault(false)
    }

    /** 应用层心跳：15s 一发，relay 单播回 pong → rttMs */
    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(15_000)
                lastPingSentAt = System.currentTimeMillis()
                send(PartyWire.Ping(at = lastPingSentAt))
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!wantConnected) return
        _events.tryEmit(Event.Disconnected(reason))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            attempts++
            _state.value = _state.value.copy(
                phase = Phase.RECONNECTING,
                error = "连接中断，重连中（第 $attempts 次）…",
            )
            // 指数退避 1/2/4/8/16s，封顶 30s
            delay(minOf(30_000L, 1_000L shl (attempts - 1).coerceAtMost(5)))
            if (wantConnected) open()
        }
    }

    private val listener = object : WebSocketListener() {
        // 回调与 connect() 重连存在竞态：close(1000) 是异步的，旧连接的 onOpen/onClosed 可能
        // 在新连接建立后才到达——非当前 socket 一律忽略，否则会产生幽灵成员/重复重连

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) {
                webSocket.cancel()
                return
            }
            attempts = 0
            webSocket.send(PartyWireCodec.encode(PartyWire.Hello(room = _state.value.roomCode, nick = nick)))
            startPing()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            when (val msg = PartyWireCodec.decode(text)) {
                is PartyWire.Welcome -> {
                    _state.value = _state.value.copy(
                        phase = Phase.JOINED,
                        selfId = msg.you,
                        hostId = msg.host,
                        members = msg.members,
                        error = null,
                    )
                    _events.tryEmit(Event.Welcome(msg.you, msg.host, msg.members))
                    // 入房/重连成功：向房主要一次最新状态对齐（房主侧 requestSync 是空操作）
                    requestSync()
                }

                is PartyWire.Members -> {
                    _state.value = _state.value.copy(hostId = msg.host, members = msg.members)
                    _events.tryEmit(Event.Members(msg.host, msg.members))
                }

                is PartyWire.State -> _events.tryEmit(Event.HostState(msg))
                is PartyWire.Sync -> _events.tryEmit(Event.SyncRequested)
                is PartyWire.Chat -> _events.tryEmit(Event.Chat(msg.nick, msg.text))
                is PartyWire.Pong -> {
                    val rtt = System.currentTimeMillis() - msg.at
                    if (rtt in 0..60_000) rttMs = rtt
                }

                else -> Unit // full 等未建模消息忽略
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws || !wantConnected) return
            if (_state.value.phase == Phase.CONNECTING) {
                // 首连即失败（地址错/服务没起）：不无限重试，交还用户处理
                _state.value = _state.value.copy(
                    phase = Phase.FAILED,
                    error = "连接失败：${t.message ?: "未知错误"}",
                )
                wantConnected = false
                return
            }
            scheduleReconnect(t.message ?: "连接中断")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws || !wantConnected) return
            // 4002=房间满员：明确失败，不重连（重连还是满）
            if (code == 4002) {
                _state.value = _state.value.copy(phase = Phase.FAILED, error = "房间已满（16 人上限）")
                wantConnected = false
                return
            }
            scheduleReconnect("连接已断开")
        }
    }

    companion object {
        /** 自建中继默认地址：部署后填「wss://你的域名」（空=App 内每次手动填写） */
        const val DEFAULT_RELAY_URL = ""
    }
}
