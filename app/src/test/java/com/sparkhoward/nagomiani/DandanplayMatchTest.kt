package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.dandanplay.DandanplayApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** dandanplay match 响应宽松解析（本地媒体 MD5 匹配用） */
class DandanplayMatchTest {

    @Test
    fun parsesMatchedResponse() {
        val text = """
            {"isMatched":true,"matches":[
              {"fileName":"[SubGroup] 异世界食堂 第01话.mp4","episodeId":1152522,"animeId":2531,
               "animeTitle":"异世界食堂","episodeTitle":"第01话 卷心菜与异世界食堂"},
              {"fileName":"...","episodeId":999}
            ],"ignored":123}
        """.trimIndent()
        val result = DandanplayApi.parseMatchResponse(text)
        assertTrue(result.isMatched)
        assertEquals(2, result.matches.size)
        val first = result.matches[0]
        assertEquals(1152522L, first.episodeId)
        assertEquals(2531L, first.animeId)
        assertEquals("异世界食堂", first.animeTitle)
        assertEquals("第01话 卷心菜与异世界食堂", first.episodeTitle)
    }

    @Test
    fun parsesUnmatchedResponse() {
        val result = DandanplayApi.parseMatchResponse("""{"isMatched":false,"matches":[]}""")
        assertTrue(!result.isMatched)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun missingAnimeIdFallsBackToZero() {
        val result = DandanplayApi.parseMatchResponse("""{"isMatched":true,"matches":[{"episodeId":42}]}""")
        assertTrue(result.isMatched)
        assertEquals(42L, result.matches[0].episodeId)
        assertEquals(0L, result.matches[0].animeId)
        assertEquals("", result.matches[0].animeTitle)
    }

    @Test
    fun entriesWithoutEpisodeIdSkipped() {
        val result = DandanplayApi.parseMatchResponse(
            """{"isMatched":true,"matches":[{"animeTitle":"没有集号"},{"episodeId":7}]}""",
        )
        assertEquals(listOf(7L), result.matches.map { it.episodeId })
    }

    @Test
    fun nonJsonYieldsNotMatched() {
        val result = DandanplayApi.parseMatchResponse("<html>Bad Gateway</html>")
        assertTrue(!result.isMatched)
        assertTrue(result.matches.isEmpty())
    }
}
