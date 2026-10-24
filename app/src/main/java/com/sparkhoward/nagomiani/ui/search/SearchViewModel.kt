package com.sparkhoward.nagomiani.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.model.SUBJECT_TYPE_ANIME
import com.sparkhoward.nagomiani.core.model.Subject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 搜索页状态：搜索结果 / 默认"新番放送"周历 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = BangumiAuth(app)
    private fun api() = BangumiApi { null } // 搜索/日历无需登录；收藏徽章等登录态接口走 auth()

    data class State(
        val keyword: String = "",
        val searching: Boolean = false,
        val results: List<Subject> = emptyList(),
        val loadingMore: Boolean = false,
        val canLoadMore: Boolean = false,
        val weekly: List<com.sparkhoward.nagomiani.core.online.WeeklyCalendar.Slot> = emptyList(),
        val weeklyLoading: Boolean = false,
        val weeklyError: String? = null,
        val weeklyFetchedAt: String? = null,
        val collectionBadge: Map<Int, Int> = emptyMap(), // subjectID → 收藏态 raw（登录时显示）
        val error: String? = null,
    )

    val state = MutableStateFlow(State())
    private var searchGeneration = 0
    private var weeklyGeneration = 0
    private var currentQuery = ""

    init {
        loadWeekly()
        loadCollectionBadges()
    }

    fun updateKeyword(value: String) {
        state.value = state.value.copy(keyword = value)
    }

    fun search() {
        val keyword = state.value.keyword.trim()
        if (keyword.isEmpty()) return
        currentQuery = keyword
        val generation = ++searchGeneration
        viewModelScope.launch {
            state.value = state.value.copy(searching = true, error = null, results = emptyList(), canLoadMore = false)
            try {
                val page = api().searchSubjects(keyword, limit = PAGE_SIZE, offset = 0)
                if (generation != searchGeneration) return@launch
                // 客户端兜底过滤：确保只留动漫（服务端 filter 失效时）
                val filtered = page.data.filter { it.type == SUBJECT_TYPE_ANIME || it.type == 0 }
                state.value = state.value.copy(
                    searching = false,
                    results = filtered,
                    canLoadMore = filtered.isNotEmpty() && filtered.size < page.total,
                )
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                state.value = state.value.copy(searching = false, results = emptyList(), canLoadMore = false, error = "搜索失败：${e.message}")
            }
        }
    }

    /** 加载更多（30/页，offset 续拉；按 id 去重，整页重复也终止——自动加载不会死循环） */
    fun loadMore() {
        val s = state.value
        if (s.searching || s.loadingMore || !s.canLoadMore) return
        val generation = searchGeneration
        viewModelScope.launch {
            state.value = state.value.copy(loadingMore = true)
            try {
                val page = api().searchSubjects(currentQuery, limit = PAGE_SIZE, offset = s.results.size)
                if (generation != searchGeneration) return@launch
                val existing = s.results.map { it.id }.toHashSet()
                val fresh = page.data
                    .filter { it.type == SUBJECT_TYPE_ANIME || it.type == 0 }
                    .filterNot { existing.contains(it.id) }
                val merged = s.results + fresh
                state.value = state.value.copy(
                    loadingMore = false,
                    results = merged,
                    canLoadMore = fresh.isNotEmpty() && merged.size < page.total,
                )
            } catch (_: Exception) {
                if (generation != searchGeneration) return@launch
                // 保留 canLoadMore，用户可点「加载更多」重试
                state.value = state.value.copy(loadingMore = false)
            }
        }
    }

    /** 新番放送（今天起未来一周，按天分组带真实日期）——/calendar 即"每周放送表"，随今天逐日向前滚动 */
    fun loadWeekly() {
        val generation = ++weeklyGeneration
        viewModelScope.launch {
            state.value = state.value.copy(weeklyLoading = true, weeklyError = null)
            try {
                val days = api().calendar()
                if (generation != weeklyGeneration) return@launch // 手动刷新已发出，过期响应不落地
                // /calendar 以日本时区（JST）编排放送日；槽位日期必须同区，否则跨天时段会错位一天
                val today = LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"))
                state.value = state.value.copy(
                    weekly = com.sparkhoward.nagomiani.core.online.WeeklyCalendar.slots(today, days),
                    weeklyLoading = false,
                    weeklyFetchedAt = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                )
            } catch (e: Exception) {
                if (generation != weeklyGeneration) return@launch
                state.value = state.value.copy(weeklyLoading = false, weeklyError = "加载失败：${e.message}")
            }
        }
    }

    /** 我的收藏徽章（登录时给搜索结果加"想看/在看"角标）。列表端点按用户名寻址，先取 me() */
    private fun loadCollectionBadges() {
        viewModelScope.launch {
            if (!auth.isLoggedIn()) return@launch
            try {
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                val me = authed.me()
                val username = me.username.ifBlank { me.id.toString() }
                val badges = HashMap<Int, Int>()
                for (type in com.sparkhoward.nagomiani.core.model.CollectionType.entries) {
                    authed.myCollections(username, type, limit = 100).data.forEach { badges[it.subjectId] = type.raw }
                }
                state.value = state.value.copy(collectionBadge = badges)
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
