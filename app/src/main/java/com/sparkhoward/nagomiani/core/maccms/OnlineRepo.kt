package com.sparkhoward.nagomiani.core.maccms

import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlinePlayback
import com.sparkhoward.nagomiani.core.model.OnlineShow
import com.sparkhoward.nagomiani.core.model.StreamSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * 在线片源仓库：站点列表（内置+用户自加）+ 跨站搜索 + 取流装配。
 * 取流时顺带完成：known-shows 注册（继续观看解析用）、绑定预取（看完同步用）。
 */
class OnlineRepo {

    private val store get() = AppGraph.store

    /** 全部站点（内置 + 用户自加，去重） */
    suspend fun allSites(): List<Pair<String, String>> {
        val user = store.userSites.first().mapNotNull { MacCMSApi.normalizeAPIBase(it) }
        val builtin = MacCMSApi.builtinSites
        val userApis = user.filter { user -> builtin.none { MacCMSApi(it.second).providerID == MacCMSApi(user).providerID } }
        return builtin + userApis.map { it.hostLabel() to it }
    }

    private fun String.hostLabel(): String = runCatching { java.net.URI(this).host ?: this }.getOrDefault(this)

    private fun api(siteURL: String) = MacCMSApi(siteURL)

    /** 单站搜索结果（label=站点名，error=请求失败原因） */
    data class SiteSearchResult(val label: String, val shows: List<OnlineShow>, val error: String?)

    /** 跨站搜索：并行打所有站点，返回各站结果（供 UI 展示每站状态，mac 版详情页"在线观看"同语义） */
    suspend fun searchAcrossSites(keyword: String): List<SiteSearchResult> = coroutineScope {
        allSites().map { (label, siteURL) ->
            async {
                val r = runCatching { api(siteURL).search(keyword) }
                SiteSearchResult(label, r.getOrDefault(emptyList()), r.exceptionOrNull()?.message)
            }
        }.awaitAll()
    }

    suspend fun episodes(show: OnlineShow): List<OnlineEpisode> {
        val site = siteURLForProvider(show.providerID) ?: return emptyList()
        return runCatching { api(site).episodes(show) }.getOrDefault(emptyList())
    }

    /** 取流并装配播放参数（记住番、绑定已关联 subject） */
    suspend fun preparePlayback(
        show: OnlineShow,
        episode: OnlineEpisode,
        showTitleForDanmaku: String? = null,
    ): OnlinePlayback? {
        val site = siteURLForProvider(show.providerID) ?: return null
        val source: StreamSource = runCatching {
            api(site).streamURL(show, episode.number, episode.title)
        }.getOrNull() ?: return null

        // 已看过的番注册（继续观看卡片解析标题/封面）
        store.rememberShow(
            com.sparkhoward.nagomiani.core.store.AppStore.KnownShow(
                providerID = show.providerID,
                showID = show.showID,
                title = show.title,
                subtitle = show.subtitle,
                coverURL = show.coverURL,
            ),
        )
        val boundSubjectID = store.bindingSubjectID(show.seriesKey)
        return OnlinePlayback(
            url = source.url,
            displayTitle = episode.title?.let { "${show.title} 第${episode.number}集 $it" }
                ?: "${show.title} 第${episode.number}集",
            seriesKey = show.seriesKey,
            episodeNumber = episode.number,
            resumeKey = episode.resumeKey,
            httpHeaders = source.httpHeaders,
            userAgent = source.userAgent,
            routes = episode.routes ?: listOf(source.url),
            showTitle = showTitleForDanmaku ?: show.title,
            boundSubjectID = boundSubjectID,
        )
    }

    suspend fun siteURLForProvider(providerID: String): String? {
        // providerID 是主机名——在全部站点里找同主机的 API
        return allSites().firstOrNull { (label, url) ->
            label == providerID || MacCMSApi(url).providerID == providerID
        }?.second
    }
}
