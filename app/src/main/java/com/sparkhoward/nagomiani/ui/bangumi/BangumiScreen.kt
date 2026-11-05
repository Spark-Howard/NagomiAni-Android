package com.sparkhoward.nagomiani.ui.bangumi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.model.BangumiUser
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.UserSubjectCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 追番页：登录态 + 收藏五态列表（可排序，全量翻页拉取） */
class BangumiViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = BangumiAuth(app)

    /** 收藏列表端点按用户名寻址（me() 后缓存） */
    private var currentUsername: String? = null

    /** 列表排序（收藏条目自带全部排序字段，纯本地排） */
    enum class Sort(val label: String) { DEFAULT("默认"), UPDATED("最近更新"), RATED("我的评分"), AIR_DATE("放送日期") }

    data class State(
        val loading: Boolean = true,
        /** 手动刷新中（已有内容、原地刷新，页头按钮转圈） */
        val refreshing: Boolean = false,
        val user: BangumiUser? = null,
        val selected: CollectionType = CollectionType.DOING,
        val sort: Sort = Sort.DEFAULT,
        val collections: List<UserSubjectCollection> = emptyList(),
        val collectionsLoading: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())
    /** 收藏列表请求世代：慢网络上快速切分类时，过期响应不得覆盖新分类的列表 */
    private var collectionsGeneration = 0

    init {
        refresh()
    }

    /** 手动刷新：重拉用户信息 + 当前分类收藏列表。
     *  已有内容时原地刷新（不整页替换，页头按钮转圈）；仅首次加载才整页 spinner */
    fun refresh() {
        viewModelScope.launch {
            if (!auth.isLoggedIn()) {
                state.value = State(loading = false)
                return@launch
            }
            val hasContent = state.value.user != null
            state.value = state.value.copy(loading = !hasContent, refreshing = hasContent)
            try {
                val api = BangumiApi { auth.refreshIfNeeded().accessToken }
                val user = api.me()
                // 收藏列表端点按用户名寻址，先存下（未设用户名则退回 UID）
                currentUsername = user.username.ifBlank { user.id.toString() }
                state.value = state.value.copy(loading = false, refreshing = false, user = user, error = null)
                loadCollections()
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false, refreshing = false, error = "加载失败：${e.message}")
            }
        }
    }

    /** 页面重新进入时调用：登录页返回后刷新登录态（未登录/加载中则跳过） */
    fun refreshIfLoggedOut() {
        val s = state.value
        if (s.user == null && !s.loading) refresh()
    }

    fun selectType(type: CollectionType) {
        state.value = state.value.copy(selected = type)
        loadCollections()
    }

    fun setSort(sort: Sort) {
        state.value = state.value.copy(sort = sort)
    }

    private fun loadCollections() {
        val username = currentUsername ?: return // 未取到用户名前无法调列表端点
        val type = state.value.selected
        val generation = ++collectionsGeneration
        viewModelScope.launch {
            state.value = state.value.copy(collectionsLoading = true)
            try {
                val api = BangumiApi { auth.refreshIfNeeded().accessToken }
                // 全量翻页：单页 100 条会截断长收藏（allCollections 内含 total 熔断）
                val all = api.allCollections(username, type)
                if (generation != collectionsGeneration) return@launch
                state.value = state.value.copy(collectionsLoading = false, collections = all)
            } catch (e: Exception) {
                if (generation != collectionsGeneration) return@launch
                state.value = state.value.copy(collectionsLoading = false, error = "收藏加载失败：${e.message}")
            }
        }
    }

    /** 排序后的列表（ISO 日期/时间串按字典序即时间序，同款端点同格式） */
    fun displayed(): List<UserSubjectCollection> {
        val s = state.value
        return when (s.sort) {
            Sort.DEFAULT -> s.collections
            Sort.UPDATED -> s.collections.sortedByDescending { it.updatedAt ?: "" }
            Sort.RATED -> s.collections.sortedByDescending { it.rate ?: 0 }
            Sort.AIR_DATE -> s.collections.sortedByDescending { it.subject?.airDate ?: "" }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            state.value = State(loading = false)
            onDone()
        }
    }
}
