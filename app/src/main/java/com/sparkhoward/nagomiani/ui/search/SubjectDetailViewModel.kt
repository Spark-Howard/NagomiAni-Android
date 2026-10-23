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
import com.sparkhoward.nagomiani.core.model.Episode
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
import java.time.LocalDate
import java.time.ZoneId

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
        /** 我的评分/短评/进度（myCollectionOf 完整对象，评分短评区回显与 ep_status 对账用） */
        val myRate: Int? = null,
        val myComment: String? = null,
        val myEpStatus: Int? = null,
        /** 收藏状态查询失败原因（401/网络错等）。null=查询成功（未收藏也是成功）——
         *  不再吞错伪装成「未收藏」，让用户看得到为什么状态缺失 */
        val collectionError: String? = null,
        /** 分集标记：本篇+SP 格子数据 / 已看分集 id 集 / 标记请求进行中（网格禁点防双击） */
        val episodes: List<Episode> = emptyList(),
        val episodesLoading: Boolean = false,
        val watchedEpisodeIds: Set<Int> = emptySet(),
        val episodeSaving: Boolean = false,
        /** 分集/看过记录加载失败原因（不再静默吞成「没有看过标记」） */
        val episodeMarksError: String? = null,
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
    /** 登录用户名缓存（me() 只取一次；未设用户名时用 UID 数字）——收藏单条查询按名寻址 */
    private var usernameCache: String? = null

    /** 解析当前登录用户名；失败返回 null（token 过期/网络失败，由调用方亮出） */
    private suspend fun resolveUsername(authed: BangumiApi): String? {
        usernameCache?.let { return it }
        val me = runCatching { authed.me() }.getOrNull() ?: return null
        return me.username.ifBlank { me.id.toString() }.also { usernameCache = it }
    }

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
                // 先刷新收藏再载分集：ep_status（看到第 N 集）是分集「已看」合并展示的输入之一
                if (auth.isLoggedIn()) {
                    try {
                        val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                        val username = resolveUsername(authed)
                            ?: throw BangumiError.Unauthorized() // me() 失败：按登录已过期亮出
                        authed.myCollectionOf(username, subjectID)?.let { c ->
                            state.value = state.value.copy(
                                myCollection = c.type.takeIf { t -> t > 0 },
                                myRate = c.rate?.takeIf { it > 0 },
                                myComment = c.comment,
                                myEpStatus = c.epStatus,
                                collectionError = null,
                            )
                        }
                    } catch (e: Exception) {
                        state.value = state.value.copy(
                            collectionError = "收藏状态查询失败：${BangumiError.describe(e)}",
                        )
                    }
                }
                loadEpisodeMeta(subjectID)
                return@launch
            }
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                val subject = authed.subject(subjectID)
                current = subject
                // 404（tryGet → null）才是确认未收藏；401/网络错等必须亮出来，
                // 否则查询失败会被呈现成「未收藏」，用户明明登录着却查不到状态
                var collection: com.sparkhoward.nagomiani.core.model.UserSubjectCollection? = null
                var collectionError: String? = null
                if (auth.isLoggedIn()) {
                    try {
                        val username = resolveUsername(authed)
                            ?: throw BangumiError.Unauthorized()
                        collection = authed.myCollectionOf(username, subjectID)
                    } catch (e: Exception) {
                        collectionError = "收藏状态查询失败：${BangumiError.describe(e)}"
                    }
                }
                state.value = state.value.copy(
                    loading = false,
                    subject = subject,
                    myCollection = collection?.type?.takeIf { it > 0 },
                    myRate = collection?.rate?.takeIf { it > 0 },
                    myComment = collection?.comment,
                    myEpStatus = collection?.epStatus,
                    collectionError = collectionError,
                )
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
        state.value = state.value.copy(episodesLoading = true, episodeMarksError = null)
        try {
            val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
            val eps = authed.allEpisodes(subjectID)
            // 放送日期以 JST 编排（与 Bangumi /calendar 一致），不能用设备本地"今天"判定，
            // 否则 JST 已开播而本地未到（如东九区以西）会多锁一天，反之会提前解锁
            val unaired = WeeklyAggregator.unairedAirdates(eps, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")))
            // 看过记录只拉一次（原来拉了两次）：watchedNumbers 给在线片源格子（集号），watchedEpisodeIds 给标记格子（分集 id）。
            // 404 = 条目无分集记录/不可见（如私密收藏）：不算错误，「看到」进度会兜底展示
            val marked = if (auth.isLoggedIn()) {
                try {
                    authed.myEpisodeCollections(subjectID).filter { it.type == 2 }
                } catch (e: BangumiError.Http) {
                    if (e.code != 404) {
                        state.value = state.value.copy(
                            episodeMarksError = "看过记录查询失败：${BangumiError.describe(e)}",
                        )
                    }
                    emptyList()
                } catch (e: Exception) {
                    // 查询失败不等于「没看过」：亮出原因，格子照常渲染（只是没有已看标记）
                    state.value = state.value.copy(
                        episodeMarksError = "看过记录查询失败：${BangumiError.describe(e)}",
                    )
                    emptyList()
                }
            } else emptyList()
            val gridEps = eps.filter { it.type == 0 || it.type == 1 }.sortedBy { it.sort } // 本篇 + SP
            // 「看过」与「看到」是两路独立信号：分集标记是逐集记录；ep_status 是收藏上的进度数字
            // （网页端拖进度只改它、不生成分集记录，Bangumi 不保证两者同步）——
            // 展示取并集：本篇集号 ≤ 看到进度 即视为已看，逐集标记在进度之外单独点亮（含 SP）
            val epStatus = state.value.myEpStatus ?: 0
            val epStatusWatched = eps.filter { it.type == 0 && it.displaySort <= epStatus }
            val markedSorts = marked.mapNotNull { m -> eps.firstOrNull { it.id == m.episodeId }?.displaySort }
            state.value = state.value.copy(
                watchedNumbers = (markedSorts + epStatusWatched.map { it.displaySort }).toSet(),
                unairedEpisodes = unaired,
                episodes = gridEps,
                episodesLoading = false,
                watchedEpisodeIds = marked.map { it.episodeId }.toSet() + epStatusWatched.map { it.id },
            )
        } catch (e: Exception) {
            // 旧代码这里静默吞掉且不复位 episodesLoading——分集区永远转圈
            state.value = state.value.copy(
                episodesLoading = false,
                episodeMarksError = "分集信息加载失败：${BangumiError.describe(e)}",
            )
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
            // 覆盖"未登录时首播、登录后补看"漏收藏的场景；已有收藏状态不覆盖。
            // myCollectionOf 只有确认未收藏（404）才返回 null，其余错误抛异常走 catch——
            // 补收藏失败不阻断点播主流程
            if (auth.isLoggedIn()) {
                current?.let { subject ->
                    try {
                        val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                        val username = resolveUsername(authed)
                        if (username != null && authed.myCollectionOf(username, subject.id) == null) {
                            authed.updateCollection(
                                subject.id,
                                CollectionModifyPayload(type = CollectionType.DOING.raw),
                            )
                            state.value = state.value.copy(myCollection = CollectionType.DOING.raw)
                        }
                    } catch (_: Exception) {
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

    // MARK: - 收藏评分与短评

    /** 保存评分/短评：rate 2..10（5 星 ×2，Bangumi 1-10 分制）；comment 空串 = 显式清除短评。
     *  payload 只带 type/rate/comment——绝不带 rate=0（删评分语义）与 vol_status（书籍专用） */
    fun saveRating(rate: Int?, comment: String) {
        val subject = current ?: return
        val generation = ++collectionGeneration
        viewModelScope.launch {
            if (!auth.isLoggedIn()) {
                state.value = state.value.copy(collectionMessage = "未登录 Bangumi，无法同步评分")
                return@launch
            }
            state.value = state.value.copy(collectionSaving = true)
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                authed.updateCollection(
                    subject.id,
                    CollectionModifyPayload(
                        type = state.value.myCollection ?: CollectionType.DOING.raw, // 未收藏时 upsert 建「在看」
                        rate = rate, // null = 不动评分（从未打星时只存短评）
                        comment = comment,
                    ),
                )
                if (generation != collectionGeneration) return@launch
                state.value = state.value.copy(
                    collectionSaving = false,
                    myRate = rate ?: state.value.myRate, // 只存短评时不动评分
                    myComment = comment,
                    collectionMessage = "评分已同步到 Bangumi ✓",
                )
            } catch (e: Exception) {
                if (generation != collectionGeneration) return@launch
                state.value = state.value.copy(
                    collectionSaving = false,
                    collectionMessage = "评分保存失败：${BangumiError.describe(e)}",
                )
            }
        }
    }

    // MARK: - 分集标记

    /** 分集是否未开播（JST 口径；airdate 缺失视为已开播——宁可放过不误杀） */
    fun isUnaired(ep: Episode): Boolean {
        val raw = ep.airdate?.trim().orEmpty()
        if (raw.length < 10) return false
        val date = runCatching { LocalDate.parse(raw.substring(0, 10)) }.getOrNull() ?: return false
        return date.isAfter(LocalDate.now(ZoneId.of("Asia/Tokyo")))
    }

    /** 点格子切换标记（已看 → 取消 type=0；未看 → 看过 type=2），随后把 ep_status 对账到「已看本篇最大集」 */
    fun toggleEpisode(ep: Episode) {
        val subject = current ?: return
        val s = state.value
        if (s.episodeSaving) return
        val marking = ep.id !in s.watchedEpisodeIds
        viewModelScope.launch {
            if (!auth.isLoggedIn()) {
                state.value = state.value.copy(collectionMessage = "未登录 Bangumi，无法标记看过")
                return@launch
            }
            state.value = state.value.copy(episodeSaving = true)
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                authed.markEpisodes(subject.id, listOf(ep.id), type = if (marking) 2 else 0)
                val watched = if (marking) s.watchedEpisodeIds + ep.id else s.watchedEpisodeIds - ep.id
                state.value = state.value.copy(episodeSaving = false, watchedEpisodeIds = watched)
                syncEpStatus(authed, subject.id, watched)
            } catch (e: Exception) {
                state.value = state.value.copy(
                    episodeSaving = false,
                    collectionMessage = "标记失败：${BangumiError.describe(e)}",
                )
            }
        }
    }

    /** 批量：全部本篇/SP 标为看过，或清除全部标记（UI 侧已加确认弹窗） */
    fun markAllEpisodes(all: Boolean) {
        val subject = current ?: return
        val s = state.value
        if (s.episodeSaving || s.episodes.isEmpty()) return
        viewModelScope.launch {
            if (!auth.isLoggedIn()) {
                state.value = state.value.copy(collectionMessage = "未登录 Bangumi，无法标记看过")
                return@launch
            }
            state.value = state.value.copy(episodeSaving = true)
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                val ids = s.episodes.map { it.id }
                if (all) {
                    // PATCH 单请求体过大风险：长番 1000+ 集，按 300 分批
                    ids.chunked(300).forEach { authed.markEpisodes(subject.id, it, type = 2) }
                } else {
                    val watched = ids.filter { it in s.watchedEpisodeIds }
                    watched.chunked(300).forEach { authed.markEpisodes(subject.id, it, type = 0) }
                }
                val newWatched = if (all) ids.toSet() else emptySet()
                state.value = state.value.copy(episodeSaving = false, watchedEpisodeIds = newWatched)
                syncEpStatus(authed, subject.id, newWatched)
                state.value = state.value.copy(collectionMessage = if (all) "已全部标记看过 ✓" else "已清除全部标记 ✓")
            } catch (e: Exception) {
                state.value = state.value.copy(
                    episodeSaving = false,
                    collectionMessage = "批量标记失败：${BangumiError.describe(e)}",
                )
            }
        }
    }

    /** ep_status 对账：只增不减——已看本篇最大集超过收藏进度时推进（不回退用户手动改的进度） */
    private suspend fun syncEpStatus(authed: BangumiApi, subjectId: Int, watched: Set<Int>) {
        val collection = state.value.myCollection ?: return // 未收藏不管
        val watchedMain = state.value.episodes
            .filter { it.type == 0 && it.id in watched }
            .maxOfOrNull { it.displaySort } ?: return
        val current = state.value.myEpStatus ?: 0
        if (watchedMain > current) {
            authed.updateCollection(subjectId, CollectionModifyPayload(type = collection, epStatus = watchedMain))
            state.value = state.value.copy(myEpStatus = watchedMain)
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
