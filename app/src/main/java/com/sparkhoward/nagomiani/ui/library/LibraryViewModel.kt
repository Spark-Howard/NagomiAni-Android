package com.sparkhoward.nagomiani.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.maccms.MacCMSApi
import com.sparkhoward.nagomiani.core.maccms.OnlineRepo
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

    fun refresh() {
        viewModelScope.launch {
            val items = buildContinueItems()
            val rows = buildCloudRows()
            state.value = state.value.copy(continueItems = items, cloudRows = rows)
        }
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
            val current = state.value
            if (current.expandedSeriesKey == row.entry.seriesKey) {
                state.value = current.copy(expandedSeriesKey = null, expandedEpisodes = emptyList())
                return@launch
            }
            state.value = current.copy(expandedSeriesKey = row.entry.seriesKey, expandedEpisodes = emptyList(), loading = true)
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
            state.value = state.value.copy(
                loading = false,
                expandedEpisodes = episodes,
                watchedNumbers = watched,
            )
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
