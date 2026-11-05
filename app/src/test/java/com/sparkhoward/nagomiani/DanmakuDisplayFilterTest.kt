package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.model.DanmakuComment
import com.sparkhoward.nagomiani.core.model.DanmakuMode
import com.sparkhoward.nagomiani.player.DanmakuDisplayFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 弹幕显示过滤（分层 + 密度抽样）：设置中心/弹幕弹层的纯逻辑契约 */
class DanmakuDisplayFilterTest {

    private fun comment(time: Double, mode: DanmakuMode = DanmakuMode.SCROLL) =
        DanmakuComment(time, mode, 0xFFFFFFu, "t")

    private val sample = listOf(
        comment(1.0),
        comment(2.0, DanmakuMode.TOP),
        comment(3.0),
        comment(4.0, DanmakuMode.BOTTOM),
        comment(5.0),
        comment(6.0, DanmakuMode.TOP),
        comment(7.0),
        comment(8.0, DanmakuMode.BOTTOM),
    )

    @Test
    fun densityFullKeepsAll() {
        assertEquals(sample.size, DanmakuDisplayFilter.filter(sample, true, true, true, 1.0f).size)
    }

    @Test
    fun densityHalfKeepsEverySecondByIndex() {
        val out = DanmakuDisplayFilter.filter(sample, true, true, true, 0.5f)
        assertEquals(listOf(1.0, 3.0, 5.0, 7.0), out.map { it.time })
    }

    @Test
    fun densityQuarterKeepsEveryFourth() {
        val out = DanmakuDisplayFilter.filter(sample, true, true, true, 0.25f)
        assertEquals(listOf(1.0, 5.0), out.map { it.time })
    }

    @Test
    fun hidingLayerRemovesOnlyThatMode() {
        val out = DanmakuDisplayFilter.filter(sample, true, false, false, 1.0f)
        assertEquals(listOf(DanmakuMode.SCROLL), out.map { it.mode }.distinct())
        assertEquals(4, out.size)
    }

    @Test
    fun canHideScrollKeepTop() {
        val out = DanmakuDisplayFilter.filter(sample, false, true, true, 1.0f)
        assertTrue(out.all { it.mode != DanmakuMode.SCROLL })
        assertEquals(4, out.size)
    }

    @Test
    fun combinedDensityAndLayers() {
        // 密度 0.5（索引 0/2/4/6 保留）再滤掉顶部/底部：1.0 滚动、3.0 滚动、5.0 滚动、7.0 滚动
        val out = DanmakuDisplayFilter.filter(sample, true, false, false, 0.5f)
        assertEquals(listOf(1.0, 3.0, 5.0, 7.0), out.map { it.time })
    }

    @Test
    fun orderAndTimePreserved() {
        val out = DanmakuDisplayFilter.filter(sample, true, true, true, 0.5f)
        assertEquals(out.map { it.time }, out.map { it.time }.sorted())
    }

    @Test
    fun emptyListSafe() {
        assertTrue(DanmakuDisplayFilter.filter(emptyList(), false, false, false, 0.25f).isEmpty())
    }

    @Test
    fun allLayersHiddenYieldsEmpty() {
        assertTrue(DanmakuDisplayFilter.filter(sample, false, false, false, 1.0f).isEmpty())
    }

    @Test
    fun blockKeywordRemovesMatchingText() {
        val comments = listOf(
            comment(1.0), // "t"
            comment(2.0),
            comment(3.0),
        )
        // 只有文本含关键词的被滤掉——这里全样本文本都是 "t"，命中后应全空
        assertTrue(DanmakuDisplayFilter.filter(comments, true, true, true, 1.0f, setOf("t")).isEmpty())
    }

    @Test
    fun blockKeywordCaseInsensitive() {
        val sp = listOf(comment(1.0), comment(2.0), comment(3.0))
        assertTrue(DanmakuDisplayFilter.filter(sp, true, true, true, 1.0f, setOf("T")).isEmpty())
    }

    @Test
    fun blockKeywordPartialMatch() {
        val texts = listOf(
            DanmakuComment(1.0, DanmakuMode.SCROLL, 0xFFFFFFu, "前方高能预警"),
            DanmakuComment(2.0, DanmakuMode.SCROLL, 0xFFFFFFu, "正常弹幕"),
        )
        val out = DanmakuDisplayFilter.filter(texts, true, true, true, 1.0f, setOf("高能"))
        assertEquals(listOf(2.0), out.map { it.time })
    }

    @Test
    fun emptyBlockKeywordsKeepsAll() {
        assertEquals(sample.size, DanmakuDisplayFilter.filter(sample, true, true, true, 1.0f, emptySet()).size)
    }

    @Test
    fun blockKeywordCombinedWithDensity() {
        val texts = listOf(
            DanmakuComment(1.0, DanmakuMode.SCROLL, 0xFFFFFFu, "a"),
            DanmakuComment(2.0, DanmakuMode.SCROLL, 0xFFFFFFu, "屏蔽我"),
            DanmakuComment(3.0, DanmakuMode.SCROLL, 0xFFFFFFu, "c"),
            DanmakuComment(4.0, DanmakuMode.SCROLL, 0xFFFFFFu, "d"),
        )
        // 密度 0.5 保留索引 0/2（1.0、3.0），屏蔽词不影响它们
        val out = DanmakuDisplayFilter.filter(texts, true, true, true, 0.5f, setOf("屏蔽"))
        assertEquals(listOf(1.0, 3.0), out.map { it.time })
    }

    @Test
    fun blankKeywordIgnored() {
        assertEquals(sample.size, DanmakuDisplayFilter.filter(sample, true, true, true, 1.0f, setOf("", "  ")).size)
    }
}
