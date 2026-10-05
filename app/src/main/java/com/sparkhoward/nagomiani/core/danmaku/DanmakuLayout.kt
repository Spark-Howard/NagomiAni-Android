package com.sparkhoward.nagomiani.core.danmaku

import com.sparkhoward.nagomiani.core.model.DanmakuComment
import com.sparkhoward.nagomiani.core.model.DanmakuMode

/** 车道分配结果：弹幕 + 分到的车道号 + 渲染宽度 */
data class DanmakuTrackItem(val comment: DanmakuComment, val lane: Int, val width: Float)

/**
 * 车道分配算法，常量与规则与 mac 版 DanmakuLayout 完全一致：
 * - 滚动弹幕横穿 12s，顶部/底部停留 5s
 * - 滚动区占屏幕上方 75%（底部 25% 留给字幕/控制条）
 * - 滚动车道复用条件：前一条的右端已完全进入屏幕 + 0.2s 间隔
 * - 堆叠车道上下各 4 条，按占用结束时间复用，全忙时取最早空出者
 */
object DanmakuLayout {
    const val travelDuration = 12.0
    const val stackDuration = 5.0
    const val scrollAreaRatio = 0.75
    const val stackLaneCount = 4

    /** 滚动车道的占用状态 */
    private class ScrollOccupancy {
        /** 该车道上一条弹幕被分配时的播放时间（秒） */
        var lastElapsed: Double = -1.0
        /** 该车道上一条弹幕的宽度 */
        var width: Float = 0f
    }

    fun assignLanes(
        comments: List<DanmakuComment>,
        laneCount: Int,
        screenWidth: Float,
        fontSize: Float,
        measure: (text: String, fontSize: Float) -> Float,
    ): List<DanmakuTrackItem> {
        if (laneCount <= 0 || screenWidth <= 0f) return emptyList()

        // 宽度估算兜底：CJK 1.0×字号，ASCII 0.55×，下限=字号
        fun fallbackWidth(text: String, size: Float): Float {
            var units = 0f
            for (ch in text) units += if (ch.code >= 0x2E80) 1.0f else 0.55f
            return maxOf(size, units * size)
        }

        val scrollOccupancy = Array(laneCount) { ScrollOccupancy() }
        val topOccupancy = DoubleArray(stackLaneCount) { -1.0 }
        val bottomOccupancy = DoubleArray(stackLaneCount) { -1.0 }
        var roundRobin = 0

        val result = ArrayList<DanmakuTrackItem>(comments.size)
        for (comment in comments) {
            val width = measure(comment.text, fontSize).takeIf { it > 0f } ?: fallbackWidth(comment.text, fontSize)
            when (comment.mode) {
                DanmakuMode.SCROLL -> {
                    val elapsed = comment.time
                    var lane = scrollOccupancy.indexOfFirst { it.lastElapsed < 0 }
                    if (lane < 0) {
                        // 复用判定：前一条右端已完全进入屏幕 + 0.2s 间隔
                        lane = scrollOccupancy.indexOfFirst { occ ->
                            elapsed >= travelDuration * (occ.width / (screenWidth + occ.width)) + occ.lastElapsed + 0.2
                        }
                    }
                    if (lane < 0) {
                        // 全拥挤：取可复用延迟最小的车道
                        var best = 0
                        var bestReady = Double.MAX_VALUE
                        for (i in scrollOccupancy.indices) {
                            val occ = scrollOccupancy[i]
                            val ready = occ.lastElapsed + travelDuration * (occ.width / (screenWidth + occ.width)) + 0.2
                            if (ready < bestReady) {
                                bestReady = ready
                                best = i
                            }
                        }
                        lane = best
                    }
                    roundRobin++
                    scrollOccupancy[lane].lastElapsed = elapsed
                    scrollOccupancy[lane].width = width
                    result += DanmakuTrackItem(comment, lane, width)
                }

                DanmakuMode.TOP -> {
                    val lane = pickStackLane(topOccupancy, comment.time)
                    topOccupancy[lane] = comment.time + stackDuration
                    result += DanmakuTrackItem(comment, lane, width)
                }

                DanmakuMode.BOTTOM -> {
                    val lane = pickStackLane(bottomOccupancy, comment.time)
                    bottomOccupancy[lane] = comment.time + stackDuration
                    result += DanmakuTrackItem(comment, lane, width)
                }
            }
        }
        return result
    }

    /** 堆叠车道：取已空出的第一条；全忙取最早空出者 */
    private fun pickStackLane(occupancy: DoubleArray, elapsed: Double): Int {
        var earliest = 0
        for (i in occupancy.indices) {
            if (occupancy[i] <= elapsed) return i
            if (occupancy[i] < occupancy[earliest]) earliest = i
        }
        return earliest
    }
}
