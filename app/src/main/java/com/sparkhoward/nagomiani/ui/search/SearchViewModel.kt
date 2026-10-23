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
        val generation = ++searchGeneration
        viewModelScope.launch {
            state.value = state.value.copy(searching = true, error = null)
            try {
                val page = api().searchSubjects(keyword, limit = 30)
                if (generation != searchGeneration) return@launch
                // 客户端兜底过滤：确保只留动漫（服务端 filter 失效时）
                val filtered = page.data.filter { it.type == SUBJECT_TYPE_ANIME || it.type == 0 }
                state.value = state.value.copy(searching = false, results = filtered)
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                state.value = state.value.copy(searching = false, results = emptyList(), error = "搜索失败：${e.message}")
            }
        }
    }

    /** 新番放送（近 7 天 + 未来 7 天，日期升序；UI 初始定位今天）——/calendar 即"每周放送表"，随今天逐日向前滚动 */
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
}
