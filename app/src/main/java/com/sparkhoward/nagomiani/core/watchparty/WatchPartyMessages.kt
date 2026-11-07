package com.sparkhoward.nagomiani.core.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PARTY_PROTOCOL_VERSION = 1

/** 房间成员（relay 维护，随 welcome/members 下发） */
@Serializable
data class PartyMember(val id: String, val nick: String)

/**
 * 一起看 wire 协议（客户端 ↔ relay，classDiscriminator="t"）。
 * WS relay 只按房间码转发：hello/ping 单播消费，state/chat/sync 原样广播并附 from 标记；
 * MQTT 模式无服务器：presence/hello 承担在场广播与房主选举（见 MqttChannel），
 * state/chat/sync 走共享主题，各端自行过滤自己的回显（MQTT 会回发给发布者）。
 * from=发送者 id：relay 附加或 MqttChannel 注入；客户端自发时省略。
 * 同步键 = 集号（+subjectId 透传展示）；各端片源可能不同，绝不同步流 URL。
 */
@Serializable
sealed interface PartyWire {

    /** 入房首条消息（WS relay 消费不转发；MQTT 模式作为在场宣告之一） */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val room: String,
        val nick: String,
        val v: Int = PARTY_PROTOCOL_VERSION,
        val from: String? = null,
    ) : PartyWire

    /** relay → 新成员：自己的 id、当前房主与成员表（MQTT 模式由本机 MqttChannel 合成） */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val you: String, val host: String, val members: List<PartyMember> = emptyList()) : PartyWire

    /** relay → 全员：成员表/房主变化（加入、离开、房主交接；MQTT 模式由在场表变化触发合成） */
    @Serializable
    @SerialName("members")
    data class Members(val host: String, val members: List<PartyMember> = emptyList()) : PartyWire

    /** MQTT 模式的在场心跳（每 4s；host=true 为房主自荐/续任，房主选举取最小 id）。WS relay 忽略此类型 */
    @Serializable
    @SerialName("presence")
    data class Presence(val nick: String = "", val host: Boolean = false, val from: String? = null) : PartyWire

    /** 房主 → 全员：权威播放状态。wall=发送时刻（guest 推算落位）；
     *  epoch 在 seek/换集等不连续点自增，guest 见到更大值立即硬对齐 */
    @Serializable
    @SerialName("state")
    data class State(
        val playing: Boolean,
        val pos: Double,
        val wall: Long,
        val epoch: Long = 0,
        val episodeNumber: Int = 0,
        val subjectId: Int? = null,
        val from: String? = null,
    ) : PartyWire

    /** 任意成员 → 全员：聊天（各端以弹幕样式飘出） */
    @Serializable
    @SerialName("chat")
    data class Chat(val nick: String, val text: String, val from: String? = null) : PartyWire

    /** guest → 全员（房主消费）：请求一次状态广播（入房/重连后对齐） */
    @Serializable
    @SerialName("sync")
    data class Sync(val at: Long = 0, val from: String? = null) : PartyWire

    /** 应用层 ping（测 RTT）；WS relay 单播回 pong，MQTT 模式本机回环应答 */
    @Serializable
    @SerialName("ping")
    data class Ping(val at: Long, val from: String? = null) : PartyWire

    /** relay → 客户端：ping 的应答 */
    @Serializable
    @SerialName("pong")
    data class Pong(val at: Long) : PartyWire
}

/** wire 编解码（与 relay 的 JSON 约定一致；坏消息静默丢弃，不让单条脏数据打断会话） */
object PartyWireCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true }

    fun encode(msg: PartyWire): String = json.encodeToString(PartyWire.serializer(), msg)

    fun decode(raw: String): PartyWire? =
        runCatching { json.decodeFromString(PartyWire.serializer(), raw) }.getOrNull()
}
