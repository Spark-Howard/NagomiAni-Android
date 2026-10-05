package com.sparkhoward.nagomiani.core.bangumi

import com.sparkhoward.nagomiani.core.Http
import com.sparkhoward.nagomiani.core.model.CalendarDay
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import com.sparkhoward.nagomiani.core.model.Episode
import com.sparkhoward.nagomiani.core.model.EpisodeMarkPayload
import com.sparkhoward.nagomiani.core.model.BangumiUser
import com.sparkhoward.nagomiani.core.model.OAuthTokenResponse
import com.sparkhoward.nagomiani.core.model.Paged
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.core.model.UserEpisodeCollection
import com.sparkhoward.nagomiani.core.model.UserSubjectCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 网络失败（可重试）；HTTP 状态码错误区分 401（需要重新登录） */
sealed class BangumiError(message: String) : Exception(message) {
    class Network(cause: Throwable) : BangumiError("网络错误：${cause.message}")
    class Unauthorized : BangumiError("登录已过期")
    class Http(val code: Int, body: String) : BangumiError("HTTP $code")
}

/**
 * Bangumi API 客户端（v0 + legacy 大条目），与 mac 版 BangumiClient 端点一致。
 * - 固定 UA（Bangumi 封禁不可识别 UA）
 * - 网络错误线性退避重试 2 次（1s/2s），401/4xx 不重试
 */
