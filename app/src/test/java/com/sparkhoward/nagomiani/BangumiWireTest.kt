package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiError
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 直连 bgm 真实服务器的协议级回归测试（需要网络）：
 * content-type 检查在鉴权之前——头正确 → 401（Unauthorized）；头错误 → 415。
 * 曾因 okhttp String.toRequestBody 运行时补 charset，所有 POST/PATCH 全部 415。
 */
class BangumiWireTest {
    @Test fun unauthenticatedPostPassesContentTypeGate() {
        val api = BangumiApi(tokenProvider = { null })
        val e = runCatching {
            runBlocking {
                api.updateCollection(454684, CollectionModifyPayload(type = 3))
            }
        }.exceptionOrNull()
        assertTrue("期望 401（头通过检查），实际: $e") { e is BangumiError.Unauthorized }
    }

    @Test fun unauthenticatedPatchPassesContentTypeGate() {
        val api = BangumiApi(tokenProvider = { null })
        val e = runCatching {
            runBlocking {
                api.markEpisodes(454684, listOf(1))
            }
        }.exceptionOrNull()
        assertTrue("期望 401（头通过检查），实际: $e") { e is BangumiError.Unauthorized }
    }
}
