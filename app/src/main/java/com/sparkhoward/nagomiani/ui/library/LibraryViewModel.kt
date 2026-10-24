package com.sparkhoward.nagomiani.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.maccms.MacCMSApi
import com.sparkhoward.nagomiani.core.maccms.OnlineRepo
import com.sparkhoward.nagomiani.core.maccms.SourceHealth
import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlinePlayback
import com.sparkhoward.nagomiani.core.model.OnlineShow
import com.sparkhoward.nagomiani.core.store.AppStore
import com.sparkhoward.nagomiani.player.PlaybackBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 番库页：继续观看（一番一卡）+ 云端番库（收藏的片源，含分集/新集计数/绑定徽章） */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val store get() = AppGraph.store
    private val onlineRepo = OnlineRepo()
    private val auth = BangumiAuth(app)

    /** 继续观看卡片（mac 版 ContentView.continueWatchingItems 同规则：一番一卡，最多 6 张） */
    data class ContinueItem(
        val resumeKey: String,
        val title: String,
        val subtitle: String,
        val coverURL: String?,
        val progress: Float,
        val playback: OnlinePlayback?,
    )

    data class CloudRow(
        val entry: AppStore.CloudEntry,
        val coverURL: String?,
        val boundName: String?,
        val boundSubjectID: Int?,
        val episodeCount: Int?,
        val newCount: Int,
    )

    data class State(
        val continueItems: List<ContinueItem> = emptyList(),
        val cloudRows: List<CloudRow> = emptyList(),
        val expandedSeriesKey: String? = null,
        val expandedEpisodes: List<OnlineEpisode> = emptyList(),
        val watchedNumbers: Set<Int> = emptySet(),
        val loading: Boolean = false,
        val message: String? = null,
    )

    val state = MutableStateFlow(State())

    /** 番库有新集时发系统通知（每日后台检查，见 EpisodeNotify） */
    val updateNotify = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            store.updateNotifyEnabled.collect { updateNotify.value = it }
        }
    }

    // MARK: - 片源健康面板（站点测速 + 自加站点管理）

    /** 健康面板行：内置站中文名/自加站 host；userRaw = AppStore 里存的原始串（删除时用） */
    data class SiteRow(
        val label: String,
        val providerID: String,
        val isUser: Boolean,
        val userRaw: String? = null,
    )

    val siteRows = MutableStateFlow<List<SiteRow>>(emptyList())
    val siteHealth = MutableStateFlow<Map<String, AppStore.SiteHealthEntry>>(emptyMap())
    val probingSites = MutableStateFlow(false)
    val siteMessage = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            store.siteHealthMap.collect { siteHealth.value = it }
        }
        viewModelScope.launch {
            store.userSites.collect { rows ->
                // 内置 4 站 + 自加站（按 host 与内置去重，同 OnlineRepo.allSites 语义）
                val list = ArrayList<SiteRow>()
                MacCMSApi.builtinSites.forEach { (label, url) ->
                    list += SiteRow(label, MacCMSApi(url).providerID, isUser = false)
                }
                val seen = list.map { it.providerID }.toSet()
                rows.mapNotNull { raw -> MacCMSApi.normalizeAPIBase(raw)?.let { raw to it } }
                    .forEach { (raw, base) ->
                        val host = MacCMSApi(base).providerID
                        if (host !in seen) list += SiteRow(host, host, isUser = true, userRaw = raw)
                    }
                siteRows.value = list
            }
        }
    }

    /** 测速全部站点（并发 ac=list 探测）并持久化；结果经 siteHealthMap 流回流 */
    fun probeSites() {
        if (probingSites.value) return
        viewModelScope.launch {
            probingSites.value = true
            try {
                store.replaceSiteHealth(SourceHealth.probeAllSites(onlineRepo.allSites()))
            } finally {
                probingSites.value = false
            }
        }
    }

    /** 添加自加站点：规范化成功且不与现有站重 host 才入库 */
    fun addUserSite(raw: String) {
        viewModelScope.launch {
            val base = MacCMSApi.normalizeAPIBase(raw)
            if (base == null) {
                showSiteMessage("无法识别该地址，请粘贴站点主页或 API 地址")
                return@launch
            }
            val host = MacCMSApi(base).providerID
            if (onlineRepo.allSites().any { MacCMSApi(it.second).providerID == host }) {
                showSiteMessage("该站点已存在（$host）")
                return@launch
            }
            store.addUserSite(base)
            showSiteMessage("已添加 $host")
        }
    }

    fun removeUserSite(row: SiteRow) {
        viewModelScope.launch {
            row.userRaw?.let {
                store.removeUserSite(it)
                showSiteMessage("已移除 ${row.label}")
            }
        }
    }

    private fun showSiteMessage(text: String) {
        siteMessage.value = text
        viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
            if (siteMessage.value == text) siteMessage.value = null
        }
    }

    fun toggleUpdateNotify() {
        viewModelScope.launch { store.setUpdateNotifyEnabled(!updateNotify.value) }
    }

    /** 返回 Job 供下拉刷新等待完成（指示器只对用户主动刷新显示，进页自动刷新不闪） */
    fun refresh(): kotlinx.coroutines.Job = viewModelScope.launch {
        // loading 置位 + 分步发布：否则多秒刷新期间列表空白，还会误渲染"还没有云端番"空态
        state.value = state.value.copy(loading = true)
        val items = buildContinueItems()
        state.value = state.value.copy(continueItems = items, loading = false)
        val rows = buildCloudRows()
        state.value = state.value.copy(cloudRows = rows)
    }

    private suspend fun buildContinueItems(): List<ContinueItem> {
        val resumeMap = store.resumeAll.first()
        val knownShows = store.knownShows().associateBy { it.seriesKey }
        // 每部番只保留最新一集的记录（resumeMap 无序 → 按 updatedAt 排序后按 seriesKey 去重）
        val sorted = resumeMap.entries.sortedByDescending { it.value.updatedAt }
        val seenSeries = HashSet<String>()
        val items = ArrayList<ContinueItem>()
        for ((key, entry) in sorted) {
            if (!key.startsWith("online:")) continue // 核心版只有云端记录
            val seriesKey = key.substringBeforeLast(':')
            if (!seenSeries.add(seriesKey)) continue
            val known = knownShows[seriesKey] ?: continue
            val providerID = known.providerID
            val showID = known.showID
            val number = key.substringAfterLast(':').toIntOrNull() ?: continue
            val show = OnlineShow(providerID, showID, known.title, known.subtitle, known.coverURL)
            val episode = OnlineEpisode(providerID, showID, number)
            val boundID = store.bindingSubjectID(seriesKey)
            val playback = onlineRepo.preparePlayback(show, episode)
            items += ContinueItem(
                resumeKey = key,
                title = known.title,
                subtitle = "上次看到第 $number 集 · 云端",
                coverURL = known.coverURL,
                progress = if (entry.duration > 0) (entry.position / entry.duration).toFloat().coerceIn(0f, 1f) else 0f,
                playback = playback,
            )
            if (items.size >= 6) break
        }
        return items
    }

    private suspend fun buildCloudRows(): List<CloudRow> {
        val entries = store.cloudLibraryOnce()
        return entries.map { entry ->
            val subjectID = store.bindingSubjectID(entry.seriesKey)
            val name = store.boundName(entry.seriesKey)
            // 新集计数：当前分集数 - 上次看到的分集数（展开时刷新基线，mac 版同语义）
            val site = onlineRepo.siteURLForProvider(entry.providerID)
            val count: Int? = site?.let { url ->
                runCatching {
                    MacCMSApi(url).search(entry.title).firstOrNull()?.let { show ->
                        onlineRepo.episodes(show).size
                    }
                }.getOrNull()
            }
            CloudRow(
                entry = entry,
                coverURL = entry.coverURL,
                boundName = name,
                boundSubjectID = subjectID,
                episodeCount = count,
                newCount = if (count != null && entry.lastSeenEpisodeCount in 1 until count) count - entry.lastSeenEpisodeCount else 0,
            )
        }
    }

    /** 展开/收起云端番的分集（展开时刷新分集 + 更新已看基线 + 拉已看徽章） */
    fun toggleExpand(row: CloudRow) {
        viewModelScope.launch {
            val key = row.entry.seriesKey
            val current = state.value
            if (current.expandedSeriesKey == key) {
                state.value = current.copy(expandedSeriesKey = null, expandedEpisodes = emptyList())
                return@launch
            }
            state.value = current.copy(expandedSeriesKey = key, expandedEpisodes = emptyList(), loading = true)
            val show = OnlineShow(row.entry.providerID, row.entry.showID, row.entry.title, row.entry.subtitle, row.entry.coverURL)
            val episodes = onlineRepo.episodes(show)
            // 刷新"已看基线"
            store.upsertCloudEntry(row.entry.copy(lastSeenEpisodeCount = episodes.size))
            var watched: Set<Int> = emptySet()
            if (auth.isLoggedIn() && row.boundSubjectID != null) {
                runCatching {
                    val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                    val marks = authed.myEpisodeCollections(row.boundSubjectID!!)
                    val eps = authed.allEpisodes(row.boundSubjectID)
                    watched = marks.filter { it.type == 2 }.mapNotNull { m ->
                        eps.firstOrNull { it.id == m.episodeId }?.displaySort
                    }.toSet()
                }
            }
            // 慢网络上快速点开另一部番（或收起）后，旧响应不能落地——否则 B 的标题下挂着 A 的分集，点播会串台
            when (val expanded = state.value.expandedSeriesKey) {
                key -> state.value = state.value.copy(
                    loading = false,
                    expandedEpisodes = episodes,
                    watchedNumbers = watched,
                )
                null -> state.value = state.value.copy(loading = false) // 已收起：只复位转圈
                else -> Unit // 已切换到另一部番：由那次的请求负责收尾
            }
        }
    }

    /** 点播某一集 */
    fun launchEpisode(row: CloudRow, episode: OnlineEpisode, onLaunched: () -> Unit) {
        viewModelScope.launch {
            val show = OnlineShow(row.entry.providerID, row.entry.showID, row.entry.title, row.entry.subtitle, row.entry.coverURL)
            val playback = onlineRepo.preparePlayback(show, episode)
            if (playback != null) {
                PlaybackBus.launch(playback)
                onLaunched()
            } else {
                state.value = state.value.copy(message = "取流失败，请稍后重试")
            }
        }
    }

    /** 从云端番库移除 */
    fun removeCloud(row: CloudRow) {
        viewModelScope.launch {
            store.removeCloudEntry(row.entry.seriesKey)
            store.removeResumeAllForSeries(row.entry.seriesKey)
            refresh()
        }
    }
}

/** 便利方法：删一个 series 的全部续播记录（挂在这里避免 AppStore 公共 API 膨胀） */
private suspend fun AppStore.removeResumeAllForSeries(seriesKey: String) {
    resumeAll.first().keys.filter { it.startsWith("$seriesKey:") }.forEach { removeResume(it) }
}
