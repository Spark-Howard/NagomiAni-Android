package com.sparkhoward.nagomiani.core.bangumi

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID

private val Context.authStore by preferencesDataStore(name = "nagomi_auth")

/** Bangumi OAuth 客户端凭据（复用 mac 版注册的 client，redirect 也保持 127.0.0.1:8123） */
object BangumiCredentials {
    const val CLIENT_ID = "bgm70036a919d2db2f29"
    const val CLIENT_SECRET = "2b3030f8a03d027754ea30f3424fe8db"
    const val REDIRECT_URI = "http://127.0.0.1:8123/callback"
    const val AUTHORIZE_URL = "https://bgm.tv/oauth/authorize"
    const val TOKEN_URL = "https://bgm.tv/oauth/access_token"
}

/**
 * Bangumi 登录态：token 存独立 DataStore；OAuth 走内嵌 WebView +
 * 本机 127.0.0.1:8123 回调服务（Android 允许 localhost 明文监听，与 mac 版同协议）。
 */
class BangumiAuth(private val context: Context) {

    data class Session(
        val accessToken: String?,
        val refreshToken: String?,
        val expiresAtMillis: Long,
        val userID: Long,
    ) {
        val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()
        fun needsRefresh(): Boolean = isLoggedIn && expiresAtMillis - System.currentTimeMillis() < 60_000
    }

    private object Keys {
        val accessToken = stringPreferencesKey("accessToken")
        val refreshToken = stringPreferencesKey("refreshToken")
        val expiresAt = longPreferencesKey("expiresAt")
        val userID = longPreferencesKey("userID")
    }

    suspend fun session(): Session = withContext(Dispatchers.IO) {
        val prefs = context.authStore.data.first()
        Session(
            accessToken = prefs[Keys.accessToken],
            refreshToken = prefs[Keys.refreshToken],
            expiresAtMillis = prefs[Keys.expiresAt] ?: 0L,
            userID = prefs[Keys.userID] ?: 0L,
        )
    }

    suspend fun isLoggedIn(): Boolean = session().isLoggedIn

    suspend fun logout() = context.authStore.edit { it.clear() }

    /** 授权页 URL（state 防 CSRF，回调校验用） */
    fun authorizeURL(state: String): String =
        "${BangumiCredentials.AUTHORIZE_URL}?client_id=${BangumiCredentials.CLIENT_ID}" +
            "&response_type=code&redirect_uri=${java.net.URLEncoder.encode(BangumiCredentials.REDIRECT_URI, "UTF-8")}" +
            "&state=$state"

    companion object {
        /**
         * 从回调 URL 提取授权码（WebView 拦截路径：不让 WebView 真去请求本机回环地址）。
         * 校验 state 防 CSRF；非本机回调、带 error、state 不符均返回 null。
         */
        fun extractAuthorizationCode(url: String, expectedState: String): String? {
            if (!url.startsWith("http://127.0.0.1:8123/callback")) return null
            val params = parseQuery(url.substringAfter('?', ""))
            if (params["error"] != null) return null
            val code = params["code"] ?: return null
            return if (params["state"] == expectedState) code else null
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) null
                else URLDecoder.decode(pair.substring(0, eq), "UTF-8") to
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.toMap()
    }

