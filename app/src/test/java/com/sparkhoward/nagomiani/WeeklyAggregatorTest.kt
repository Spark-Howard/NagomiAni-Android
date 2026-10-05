package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.matching.MediaMatching
import com.sparkhoward.nagomiani.core.model.Episode
import com.sparkhoward.nagomiani.core.model.OnlineShow
import com.sparkhoward.nagomiani.core.online.WeeklyAggregator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 片源评分/过滤（与 mac 版 WeeklyAggregator 同口径） */
class WeeklyAggregatorTest {
    private fun show(title: String, subtitle: String? = null) = OnlineShow("p", "1", title, subtitle = subtitle)

    @Test
    fun cleanTitleStripsTagsAndQualityMarkers() {
        assertEquals("葬送的芙莉莲", WeeklyAggregator.cleanTitle("[NagomiSub] 葬送的芙莉莲 [1080p][简日内嵌]"))
        assertEquals("Frieren Season 2", WeeklyAggregator.cleanTitle("Frieren Season 2 [BDRip x265]"))
    }

    @Test
    fun taggedCandidateMatchesSubject() {
        val scored = WeeklyAggregator.score(
            "败犬女主太多了！",
            listOf(show("【诸神字幕组】败犬女主太多了！[01-12][1080p][简日内嵌]")),
        )
        assertEquals(1, scored.size)
        assertTrue(scored[0].second >= WeeklyAggregator.MATCH_THRESHOLD)
    }

    @Test
    fun sportsAndVarietyJunkFilteredByThreshold() {
        val scored = WeeklyAggregator.score(
            "败犬女主太多了！",
            listOf(show("NBA常规赛直播"), show("英超精华集锦"), show("王牌对王牌综艺第3季")),
        )
        assertTrue(scored.isEmpty(), "体育/综艺垃圾源不应通过阈值: $scored")
    }

    @Test
    fun lowScoreLegitTitleDropped() {
        // 站内误搜的同字面但无关条目：相似度低于 0.4 应被丢弃
        val scored = WeeklyAggregator.score(
            "葬送的芙莉莲",
            listOf(show("葬爱家族青春回忆录"), show("葬礼上的发言指南")),
        )
        assertTrue(scored.isEmpty())
    }

    @Test
    fun seasonBonusApplies() {
        val plain = WeeklyAggregator.score("某番", listOf(show("某番 第2季")))
        // 某番(无季号) vs 某番 第2季：清洗后包含关系，再叠季号不加分（subject 无季号）
        assertTrue(plain.isNotEmpty())
        val withSeason = WeeklyAggregator.score("某番 第二季", listOf(show("某番 第2季 [1080p]")))
        assertEquals(1, withSeason.size)
        assertTrue(withSeason[0].second >= WeeklyAggregator.MATCH_THRESHOLD)
    }

    @Test
    fun sortedByScoreDescending() {
        val scored = WeeklyAggregator.score(
            "败犬女主太多了！",
            listOf(show("败犬女主太多了 第二季"), show("败犬女主太多了！ [1080p]")),
        )
        assertTrue(scored.size >= 2)
        assertTrue(scored[0].second >= scored[1].second)
    }

    @Test
    fun seasonNumberParsing() {
        assertEquals(2, MediaMatching.seasonNumber("某番 第二季"))
        assertEquals(3, MediaMatching.seasonNumber("某番 第3季"))
        assertEquals(2, MediaMatching.seasonNumber("Show S02"))
        assertEquals(2, MediaMatching.seasonNumber("Show Season 2"))
        assertEquals(2, MediaMatching.seasonNumber("Show Part 2"))
        assertEquals(2, MediaMatching.seasonNumber("Show II"))
        assertEquals(2, MediaMatching.seasonNumber("命运石之门0"))
        assertNull(MediaMatching.seasonNumber("败犬女主太多了！"))
    }

    @Test
    fun movieCandidatesExcludedForTvSubject() {
        // 同系列剧场版与 TV 版同挂"日韩动漫"类目且标题必含主名（包含关系 0.85），
        // 必须按"剧场版"关键词先行排除，否则污染 TV 条目的搜源结果
        val scored = WeeklyAggregator.score(
            "咒术回战",
            listOf(
                show("咒术回战"),
                show("咒术回战第二季"),
                show("咒术回战0剧场版"),
                show("咒术回战涩谷事变×死灭回游剧场版"),
            ),
        )
        assertEquals(listOf("咒术回战", "咒术回战第二季"), scored.map { it.first.title })
    }

    @Test
    fun movieCandidatesExcludedViaSubtitleCategory() {
        // 部分站点类目名即"剧场版"，标题不带字样时靠 subtitle（类目 · 备注）识别
        val movie = OnlineShow("p", "2", "鬼灭之刃无限列车篇", subtitle = "剧场版 · HD高清")
        val scored = WeeklyAggregator.score("鬼灭之刃", listOf(movie))
        assertTrue(scored.isEmpty(), "类目标注剧场版的候选应被排除: $scored")
    }

