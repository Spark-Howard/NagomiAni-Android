package com.sparkhoward.nagomiani.core.watchparty

/**
 * 公共节点候选排序（纯函数）：按房间码哈希确定性轮转——
 * 同一房间的所有设备算出完全相同的节点顺序，必然连上同一 broker。
 * 关键不变量：**同房间不可分裂**。此前按设备各自竞速选最快节点，
 * 房主与朋友落在不同 broker 上互不可见（「好友看不到在播什么」的根因）。
 */
object PartyEndpoints {

    /** 轮转排序：主节点 = |roomCode.hashCode| % n，其后按原序循环展开（覆盖全部节点作兜底） */
    fun ordered(base: List<String>, roomCode: String): List<String> {
        if (base.size <= 1) return base
        val primary = ((roomCode.hashCode() % base.size) + base.size) % base.size
        return base.slice(primary until base.size) + base.slice(0 until primary)
    }
}
