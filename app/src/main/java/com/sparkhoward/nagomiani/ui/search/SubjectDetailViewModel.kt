package com.sparkhoward.nagomiani.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.bangumi.BangumiError
import com.sparkhoward.nagomiani.core.maccms.OnlineRepo
import com.sparkhoward.nagomiani.core.online.WeeklyAggregator
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlineShow
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.core.store.AppStore
import com.sparkhoward.nagomiani.player.PlaybackBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 详情页状态：条目 + 我的收藏 + 跨站片源（番 → 分集） */
class SubjectDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = BangumiAuth(app)
    private val onlineRepo = OnlineRepo()
    private val store get() = AppGraph.store

    data class State(
        val loading: Boolean = true,
        val subject: Subject? = null,
        val error: String? = null,
        val myCollection: Int? = null,
        val collectionMessage: String? = null,
        /** 收藏五态同步中（按钮区转圈） */
        val collectionSaving: Boolean = false,
        /** 是否已登录 Bangumi（决定收藏区是否提示同步不可用） */
        val isLoggedIn: Boolean = false,
        val sourcesLoading: Boolean = false,
        val sourcesError: String? = null,
        /** 每站搜索结果概览（如「量子资源 2 · 极速资源 0 · 暴风资源 失败」） */
        val sourcesStatus: String? = null,
        /** 片源（番 → 已解析分集） */
        val sources: List<Pair<OnlineShow, List<OnlineEpisode>>> = emptyList(),
        /** 已加入云端番库的 seriesKey（控制"加入番库"按钮态） */
        val bookmarkedKeys: Set<String> = emptySet(),
        /** 已看集号（有绑定时从 Bangumi 拉取） */
        val watchedNumbers: Set<Int> = emptySet(),
        /** 未放送集号 → 官方 airdate（放送门禁：在线源里这些集置灰禁播，开播当天自动解禁） */
        val unairedEpisodes: Map<Int, String> = emptyMap(),
    )

    val state = MutableStateFlow(State())
    /** 各站点最近测速结果（番库「片源健康」面板写入，这里只读展示徽章；key=providerID） */
    val siteHealth = store.siteHealthMap.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    /** 离线缓存状态（resumeKey → 状态/进度），详情页方框与缓存按钮共用 */
    val downloadStates = com.sparkhoward.nagomiani.core.download.DownloadUtil.states
    private var current: Subject? = null
    /** 收藏修改世代：快速连点两个状态时，过期响应不得覆盖新选择 */
    private var collectionGeneration = 0

    fun load(subjectID: Int) {
        // 同条目重进（播放器/登录页返回）：不整页重置——State(loading=true) 会把已渲染的
        // 条目/片源清空成转圈，还要重新跨站搜片；只静默刷新会话中会变化的动态数据
        val sameSubject = current?.id == subjectID
        if (!sameSubject) state.value = State(loading = true)
        viewModelScope.launch {
            // 登录态决定收藏区提示（从登录页返回后要及时反映）
            state.value = state.value.copy(isLoggedIn = auth.isLoggedIn())
            if (sameSubject) {
                loadBookmarked()
                loadEpisodeMeta(subjectID)
                // 收藏态也要刷新：播放器看完自动同步会补「在看」，静默重进不能让五态按钮停在旧状态
                if (auth.isLoggedIn()) {
                    runCatching {
                        val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                        authed.myCollectionOf(subjectID)?.let { c ->
                            state.value = state.value.copy(myCollection = c.type.takeIf { t -> t > 0 })
                        }
                    }
                }
                return@launch
            }
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                val subject = authed.subject(subjectID)
                current = subject
                var myCollection: Int? = null
                if (auth.isLoggedIn()) {
                    myCollection = runCatching { authed.myCollectionOf(subjectID) }.getOrNull()
                        ?.let { it.type.takeIf { t -> t > 0 } }
                }
                state.value = state.value.copy(loading = false, subject = subject, myCollection = myCollection)
                loadBookmarked()
                loadEpisodeMeta(subjectID)
                searchSources(subject.displayName)
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false, error = "加载失败：${e.message}")
            }
        }
    }

    /** 载入云端番库中已有的 seriesKey（控制"加入番库"按钮态） */
    private suspend fun loadBookmarked() {
        val keys = store.cloudLibraryOnce().map { it.seriesKey }.toSet()
        state.value = state.value.copy(bookmarkedKeys = keys)
    }

    /** 分集元数据：已看徽章（登录时）+ 未放送门禁。分集表是公开端点，未登录也拉——门禁不依赖登录态 */
    private suspend fun loadEpisodeMeta(subjectID: Int) {
        try {
            val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
            val eps = authed.allEpisodes(subjectID)
            // 放送日期以 JST 编排（与 Bangumi /calendar 一致），不能用设备本地"今天"判定，
            // 否则 JST 已开播而本地未到（如东九区以西）会多锁一天，反之会提前解锁
            val unaired = WeeklyAggregator.unairedAirdates(eps, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")))
            val watched = if (auth.isLoggedIn()) {
                runCatching { authed.myEpisodeCollections(subjectID) }.getOrDefault(emptyList())
                    .filter { it.type == 2 }.mapNotNull { mark ->
                        eps.firstOrNull { it.id == mark.episodeId }?.displaySort
                    }.toSet()
            } else emptySet()
            state.value = state.value.copy(watchedNumbers = watched, unairedEpisodes = unaired)
        } catch (_: Exception) {
        }
    }

    /** 跨站片源搜索：中文名 → 日文原名 → infobox 别名逐词全站搜（≥12 字长名自动补主名短段降级，
     *  超长译名原文做 wd 子串匹配几乎必落空）；
     *  评分与 mac 版同口径（cleanTitle 双侧清洗 + 0.4 阈值 + 季号加分），体育/综艺等垃圾源由此排除；
     *  TV 条目排除剧场版候选（同系列电影必含主名，包含关系保底挡不住）；
     *  短时间重进页面走会话缓存（5 分钟 TTL），手动"刷新"绕过缓存强制重搜 */
    private suspend fun searchSources(title: String, useCache: Boolean = true) {
        val subjectID = current?.id ?: return
        if (useCache) {
            com.sparkhoward.nagomiani.core.online.SourceSearchCache.get(subjectID)?.let { cached ->
                state.value = state.value.copy(
                    sources = cached.sources,
                    sourcesStatus = cached.status,
                    sourcesLoading = false,
                    sourcesError = null,
                )
                return
            }
        }
        state.value = state.value.copy(sourcesLoading = true, sourcesError = null, sourcesStatus = null)
        try {
            val subject = current
            val keywords = subject
                ?.let { WeeklyAggregator.searchKeywords(title, it.name, subjectAliases()) }
                ?: listOf(title)
            // 条目是否剧场版（platform 优先、中/日主名兜底），传给每轮评分做类型分流
            val subjectIsMovie = subject
                ?.let { WeeklyAggregator.isMovieSubject(it.displayName, it.name, it.platform) }
                ?: false
            val scored = HashMap<String, Pair<OnlineShow, Double>>()
            val statusParts = ArrayList<String>()
            for (kw in keywords) {
                val siteResults = onlineRepo.searchAcrossSites(kw)
                statusParts += "$kw(" + siteResults.joinToString("·") { r ->
                    if (r.error != null) "${r.label}✗" else "${r.label}${r.shows.size}"
                } + ")"
                for ((show, score) in WeeklyAggregator.score(kw, siteResults.flatMap { it.shows }, subjectIsMovie)) {
                    val prev = scored[show.seriesKey]
                    if (prev == null || prev.second < score) scored[show.seriesKey] = show to score
                }
            }
            val flat = scored.values.sortedByDescending { it.second }.map { it.first }.take(10)
            // 并行取各站分集（串行时手机上要等 10s+；显式 IO 避免任何主线程工作）
            val pairs: List<Pair<OnlineShow, List<OnlineEpisode>>> = coroutineScope {
                flat.map { show -> async(Dispatchers.IO) { show to onlineRepo.episodes(show) } }
                    .awaitAll()
                    .filter { (_, eps) -> eps.isNotEmpty() }
                    .take(8)
            }
            val statusText = statusParts.joinToString("  ")
            state.value = state.value.copy(
                sourcesLoading = false,
                sources = pairs,
                sourcesStatus = statusText,
                sourcesError = if (pairs.isEmpty()) "未找到可用片源" else null,
            )
            com.sparkhoward.nagomiani.core.online.SourceSearchCache.put(subjectID, pairs, statusText)
        } catch (e: Exception) {
            state.value = state.value.copy(sourcesLoading = false, sourcesError = "片源搜索失败：${e.message}")
        }
    }

    /** infobox 别名展平（与详情页展示同规则），供片源搜索用 */
    private fun subjectAliases(): List<String> {
        val v = current?.infobox?.firstOrNull { it.key == "别名" }?.value ?: return emptyList()
        return when (v) {
            is kotlinx.serialization.json.JsonPrimitive -> listOf(v.content)
            is kotlinx.serialization.json.JsonObject ->
                listOfNotNull((v["v"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
            is kotlinx.serialization.json.JsonArray -> v.mapNotNull { e ->
                when (e) {
                    is kotlinx.serialization.json.JsonPrimitive -> e.content
                    is kotlinx.serialization.json.JsonObject ->
                        (e["v"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    else -> null
                }
            }
            else -> emptyList()
        }.filter { it.isNotBlank() && it.length >= 2 }
    }

    /** 手动重试片源搜索（绕过会话缓存，强制全站重搜并回写缓存） */
    fun retrySources() {
        val title = current?.displayName ?: return
        viewModelScope.launch { searchSources(title, useCache = false) }
    }

    /** 该番在当前片源列表里的总集数（入库播种「已看基线」用：否则更新提醒/角标无从判新） */
    private fun knownEpisodeCount(seriesKey: String): Int =
        state.value.sources.firstOrNull { it.first.seriesKey == seriesKey }?.second?.size ?: 0

    /** 点播：自动加入云端番库（收藏）+ 绑定（保证看完同步可用），装配播放参数；
     *  取流成功后才回调 [onLaunched]（导航去播放器），失败在本页提示 */
    fun launchEpisode(show: OnlineShow, episode: OnlineEpisode, onLaunched: () -> Unit = {}) {
        viewModelScope.launch {
            // 放送门禁兜底（UI 已置灰禁点）：官方 airdate 未到的集不放行
            state.value.unairedEpisodes[episode.number]?.let { airdate ->
                state.value = state.value.copy(sourcesError = "第${episode.number}集 ${airdate} 开播后可看")
                return@launch
            }
            // 首次点播自动绑定到当前条目（与 mac 版"在线页点播自动关联"一致）
            if (store.bindingSubjectID(show.seriesKey) == null) {
                current?.let { subject ->
                    store.bind(show.seriesKey, subject.id, subject.displayName)
                }
            }
            // 未收藏则补「在看」（未收藏条目 Bangumi 不允许标记单集看过）：不再仅限首次绑定，
            // 覆盖"未登录时首播、登录后补看"漏收藏的场景；已有收藏状态不覆盖
            if (auth.isLoggedIn()) {
                current?.let { subject ->
                    runCatching {
                        val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                        if (authed.myCollectionOf(subject.id) == null) {
                            authed.updateCollection(
                                subject.id,
                                CollectionModifyPayload(type = CollectionType.DOING.raw),
                            )
                            state.value = state.value.copy(myCollection = CollectionType.DOING.raw)
                        }
                    }
                }
            }
            store.upsertCloudEntry(
                AppStore.CloudEntry(
                    providerID = show.providerID,
                    showID = show.showID,
                    title = show.title,
                    subtitle = show.subtitle,
                    coverURL = show.coverURL,
                    lastSeenEpisodeCount = knownEpisodeCount(show.seriesKey),
                ),
            )
            state.value = state.value.copy(bookmarkedKeys = state.value.bookmarkedKeys + show.seriesKey)
            val playback = onlineRepo.preparePlayback(show, episode)
            if (playback != null) {
                PlaybackBus.launch(playback)
                onLaunched()
            } else {
                state.value = state.value.copy(sourcesError = "取流失败，请稍后重试")
            }
        }
    }

    /** 收藏片源（加入云端番库）+ 绑定 */
    fun bookmark(show: OnlineShow) {
        val subject = current ?: return
        viewModelScope.launch {
            store.upsertCloudEntry(
                AppStore.CloudEntry(
                    providerID = show.providerID,
                    showID = show.showID,
                    title = show.title,
                    subtitle = show.subtitle,
                    coverURL = show.coverURL,
                    lastSeenEpisodeCount = knownEpisodeCount(show.seriesKey),
                ),
            )
            store.bind(show.seriesKey, subject.id, subject.displayName)
            state.value = state.value.copy(
                collectionMessage = "已加入云端番库「${show.title}」",
                bookmarkedKeys = state.value.bookmarkedKeys + show.seriesKey,
            )
        }
    }

    // MARK: - 离线缓存

    /** 单集缓存开关：长按集数方框触发。未缓存→下载；下载中/已完成→取消并移除。未放送的集不缓存 */
    fun toggleEpisodeCache(show: OnlineShow, episode: OnlineEpisode) {
        if (state.value.unairedEpisodes.containsKey(episode.number)) return
        val url = episode.streamHint ?: return
        val id = episode.resumeKey
        val st = downloadStates.value[id]
        if (st != null && (st.isCompleted || st.isActive)) {
            com.sparkhoward.nagomiani.core.download.DownloadUtil.removeDownload(id)
        } else {
            com.sparkhoward.nagomiani.core.download.DownloadUtil.addDownload(id, url, show.title)
        }
    }

    /** 批量缓存传入的集（"缓存全部"与"选集缓存"共用；跳过已完成/下载中/未放送的集）；
     *  同时自动绑定当前条目——看完同步依赖绑定表，只缓存不点播的番也要能同步 */
    fun cacheEpisodes(show: OnlineShow, episodes: List<OnlineEpisode>) {
        val subject = current
        viewModelScope.launch {
            if (subject != null && store.bindingSubjectID(show.seriesKey) == null) {
                store.bind(show.seriesKey, subject.id, subject.displayName)
            }
        }
        val util = com.sparkhoward.nagomiani.core.download.DownloadUtil
        val unaired = state.value.unairedEpisodes
        var added = 0
        var skippedUnaired = 0
        for (ep in episodes) {
            val url = ep.streamHint ?: continue
            if (unaired.containsKey(ep.number)) { skippedUnaired++; continue }
            val st = util.states.value[ep.resumeKey]
            if (st != null && (st.isCompleted || st.isActive)) continue
            util.addDownload(ep.resumeKey, url, show.title, show.coverURL)
            added++
        }
        // 批量缓存视为「已知当前集数」：播种已看基线，让更新提醒从这一刻起判新
        if (episodes.isNotEmpty()) {
            viewModelScope.launch {
                store.upsertCloudEntry(
                    AppStore.CloudEntry(
                        providerID = show.providerID,
                        showID = show.showID,
                        title = show.title,
                        subtitle = show.subtitle,
                        coverURL = show.coverURL,
                        lastSeenEpisodeCount = episodes.size,
                    ),
                )
            }
        }
        when {
            added > 0 -> state.value = state.value.copy(
                collectionMessage = "已加入 ${added} 集离线缓存" +
                    if (skippedUnaired > 0) "（跳过 ${skippedUnaired} 集未开播）" else "",
            )
            skippedUnaired > 0 -> state.value = state.value.copy(
                collectionMessage = "有 ${skippedUnaired} 集未开播，开播后才能缓存",
            )
        }
    }

    /** 修改我的收藏（五态按钮）：POST /collections 为 upsert——未收藏条目直接创建；
     *  未登录时不同步只提示（有绑定的条目看完同步由播放器离线队列兜底，这里不做本地伪装态） */
    fun setCollection(type: CollectionType) {
        val subject = current ?: return
        if (state.value.myCollection == type.raw) return // 已是该状态：避免重复请求
        val generation = ++collectionGeneration
        viewModelScope.launch {
            if (!auth.isLoggedIn()) {
                state.value = state.value.copy(collectionMessage = "未登录 Bangumi，无法同步收藏状态")
                return@launch
            }
            state.value = state.value.copy(collectionSaving = true)
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                authed.updateCollection(subject.id, CollectionModifyPayload(type = type.raw))
                if (generation != collectionGeneration) return@launch
                state.value = state.value.copy(
                    collectionSaving = false,
                    myCollection = type.raw,
                    collectionMessage = "已标记「${type.label}」并同步到 Bangumi ✓",
                )
            } catch (e: Exception) {
                if (generation != collectionGeneration) return@launch
                state.value = state.value.copy(
                    collectionSaving = false,
                    collectionMessage = "收藏失败：${BangumiError.describe(e)}",
                )
            }
        }
    }

    fun removeCollection() {
        viewModelScope.launch {
            if (!auth.isLoggedIn()) {
                state.value = state.value.copy(collectionMessage = "未登录 Bangumi，无法同步收藏状态")
                return@launch
            }
            // Bangumi v0 无取消条目收藏端点（/v0/users/-/collections/{subject_id} 只有 POST/PATCH，
            // 且 type 合法值为 1–5，发送 0 必返回 400）——不发起注定失败的请求，如实提示
            state.value = state.value.copy(collectionMessage = "Bangumi API 暂不支持取消条目收藏，可在网页端操作")
        }
    }
}
