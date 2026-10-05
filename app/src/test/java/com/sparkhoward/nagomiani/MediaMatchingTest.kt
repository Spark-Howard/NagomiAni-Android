package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.matching.MediaMatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 与 mac 版 MediaMatchingTests 同源用例（子集） */
class MediaMatchingTest {
    @Test fun parsesCommonPatterns() {
        assertEquals(3, MediaMatching.episodeNumber("[SubGroup] Show S01E03 [1080p]"))
        assertEquals(12, MediaMatching.episodeNumber("Show S2 12 [BDRip]"))
        assertEquals(5, MediaMatching.episodeNumber("Show EP05"))
        assertEquals(7, MediaMatching.episodeNumber("Show E07 [720p]"))
        assertEquals(8, MediaMatching.episodeNumber("Show 第08话"))
        assertEquals(9, MediaMatching.episodeNumber("Show 第9集"))
        assertEquals(10, MediaMatching.episodeNumber("Show #10"))
        assertEquals(4, MediaMatching.episodeNumber("Show - 04 [1080p]"))
        assertEquals(2, MediaMatching.episodeNumber("[02]"))
        assertEquals(1, MediaMatching.episodeNumber("01.mkv"))
        assertEquals(3, MediaMatching.episodeNumber("03v2 [1080p]"))
        assertEquals(21, MediaMatching.episodeNumber("Show - 21"))
    }

    @Test fun rejectsNonEpisodeNumbers() {
        // "01title"（无分隔符的点/横杠）不应被解析
        assertNull(MediaMatching.episodeNumber("01title"))
        // "3-gatsu"（季名以数字开头）的集文件 "- 01" 后缀仍应正常解析
        assertEquals(1, MediaMatching.episodeNumber("3-gatsu no Lion - 01"))
    }

    @Test fun rejectsPlain() {
        assertNull(MediaMatching.episodeNumber("Some Movie"))
        assertNull(MediaMatching.episodeNumber(""))
    }
}
