package com.sparkhoward.nagomiani.player

import com.sparkhoward.nagomiani.core.dandanplay.DandanplayApi
import com.sparkhoward.nagomiani.core.model.DanmakuComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 弹幕控制器：按「番名+集号」匹配 dandanplay 并拉取弹幕（与 mac 版同语义：宁缺毋滥）。
 * 内存缓存按 resumeKey（换集回来不重复请求）；换集先清旧弹幕，加载失败不残留。
 */
class DanmakuController(private val api: DandanplayApi) {

    enum class Phase { IDLE, MATCHING, LOADING, LOADED, NOT_FOUND, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val comments: List<DanmakuComment> = emptyList(),
        val statusText: String = "",
    )

    val state = MutableStateFlow(State())
    private val cache = HashMap<String, List<DanmakuComment>>()
    private var currentKey: String? = null
    private var loadJob: Job? = null

    /** 换集时调用：先切状态清旧弹幕，异步匹配+拉取 */
    fun prepare(resumeKey: String, showTitle: String, episodeNumber: Int) {
        if (currentKey == resumeKey && state.value.phase == Phase.LOADED) return
        currentKey = resumeKey
        state.value = State(Phase.MATCHING, statusText = "正在匹配剧集…")
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            val cached = cache[resumeKey]
            if (cached != null) {
                state.value = State(Phase.LOADED, comments = cached, statusText = "已加载 ${cached.size} 条弹幕")
                return@launch
            }
            val found = api.searchEpisodes(showTitle)
            if (currentKey != resumeKey) return@launch
            val episodeId = api.pickEpisodeId(found, showTitle, episodeNumber)
            if (episodeId == null) {
                state.value = State(Phase.NOT_FOUND, statusText = "弹幕库未找到对应剧集")
                return@launch
            }
            state.value = State(Phase.MATCHING, statusText = "正在拉取弹幕…")
            val comments = api.comments(episodeId)
            if (currentKey != resumeKey) return@launch
            cache[resumeKey] = comments
            if (cache.size > 10) cache.remove(cache.keys.first())
            state.value = State(Phase.LOADED, comments = comments, statusText = "已加载 ${comments.size} 条弹幕")
        }
    }

    /** 手动重新获取（忽略缓存） */
    fun refetch(resumeKey: String, showTitle: String, episodeNumber: Int) {
        cache.remove(resumeKey)
        currentKey = null
        prepare(resumeKey, showTitle, episodeNumber)
    }

    fun clear() {
        currentKey = null
        state.value = State()
    }
}

/** 弹幕设置（即时生效；持久化由 ViewModel 走 AppStore） */
data class DanmakuSettings(
    val enabled: Boolean = true,
    /** 字号（sp 口径：默认 14，滑动条 8–28 连续可调；渲染层按屏幕密度换算 px） */
    val fontSize: Float = 14f,
    val colorMode: ColorMode = ColorMode.ORIGINAL,
    /** 0xRRGGBB */
    val customColor: UInt = 0xEC6A88u,
    val opacity: Float = 1.0f,
) {
    enum class ColorMode(val label: String) { ORIGINAL("跟随弹幕"), WHITE("纯白"), CUSTOM("自定义") }
}
