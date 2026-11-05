package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.local.FileFingerprint
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 本地视频指纹（首 N 字节 MD5）纯逻辑 */
class FileFingerprintTest {

    @Test
    fun md5MatchesKnownVector() {
        // "abc" 的 MD5 是标准测试向量
        val hash = FileFingerprint.md5Prefix(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hash)
    }

    @Test
    fun limitTruncatesInput() {
        val data = "0123456789".toByteArray()
        val limited = FileFingerprint.md5Prefix(ByteArrayInputStream(data), limitBytes = 5)
        val expected = java.security.MessageDigest.getInstance("MD5")
            .digest("01234".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, limited)
    }

    @Test
    fun limitLargerThanStreamReadsAll() {
        val data = "0123456789".toByteArray()
        val full = FileFingerprint.md5Prefix(ByteArrayInputStream(data), limitBytes = 1 shl 20)
        val expected = java.security.MessageDigest.getInstance("MD5")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, full)
    }

    @Test
    fun hashIsLowercaseHexOfFixedLength() {
        val hash = FileFingerprint.md5Prefix(ByteArrayInputStream(ByteArray(1024)))
        assertEquals(32, hash.length)
        assertTrue("应为小写十六进制：$hash") { hash.all { it.isDigit() || it in 'a'..'f' } }
    }

    @Test
    fun emptyStreamYieldsMd5OfNothing() {
        // 空输入 = d41d8cd98f00b204e9800998ecf8427e（MD5 标准向量）
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", FileFingerprint.md5Prefix(ByteArrayInputStream(ByteArray(0))))
    }
}
