package com.sparkhoward.nagomiani.core.model

/**
 * 在线片源与弹幕模型，与 mac 版 OnlineModels / DanmakuModels 对齐。
 */

/** 一部云端番。seriesKey = "online:<provider>:<showID>"，是绑定表/续播/已看同步的稳定键 */
data class OnlineShow(
    val providerID: String,
    val showID: String,
    val title: String,
    val subtitle: String? = null,
    val coverURL: String? = null,
) {
    val id: String get() = "$providerID:$showID"
    val seriesKey: String get() = "online:$providerID:$showID"
}

/** 一集。resumeKey = "<seriesKey>:<number>"，同时是缓存键 */
data class OnlineEpisode(
    val providerID: String,
    val showID: String,
    val number: Int,
    val title: String? = null,
    val streamHint: String? = null,
    /** 同一集的全部线路（换线用；首条为首选） */
    val routes: List<String>? = null,
) {
    val seriesKey: String get() = "online:$providerID:$showID"
    val resumeKey: String get() = "$seriesKey:$number"
    val displayTitle: String? get() = title
}

/** 取流结果：url + 防盗链头（片源要求 Referer/UA 时必须带上） */
data class StreamSource(
    val url: String,
    val httpHeaders: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val isHLS: Boolean = false,
)

/** 一次可播放的完整参数（点播 → 播放器传递） */
data class OnlinePlayback(
    val url: String,
    val displayTitle: String?,
    val seriesKey: String,
    val episodeNumber: Int,
    val resumeKey: String,
    val httpHeaders: Map<String, String>,
    val userAgent: String?,
    val routes: List<String>,
    val showTitle: String?,
    val boundSubjectID: Int? = null,
)

/** 弹幕模式（dandanplay 约定：1 滚动 / 4 底部 / 5 顶部） */
enum class DanmakuMode(val raw: Int) { SCROLL(1), BOTTOM(4), TOP(5);
    companion object {
        fun fromRaw(raw: Int): DanmakuMode = entries.firstOrNull { it.raw == raw } ?: SCROLL
    }
}

data class DanmakuComment(
    val time: Double,
    val mode: DanmakuMode,
    /** 0xRRGGBB */
    val color: UInt,
    val text: String,
)
