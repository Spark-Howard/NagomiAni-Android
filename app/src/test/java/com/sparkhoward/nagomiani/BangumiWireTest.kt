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

    /** 真实「单条收藏」响应（/v0/users/{username}/collections/{id}，公开收藏无需 token）
     *  必须能用应用同款 Json 配置无损解析——详情页收藏状态就靠它回显（type/rate/ep_status/comment） */
    @Test fun parsesUserSubjectCollectionFromRealPayload() {
        val text = javaClass.getResourceAsStream("/user_collection_sample.json")!!
            .readBytes().decodeToString()
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
        val c = json.decodeFromString(
            com.sparkhoward.nagomiani.core.model.UserSubjectCollection.serializer(),
            text,
        )
        assertTrue("subject_id 应为 265，实际 ${c.subjectId}") { c.subjectId == 265 }
        assertTrue("type 应为 2（看过），实际 ${c.type}") { c.type == 2 }
        assertTrue("ep_status 不为负，实际 ${c.epStatus}") { c.epStatus >= 0 }
    }
}
