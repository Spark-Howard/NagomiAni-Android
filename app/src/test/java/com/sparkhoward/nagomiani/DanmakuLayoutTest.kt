package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.danmaku.DanmakuLayout
import com.sparkhoward.nagomiani.core.model.DanmakuComment
import com.sparkhoward.nagomiani.core.model.DanmakuMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmakuLayoutTest {
    private fun comment(time: Double, mode: DanmakuMode = DanmakuMode.SCROLL, text: String = "测试弹幕内容") =
        DanmakuComment(time = time, mode = mode, color = 0xFFFFFFu, text = text)

    @Test
    fun scrollCommentsGetDistinctLanesWhenRoom() {
        val items = DanmakuLayout.assignLanes(
            comments = listOf(comment(0.0), comment(0.2), comment(0.4)),
            laneCount = 8, screenWidth = 800f, fontSize = 22f, measure = { text, size -> text.length * size },
        )
        assertEquals(3, items.size)
        assertEquals(listOf(0, 1, 2), items.map { it.lane })
    }

    @Test
    fun laneReusedAfterPreviousBulletFullyEntered() {
        // 同一条车道：t=0 发出后，前一条右端进入屏幕需要 travelDuration*(w/(screen+w))
        val items = DanmakuLayout.assignLanes(
            comments = listOf(comment(0.0, text = "AAAA"), comment(8.0, text = "AAAA")),
            laneCount = 1, screenWidth = 800f, fontSize = 22f, measure = { text, size -> text.length * size },
        )
        assertEquals(2, items.size)
        assertEquals(0, items[0].lane)
        assertEquals(0, items[1].lane) // 8s 后肯定已可复用
    }

    @Test
    fun stackLanesSeparateForTopAndBottom() {
        val items = DanmakuLayout.assignLanes(
            comments = listOf(comment(0.0, DanmakuMode.TOP), comment(0.0, DanmakuMode.TOP), comment(0.0, DanmakuMode.BOTTOM)),
            laneCount = 8, screenWidth = 800f, fontSize = 22f, measure = { text, size -> text.length * size },
        )
        assertEquals(0, items[0].lane)
        assertEquals(1, items[1].lane)
        assertEquals(0, items[2].lane) // bottom 与 top 车道池独立
    }

    @Test
    fun stackLaneOccupancyExpiresAfterDuration() {
        val items = DanmakuLayout.assignLanes(
            comments = listOf(comment(0.0, DanmakuMode.TOP), comment(5.5, DanmakuMode.TOP)),
            laneCount = 8, screenWidth = 800f, fontSize = 22f, measure = { text, size -> text.length * size },
        )
        assertEquals(0, items[1].lane) // 5s 停留已过，车道 0 空出
        assertTrue(items[1].lane in 0 until DanmakuLayout.stackLaneCount)
    }
}
