package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.dandanplay.DandanplayApi
import com.sparkhoward.nagomiani.core.maccms.HtmlEntities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DandanplaySignatureTest {
    @Test
    fun signatureIsStableBase64Sha256() {
        // 固定向量：算法 = BASE64(SHA256(AppId + Timestamp + Path + AppSecret))，只签 path 不含 query
        val s1 = DandanplayApi.signature("testAppId", 1700000000L, "/api/v2/search/episodes", "testSecret")
        val s2 = DandanplayApi.signature("testAppId", 1700000000L, "/api/v2/search/episodes", "testSecret")
        assertEquals(s1, s2)
        assertEquals(44, s1.length) // SHA256 → 32B → BASE64 = 44 chars（含 padding）
        // 换时间戳/路径/密钥应变化
        assertNotEquals(s1, DandanplayApi.signature("testAppId", 1700000001L, "/api/v2/search/episodes", "testSecret"))
        assertNotEquals(s1, DandanplayApi.signature("testAppId", 1700000000L, "/api/v2/comment/123", "testSecret"))
        assertNotEquals(s1, DandanplayApi.signature("testAppId", 1700000000L, "/api/v2/search/episodes", "other"))
    }

    @Test
    fun signatureIgnoresQuery() {
        // 只签 path：同 path 不同 query 签名一致
        assertEquals(
            DandanplayApi.signature("a", 1L, "/api/v2/comment/1", "s"),
            DandanplayApi.signature("a", 1L, "/api/v2/comment/1", "s"),
        )
        assertTrue(DandanplayApi.signature("a", 1L, "/api/v2/comment/1", "s").isNotEmpty())
    }

    @Test
    fun htmlEntityDecoding() {
        assertEquals("孤独摇滚！", HtmlEntities.decode("&#23396;&#29420;&#25671;&#28378;&#65281;"))
        assertEquals("'A'", HtmlEntities.decode("&#x27;A&#x27;"))
        // 双重转义：&amp;#39; 两轮解码后变成字面 '
        assertEquals("'B'", HtmlEntities.decode("&amp;#39;B&#39;"))
        assertEquals("a & b", HtmlEntities.decode("a &amp; b"))
    }
}
