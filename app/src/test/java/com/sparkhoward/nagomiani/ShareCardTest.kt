package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.player.PixelRect
import com.sparkhoward.nagomiani.player.ShareCardMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareCardTest {

    // MARK: - videoContentRect（PlayerView FIT 缩放的内容区/黑边计算）

    @Test
    fun aspectMatchMeansFullSurface() {
        assertEquals(PixelRect(0, 0, 1920, 1080), ShareCardMath.videoContentRect(1920, 1080, 1920, 1080))
    }

    @Test
    fun widerThanSurfaceGetsTopBottomBars() {
        // 16:9 视频放进正方形表面：上下留黑边，宽度铺满
        val r = ShareCardMath.videoContentRect(1000, 1000, 1600, 900)
        assertEquals(PixelRect(0, 219, 1000, 781), r)
    }

    @Test
    fun tallerThanSurfaceGetsSideBars() {
        // 16:9 视频放进竖屏表面（全屏播放常见）：左右留黑边，高度铺满
        val r = ShareCardMath.videoContentRect(1080, 1920, 1920, 1080)
        assertEquals(PixelRect(0, 656, 1080, 1263), r)
    }

    @Test
    fun unknownVideoSizeFallsBackToFullSurface() {
        assertEquals(PixelRect(0, 0, 800, 600), ShareCardMath.videoContentRect(800, 600, 0, 0))
    }

    @Test
    fun degenerateSurfaceYieldsEmptyRect() {
        assertEquals(PixelRect(0, 0, 0, 0), ShareCardMath.videoContentRect(0, 0, 1920, 1080))
    }

    // MARK: - fileName（分享/保存文件名清理）

    @Test
    fun fileNameSanitizesIllegalAndWhitespaceRuns() {
        assertEquals("NagomiAni_FOO_BAR_ep_1_EP2_123", ShareCardMath.fileName("FOO/BAR: ep 1", 2, 123))
        assertEquals("NagomiAni_葬送的芙莉莲_EP3_9", ShareCardMath.fileName("葬送的芙莉莲", 3, 9))
    }

    @Test
    fun fileNameTruncatesLongTitles() {
        val long = "a".repeat(60)
        val name = ShareCardMath.fileName(long, 1, 7)
        assertTrue(name.startsWith("NagomiAni_${"a".repeat(40)}_EP1_7"))
    }

    @Test
    fun fileNameFallsBackWhenTitleEmpty() {
        assertEquals("NagomiAni_video_EP1_5", ShareCardMath.fileName("///", 1, 5))
    }

    // MARK: - wrapText（配文折行，measure 注入 = 每字符 10px）

    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun shortTextStaysSingleLine() {
        assertEquals(listOf("你好"), ShareCardMath.wrapText("你好", 100f, 2, measure))
    }

    @Test
    fun longTextWrapsByCharacter() {
        assertEquals(listOf("AAA", "BBB"), ShareCardMath.wrapText("AAABBB", 30f, 2, measure))
    }

    @Test
    fun overflowGetsEllipsisOnLastLine() {
        assertEquals(listOf("ABC", "DE…"), ShareCardMath.wrapText("ABCDEFG", 30f, 2, measure))
    }

    @Test
    fun blankTextYieldsNoLines() {
        assertTrue(ShareCardMath.wrapText("   ", 100f, 2, measure).isEmpty())
        assertTrue(ShareCardMath.wrapText("内容", 100f, 0, measure).isEmpty())
    }
}
