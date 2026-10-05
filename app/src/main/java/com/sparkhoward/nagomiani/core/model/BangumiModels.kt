package com.sparkhoward.nagomiani.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bangumi API wire 模型（宽松解析：未知枚举值回退、可空字段兜底），与 mac 版 BangumiModels 对齐。
 */

const val SUBJECT_TYPE_ANIME = 2

/** 收藏五态：1 想看 / 2 看过 / 3 在看 / 4 搁置 / 5 抛弃 */
enum class CollectionType(val raw: Int, val label: String) {
    WISH(1, "想看"), WATCHED(2, "看过"), DOING(3, "在看"), ON_HOLD(4, "搁置"), DROPPED(5, "抛弃");

    companion object {
        fun fromRaw(raw: Int?): CollectionType = entries.firstOrNull { it.raw == raw } ?: DOING
    }
}

@Serializable
data class SubjectImages(
    val large: String? = null,
    @JsonNames("common") val common: String? = null,
    val medium: String? = null,
    val small: String? = null,
    val grid: String? = null,
) {
    /** 封面按显示档位取：mac 版 bestURL 的 /r/N 缩放段重写（100/200/400/800/975） */
    fun bestURL(preferLarge: Boolean = false): String? {
        val raw = (if (preferLarge) large ?: common else common ?: medium ?: small) ?: large
        ?: medium ?: small ?: grid ?: return null
        return if (!raw.startsWith("http://")) raw
        else raw.replaceFirst("http://", "https://")
    }
}

@Serializable
data class SubjectRating(
    val total: Int = 0,
    val score: Double = 0.0,
    val rank: Int = 0,
    // 注意：不解析 count——legacy 端点（/calendar、/subject/{id}）返回对象 {"10":2,...}，
    // v0 端点返回数组 [0,0,...]，且 UI 未用到该字段；交给 ignoreUnknownKeys 忽略
)

@Serializable
data class SubjectTag(val name: String = "", val count: Int = 0)

/** infobox 值可能是字符串或 {v: "..."} 对象——自定义为 JsonElement 在 UI 层做扁平化 */
@Serializable
data class SubjectInfobox(val key: String = "", val value: kotlinx.serialization.json.JsonElement? = null)

@Serializable
data class SubjectCollectionStats(
    val wish: Int = 0, val collect: Int = 0, val doing: Int = 0,
    @SerialName("on_hold") val onHold: Int = 0, val dropped: Int = 0,
)

@Serializable
data class Subject(
    val id: Int = 0,
    val type: Int = 0,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val summary: String = "",
    @SerialName("air_date") val airDate: String? = null,
    // legacy 大条目里 eps 是分集对象数组、v0/legacy small 里是集数数字——宽容解析（数组取长度）
    @Serializable(with = LenientEpisodeCount::class)
    val eps: Int? = null,
    @SerialName("total_episodes") val totalEpisodes: Int? = null,
    val images: SubjectImages? = null,
    val rating: SubjectRating? = null,
    val infobox: List<SubjectInfobox> = emptyList(),
    val tags: List<SubjectTag> = emptyList(),
    val collection: SubjectCollectionStats? = null,
    val date: String? = null,
    /** 放送平台/类型（v0：TV / Web / OVA / 剧场版 …；legacy 端点可能缺）——片源搜源判剧场版用 */
    val platform: String? = null,
) {
    /** 中文名优先（可能为空串），与 mac 版 displayName 规则一致 */
    val displayName: String get() = nameCn.ifEmpty { name }
    val episodeCount: Int? get() = totalEpisodes ?: eps
}

/** 集数字段的宽容解码：JSON 数字 → Int；JSON 数组（legacy 大条目的 eps 分集列表）→ 数组长度；其他 → null */
private object LenientEpisodeCount : KSerializer<Int?> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientEpisodeCount", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return null
        return when (el) {
            is JsonPrimitive -> el.content.toIntOrNull()
            is JsonArray -> el.size
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

@Serializable
data class Episode(
    val id: Int = 0,
    val type: Int = 0,
    val sort: Double = 0.0,
    val ep: Double? = null,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val airdate: String? = null,
    val duration: String? = null,
) {
    val displaySort: Int get() = sort.toIntOrNullRounded()
    val displayName: String get() = nameCn.ifEmpty { name }
}

private fun Double.toIntOrNullRounded(): Int = kotlin.math.round(this).toInt()

/** /calendar 的按天结构 */
@Serializable
data class CalendarWeekday(val id: Int = 1, val cn: String? = null)

@Serializable
data class CalendarDay(val weekday: CalendarWeekday = CalendarWeekday(), val items: List<Subject> = emptyList())

/** 分页信封 {total, limit, offset, data} */
@Serializable
data class Paged<T>(
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val data: List<T> = emptyList(),
)

@Serializable
data class UserSubjectCollection(
    @SerialName("subject_id") val subjectId: Int = 0,
    @SerialName("subject_type") val subjectType: Int = 0,
    val rate: Int? = null,
    val type: Int = 0,
    @SerialName("ep_status") val epStatus: Int = 0,
    @SerialName("vol_status") val volStatus: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    val subject: Subject? = null,
)

/** 收藏修改请求体（type=收藏态，ep_status=看到第几集） */
@Serializable
data class CollectionModifyPayload(
    val type: Int? = null,
    val rate: Int? = null,
    val comment: String? = null,
    val private: Boolean? = null,
    val tags: List<String>? = null,
    @SerialName("ep_status") val epStatus: Int? = null,
    @SerialName("vol_status") val volStatus: Int? = null,
)

@Serializable
data class UserEpisodeCollection(
    @SerialName("episode_id") val episodeId: Int = 0,
    val type: Int = 0,
)

/** PATCH episodes 请求体：type=2 表示看过 */
@Serializable
data class EpisodeMarkPayload(
    @SerialName("episode_id") val episodeId: List<Int>,
    val type: Int,
)

@Serializable
data class BangumiUser(
    val id: Int = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: SubjectImages? = null,
)

/** OAuth token 响应 */
@Serializable
data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("user_id") val userId: Long = 0,
)
