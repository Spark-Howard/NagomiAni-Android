package com.sparkhoward.nagomiani.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.bangumi.BangumiError
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.Episode
import com.sparkhoward.nagomiani.core.model.Subject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** 详情页状态（合规版：条目信息 + 我的收藏管理 + 分集标记；在线片源功能已整体移除） */
class SubjectDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = BangumiAuth(app)
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
        /** 我的评分/短评（myCollectionOf 完整对象，评分短评区回显用） */
        val myRate: Int? = null,
        val myComment: String? = null,
        val myEpStatus: Int? = null,
        /** 分集标记 */
        val episodes: List<Episode> = emptyList(),
        val episodesLoading: Boolean = false,
        val watchedEpisodeIds: Set<Int> = emptySet(),
        /** 分集标记请求进行中（整个网格禁点，防双击竞态） */
        val episodeSaving: Boolean = false,
    )

    val state = MutableStateFlow(State())
    private var current: Subject? = null
    /** 收藏修改世代：快速连点两个状态时，过期响应不得覆盖新选择 */
    private var collectionGeneration = 0

    fun load(subjectID: Int) {
        // 同条目重进（登录页返回）：不整页重置——State(loading=true) 会把已渲染的
        // 条目清空成转圈；只静默刷新会话中会变化的动态数据
        val sameSubject = current?.id == subjectID
        if (!sameSubject) state.value = State(loading = true)
        viewModelScope.launch {
            // 登录态决定收藏区提示（从登录页返回后要及时反映）
            val loggedIn = auth.isLoggedIn()
            state.value = state.value.copy(isLoggedIn = loggedIn)
            if (sameSubject) {
                // 收藏态也要刷新：静默重进不能让五态按钮停在旧状态
                refreshCollectionOf(subjectID)
                return@launch
            }
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                val subject = authed.subject(subjectID)
                current = subject
                var collection = if (loggedIn) {
                    runCatching { authed.myCollectionOf(subjectID) }.getOrNull()
                } else null
                state.value = state.value.copy(
                    loading = false,
                    subject = subject,
                    myCollection = collection?.type?.takeIf { it > 0 },
                    myRate = collection?.rate?.takeIf { it > 0 },
                    myComment = collection?.comment,
                    myEpStatus = collection?.epStatus,
                )
                loadEpisodes(subjectID, loggedIn)
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false, error = "加载失败：${e.message}")
            }
        }
    }

    /** 收藏态静默刷新（只拉 1 次收藏）：已收藏返回五态，未收藏返回 null。
     *  必须无条件回写 myCollection——网页端删除收藏后 404 → null，徽章才能从旧状态恢复成「未收藏」；
     *  网络失败时 onSuccess 不触发，保留旧值避免误清。评分/短评/ep_status 随完整对象一并刷新，
     *  分集已看标记也顺带更新（网页端标记后回 App 能跟上） */
    fun refreshCollectionOf(subjectID: Int) {
        viewModelScope.launch {
            if (!auth.isLoggedIn()) return@launch
            runCatching {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                val collection = authed.myCollectionOf(subjectID)
                collection to runCatching { authed.myEpisodeCollections(subjectID) }.getOrDefault(emptyList())
            }.onSuccess { (c, marks) ->
                state.value = state.value.copy(
                    myCollection = c?.type?.takeIf { t -> t > 0 },
                    myRate = c?.rate?.takeIf { it > 0 },
                    myComment = c?.comment,
                    myEpStatus = c?.epStatus,
                    watchedEpisodeIds = marks.filter { it.type == 2 }.map { it.episodeId }.toSet(),
                )
            }
        }
    }

    /** 修改我的收藏（五态按钮）：POST /collections 为 upsert——未收藏条目直接创建；
     *  未登录时不同步只提示（不做本地伪装态） */
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

    // MARK: - 收藏评分与短评

    /** 保存评分/短评：rate 2..10（5 星 ×2）；comment 空串 = 显式清除短评。
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

    private fun loadEpisodes(subjectID: Int, loggedIn: Boolean) {
        viewModelScope.launch {
            state.value = state.value.copy(episodesLoading = true)
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                val eps = authed.allEpisodes(subjectID)
                    .filter { it.type == 0 || it.type == 1 } // 本篇 + SP（OP/ED 等不入格）
                    .sortedBy { it.sort }
                val watched = if (loggedIn) {
                    runCatching { authed.myEpisodeCollections(subjectID) }.getOrDefault(emptyList())
                        .filter { it.type == 2 }.map { it.episodeId }.toSet()
                } else emptySet()
                state.value = state.value.copy(episodesLoading = false, episodes = eps, watchedEpisodeIds = watched)
            } catch (e: Exception) {
                state.value = state.value.copy(episodesLoading = false)
            }
        }
    }

    /** 分集是否未开播（JST 口径，AGENTS 约定；airdate 缺失视为已开播——宁可放过不误杀） */
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

    /** ep_status 对账：只增不减——已看本篇最大集超过收藏进度时推进（换线路重看不回退用户手改） */
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
}
