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

    /** qos：MQTT 生效（关键消息 1，常规 0）；WS 通道忽略（自带可靠序） */
    fun send(text: String, qos: Int = 0): Boolean
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
        /** CONNECTING 期间正在尝试的候选节点（1 基；UI 显示「节点 i/n」） */
        val nodeIndex: Int = 0,
        val nodeCount: Int = 0,
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

    /** 当前通道；赋值发生在 Default 线程，读在 paho/主线程——@Volatile 保可见性，
     *  回调一律用 `ch !== channel` 拒绝旧通道 */
    @Volatile private var channel: WireChannel? = null
    /** 候选节点（留空地址=国内优先+全球兜底）与胜出者下标 */
    private var endpoints: List<Endpoint> = listOf(Endpoint(mqtt = true, uri = DEFAULT_MQTT_ENDPOINTS.first()))
    private var endpointIndex = 0
    private var nick = ""
    private var hostHint = false
    private var wantConnected = false
    /** 连接代际：新 connect/close 使 in-flight 的通道构造作废（防慢路径交错赋值） */
    @Volatile private var connectGen = 0L
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var connectingGuard: Job? = null
    /** 竞速期：并行打开的候选通道（首个 onOpen 者胜出并接管为 channel） */
    private data class RaceEntry(val index: Int, val ch: WireChannel)
    @Volatile private var race: List<RaceEntry>? = null
    /** 最近一次收到对端消息的时刻（JOINED 静默看门狗用；0=尚未收到） */
    @Volatile private var lastIncomingAt = 0L

    private data class Endpoint(val mqtt: Boolean, val uri: String)

    /** 独立 WS client + 15s 协议层心跳。强制直连（NO_PROXY）：一起看连的是局域网房主手机或
     *  用户自填的中继，走应用内代理会被隧道到代理机器上（目标端口在手机/局域网，代理侧必然
     *  连不通，真机复现为 unexpected end of stream）；继承 Http.client 是要保留其 DNS/超时基线 */
    private val wsClient: OkHttpClient = Http.client.newBuilder()
        .pingInterval(15, TimeUnit.SECONDS)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    /** 创建/加入房间。服务器地址：留空=公共 MQTT 节点（国内优先、全球兜底，首连失败自动切换）；
     *  mqtt(s)://=自定 MQTT；ws(s)://=自建中继；裸 host:port 按 MQTT 处理。
     *  同码重复调用会先干净断开旧连接。
     *  [hostHint]=创建方：MQTT 模式下使用房间码派生的稳定 id 并恒自任房主（断线重连无缝夺回）。
     *  真机 ANR 教训：paho 的客户端构造/disconnectForcibly 在弱网下会阻塞秒级——
     *  通道的关旧/建新全程离主线程，本函数只做状态翻转后即刻返回 */
    fun connect(serverUrl: String, roomCode: String, nick: String, hostHint: Boolean = false) {
        val gen = ++connectGen
        wantConnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        this.nick = nick.trim().ifBlank { "路人" }
        this.hostHint = hostHint
        attempts = 0
        endpoints = resolveEndpoints(serverUrl)
        endpointIndex = 0
        _state.value = ConnectionState(
            phase = Phase.CONNECTING,
            roomCode = roomCode,
            nodeIndex = endpoints.size,
            nodeCount = endpoints.size,
        )
        android.util.Log.i(
            LOG_TAG,
            "connect room=$roomCode host=$hostHint nodes=${endpoints.map { it.uri }}",
        )
        buildAndRace(gen)
        scheduleConnectingGuard(gen)
    }

    fun sendState(msg: PartyWire.State) {
        // 房主权威状态是同步的生命线：QoS1 保证送达（重复无害，guest 以最新状态覆盖）
        send(msg, qos = 1)
    }

    fun sendChat(text: String) {
        // 聊天丢失比重复更伤观感：同样 QoS1
        send(PartyWire.Chat(nick = _state.value.nick.ifBlank { nick }, text = text.take(200)), qos = 1)
    }

    /** 入房/重连后向房主要一次最新状态 */
    fun requestSync() {
        send(PartyWire.Sync(at = System.currentTimeMillis()))
    }

    fun close(reason: String = "leave") {
        connectGen++ // in-flight 通道构造作废
        wantConnected = false
        connectingGuard?.cancel()
        connectingGuard = null
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        val r = race
        race = null
        val olds = (r?.map { it.ch } ?: emptyList()).plus(listOfNotNull(channel))
        channel = null
        // disconnectForcibly 可能阻塞秒级，绝不碰主线程
        olds.forEach { prev -> scope.launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { prev.close() } } }
        _state.value = ConnectionState(phase = Phase.IDLE)
    }

    /** 地址 → 候选节点列表（首连按序尝试，失败自动切下一个） */
    private fun resolveEndpoints(raw: String): List<Endpoint> {
        val s = raw.trim().trimEnd('/')
        val single = { mqtt: Boolean, uri: String -> listOf(Endpoint(mqtt, uri)) }
        return when {
            s.isEmpty() -> DEFAULT_MQTT_ENDPOINTS.map { Endpoint(mqtt = true, uri = it) }
            s.startsWith("mqtt://") -> single(true, "tcp://" + s.removePrefix("mqtt://"))
            s.startsWith("mqtts://") -> single(true, "ssl://" + s.removePrefix("mqtts://"))
            s.startsWith("ws://") || s.startsWith("wss://") -> single(false, s)
            else -> single(true, "tcp://$s")
        }
    }

    private val bridge = object : Bridge {
        override fun onOpen(ch: WireChannel) = handleOpen(ch)
        override fun onMessage(ch: WireChannel, text: String) = handleMessage(ch, text)
        override fun onDown(ch: WireChannel, t: Throwable?, closeCode: Int) = handleDown(ch, t, closeCode)
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
            if (wantConnected) {
                val gen = ++connectGen
                buildSingle(gen)
            }
        }
    }

    /** 重连专用：沿用胜出节点的单通道重建（离主线程） */
    private fun buildSingle(gen: Long) {
        val old = channel
        channel = null
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            old?.let { runCatching { it.close() } }
            if (gen != connectGen) return@launch
            val ep = endpoints[endpointIndex.coerceIn(endpoints.indices)]
            android.util.Log.i(LOG_TAG, "reconnect endpoint=${ep.uri} attempt=$attempts")
            val ch: WireChannel = if (ep.mqtt) {
                MqttChannel(ep.uri, _state.value.roomCode, nick, hostHint, bridge)
            } else {
                WsChannel(wsClient, ep.uri, _state.value.roomCode, bridge)
            }
            if (gen != connectGen) {
                runCatching { ch.close() }
                return@launch
            }
            channel = ch
            runCatching { ch.open() }.onFailure { handleDown(ch, it, 0) }
        }
    }

    /** 首连竞速：并行打开全部候选节点（彼此错开小延时，略偏向排序靠前的节点），
     *  最先完成握手者胜出接管，其余立即关闭。总时长 = 最快节点的握手耗时，而非逐个累加 */
    private fun buildAndRace(gen: Long) {
        val old = channel
        val oldRace = race
        channel = null
        race = null
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            (oldRace?.map { it.ch } ?: emptyList()).plus(listOfNotNull(old)).forEach {
                runCatching { it.close() }
            }
            if (gen != connectGen) return@launch
            val entries = endpoints.mapIndexed { index, ep ->
                val ch: WireChannel = if (ep.mqtt) {
                    MqttChannel(ep.uri, _state.value.roomCode, nick, hostHint, bridge)
                } else {
                    WsChannel(wsClient, ep.uri, _state.value.roomCode, bridge)
                }
                RaceEntry(index, ch)
            }
            if (gen != connectGen) {
                entries.forEach { runCatching { it.ch.close() } }
                return@launch
            }
            race = entries
            entries.forEachIndexed { index, entry ->
                scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    if (index > 0) delay(index * RACE_STAGGER_MS)
                    if (gen != connectGen || race?.any { it.ch === entry.ch } != true) {
                        runCatching { entry.ch.close() }
                        return@launch
                    }
                    android.util.Log.i(LOG_TAG, "race open ${index + 1}/${endpoints.size} ${endpoints[index].uri}")
                    runCatching { entry.ch.open() }.onFailure { handleDown(entry.ch, it, 0) }
                }
            }
        }
    }

    /** CONNECTING 看门狗：paho 的 connectionTimeout 不覆盖 DNS 挂起等场景，
     *  超时仍 CONNECTING（竞速无胜者/胜者未入房）就明确 FAILED */
    private fun scheduleConnectingGuard(gen: Long) {
        connectingGuard?.cancel()
        connectingGuard = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            if (gen == connectGen && wantConnected && _state.value.phase == Phase.CONNECTING) {
                failConnecting("连接超时（${CONNECTING_TIMEOUT_MS / 1000}s）")
            }
        }
    }

    /** CONNECTING 阶段的统一失败出口：清掉竞速/当前通道，给出带候选数的明确错误 */
    private fun failConnecting(reason: String) {
        if (!wantConnected || _state.value.phase != Phase.CONNECTING) return
        connectingGuard?.cancel()
        val r = race
        race = null
        val old = channel
        channel = null
        (r?.map { it.ch } ?: emptyList()).plus(listOfNotNull(old)).forEach {
            scope.launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { it.close() } }
        }
        _state.value = _state.value.copy(
            phase = Phase.FAILED,
            error = "连接房间服务器失败（${endpoints.size} 个节点均不可达）：$reason。" +
                "请检查网络/防火墙（公共节点走 8084/1883 端口），或稍后重试",
        )
        wantConnected = false
        android.util.Log.w(LOG_TAG, "connecting failed: $reason")
    }

    private fun send(msg: PartyWire, qos: Int = 0): Boolean {
        val ch = channel ?: return false
        return runCatching { ch.send(PartyWireCodec.encode(msg), qos) }.getOrDefault(false)
    }

    /** 应用层心跳：15s 一发；WS relay 单播回 pong，MQTT 模式本机回环应答 → rttMs。
     *  兼做 JOINED 静默看门狗：45s 未收到任何对端消息（心跳/成员/状态都算）判定半开连接，
     *  强制走重连——移动网络下 TCP 半开并不罕见，pako keepalive 之外的双保险 */
    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(15_000)
                send(PartyWire.Ping(at = System.currentTimeMillis()))
                if (_state.value.phase == Phase.JOINED &&
                    lastIncomingAt > 0 &&
                    System.currentTimeMillis() - lastIncomingAt > SILENCE_TIMEOUT_MS
                ) {
                    android.util.Log.w(LOG_TAG, "silence watchdog: no incoming for ${SILENCE_TIMEOUT_MS / 1000}s, forcing reconnect")
                    lastIncomingAt = System.currentTimeMillis()
                    scheduleReconnect("连接静默超时")
                }
            }
        }
    }

    // MARK: - 通道回调（ch !== channel 的是旧通道迟到回调，一律忽略防幽灵）

    private fun handleOpen(ch: WireChannel) {
        val r = race
        if (r != null) {
            // 竞速期：首个完成握手的通道胜出，其余立即关闭
            val entry = r.firstOrNull { it.ch === ch } ?: return
            race = null
            channel = ch
            endpointIndex = entry.index
            r.forEach {
                if (it.ch !== ch) {
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { it.ch.close() } }
                }
            }
            android.util.Log.i(LOG_TAG, "race winner endpoint=${endpoints[entry.index].uri}")
        } else if (ch !== channel) return
        attempts = 0
        lastIncomingAt = System.currentTimeMillis()
        // hello 是成员表/房主解析的关键消息：MQTT 通道走 QoS1 防丢（WS 自带可靠，qos 参数被忽略）
        runCatching {
            ch.send(PartyWireCodec.encode(PartyWire.Hello(room = _state.value.roomCode, nick = nick)), qos = 1)
        }
        startPing()
    }

    private fun handleMessage(ch: WireChannel, text: String) {
        if (ch !== channel) return
        lastIncomingAt = System.currentTimeMillis()
        when (val msg = PartyWireCodec.decode(text)) {
            is PartyWire.Welcome -> {
                connectingGuard?.cancel()
                android.util.Log.i(
                    LOG_TAG,
                    "joined room=${_state.value.roomCode} endpoint=${endpoints[endpointIndex.coerceIn(endpoints.indices)].uri} you=${msg.you} host=${msg.host}",
                )
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
        val r = race
        if (r != null) {
            // 竞速期：只统计失败者，还有人活着就继续等（总时限由 connectingGuard 把守）
            val entry = r.firstOrNull { it.ch === ch } ?: return
            val remaining = r.filter { it.ch !== ch }
            race = remaining
            android.util.Log.w(LOG_TAG, "race candidate down ${entry.index + 1}/${endpoints.size} err=${t?.message}")
            if (remaining.isEmpty()) failConnecting(t?.message ?: "全部节点不可达（code=$closeCode）")
            return
        }
        if (ch !== channel || !wantConnected) return
        connectingGuard?.cancel()
        android.util.Log.w(LOG_TAG, "down phase=${_state.value.phase} code=$closeCode err=${t?.message}")
        // 4002=房间满员：明确失败，不重连（重连还是满）
        if (closeCode == 4002) {
            _state.value = _state.value.copy(phase = Phase.FAILED, error = "房间已满（16 人上限）")
            wantConnected = false
            return
        }
        if (_state.value.phase == Phase.CONNECTING) {
            // 胜出通道在 welcome 前死掉：所有候选已试过，直接明确失败
            failConnecting(t?.message ?: "未知错误")
            return
        }
        scheduleReconnect(t?.message ?: "连接中断")
    }

    companion object {
        private const val LOG_TAG = "WatchParty"

        /** 自建 WS 中继默认地址：部署后填「wss://你的域名」（空=App 内每次手动填写） */
        const val DEFAULT_RELAY_URL = ""

        /** 公共 MQTT 节点候选（EMQX 官方免费节点，免注册；仅转发 KB 级房间消息）。
         *  WSS（TLS WebSocket）优先——1883 等非标准端口的出站 TCP 常被运营商/防火墙拦截，
         *  WSS 流量形如普通 HTTPS，可达性远更好；TCP 作为兜底。首连失败按序自动切换。
         *  想自持化：任选 MQTT broker（EMQX/NanoMQ…）部署后填 mqtt://你的地址 */
        val DEFAULT_MQTT_ENDPOINTS = listOf(
            "wss://broker-cn.emqx.io:8084/mqtt",
            "wss://broker.emqx.io:8084/mqtt",
            "tcp://broker-cn.emqx.io:1883",
            "tcp://broker.emqx.io:1883",
        )

        /** 单次连接尝试的看门狗时限：paho connectionTimeout(5s) + DNS 余量 */
        const val CONNECTING_TIMEOUT_MS = 8_000L

        /** 竞速开闸错峰：排序靠前的节点先一点起跑，打平 microRTT 时偏向国内节点 */
        const val RACE_STAGGER_MS = 120L

        /** JOINED 静默看门狗阈值：45s（≥3 个心跳周期）无对端消息即强制重连 */
        const val SILENCE_TIMEOUT_MS = 45_000L
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

    override fun send(text: String, qos: Int): Boolean = ws?.send(text) ?: false

    override fun close() {
        ws?.close(1000, "leave")
        ws = null
    }
}
