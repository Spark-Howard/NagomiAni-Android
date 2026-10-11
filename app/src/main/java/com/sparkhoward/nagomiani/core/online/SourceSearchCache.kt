package com.sparkhoward.nagomiani.core.online

import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlineShow

/**
 * 片源搜索会话缓存（仅进程内存）：详情页短时间内退出重进时直接复用上次结果，
 * 不再对全部站点重新搜索。TTL 5 分钟，按 subjectID 键控；手动"刷新"绕过缓存并回写。
 * LRU 封顶 6 个条目——长番单条目（8 部番 × 上千集分集含流地址）可达数十 MB，
 * TTL 内连续浏览多个条目时只保留最近访问的几个。
 */
object SourceSearchCache {
    private const val TTL_MS = 5 * 60_000L
    private const val MAX_ENTRIES = 6

    data class Entry(
        val savedAt: Long = System.currentTimeMillis(),
        val sources: List<Pair<OnlineShow, List<OnlineEpisode>>>,
        val status: String,
    )

    /** 访问序 LRU：get 也刷新热度，重进同一个详情页永远命中 */
    private val cache = object : LinkedHashMap<Int, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>): Boolean =
            size > MAX_ENTRIES
    }

    fun get(subjectID: Int): Entry? =
        cache[subjectID]?.takeIf { System.currentTimeMillis() - it.savedAt < TTL_MS }

    fun put(subjectID: Int, sources: List<Pair<OnlineShow, List<OnlineEpisode>>>, status: String) {
        // TTL 只挡读取，不挡内存：写入时顺手清掉全部过期项，再由 LRU 兜底封顶
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.savedAt >= TTL_MS }
        cache[subjectID] = Entry(sources = sources, status = status)
    }

    fun invalidate(subjectID: Int) {
        cache.remove(subjectID)
    }
}
