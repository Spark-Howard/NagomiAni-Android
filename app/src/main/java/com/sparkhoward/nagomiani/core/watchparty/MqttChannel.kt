package com.sparkhoward.nagomiani.core.watchparty

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * MQTT 无服务器中继通道：一起看的「异地免部署」传输。
 * 与 WS relay 的对外语义完全一致（见 RoomClient.Bridge）——上层 RoomClient 感知不到差别：
 * - 房间 = 单一共享主题 `nagomi-party/<CODE>`，QoS 0（KB 级状态每 5s 心跳自愈，聊天偶丢可接受）
 * - 成员/房主没有服务器分配：presence 心跳（[PresenceTable]）自组织，本机合成 welcome/members
 * - state/chat/sync 共享主题广播，过滤本机回显；ping 本机回环应答（RTT≈0，仅供保活统计）
 * 客户端 id 每次连接重新生成，旧 id 靠 TTL 自然过期，与断线重连/房主接棒语义天然兼容。
 */
internal class MqttChannel(
    brokerUri: String,
    roomCode: String,
    private val nick: String,
    private val bridge: Bridge,
) : MqttCallbackExtended, WireChannel {


    private val selfId = "m" + java.lang.Long.toHexString(System.nanoTime()).takeLast(8) + (0..99).random()
    private val topic = "nagomi-party/$roomCode"
    private val presence = PresenceTable()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "party-mqtt").apply { isDaemon = true } }
    private val client = MqttAsyncClient(brokerUri, "nagomi-$selfId", MemoryPersistence())

    @Volatile private var closed = false
    private var welcomed = false
    private var lastMembersPayload = ""
    /** 本机是否现任房主（粘滞：welcome 时无人自荐即自任；被更大优先级自荐顶替时让位） */
    @Volatile private var selfIsHost = false
    /** 房主解析窗口截止时刻：窗口内不合成 welcome（等既存自荐者心跳），到点强制合成 */
    private var resolveDeadline = 0L
    private var resolveTask: ScheduledFuture<*>? = null
    private var presenceTask: ScheduledFuture<*>? = null

    /** 打开：连上后订阅；房主解析窗口（[RESOLVE_DELAY_MS]）后合成 welcome */
    override fun open() {
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            isAutomaticReconnect = false // 重连由 RoomClient 统一退避调度
            maxInflight = 32
        }
        client.setCallback(this)
        client.connect(options).setActionCallback(object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                if (closed) return
                runCatching {
                    client.subscribe(topic, 1)
                    // 房主解析窗口：给既存自荐者一个心跳窗口（hello 的欢迎握手会在 RTT 内补发在场），
                    // 到点仍无人自荐则本机以最小 id 补位（创建者/空房接任）
                    resolveDeadline = System.currentTimeMillis() + RESOLVE_DELAY_MS
                    resolveTask = scheduler.schedule(
                        Runnable { resolveAndBroadcast(forceWelcome = true) },
                        RESOLVE_DELAY_MS, TimeUnit.MILLISECONDS,
                    )
                    presenceTask = scheduler.scheduleAtFixedRate(
                        {
                            if (closed) return@scheduleAtFixedRate
                            runCatching {
                                resolveAndBroadcast(forceWelcome = false)
                                send(PartyWireCodec.encode(PartyWire.Presence(nick = nick, host = selfIsHost, from = selfId)))
                            }
                        },
                        PRESENCE_PERIOD_MS, PRESENCE_PERIOD_MS, TimeUnit.MILLISECONDS,
                    )
                    bridge.onOpen(this@MqttChannel)
                }.onFailure { bridge.onDown(this@MqttChannel, it, 0) }
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                if (closed) return
                bridge.onDown(this@MqttChannel, exception, 0)
            }
        })
    }

    /** 发送：注入 from 后发布到房间主题（MQTT 会回发给发布者，onMessage 里统一过滤） */
    override fun send(text: String): Boolean {
        if (closed) return false
        val injected = PartyWireCodec.decode(text)?.let { PartyWireCodec.encode(withFrom(it)) } ?: return false
        return runCatching {
            client.publish(topic, MqttMessage(injected.toByteArray()).apply { qos = 0 })
            true
        }.getOrDefault(false)
    }

    override fun close() {
        closed = true
        resolveTask?.cancel(false)
        presenceTask?.cancel(false)
        scheduler.shutdownNow()
        runCatching { client.disconnectForcibly(500) }
        runCatching { client.close() }
    }

    // MARK: - MqttCallbackExtended

    override fun connectComplete(reconnect: Boolean, serverURI: String) = Unit

    override fun connectionLost(cause: Throwable?) {
        if (closed) return
        bridge.onDown(this, cause, 0)
    }

    override fun messageArrived(topic: String, message: MqttMessage) {
        if (closed) return
        val msg = PartyWireCodec.decode(String(message.payload)) ?: return
        val from = fromOf(msg) ?: return // 无 from 的消息不参与会话
        if (from == selfId) {
            // 本机回环：ping 自答 pong（RTT≈链路往返），其余回显一律丢弃（替代 relay 的排除发送者）
            if (msg is PartyWire.Ping) bridge.onMessage(this, PartyWireCodec.encode(PartyWire.Pong(at = msg.at)))
            return
        }
        when (msg) {
            is PartyWire.Hello -> {
                presence.record(from, msg.nick, hostClaim = false, System.currentTimeMillis())
                // 欢迎握手：新成员要等下一个心跳（≤4s）才能看到房主自荐，会超出 welcome 解析窗口——
                // 看到新面孔立即补发一次在场宣告（含本机自荐状态），让它在窗口内完成房主解析
                runCatching { send(PartyWireCodec.encode(PartyWire.Presence(nick = nick, host = selfIsHost, from = selfId))) }
                resolveAndBroadcast(forceWelcome = false)
            }

            is PartyWire.Presence -> {
                presence.record(from, msg.nick, msg.host, System.currentTimeMillis())
                resolveAndBroadcast(forceWelcome = false)
            }

            is PartyWire.State, is PartyWire.Chat, is PartyWire.Sync ->
                bridge.onMessage(this, PartyWireCodec.encode(msg))
            else -> Unit // welcome/members 系本机合成，不会从主题收到；bye 等忽略
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

    // MARK: - 内部

    /** 房主解析 + 成员变化广播。规则：TTL 内自荐者优先（取最小 id，防双创建者脑裂）；
     *  无人自荐（房主掉线自荐过期）→ 最小 id 成员接任——所有成员算出同一结果，确定性收敛。
     *  welcome 只发一次：解析窗口内等自荐，到点强制合成。 */
    private fun resolveAndBroadcast(forceWelcome: Boolean) {
        val now = System.currentTimeMillis()
        val host = presence.resolveHost(now)
            ?: presence.minMemberId(now)
            ?: selfId
        selfIsHost = host == selfId
        val membersPayload = PartyWireCodec.encode(
            PartyWire.Members(host = host, members = presence.members(now)),
        )
        if (!welcomed) {
            if (now < resolveDeadline && !forceWelcome) return
            welcomed = true
            lastMembersPayload = membersPayload
            bridge.onMessage(
                this,
                PartyWireCodec.encode(PartyWire.Welcome(you = selfId, host = host, members = presence.members(now))),
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
        else -> null
    }

    companion object {
        private const val RESOLVE_DELAY_MS = 800L
        private const val PRESENCE_PERIOD_MS = 4_000L
    }
}
