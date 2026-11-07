package com.sparkhoward.nagomiani.core.watchparty

/**
 * MQTT 无服务器模式的在场表（纯逻辑，可注入时钟，DanmakuLayout 范式）。
 * 没有中继分配成员/房主，靠各端周期广播 presence 自组织：
 * - 成员 = TTL 内有心跳的人（超时自动出局，替代 relay 的 close 通知）
 * - 房主 = TTL 内自荐（presence.host=true）的成员中 id 最小者——创建者先自荐当房主，
 *   掉线后其自荐过期，剩余成员中最小 id 自动接任（对齐 VM 既有的「顺位接棒」语义）
 */
class PresenceTable(private val ttlMs: Long = MEMBER_TTL_MS) {

    private data class Entry(val nick: String, val hostClaim: Boolean, val lastSeen: Long)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun record(id: String, nick: String, hostClaim: Boolean, nowMs: Long) {
        entries[id] = Entry(nick.ifBlank { "路人" }.take(24), hostClaim, nowMs)
    }

    @Synchronized
    fun remove(id: String) {
        entries.remove(id)
    }

    /** TTL 内自荐房主的成员中取最小 id；无人自荐返回 null（调用方按最小 id 成员接任） */
    @Synchronized
    fun resolveHost(nowMs: Long): String? =
        entries.filter { it.value.hostClaim && nowMs - it.value.lastSeen <= ttlMs }.keys.minOrNull()

    /** TTL 内成员中 id 最小者（房主接任的确定性次序：无人自荐时由它补位） */
    @Synchronized
    fun minMemberId(nowMs: Long): String? =
        entries.filterValues { nowMs - it.lastSeen <= ttlMs }.keys.minOrNull()

    /** TTL 内的成员快照（id 升序，稳定输出） */
    @Synchronized
    fun members(nowMs: Long): List<PartyMember> =
        entries.filterValues { nowMs - it.lastSeen <= ttlMs }
            .map { (id, e) -> PartyMember(id, e.nick) }
            .sortedBy { it.id }

    companion object {
        /** 心跳 4s 一次，容忍连续丢 2 拍 */
        const val MEMBER_TTL_MS = 12_000L
    }
}
