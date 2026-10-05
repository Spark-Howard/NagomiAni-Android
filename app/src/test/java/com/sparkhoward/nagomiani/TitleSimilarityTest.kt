package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.similarity.TitleSimilarity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleSimilarityTest {
    @Test fun identicalAfterNormalize() {
        assertEquals(1.0, TitleSimilarity.similarity("孤独摇滚！", "孤独摇滚"))
    }

    @Test fun containmentGetsFloor() {
        val score = TitleSimilarity.similarity("Bocchi the Rock", "Bocchi the Rock! Season 2")
        assertTrue(score >= 0.85, "包含关系应 ≥0.85，实际 $score")
    }

    @Test fun containmentFloorTightenable() {
        // 片源评分把门槛收到 6：4 字主名不再对同系列长标题（剧场版/国语版）拿保底分
        val score = TitleSimilarity.similarity("鬼灭之刃", "鬼灭之刃无限列车篇国语", containmentMinLength = 6)
        assertTrue(score < 0.85, "4 字主名 + 门槛 6 不应命中包含保底: $score")
        // 长名包含关系不受影响
        assertEquals(
            0.85,
            TitleSimilarity.similarity("败犬女主太多了！", "败犬女主太多了 第二季", containmentMinLength = 6),
        )
        // 缺省门槛维持 4（弹幕匹配等调用方口径不变）
        assertEquals(0.85, TitleSimilarity.similarity("鬼灭之刃", "鬼灭之刃无限列车篇国语"))
    }

    @Test fun differentTitlesScoreLow() {
        val score = TitleSimilarity.similarity("葬送的芙莉莲", "间谍过家家")
        assertTrue(score < 0.4, "不同番相似度应 <0.4，实际 $score")
    }

    @Test fun caseAndPunctuationInsensitive() {
        assertEquals(1.0, TitleSimilarity.similarity("Frieren", "frieren"))
    }
}
