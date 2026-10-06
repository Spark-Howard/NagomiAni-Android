package com.sparkhoward.nagomiani.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/**
 * 应用内更新（GitHub Releases 渠道，无需自建服务端）：
 * - 启动时静默检查 `releases/latest`（网络失败 / 无 Release / 版本不新 均静默，不打扰）；
 * - 发现新 tag（vX.Y.Z）弹更新框，用户点「更新」后应用内下载 APK（带进度）；
 * - 下载完成经 FileProvider 唤起系统安装器（Android 8+ 需用户允许「安装未知应用」）；
 * - 「下次再说」记住该版本，同版本不再提示，出现更新的 tag 才重新提示。
 * 发布流程见 README「发布新版本」。
 */
object UpdateCenter {

    private const val API_URL = "https://api.github.com/repos/Spark-Howard/NagomiAni-Android/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkedThisProcess = false

    /** 派生 client：APK 可能几十 MB，去掉 Http.client 的 60s callTimeout（覆盖整次调用，会掐断大下载） */
    private val downloadClient by lazy {
        Http.client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    }

    sealed interface State {
        data object Idle : State
        data class Available(val release: LatestRelease) : State
        data class Downloading(val received: Long, val total: Long) : State
        data class ReadyToInstall(val release: LatestRelease, val file: File) : State
        data class Failed(val release: LatestRelease?, val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    @Serializable
    data class LatestRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val assets: List<Asset> = emptyList(),
    ) {
        @Serializable
        data class Asset(
            val name: String = "",
            @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        )

        val apkAsset: Asset? get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    // MARK: - 检查

    fun checkForUpdate(context: Context) {
        if (checkedThisProcess) return
        checkedThisProcess = true
        val localVersion = versionNameOf(context)
        scope.launch {
            val release = runCatching {
                val request = Request.Builder()
                    .url(API_URL)
                    .header("User-Agent", "NagomiAni-Android/$localVersion")
                    .build()
                Http.client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) null else parseRelease(resp.body?.string().orEmpty())
                }
            }.getOrNull() ?: return@launch // 无 Release（404）/网络失败：静默
            if (release.apkAsset == null) return@launch
            if (!isNewer(release.tagName, localVersion)) return@launch
            if (release.tagName == AppGraph.store.skippedUpdateVersionOnce()) return@launch
            state.value = State.Available(release)
        }
    }

    /** 「下次再说」：记住该版本，之后同版本不再提示 */
    fun dismiss(context: Context) {
        val release = (state.value as? State.Available)?.release
        state.value = State.Idle
        if (release != null) {
            scope.launch { runCatching { AppGraph.store.setSkippedUpdateVersion(release.tagName) } }
        }
    }

    // MARK: - 下载

    fun startDownload(context: Context, release: LatestRelease) {
        val asset = release.apkAsset ?: return
        state.value = State.Downloading(0, 0)
        scope.launch {
            try {
                val dir = context.getExternalFilesDir("update") ?: File(context.cacheDir, "update")
                dir.mkdirs()
                dir.listFiles()?.filter { it.extension == "apk" }?.forEach { it.delete() } // 清理旧包
                val file = File(dir, "NagomiAni-${release.tagName.removePrefix("v")}.apk")
                val request = Request.Builder()
                    .url(asset.browserDownloadUrl)
                    .header("User-Agent", "NagomiAni-Android/${versionNameOf(context)}")
                    .build()
                downloadClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                received += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 200) {
                                    lastReport = now
                                    state.value = State.Downloading(received, total)
                                }
                            }
                            state.value = State.Downloading(received, total)
                        }
                    }
                }
                state.value = State.ReadyToInstall(release, file)
            } catch (e: Exception) {
                state.value = State.Failed(release, e.message ?: "下载失败")
            }
        }
    }

    // MARK: - 安装

    fun install(context: Context) {
        val ready = state.value as? State.ReadyToInstall ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Android 8+ 未授予「安装未知应用」：引导到授权页，回来后再点「安装」
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            state.value = State.Failed(ready.release, "请先允许「安装未知应用」，再回来点「安装」")
        }
    }

    fun toIdle() {
        state.value = State.Idle
    }

    // MARK: - 版本解析（纯函数，供单元测试）

    /** releases/latest JSON → LatestRelease；解析失败返回 null */
    fun parseRelease(text: String): LatestRelease? =
        runCatching { json.decodeFromString(LatestRelease.serializer(), text) }.getOrNull()

    /** 版本比较：vX.Y.Z 逐段数值比较（缺失段补 0）；任一侧解析失败一律视为不更新（fail-safe） */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** "v0.2.1" → [0,2,1]；"v0.2" → [0,2]；"0.2.0-beta" → [0,2,0]（后缀忽略）；无数字段 → null */
    private fun parseVersion(raw: String): List<Int>? =
        raw.trim().removePrefix("v").removePrefix("V").split('.').map { part ->
            part.takeWhile { it.isDigit() }.ifEmpty { return null }.toInt()
        }

    private fun versionNameOf(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"
}
