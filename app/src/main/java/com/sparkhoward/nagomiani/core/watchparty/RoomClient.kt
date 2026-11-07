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

/** 传输通道：RoomClient 之上的协议状态机不感知底层是 WS 中继还是 MQTT 公共节点 */
internal interface WireChannel {
    fun open()
    fun send(text: String): Boolean
    fun close()
}

internal interface Bridge {
    fun onOpen(ch: WireChannel)
    fun onMessage(ch: WireChannel, text: String)
    fun onDown(ch: WireChannel, t: Throwable?, closeCode: Int)
}

/**
 * 一起看房间连接。只管传输与会话状态机：连接/自动重连/收发 wire 消息/测 RTT，
 * 同步决策与播放器操作不在这里（PlayerViewModel 消费事件流做裁决）。
 * 双通道，按服务器地址自动选择：
 * - 留空 / mqtt(s):// → [MqttChannel]（公共 MQTT 节点，免部署，异地同看零配置）
 * - ws(s):// → OkHttp WebSocket 连自建中继（server/ 目录，功能最全：房间满员判定等）
 * 两条通道对上层呈现同一套 welcome/members/state 语义（成员与房主由 relay 分配，
 * 或 MQTT 模式下经 presence 自组织——见 PresenceTable/MqttChannel）。
 */
class RoomClient(private val scope: CoroutineScope) {

    enum class Role { HOST, GUEST }

    sealed interface Event {
        /** welcome：入房成功（自己 id、房主、成员表）。重连成功也会走到这里 */
        data class Welcome(val selfId: String, val hostId: String, val members: List<PartyMember>) : Event

        /** 成员表/房主变化 */
        data class Members(val hostId: String, val members: List<PartyMember>) : Event

        /** 房主播放状态（发送者本人不会收到自己的广播） */
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

    private var channel: WireChannel? = null
    private var endpoint: Endpoint = Endpoint(mqtt = true, uri = DEFAULT_MQTT_BROKER)
    private var nick = ""
    private var wantConnected = false
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null

    private data class Endpoint(val mqtt: Boolean, val uri: String)

    /** 独立 WS client + 15s 协议层心跳。强制直连（NO_PROXY）：一起看连的是局域网房主手机或
     *  用户自填的中继，走应用内代理会被隧道到代理机器上（目标端口在手机/局域网，代理侧必然
     *  连不通，真机复现为 unexpected end of stream）；继承 Http.client 是要保留其 DNS/超时基线 */
    private val wsClient: OkHttpClient = Http.client.newBuilder()
        .pingInterval(15, TimeUnit.SECONDS)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    /** 创建/加入房间。服务器地址：留空=公共 MQTT 节点；mqtt(s)://=自定 MQTT；ws(s)://=自建中继；
     *  裸 host:port 按 MQTT 处理。同码重复调用会先干净断开旧连接 */
    fun connect(serverUrl: String, roomCode: String, nick: String) {
        close("reconnect")
        wantConnected = true
        this.nick = nick.trim().ifBlank { "路人" }
        attempts = 0
        endpoint = resolveEndpoint(serverUrl)
        _state.value = ConnectionState(phase = Phase.CONNECTING, roomCode = roomCode)
        openChannel()
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
        channel?.close()
        channel = null
        _state.value = ConnectionState(phase = Phase.IDLE)
    }

    private fun resolveEndpoint(raw: String): Endpoint {
        val s = raw.trim().trimEnd('/')
        return when {
            s.isEmpty() -> Endpoint(mqtt = true, uri = DEFAULT_MQTT_BROKER)
            s.startsWith("mqtt://") -> Endpoint(mqtt = true, uri = "tcp://" + s.removePrefix("mqtt://"))
            s.startsWith("mqtts://") -> Endpoint(mqtt = true, uri = "ssl://" + s.removePrefix("mqtts://"))
            s.startsWith("ws://") || s.startsWith("wss://") -> Endpoint(mqtt = false, uri = s)
            else -> Endpoint(mqtt = true, uri = "tcp://$s")
        }
    }

