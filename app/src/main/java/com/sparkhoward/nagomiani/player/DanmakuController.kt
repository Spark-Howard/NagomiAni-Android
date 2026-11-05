package com.sparkhoward.nagomiani.player

import com.sparkhoward.nagomiani.core.dandanplay.DandanplayApi
import com.sparkhoward.nagomiani.core.model.DanmakuComment
import com.sparkhoward.nagomiani.core.model.DanmakuMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 弹幕控制器：按「番名+集号」匹配 dandanplay 并拉取弹幕（与 mac 版同语义：宁缺毋滥）。
 * 本地文件走 MD5 指纹匹配（[episodeIdHint] 直给 episodeId，跳过标题搜索）。
 * 内存缓存按 resumeKey（换集回来不重复请求），LRU 封顶 4 集——每集可达数千条弹幕，
 * 连看一季时只保留最近几集，避免无界增长；换集先清旧弹幕，加载失败不残留。
 */
class DanmakuController(private val api: DandanplayApi) {

    enum class Phase { IDLE, MATCHING, LOADING, LOADED, NOT_FOUND, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val comments: List<DanmakuComment> = emptyList(),
        val statusText: String = "",
    )

    val state = MutableStateFlow(State())
    private val cache = object : LinkedHashMap<String, List<DanmakuComment>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DanmakuComment>>): Boolean =
            size > CACHE_MAX_EPISODES
    }
    private var currentKey: String? = null
    private var loadJob: Job? = null

    /** 换集时调用：先切状态清旧弹幕，异步匹配+拉取；[episodeIdHint] 非 null 时跳过标题匹配直取弹幕 */
    fun prepare(resumeKey: String, showTitle: String, episodeNumber: Int, episodeIdHint: Long? = null) {
        if (currentKey == resumeKey && state.value.phase == Phase.LOADED) return
        currentKey = resumeKey
        state.value = State(Phase.MATCHING, statusText = if (episodeIdHint != null) "正在拉取弹幕…" else "正在匹配剧集…")
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            val cached = cache[resumeKey]
            if (cached != null) {
                state.value = State(Phase.LOADED, comments = cached, statusText = "已加载 ${cached.size} 条弹幕")
                return@launch
            }
            val episodeId = episodeIdHint ?: run {
                val found = api.searchEpisodes(showTitle)
                if (currentKey != resumeKey) return@launch
                api.pickEpisodeId(found, showTitle, episodeNumber)
            }
            if (episodeId == null) {
                state.value = State(Phase.NOT_FOUND, statusText = "弹幕库未找到对应剧集")
                return@launch
            }
            state.value = State(Phase.MATCHING, statusText = "正在拉取弹幕…")
            val comments = api.comments(episodeId)
            if (currentKey != resumeKey) return@launch
            cache[resumeKey] = comments
            state.value = State(Phase.LOADED, comments = comments, statusText = "已加载 ${comments.size} 条弹幕")
        }
    }

    /** 手动重新获取（忽略缓存） */
    fun refetch(resumeKey: String, showTitle: String, episodeNumber: Int, episodeIdHint: Long? = null) {
        cache.remove(resumeKey)
        currentKey = null
        prepare(resumeKey, showTitle, episodeNumber, episodeIdHint)
    }

    fun clear() {
        currentKey = null
        state.value = State()
    }

    private companion object {
        const val CACHE_MAX_EPISODES = 4
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
    /** 分层开关：滚动/顶部/底部三类弹幕分别显隐（DanmakuMode 已按 dandanplay 约定解析） */
    val showScroll: Boolean = true,
    val showTop: Boolean = true,
    val showBottom: Boolean = true,
    /** 密度抽样比例：1.0 全量 / 0.5 每隔一条 / 0.25 每隔四条（刷屏番减负） */
    val density: Float = 1.0f,
    /** 关键词屏蔽：弹幕文本命中任一词（不区分大小写）即不显示 */
    val blockKeywords: Set<String> = emptySet(),
) {
    enum class ColorMode(val label: String) { ORIGINAL("跟随弹幕"), WHITE("纯白"), CUSTOM("自定义") }
}

/**
 * 弹幕显示过滤（纯函数，供 UI 层在 setComments 前调用；单元测试覆盖）：
 * 分层显隐 + 密度抽样（按时间序每隔 N 条保留一条，保序）+ 关键词屏蔽。不改缓存——
 * 设置变化即时重过滤，不用重新拉弹幕。
 */
object DanmakuDisplayFilter {
    fun filter(
        comments: List<DanmakuComment>,
        showScroll: Boolean,
        showTop: Boolean,
        showBottom: Boolean,
        density: Float,
        blockKeywords: Set<String> = emptySet(),
    ): List<DanmakuComment> {
        val step = when {
            density >= 0.99f -> 1
            density >= 0.4f -> 2
            else -> 4
        }
        return comments.filterIndexed { index, comment ->
            index % step == 0 &&
                blockKeywords.none { keyword -> keyword.isNotBlank() && comment.text.contains(keyword, ignoreCase = true) } &&
                when (comment.mode) {
                    DanmakuMode.SCROLL -> showScroll
                    DanmakuMode.TOP -> showTop
                    DanmakuMode.BOTTOM -> showBottom
                }
        }
    }
}