    @Test
    fun movieSubjectStillMatchesUntaggedMovieTitle() {
        // 剧场版条目不反向排除 TV 名候选——资源站收录电影时常省略"剧场版"字样，反向排除会误杀正主
        val scored = WeeklyAggregator.score(
            "剧场版 鬼灭之刃 无限列车篇",
            listOf(show("鬼灭之刃无限列车篇国语"), show("鬼灭之刃")),
            subjectIsMovie = true,
        )
        assertTrue(scored.map { it.first.title }.contains("鬼灭之刃无限列车篇国语"))
    }

    @Test
    fun containmentFloorTightenedForShortFranchiseNames() {
        // 4 字主名与同系列长标题（国语版）不再拿 0.85 保底，排序让位给正片
        val scored = WeeklyAggregator.score(
            "鬼灭之刃",
            listOf(show("鬼灭之刃"), show("鬼灭之刃无限列车篇国语")),
        )
        assertEquals(2, scored.size)
        assertEquals("鬼灭之刃", scored[0].first.title)
        assertTrue(scored[1].second < 0.85, "同系列长标题不应再拿包含保底: ${scored[1].second}")
    }

    @Test
    fun searchKeywordsAddsHeadForLongTitles() {
        // 超长译名（主名+副标题）补首段短词降级；日文名按 、 切；英文名切出的词根无 CJK 不收
        val kws = WeeklyAggregator.searchKeywords(
            "一觉醒来就有了最强装备跟太空船 决定以自家独栋建筑为目标当佣兵自由过活",
            "目覚めたら最強装備と宇宙船持ちだったので、一戸建て目指して傭兵として自由に生きたい",
            listOf(
                "一觉醒来坐拥神装和飞船，我决定以买一套独门独户的房子为目标作为佣兵自由地活下去",
                "Reborn as a Space Mercenary: I Woke Up Piloting the Strongest Starship!",
            ),
        )
        assertEquals(
            listOf(
                "一觉醒来就有了最强装备跟太空船 决定以自家独栋建筑为目标当佣兵自由过活",
                "一觉醒来就有了最强装备跟太空船",
                "目覚めたら最強装備と宇宙船持ちだったので、一戸建て目指して傭兵として自由に生きたい",
                "目覚めたら最強装備と宇宙船持ちだったので",
                "一觉醒来坐拥神装和飞船，我决定以买一套独门独户的房子为目标作为佣兵自由地活下去",
                "一觉醒来坐拥神装和飞船",
            ),
            kws,
        )
    }

    @Test
    fun searchKeywordsKeepsShortNamesAsIs() {
        assertEquals(
            listOf("葬送的芙莉莲", "Frieren"),
            WeeklyAggregator.searchKeywords("葬送的芙莉莲", "Frieren", emptyList()),
        )
        // 中文名与日文原名相同时去重
        assertEquals(
            listOf("孤独摇滚！"),
            WeeklyAggregator.searchKeywords("孤独摇滚！", "孤独摇滚！", emptyList()),
        )
    }

    @Test
    fun isMovieSubjectDetection() {
        assertTrue(WeeklyAggregator.isMovieTitle("咒术回战0剧场版"))
        assertTrue(WeeklyAggregator.isMovieTitle("Jujutsu Kaisen Movie"))
        assertTrue(WeeklyAggregator.isMovieTitle("某番", "剧场版"))
        assertFalse(WeeklyAggregator.isMovieTitle("某番", "TV"))
        assertTrue(WeeklyAggregator.isMovieSubject("天气之子", "天気の子", "电影"))
        assertFalse(WeeklyAggregator.isMovieSubject("咒术回战", "呪術廻戦", "TV"))
    }

    @Test
    fun unairedAirdatesGatesFutureMainEpisodesOnly() {
        val eps = listOf(
            Episode(id = 1, type = 0, sort = 1.0, airdate = "2026-10-03"),   // 已开播 → 不门禁
            Episode(id = 2, type = 0, sort = 2.0, airdate = "2026-10-10"),   // 未开播 → 门禁
            Episode(id = 3, type = 0, sort = 3.0, airdate = ""),             // 无日期 → 不门禁（宁可放过）
            Episode(id = 4, type = 1, sort = 4.0, airdate = "2026-10-11"),   // SP → 不门禁
            Episode(id = 5, type = 0, sort = 5.0, airdate = "2026-10"),      // 精度不足 → 不门禁
            Episode(id = 6, type = 0, sort = 6.0, airdate = "垃圾数据"),      // 解析失败 → 不门禁
        )
        val gated = WeeklyAggregator.unairedAirdates(eps, java.time.LocalDate.parse("2026-10-04"))
        assertEquals(mapOf(2 to "2026-10-10"), gated)
    }

    @Test
    fun unairedAirdatesUnlocksOnAirday() {
        // 开播当天即解禁（airdate == today 不算未放送）
        val eps = listOf(Episode(id = 1, type = 0, sort = 1.0, airdate = "2026-10-10"))
        assertTrue(WeeklyAggregator.unairedAirdates(eps, java.time.LocalDate.parse("2026-10-10")).isEmpty())
        assertEquals(mapOf(1 to "2026-10-10"), WeeklyAggregator.unairedAirdates(eps, java.time.LocalDate.parse("2026-10-09")))
    }
}