    private fun openChannel() {
        val bridge = object : Bridge {
            override fun onOpen(ch: WireChannel) = handleOpen(ch)
            override fun onMessage(ch: WireChannel, text: String) = handleMessage(ch, text)
            override fun onDown(ch: WireChannel, t: Throwable?, closeCode: Int) = handleDown(ch, t, closeCode)
        }
        val ch: WireChannel = if (endpoint.mqtt) {
            MqttChannel(endpoint.uri, _state.value.roomCode, nick, bridge)
        } else {
            WsChannel(wsClient, endpoint.uri, _state.value.roomCode, bridge)
        }
        channel = ch
        runCatching { ch.open() }.onFailure { handleDown(ch, it, 0) }
    }

    private fun send(msg: PartyWire): Boolean {
        val ch = channel ?: return false
        return runCatching { ch.send(PartyWireCodec.encode(msg)) }.getOrDefault(false)
    }

    /** 应用层心跳：15s 一发；WS relay 单播回 pong，MQTT 模式本机回环应答 → rttMs */
    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(15_000)
                send(PartyWire.Ping(at = System.currentTimeMillis()))
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
            if (wantConnected) openChannel()
        }
    }

    // MARK: - 通道回调（ch !== channel 的是旧通道迟到回调，一律忽略防幽灵）

    private fun handleOpen(ch: WireChannel) {
        if (ch !== channel) return
        attempts = 0
        send(PartyWire.Hello(room = _state.value.roomCode, nick = nick))
        startPing()
    }

    private fun handleMessage(ch: WireChannel, text: String) {
        if (ch !== channel) return
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

            else -> Unit // full/presence 等通道内部消息忽略
        }
    }

    private fun handleDown(ch: WireChannel, t: Throwable?, closeCode: Int) {
        if (ch !== channel || !wantConnected) return
        // 4002=房间满员：明确失败，不重连（重连还是满）
        if (closeCode == 4002) {
            _state.value = _state.value.copy(phase = Phase.FAILED, error = "房间已满（16 人上限）")
            wantConnected = false
            return
        }
        if (_state.value.phase == Phase.CONNECTING) {
            // 首连即失败（地址错/服务没起/网络不通）：不无限重试，交还用户处理
            _state.value = _state.value.copy(
                phase = Phase.FAILED,
                error = "连接失败：${t?.message ?: "未知错误"}",
            )
            wantConnected = false
            return
        }
        scheduleReconnect(t?.message ?: "连接中断")
    }

    companion object {
        /** 自建 WS 中继默认地址：部署后填「wss://你的域名」（空=App 内每次手动填写） */
        const val DEFAULT_RELAY_URL = ""

        /** 公共 MQTT 节点（EMQX 官方免费节点，免注册；仅转发 KB 级房间消息）。
         *  想自持化：任选 MQTT broker（EMQX/NanoMQ…）部署后填 mqtt://你的地址 */
        const val DEFAULT_MQTT_BROKER = "tcp://broker.emqx.io:1883"
    }
}

/** OkHttp WebSocket 通道（连自建 server/ 中继） */
internal class WsChannel(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val roomCode: String,
    private val bridge: Bridge,
) : WireChannel {

    private var ws: WebSocket? = null

    private val listener = object : WebSocketListener() {
        // 回调与重连存在竞态：close(1000) 是异步的，旧连接的回调可能在新连接建立后才到达
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) {
                webSocket.cancel()
                return
            }
            bridge.onOpen(this@WsChannel)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            bridge.onMessage(this@WsChannel, text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            bridge.onDown(this@WsChannel, t, 0)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            bridge.onDown(this@WsChannel, null, code)
        }
    }

    override fun open() {
        ws = client.newWebSocket(Request.Builder().url("$baseUrl/room/$roomCode").build(), listener)
    }

    override fun send(text: String): Boolean = ws?.send(text) ?: false

    override fun close() {
        ws?.close(1000, "leave")
        ws = null
    }
}
