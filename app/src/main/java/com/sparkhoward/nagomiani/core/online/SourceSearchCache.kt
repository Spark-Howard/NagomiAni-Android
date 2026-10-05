package com.sparkhoward.nagomiani.core.online

import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlineShow

/**
 * 片源搜索会话缓存（仅进程内存）：详情页短时间内退出重进时直接复用上次结果，
 * 不再对全部站点重新搜索。TTL 5 分钟，按 subjectID 键控；手动"刷新"绕过缓存并回写。
 */
object SourceSearchCache {
    private const val TTL_MS = 5 * 60_000L

    data class Entry(
        val savedAt: Long = System.currentTimeMillis(),
        val sources: List<Pair<OnlineShow, List<OnlineEpisode>>>,
        val status: String,
    )

    private val cache = HashMap<Int, Entry>()

    fun get(subjectID: Int): Entry? =
        cache[subjectID]?.takeIf { System.currentTimeMillis() - it.savedAt < TTL_MS }

    fun put(subjectID: Int, sources: List<Pair<OnlineShow, List<OnlineEpisode>>>, status: String) {
        cache[subjectID] = Entry(sources = sources, status = status)
    }

    fun invalidate(subjectID: Int) {
        cache.remove(subjectID)
    }
}
