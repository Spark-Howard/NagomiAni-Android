package com.sparkhoward.nagomiani.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.model.CalendarDay
import com.sparkhoward.nagomiani.core.model.SUBJECT_TYPE_ANIME
import com.sparkhoward.nagomiani.core.model.Subject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 搜索页状态：搜索结果 / 默认"最近更新"周历 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = BangumiAuth(app)
    private fun api() = BangumiApi { null } // 搜索/日历无需登录；收藏徽章等登录态接口走 auth()

    data class State(
        val keyword: String = "",
        val searching: Boolean = false,
        val results: List<Subject> = emptyList(),
        val weekly: List<CalendarDay> = emptyList(),
        val weeklyLoading: Boolean = false,
        val weeklyError: String? = null,
        val weeklyFetchedAt: String? = null,
        val collectionBadge: Map<Int, Int> = emptyMap(), // subjectID → 收藏态 raw（登录时显示）
        val error: String? = null,
    )

    val state = MutableStateFlow(State())
    private var searchGeneration = 0

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

    /** 最近更新（过去一周，按天分组；今天在前）——/calendar 即"每周放送"，聚合规则同 mac 版 */
    fun loadWeekly() {
        viewModelScope.launch {
            state.value = state.value.copy(weeklyLoading = true, weeklyError = null)
            try {
                val days = api().calendar()
                val todayIndex = todayBangumiWeekday()
                // 重排：今天 → 前 6 天（/calendar 的 id: 1=周一…7=周日）
                val ordered = buildList {
                    for (i in 0..6) {
                        val id = ((todayIndex - i + 6) % 7) + 1
                        days.firstOrNull { it.weekday.id == id }?.let { add(it) }
                    }
                }
                state.value = state.value.copy(
                    weekly = ordered,
                    weeklyLoading = false,
                    weeklyFetchedAt = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                )
            } catch (e: Exception) {
                state.value = state.value.copy(weeklyLoading = false, weeklyError = "加载失败：${e.message}")
            }
        }
    }

    private fun todayBangumiWeekday(): Int {
        // Gregorian: 1=周日…7=周六 → Bangumi: 1=周一…7=周日
        val gregorian = LocalDate.now().dayOfWeek.value // 1=周一…7=周日
        return gregorian
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
