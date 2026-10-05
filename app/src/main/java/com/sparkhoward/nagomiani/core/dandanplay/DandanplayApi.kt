package com.sparkhoward.nagomiani.core.dandanplay

import com.sparkhoward.nagomiani.core.Http
import com.sparkhoward.nagomiani.core.matching.MediaMatching
import com.sparkhoward.nagomiani.core.model.DanmakuComment
import com.sparkhoward.nagomiani.core.model.DanmakuMode
import com.sparkhoward.nagomiani.core.similarity.TitleSimilarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Base64

/**
 * dandanplay v2 开放 API 客户端。
 * 签名：BASE64(SHA256(AppId + Timestamp + Path + AppSecret))——非 HMAC，secret 拼在最后，只签 path（不含 query）。
 * 端点：search/episodes、comment/{id}（本地文件 match 在手机端不适用，省略）。
 */
class DandanplayApi(
    private val appId: String,
    private val appSecret: String,
    private val baseURL: String = "https://api.dandanplay.net",
) {

    data class EpisodeResult(val episodeId: Long, val episodeTitle: String)
    data class AnimeResult(val animeId: Long, val animeTitle: String, val episodes: List<EpisodeResult>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        /** BASE64(SHA256(AppId + Timestamp + Path + AppSecret))——纯哈希拼接，非 HMAC */
        fun signature(appId: String, timestamp: Long, path: String, appSecret: String): String {
            val data = "$appId$timestamp$path$appSecret"
            val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(digest)
        }
    }

    /** 番名→剧集搜索 */
    suspend fun searchEpisodes(anime: String): List<AnimeResult> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(anime, "UTF-8")
        val request = signed("GET", "/api/v2/search/episodes?anime=$encoded")
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val animes = root["animes"]?.jsonArray ?: return@use emptyList()
                animes.mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val animeId = obj.longField("animeId") ?: return@mapNotNull null
                    val animeTitle = obj.stringField("animeTitle") ?: return@mapNotNull null
                    val eps = obj["episodes"]?.jsonArray?.mapNotNull { e ->
                        val eo = e.jsonObject
                        EpisodeResult(
                            episodeId = eo.longField("episodeId") ?: return@mapNotNull null,
                            episodeTitle = eo.stringField("episodeTitle") ?: "",
                        )
                    } ?: emptyList()
                    AnimeResult(animeId, animeTitle, eps)
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 从剧集列表挑出对应集：番名相似（≥0.35 或包含）者优先，集名解析出的集号必须一致；
     * 同号多候选取番名相似度最高——"宁缺毋滥"，找不到返回 null（与 mac 版 pickEpisode 一致）
     */
    fun pickEpisodeId(animes: List<AnimeResult>, showTitle: String, episodeNumber: Int): Long? {
        val pool = animes.filter {
            TitleSimilarity.similarity(showTitle, it.animeTitle) >= 0.35 ||
                it.animeTitle.contains(showTitle) || showTitle.contains(it.animeTitle)
        }
        if (pool.isEmpty()) return null
        data class Candidate(val episodeId: Long, val animeTitle: String)
        val numbered = pool.flatMap { anime ->
            anime.episodes
                .filter { MediaMatching.episodeNumber(it.episodeTitle) == episodeNumber }
                .map { Candidate(it.episodeId, anime.animeTitle) }
        }
        if (numbered.isEmpty()) return null
        return numbered.maxByOrNull { TitleSimilarity.similarity(showTitle, it.animeTitle) }?.episodeId
    }

    /** 弹幕拉取（withRelated=true 合并相关集，chConvert=1 简体），按时间升序 */
    suspend fun comments(episodeId: Long): List<DanmakuComment> = withContext(Dispatchers.IO) {
        val request = signed("GET", "/api/v2/comment/$episodeId?withRelated=true&chConvert=1")
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val comments = root["comments"]?.jsonArray ?: return@use emptyList()
                comments.mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val p = obj.stringField("p") ?: return@mapNotNull null
                    val text = obj.stringField("m") ?: return@mapNotNull null
                    val parts = p.split(",")
                    if (parts.size < 3) return@mapNotNull null
                    val time = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                    val mode = parts[1].toIntOrNull()?.let { DanmakuMode.fromRaw(it) } ?: DanmakuMode.SCROLL
                    val color = parts[2].toLongOrNull()?.toInt()?.toUInt() ?: 0xFFFFFFu
                    DanmakuComment(time = time, mode = mode, color = color, text = text)
                }.sortedBy { it.time }
            }
        }.getOrDefault(emptyList())
    }

    // MARK: - 签名请求

    private fun signed(method: String, pathWithQuery: String): Request {
        val timestamp = System.currentTimeMillis() / 1000
        val path = pathWithQuery.substringBefore('?')
        val signature = signature(appId, timestamp, path, appSecret)
        return Request.Builder()
            .url(baseURL.trimEnd('/') + pathWithQuery)
            .header("X-AppId", appId)
            .header("X-Timestamp", timestamp.toString())
            .header("X-Signature", signature)
            .header("Accept", "application/json")
            .build()
    }

    private fun JsonObject.stringField(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.longField(name: String): Long? =
        runCatching { this[name]?.jsonPrimitive?.long }.getOrNull()
}
