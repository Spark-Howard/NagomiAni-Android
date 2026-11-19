package com.sparkhoward.nagomiani.core.watchparty

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttWebSocketConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "WatchPartyMqtt"

/**
 * MQTT 无服务器中继通道（HiveMQ client 内核）。
 * 与 WS relay 的对外语义完全一致（见 RoomClient.Bridge）——上层 RoomClient 感知不到差别：
 * - 房间 = 单一共享主题 `nagomi-party/<CODE>`（hello/presence/state/chat QoS1）
 * - 成员/房主没有服务器分配：presence 心跳（[PresenceTable]）自组织，本机合成 welcome/members
 * - state/chat/sync 共享主题广播，过滤本机回显；ping 本机回环应答
 * 传输选型备注：曾用 paho 1.2.5——桌面 JVM 正常但部分 Android 环境 connect 静默挂起
 * （提交后 onSuccess/onFailure/内部线程均无动静，模拟器复现），换 HiveMQ 后解决。
 * 客户端 id 每次连接重新生成，旧 id 靠 TTL 自然过期，与断线重连/房主接棒语义天然兼容。
 */
internal class MqttChannel(
    private val endpointUri: String,
    roomCode: String,
    private val nick: String,
    private val hostHint: Boolean,
    private val bridge: Bridge,
) : WireChannel {

    /** 本机身份：房主（创建方）用「房间码派生的稳定 id」——断线重连后仍是同一 id，
     *  自荐连续、成员表无缝衔接，房主位不会漂移到别的成员；guest 用随机 id。 */
    private val selfId = if (hostHint) {
        "h" + Integer.toHexString(roomCode.toByteArray().fold(0) { acc, b -> acc * 31 + b }).padStart(8, '0')
    } else {
        "m" + java.lang.Long.toHexString(System.nanoTime()).takeLast(8) + (0..99).random()
    }
    private val topic = "nagomi-party/$roomCode"
    private val presence = PresenceTable()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "party-mqtt").apply { isDaemon = true } }

    // clientId 与身份解耦（加随机后缀）：同码双创建者不会互踢连接；身份只由消息里的 from 承载
    private var client: Mqtt3AsyncClient? = null

    @Volatile private var closed = false
    private var welcomed = false
    private var lastMembersPayload = ""
    /** 本机是否现任房主（粘滞：welcome 时无人自荐即自任；被更大优先级自荐顶替时让位） */
    @Volatile private var selfIsHost = false
    /** 当前房主 id（guest 定址 ping 探测「到房主」延迟用） */
    @Volatile private var currentHostId: String? = null
    /** 本机测得的到房主延迟（guest 定址 ping；房主为 null=锚点），随 presence 广播 */
    @Volatile private var myHostRttMs: Long? = null
    /** 房主解析窗口截止时刻：窗口内不合成 welcome（等既存自荐者心跳），到点强制合成 */
    private var resolveDeadline = 0L
    private var resolveTask: ScheduledFuture<*>? = null
    private var presenceTask: ScheduledFuture<*>? = null
    private var rttProbeTask: ScheduledFuture<*>? = null

    /** 解析端点 URI：tcp://h:p / ssl://h:p / ws(s)://h:p[/path]（mqtt:// 等价 tcp://，mqtts:// 等价 ssl://） */
    private data class EndpointParts(
        val host: String,
        val port: Int,
        val websocket: Boolean,
        val ssl: Boolean,
        val path: String?,
    )

    /** 打开：连上后订阅；房主解析窗口（[RESOLVE_DELAY_MS]）后合成 welcome */
    override fun open() {
        android.util.Log.i(LOG_TAG, "open uri=$endpointUri")
        val parts = parseUri(endpointUri)
        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("nagomi-" + java.lang.Long.toHexString(System.nanoTime()).takeLast(10))
            .serverHost(parts.host)
            .serverPort(parts.port)
        if (parts.ssl) builder.sslWithDefaultConfig()
        if (parts.websocket) {
            builder.webSocketConfig(
                MqttWebSocketConfig.builder()
                    .serverPath(parts.path ?: "/mqtt")
                    .build(),
            )
        }
        val mqttClient = builder.buildAsync()
        client = mqttClient
        mqttClient.connectWith()
            .cleanSession(true)
            .keepAlive(30)
            .send()
            .whenComplete { _, err ->
                if (closed) return@whenComplete
                if (err != null) {
                    android.util.Log.w(LOG_TAG, "connect failed: ${err.message}")
                    bridge.onDown(this, err, 0)
                    return@whenComplete
                }
                android.util.Log.i(LOG_TAG, "connected to $endpointUri")
                runCatching {
                    mqttClient.subscribeWith()
                        .topicFilter(topic)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .callback { publish -> onIncoming(publish.payloadAsBytes) }
                        .send()
                    // welcome 合成时机：创建方立即自任；guest 等「首个他人在场」或超时兜底
                    // （赌固定窗口会在真实网络下输——欢迎握手回包可能晚于窗口）
                    resolveDeadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS
                    resolveTask = scheduler.schedule(
                        Runnable { resolveAndBroadcast(forceWelcome = true) },
                        if (hostHint) 0 else RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS,
                    )
                    presenceTask = scheduler.scheduleAtFixedRate(
                        {
                            if (closed) return@scheduleAtFixedRate
                            runCatching {
                                resolveAndBroadcast(forceWelcome = false)
                                sendReliable(
                                    PartyWire.Presence(
                                        nick = nick,
                                        host = selfIsHost,
                                        rttMs = if (selfIsHost) null else myHostRttMs,
                                        from = selfId,
                                    ),
                                )
                            }
                        },
                        PRESENCE_PERIOD_MS, PRESENCE_PERIOD_MS, TimeUnit.MILLISECONDS,
                    )
                    // 「到房主」延迟探测（guest）：定址 ping 10s 一发，房主回 pong 量往返。
                    // 时间戳是本机发出时写的、pong 原样带回——无时钟偏差
                    rttProbeTask = scheduler.scheduleAtFixedRate(
                        {
                            if (closed || selfIsHost) return@scheduleAtFixedRate
                            val host = currentHostId ?: return@scheduleAtFixedRate
                            if (host == selfId) return@scheduleAtFixedRate
                            runCatching { send(PartyWireCodec.encode(PartyWire.Ping(at = System.currentTimeMillis(), from = selfId, to = host)), qos = 0) }
                        },
                        RTT_PROBE_PERIOD_MS, RTT_PROBE_PERIOD_MS, TimeUnit.MILLISECONDS,
                    )
                    bridge.onOpen(this)
                }.onFailure {
                    android.util.Log.w(LOG_TAG, "post-connect setup failed: ${it.message}")
                    bridge.onDown(this, it, 0)
                }
            }
    }

    /** 发送：注入 from 后发布到房间主题（MQTT broker 会回发给发布者，onIncoming 里统一过滤）。
     *  hello/presence/state/chat 走 QoS1（关键消息防丢）；QoS 失败仅记日志（心跳自愈） */
    override fun send(text: String, qos: Int): Boolean {
        if (closed) return false
        val injected = PartyWireCodec.decode(text)?.let { PartyWireCodec.encode(withFrom(it)) } ?: return false
        val c = client ?: return false
        return runCatching {
            c.publishWith()
                .topic(topic)
                .payload(injected.toByteArray())
                .qos(if (qos >= 1) MqttQos.AT_LEAST_ONCE else MqttQos.AT_MOST_ONCE)
                .send()
                .whenComplete { _, err -> if (err != null) android.util.Log.w(LOG_TAG, "publish async failed: ${err.message}") }
            true
        }.getOrElse {
            android.util.Log.w(LOG_TAG, "publish failed: ${it.message}")
            false
        }
    }

    private fun sendReliable(msg: PartyWire) {
        send(PartyWireCodec.encode(msg), qos = 1)
    }

    override fun close() {
        closed = true
        resolveTask?.cancel(false)
        presenceTask?.cancel(false)
        rttProbeTask?.cancel(false)
        scheduler.shutdownNow()
        runCatching { client?.disconnect() }
    }

    // MARK: - 收包分发

    private fun onIncoming(bytes: ByteArray) {
        if (closed) return
        val msg = PartyWireCodec.decode(String(bytes)) ?: return
        val from = fromOf(msg) ?: return // 无 from 的消息不参与会话
        if (from == selfId) {
            // 本机回环：无 to 的 ping 自答 pong（回环 RTT，同步落位补偿用）；
            // 定址 ping（to=别人）的回显不答。其余回显一律丢弃（替代 relay 的排除发送者）
            if (msg is PartyWire.Ping && (msg.to == null || msg.to == selfId)) {
                bridge.onMessage(this, PartyWireCodec.encode(PartyWire.Pong(at = msg.at)))
            }
            return
        }
        android.util.Log.i(LOG_TAG, "incoming t=${msg::class.simpleName} from=$from")
        when (msg) {
            is PartyWire.Hello -> {
                presence.record(from, msg.nick, hostClaim = false, rttMs = null, nowMs = System.currentTimeMillis())
                // 欢迎握手：新成员要等下一个心跳（≤4s）才能看到房主自荐，会超出 welcome 解析窗口——
                // 看到新面孔立即补发一次在场宣告（含本机自荐状态与 RTT），让它在窗口内完成房主解析
                runCatching {
                    sendReliable(
                        PartyWire.Presence(
                            nick = nick,
                            host = selfIsHost,
                            rttMs = if (selfIsHost) null else myHostRttMs,
                            from = selfId,
                        ),
                    )
                }
                resolveAndBroadcast(forceWelcome = false)
            }

            is PartyWire.Presence -> {
                presence.record(from, msg.nick, msg.host, msg.rttMs, System.currentTimeMillis())
                resolveAndBroadcast(forceWelcome = false)
            }

            is PartyWire.Ping ->
                // 定址 ping（to=我）：回 pong 带原 at（探测方量往返）；别人的定址 ping 不理会
                if (msg.to == selfId) {
                    runCatching { sendReliable(PartyWire.Pong(at = msg.at, from = selfId, to = from)) }
                } else Unit

            is PartyWire.Pong ->
                // 定址 pong（to=我，from=房主）：量「到房主」往返并写进自己的在场条目随心跳广播
                if (msg.to == selfId && msg.from == currentHostId) {
                    val rtt = System.currentTimeMillis() - msg.at
                    android.util.Log.i(LOG_TAG, "host rtt measured: ${rtt}ms")
                    if (rtt in 0..60_000) {
                        myHostRttMs = rtt
                        presence.updateRtt(selfId, rtt, System.currentTimeMillis())
                    }
                } else Unit

            is PartyWire.State, is PartyWire.Chat, is PartyWire.Sync ->
                bridge.onMessage(this, PartyWireCodec.encode(msg))
            else -> Unit // welcome/members 系本机合成，不会从主题收到；bye 等忽略
        }
    }

    // MARK: - 内部

    private fun parseUri(raw: String): EndpointParts {
        val uri = raw.trim()
        val scheme = uri.substringBefore("://").lowercase()
        val rest = uri.substringAfter("://")
        val pathPortion = "/" + rest.substringAfter('/', missingDelimiterValue = "").trimStart('/')
        val hostPort = rest.substringBefore('/')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "0").toIntOrNull() ?: 0
        return when (scheme) {
            "ws" -> EndpointParts(host, port.ifZero(80), websocket = true, ssl = false, path = pathPortion.takeIf { it != "/" })
            "wss" -> EndpointParts(host, port.ifZero(443), websocket = true, ssl = true, path = pathPortion.takeIf { it != "/" })
            "ssl", "mqtts" -> EndpointParts(host, port.ifZero(8883), websocket = false, ssl = true, path = null)
            else -> EndpointParts(host, port.ifZero(1883), websocket = false, ssl = false, path = null)
        }
    }

    private fun Int.ifZero(default: Int) = if (this == 0) default else this

    /** 房主解析 + 成员变化广播。规则（MqttHostPolicy）：
     *  - 创建方（hostHint）：恒自任——断线重连凭稳定 id 立即夺回，成员表无缝衔接
     *  - 其余：TTL 内自荐者优先（取最小 id，防双自荐脑裂）；无人自荐（房主掉线自荐过期）→
     *    「含自己在内的最小 id 成员」接任——含自己才能保证所有成员算出同一结果、恰好一人接任
     *  welcome 只发一次：guest 在「首个他人在场」时立即合成（兜底超时才强制）；
     *  兜底时成员表仍空 = 这个节点上根本没有这个房间 → 4004 报「房间不存在」，绝不假扮房主 */
    private fun resolveAndBroadcast(forceWelcome: Boolean) {
        val now = System.currentTimeMillis()
        val host = MqttHostPolicy.resolveHost(
            liveClaimMinId = presence.resolveHost(now),
            minMemberId = presence.minMemberId(now),
            selfId = selfId,
            hostHint = hostHint,
        )
        selfIsHost = host == selfId
        currentHostId = host
        // 成员表含自己（回显过滤导致自己的 presence 不在表里）；自己的 RTT = 测得的到房主延迟
        val membersWithSelf = presence.members(now)
            .filterNot { it.id == selfId }
            .plus(PartyMember(selfId, nick, rttMs = if (selfIsHost) null else myHostRttMs))
            .sortedBy { it.id }
        val membersPayload = PartyWireCodec.encode(
            PartyWire.Members(host = host, members = membersWithSelf),
        )
        if (!welcomed) {
            // guest：成员表仍空且未到兜底超时 → 继续等（收到首个他人 presence 会立即进入合成）
            if (!hostHint && !forceWelcome && now < resolveDeadline && presence.members(now).isEmpty()) return
            if (!hostHint && presence.members(now).isEmpty()) {
                // guest 兜底仍空房：房间不存在（码错/房主未创建/不在同一节点），明确失败不假扮房主
                android.util.Log.w(LOG_TAG, "guest resolve timeout with empty room: room not found")
                bridge.onDown(this, java.net.ConnectException("房间不存在或已失效"), 4004)
                return
            }
            welcomed = true
            lastMembersPayload = membersPayload
            bridge.onMessage(
                this,
                PartyWireCodec.encode(PartyWire.Welcome(you = selfId, host = host, members = membersWithSelf)),
            )
            return
        }
        if (membersPayload != lastMembersPayload) {
            lastMembersPayload = membersPayload
            bridge.onMessage(this, membersPayload)
        }
    }

    private fun withFrom(msg: PartyWire): PartyWire = when (msg) {
        is PartyWire.Hello -> msg.copy(from = selfId)
        is PartyWire.Presence -> msg.copy(from = selfId)
        is PartyWire.State -> msg.copy(from = selfId)
        is PartyWire.Chat -> msg.copy(from = selfId)
        is PartyWire.Sync -> msg.copy(from = selfId)
        is PartyWire.Ping -> msg.copy(from = selfId)
        else -> msg
    }

    private fun fromOf(msg: PartyWire): String? = when (msg) {
        is PartyWire.Hello -> msg.from
        is PartyWire.Presence -> msg.from
        is PartyWire.State -> msg.from
        is PartyWire.Chat -> msg.from
        is PartyWire.Sync -> msg.from
        is PartyWire.Ping -> msg.from
        is PartyWire.Pong -> msg.from // 定址 pong（from=房主）参与 RTT 测量，不能在此丢弃
        else -> null
    }

    companion object {
        /** guest 的 welcome 兜底超时：房主在场时不生效（首个 presence 即合成） */
        private const val RESOLVE_TIMEOUT_MS = 3_000L
        private const val PRESENCE_PERIOD_MS = 4_000L
        private const val RTT_PROBE_PERIOD_MS = 10_000L
    }
}
