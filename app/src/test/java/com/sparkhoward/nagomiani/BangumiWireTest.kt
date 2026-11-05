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

    /** 真实「角色/制作人员」响应（/subject/{id}?responseGroup=large 的 crt/staff）——
     *  role_name（角色身份；制作常为空串）+ jobs（制作职位数组）映射，
     *  actors/info/topic/blog 等大字段必须能被 ignoreUnknownKeys 无损忽略 */
    @Test fun parsesSubjectCastsFromRealPayload() {
        val text = javaClass.getResourceAsStream("/subject_casts_sample.json")!!
            .readBytes().decodeToString()
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
        val c = json.decodeFromString(
            com.sparkhoward.nagomiani.core.model.SubjectCasts.serializer(),
            text,
        )
        assertTrue("crt 应 2 位，实际 ${c.crt.size}") { c.crt.size == 2 }
        assertTrue("staff 应 2 位，实际 ${c.staff.size}") { c.staff.size == 2 }
        val shinji = c.crt[0]
        assertTrue("角色中文名应为 碇真嗣，实际 ${shinji.displayName}") { shinji.displayName == "碇真嗣" }
        assertTrue("角色身份应为主角，实际 ${shinji.roleText}") { shinji.roleText == "主角" }
        assertTrue("角色头像应取到 medium 档，实际 ${shinji.images?.bestURL()}") {
            shinji.images?.bestURL()?.contains("/crt/m/") == true
        }
        val anno = c.staff[1]
        assertTrue("制作中文名应为 庵野秀明，实际 ${anno.displayName}") { anno.displayName == "庵野秀明" }
        assertTrue("制作职位应拼串，实际 ${anno.roleText}") { anno.roleText == "原作 / 导演 / 脚本" }
        assertTrue("images 缺失的条目不崩且中文名兜底，实际 ${c.staff[0].displayName}") {
            c.staff[0].displayName == "GAINAX"
        }
    }
}
