package com.sparkhoward.nagomiani.ui.bangumi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.model.BangumiUser
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.UserSubjectCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 追番页：登录态 + 收藏五态列表（可排序，全量翻页拉取） */
class BangumiViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = BangumiAuth(app)
    private val store get() = AppGraph.store

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

    /** 今日更新提醒开关（DailyAiringNotifier 的 Worker 每天读它决定是否检查） */
    val notifyEnabled = MutableStateFlow(true)

    /** 收藏行 +1 请求进行中（按钮防抖） */
    val rowUpdating = MutableStateFlow(false)

    /** 应用内代理（输入草稿 + 保存结果提示） */
    val proxyDraft = MutableStateFlow("")
    val proxyMessage = MutableStateFlow<String?>(null)

    init {
        refresh()
        viewModelScope.launch { proxyDraft.value = store.networkProxyRaw.first() }
        viewModelScope.launch {
            store.dailyAiringEnabled.collect { notifyEnabled.value = it }
        }
    }

    fun toggleNotify(enabled: Boolean) {
        viewModelScope.launch { store.setDailyAiringEnabled(enabled) }
    }

    /** 保存应用内代理：格式 host:port；清空保存 = 恢复直连。生效由 NagomiApp 统一应用 */
    fun saveProxy() {
        val raw = proxyDraft.value.trim()
        if (raw.isNotEmpty()) {
            val idx = raw.lastIndexOf(':')
            val host = if (idx > 0) raw.substring(0, idx).trim() else ""
            val port = if (idx > 0) raw.substring(idx + 1).trim().toIntOrNull() else null
            if (host.isEmpty() || port == null || port !in 1..65535) {
                proxyMessage.value = "格式不对：应为 host:port，例如 192.168.1.5:7890"
                return
            }
        }
        viewModelScope.launch {
            store.setNetworkProxy(raw)
            proxyMessage.value = if (raw.isEmpty()) "已恢复直连" else "代理已保存并生效"
        }
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

    /** 收藏行 +1：ep_status 推进一集（payload 带原收藏态——upsert 不改五态；绝不带 rate/vol_status）。
     *  乐观更新本地列表，失败还原；总集数已知时封顶 */
    fun advanceEpisode(collection: UserSubjectCollection) {
        val total = collection.subject?.episodeCount
        val next = collection.epStatus + 1
        if (total != null && next > total) return
        if (rowUpdating.value) return
        viewModelScope.launch {
            if (!auth.isLoggedIn()) {
                state.value = state.value.copy(error = "未登录 Bangumi，无法更新进度")
                return@launch
            }
            rowUpdating.value = true
            val old = state.value.collections
            state.value = state.value.copy(
                collections = old.map {
                    if (it.subjectId == collection.subjectId) it.copy(epStatus = next) else it
                },
            )
            try {
                val api = BangumiApi { auth.refreshIfNeeded().accessToken }
                api.updateCollection(
                    collection.subjectId,
                    CollectionModifyPayload(type = collection.type, epStatus = next),
                )
            } catch (e: Exception) {
                state.value = state.value.copy(collections = old, error = "进度更新失败：${e.message ?: "网络错误"}")
            }
            rowUpdating.value = false
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
