package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 收藏五态同步的线上请求体契约（POST /v0/users/-/collections/{id}）：
 * 1. 只含 type 字段——官方 UserSubjectCollectionModifyPayload 所有字段可选，
 *    其中 ep_status/vol_status 仅书籍类目可用，误发可能导致 400，必须被省略；
 *    rate 传 0 是「删除评分」语义，绝不能默认带上
 * 2. 字段名 snake_case（ep_status/vol_status）
 * Json 配置须与 BangumiApi.json 一致（explicitNulls = false 是省略 null 的前提）
 */
class CollectionPayloadTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test fun fiveStatePayloadContainsOnlyType() {
        // 1 想看 / 2 看过 / 3 在看 / 4 搁置 / 5 抛弃（SubjectCollectionType）
        listOf(1, 2, 3, 4, 5).forEach { type ->
            assertEquals(
                """{"type":$type}""",
                json.encodeToString(CollectionModifyPayload.serializer(), CollectionModifyPayload(type = type)),
                "type=$type 的请求体应只含 type 字段",
            )
        }
    }

    @Test fun explicitPayloadKeepsSnakeCaseNames() {
        val body = json.encodeToString(
            CollectionModifyPayload.serializer(),
            CollectionModifyPayload(type = 2, rate = 8, epStatus = 5, volStatus = 1, comment = "好", private = false, tags = listOf("TV")),
        )
        assertEquals(
            """{"type":2,"rate":8,"comment":"好","private":false,"tags":["TV"],"ep_status":5,"vol_status":1}""",
            body,
        )
    }

    @Test fun collectionTypeRawMatchesOfficialEnum() {
        // 与 open-api v0.yaml SubjectCollectionType 逐一对表，防止 enum 顺序调整错位
        assertEquals(1, com.sparkhoward.nagomiani.core.model.CollectionType.WISH.raw)
        assertEquals(2, com.sparkhoward.nagomiani.core.model.CollectionType.WATCHED.raw)
        assertEquals(3, com.sparkhoward.nagomiani.core.model.CollectionType.DOING.raw)
        assertEquals(4, com.sparkhoward.nagomiani.core.model.CollectionType.ON_HOLD.raw)
        assertEquals(5, com.sparkhoward.nagomiani.core.model.CollectionType.DROPPED.raw)
    }
}
