package com.sparkhoward.nagomiani.core.model

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