class BangumiApi(private val tokenProvider: suspend () -> String?) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    suspend fun me(): BangumiUser = get("/v0/me")
    suspend fun calendar(): List<CalendarDay> = get("/calendar")

    /** 搜索条目（type=2 动漫过滤；服务端 filter 失败时客户端兜底过滤由调用方做） */
    suspend fun searchSubjects(keyword: String, limit: Int = 20, offset: Int = 0): Paged<Subject> {
        val escaped = keyword.replace("\\", "\\\\").replace("\"", "\\\"")
        return post(
            "/v0/search/subjects?limit=$limit&offset=$offset",
            """{"keyword":"$escaped","sort":"match","filter":{"type":[2]}}""",
        )
    }

    suspend fun subject(id: Int): Subject = get("/v0/subjects/$id")

    /** legacy 大条目（eps/crt/staff/topic/blog 一次拿全）——核心版只用到 eps 兜底，其余字段忽略 */
    suspend fun subjectLarge(id: Int): Subject = get("/subject/$id?responseGroup=large")

    suspend fun episodes(subjectId: Int, limit: Int = 300, offset: Int = 0): Paged<Episode> =
        get("/v0/episodes?subject_id=$subjectId&type=0&limit=$limit&offset=$offset")

    /** 全部集数（300/页翻页，长番不漏） */
    suspend fun allEpisodes(subjectId: Int): List<Episode> {
        val result = ArrayList<Episode>()
        var offset = 0
        while (true) {
            val page = episodes(subjectId, limit = 300, offset = offset)
            result.addAll(page.data)
            if (page.data.size < 300 || result.size >= page.total) break
            offset += page.data.size
        }
        return result
    }

    suspend fun myEpisodeCollections(subjectId: Int): List<UserEpisodeCollection> {
        val page: Paged<UserEpisodeCollection> =
            get("/v0/users/-/collections/${subjectId}/episodes?limit=1000")
        return page.data
    }

    /** 标记看过：PATCH type=2 */
    suspend fun markEpisodes(subjectId: Int, episodeIDs: List<Int>, type: Int = 2) {
        patch(
            "/v0/users/-/collections/$subjectId/episodes",
            json.encodeToString(EpisodeMarkPayload.serializer(), EpisodeMarkPayload(episodeIDs, type)),
        )
    }

    /**
     * 用户收藏列表（subject_type=2 动漫）。
     * 注意：列表端点是 /v0/users/{username}/collections，不支持 "-" 占位（404），
     * [username] 必须传 me() 返回的用户名（未设用户名时传 UID 数字）。
     */
    suspend fun myCollections(username: String, type: CollectionType, limit: Int = 100, offset: Int = 0): Paged<UserSubjectCollection> =
        get("/v0/users/${java.net.URLEncoder.encode(username, "UTF-8")}/collections?subject_type=2&type=${type.raw}&limit=$limit&offset=$offset")

    /** 全量翻页拉取收藏（100/页） */
    suspend fun allCollections(username: String, type: CollectionType): List<UserSubjectCollection> {
        val result = ArrayList<UserSubjectCollection>()
        var offset = 0
        while (true) {
            val page = myCollections(username, type, offset = offset)
            result.addAll(page.data)
            if (page.data.size < 100 || result.size >= page.total) break
            offset += page.data.size
        }
        return result
    }

    /** 修改收藏（204 无响应体，不解析） */
    suspend fun updateCollection(subjectId: Int, payload: CollectionModifyPayload): Unit =
        send(
            "POST",
            "/v0/users/-/collections/$subjectId",
            json.encodeToString(CollectionModifyPayload.serializer(), payload),
        ).let { }

    suspend fun myCollectionOf(subjectId: Int): UserSubjectCollection? = tryGet("/v0/users/-/collections/$subjectId")

    // MARK: - OAuth

    suspend fun exchangeToken(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String,
        state: String,
    ): OAuthTokenResponse = token(
        listOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "redirect_uri" to redirectUri,
            "code" to code,
            "state" to state,
        ),
    )

    suspend fun refreshToken(clientId: String, clientSecret: String, refreshToken: String): OAuthTokenResponse =
        token(
            listOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "refresh_token" to refreshToken,
            ),
        )

    private suspend fun token(form: List<Pair<String, String>>): OAuthTokenResponse = withContext(Dispatchers.IO) {
        val body = form.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val request = Request.Builder()
            .url("https://bgm.tv/oauth/access_token")
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", Http.BANGUMI_UA)
            .build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BangumiError.Http(response.code, text.take(200))
            json.decodeFromString(OAuthTokenResponse.serializer(), text)
        }
    }

    // MARK: - HTTP 骨架

    private suspend inline fun <reified T> get(path: String): T {
        val text = send("GET", path, null)
        return json.decodeFromString(serializer<T>(), text)
    }

    private suspend inline fun <reified T> tryGet(path: String): T? =
        try {
            val text = send("GET", path, null)
            json.decodeFromString(serializer<T>(), text)
        } catch (e: BangumiError.Http) {
            if (e.code == 404) null else throw e
        }

    private suspend inline fun <reified T> post(path: String, body: String): T {
        val text = send("POST", path, body)
        return json.decodeFromString(serializer<T>(), text)
    }

    private suspend fun patch(path: String, body: String) {
        send("PATCH", path, body)
    }

    @Suppress("SameParameterValue")
    private suspend fun send(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.bgm.tv$path")
                .header("User-Agent", Http.BANGUMI_UA)
                .header("Accept", "application/json")
                .apply {
                    tokenProvider()?.let { header("Authorization", "Bearer $it") }
                    when (method) {
                        "POST" -> post((body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()))
                        "PATCH" -> patch2((body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()))
                    }
                }
                .build()

            var lastError: Exception? = null
            for (attempt in 0..2) {
                try {
                    return@withContext execute(request).use { response ->
                        val text = response.body?.string().orEmpty()
                        when {
                            response.code == 401 -> throw BangumiError.Unauthorized()
                            !response.isSuccessful && response.code != 204 ->
                                throw BangumiError.Http(response.code, text.take(200))
                            else -> text
                        }
                    }
                } catch (e: BangumiError) {
                    throw e // 业务错误不重试
                } catch (e: IOException) {
                    lastError = e
                    if (attempt < 2) kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
            throw BangumiError.Network(lastError ?: IOException("unknown"))
        }

    // Request.Builder.PATCH 不是链式方法名，起个别名避免关键字冲突
    private fun Request.Builder.patch2(body: okhttp3.RequestBody): Request.Builder =
        method("PATCH", body)

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = Http.client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response)
                }
            })
        }
}

// CollectionType 从 model 层引用，避免循环依赖
typealias CollectionType = com.sparkhoward.nagomiani.core.model.CollectionType
