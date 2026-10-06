package com.sparkhoward.nagomiani

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归测试：bgm 服务器要求 Content-Type 精确等于 application/json（不接受 charset 参数，
 * 大小写敏感），而 okhttp 的 String.toRequestBody 会给无 charset 的 MediaType 运行时补
 * "; charset=utf-8"（曾导致所有 POST/PATCH 全部 415）。请求体必须用字节数组构造。
 */
class BangumiContentTypeTest {
    @Test fun byteArrayBodyKeepsExactContentType() {
        val body = "{}".encodeToByteArray().toRequestBody("application/json".toMediaType())
        assertEquals("application/json", body.contentType().toString())
    }

    @Test fun stringBodyGetsCharsetAppendedByOkhttp() {
        // 固化 okhttp 的行为事实，防止有人改回字符串构造还不明所以
        val body = "{}".toRequestBody("application/json".toMediaType())
        assertEquals("application/json; charset=utf-8", body.contentType().toString())
    }
}
