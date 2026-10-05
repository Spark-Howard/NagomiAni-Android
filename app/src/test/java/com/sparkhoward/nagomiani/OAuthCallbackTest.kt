package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OAuth 回调 URL 解析（WebView 拦截路径的核心逻辑） */
class OAuthCallbackTest {
    private val state = "test-state-uuid"

    @Test
    fun extractsCodeFromValidCallback() {
        val url = "http://127.0.0.1:8123/callback?code=abc123&state=$state"
        assertEquals("abc123", BangumiAuth.extractAuthorizationCode(url, state))
    }

    @Test
    fun decodesUrlEncodedCode() {
        val url = "http://127.0.0.1:8123/callback?code=a%2Bb%3Dc&state=$state"
        assertEquals("a+b=c", BangumiAuth.extractAuthorizationCode(url, state))
    }

    @Test
    fun rejectsWrongState() {
        val url = "http://127.0.0.1:8123/callback?code=abc123&state=other"
        assertNull(BangumiAuth.extractAuthorizationCode(url, state))
    }

    @Test
    fun rejectsErrorCallback() {
        val url = "http://127.0.0.1:8123/callback?error=access_denied&error_description=x&state=$state"
        assertNull(BangumiAuth.extractAuthorizationCode(url, state))
    }

    @Test
    fun rejectsMissingCode() {
        val url = "http://127.0.0.1:8123/callback?state=$state"
        assertNull(BangumiAuth.extractAuthorizationCode(url, state))
    }

    @Test
    fun rejectsNonCallbackUrls() {
        assertNull(BangumiAuth.extractAuthorizationCode("https://bgm.tv/oauth/authorize?code=x", state))
        assertNull(BangumiAuth.extractAuthorizationCode("http://127.0.0.1:8123/", state))
    }
}
