package com.sparkhoward.nagomiani.ui.bangumi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.model.BangumiUser
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.UserSubjectCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 追番页：登录态 + 收藏五态列表 */
class BangumiViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = BangumiAuth(app)
    private val store get() = AppGraph.store

    /** 收藏列表端点按用户名寻址（me() 后缓存） */
    private var currentUsername: String? = null

    data class State(
        val loading: Boolean = true,
        val user: BangumiUser? = null,
        val selected: CollectionType = CollectionType.DOING,
        val collections: List<UserSubjectCollection> = emptyList(),
        val collectionsLoading: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())

    /** 应用内代理（输入草稿 + 保存结果提示） */
    val proxyDraft = MutableStateFlow("")
    val proxyMessage = MutableStateFlow<String?>(null)

    init {
        refresh()
        viewModelScope.launch { proxyDraft.value = store.networkProxyRaw.first() }
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

    fun refresh() {
        viewModelScope.launch {
            if (!auth.isLoggedIn()) {
                state.value = State(loading = false)
                return@launch
            }
            state.value = state.value.copy(loading = true)
            try {
                val api = BangumiApi { auth.refreshIfNeeded().accessToken }
                val user = api.me()
                // 收藏列表端点按用户名寻址，先存下（未设用户名则退回 UID）
                currentUsername = user.username.ifBlank { user.id.toString() }
                state.value = state.value.copy(loading = false, user = user, error = null)
                loadCollections()
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false, error = "加载失败：${e.message}")
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

    private fun loadCollections() {
        val username = currentUsername ?: return // 未取到用户名前无法调列表端点
        val type = state.value.selected
        viewModelScope.launch {
            state.value = state.value.copy(collectionsLoading = true)
            try {
                val api = BangumiApi { auth.refreshIfNeeded().accessToken }
                val page = api.myCollections(username, type, limit = 100)
                state.value = state.value.copy(collectionsLoading = false, collections = page.data)
            } catch (e: Exception) {
                state.value = state.value.copy(collectionsLoading = false, error = "收藏加载失败：${e.message}")
            }
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