    /**
     * 启动本机回调服务并等待授权码（总时长 5 分钟：内嵌 WebView 里首次登录 bgm.tv 较慢）。
     * 校验 [expectedState] 防 CSRF。WebView 页面加载到含 code 的回调 URL 时也会直接通知 [onRedirect]，双保险。
     */
    suspend fun awaitAuthorizationCode(expectedState: String, onRedirect: (url: String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            var result: String? = null
            // 端口可能被上一次退出登录页尚未释放的监听短暂占用：小退避重试（约 5s 窗口）
            var bound: ServerSocket? = null
            for (attempt in 0 until 10) {
                bound = runCatching {
                    ServerSocket(8123, 5, java.net.InetAddress.getLoopbackAddress()).apply { reuseAddress = true }
                }.getOrNull()
                if (bound != null) break
                delay(500)
            }
            val server = bound ?: return@withContext null // 端口被占用，无法捕获回调
            try {
                server.soTimeout = 5_000 // 短轮询 accept：页面退出/超时都能及时收尾
                val deadline = System.currentTimeMillis() + 300_000L
                while (result == null && System.currentTimeMillis() < deadline) {
                    coroutineContext.ensureActive() // 登录页退出时立刻停止监听并释放端口
                    val socket = try {
                        server.accept()
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: SocketException) {
                        break // server 已被关闭
                    }
                    try {
                        socket.use { s ->
                            s.soTimeout = 5_000
                            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                            val requestLine = reader.readLine() ?: return@use
                            // 读完请求头（忽略内容）；空行或 EOF 都结束（EOF 继续读会死循环）
                            while (reader.readLine()?.isNotEmpty() == true) { /* drain */ }
                            val url = requestLine.split(" ").getOrNull(1) ?: return@use
                            val fullURL = "http://127.0.0.1:8123$url"
                            onRedirect(fullURL)
                            val params = parseQuery(url.substringAfter('?', ""))
                            if (params["error"] != null) {
                                respondAndClose(s, success = false)
                                return@use
                            }
                            val code = params["code"]
                            val codeState = params["state"]
                            if (code != null && codeState == expectedState) {
                                respondAndClose(s, success = true)
                                result = code
                            } else {
                                respondAndClose(s, success = false)
                            }
                        }
                    } catch (e: IOException) {
                        // 单个连接失败（预连接无数据/客户端中途断开）：跳过，继续等下一个
                    }
                }
            } finally {
                runCatching { server.close() }
            }
            result
        }

    /** 用授权码换 token 并落盘；返回登录后 session。首次失败自动重试一次（授权码签发/网络偶发不同步） */
    suspend fun completeLogin(code: String): Session {
        val api = BangumiApi(tokenProvider = { null })
        val token = try {
            api.exchangeToken(
                clientId = BangumiCredentials.CLIENT_ID,
                clientSecret = BangumiCredentials.CLIENT_SECRET,
                redirectUri = BangumiCredentials.REDIRECT_URI,
                code = code,
                state = "",
            )
        } catch (e: Exception) {
            kotlinx.coroutines.delay(1500)
            api.exchangeToken(
                clientId = BangumiCredentials.CLIENT_ID,
                clientSecret = BangumiCredentials.CLIENT_SECRET,
                redirectUri = BangumiCredentials.REDIRECT_URI,
                code = code,
                state = "",
            )
        }
        val session = Session(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtMillis = System.currentTimeMillis() + token.expiresInSeconds * 1000,
            userID = token.userId,
        )
        persist(session)
        return session
    }

    /** 过期前 60s 内刷新（refresh_token 轮换：响应里的新 token 覆盖旧值） */
    suspend fun refreshIfNeeded(): Session {
        val current = session()
        if (!current.needsRefresh()) return current
        val refreshToken = current.refreshToken ?: return current
        return try {
            val api = BangumiApi(tokenProvider = { null })
            val token = api.refreshToken(BangumiCredentials.CLIENT_ID, BangumiCredentials.CLIENT_SECRET, refreshToken)
            val refreshed = current.copy(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken ?: current.refreshToken,
                expiresAtMillis = System.currentTimeMillis() + token.expiresInSeconds * 1000,
            )
            persist(refreshed)
            refreshed
        } catch (e: Exception) {
            current // 刷新失败保留旧值（可能还有效）
        }
    }

    private suspend fun persist(session: Session) = context.authStore.edit { prefs ->
        if (session.accessToken != null) prefs[Keys.accessToken] = session.accessToken
        if (session.refreshToken != null) prefs[Keys.refreshToken] = session.refreshToken
        prefs[Keys.expiresAt] = session.expiresAtMillis
        prefs[Keys.userID] = session.userID
    }

    private fun respondAndClose(socket: java.net.Socket, success: Boolean) {
        runCatching {
            val body = if (success) {
                "<html><head><meta charset='utf-8'></head><body style='font-family:sans-serif;text-align:center;padding-top:80px'><h2>登录成功 ✓</h2><p>请返回 NagomiAni</p></body></html>"
            } else {
                "<html><body></body></html>"
            }
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body"
            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        }
        socket.getOutputStream().let { socket.close() }
    }
}
