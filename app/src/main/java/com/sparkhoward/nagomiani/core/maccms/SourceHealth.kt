package com.sparkhoward.nagomiani.core.maccms

import com.sparkhoward.nagomiani.core.Http
import com.sparkhoward.nagomiani.core.store.AppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 片源测速：站点 API 探测（ac=list 最轻请求）与线路直链探测（Range GET 取 TTFB）。
 * 共享 Http.client 的连接池/DNS/代理/应用内代理，仅派生短超时客户端；
 * 探测不读 body（响应头到达即计时结束），结果由调用方持久化到 AppStore。
 * 注意：站点探测打的是 API 域，与实际播放的 CDN 域可能不同——结果代表 API 可达性/快慢。
 */
object SourceHealth {

    /** 探测专用短超时：callTimeout 6s 兜住连接+响应全过程（共享 client 是 60s，测速不能等它） */
    private val probeClient by lazy {
        Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    }

    /** 站点 API 探测：ac=list&pg=1（项目已在用的最小合法请求），2xx 即可达；返回 TTFB ms，不可达 null */
    suspend fun probeSite(siteURL: String): Long? = probe(
        Request.Builder()
            .url(probeURL(siteURL))
            .header("User-Agent", Http.BROWSER_UA)
            .build(),
    )

    /**
     * 探测地址：站点 URL 必须先经 normalizeAPIBase 补全 /api.php/provide/vod/ 再拼参数。
     * 内置站在 builtinSites 里是裸域名，直接拼 "?ac=list" 会打到站点根路径——
     * 量子/极速实测 403、暴风 500，全被误判「不可达」（爱坤根路径恰好 200 才漏网）。
     */
    fun probeURL(siteURL: String): String =
        "${MacCMSApi.normalizeAPIBase(siteURL) ?: siteURL.trimEnd('/')}?ac=list&pg=1"

    /** 线路直链探测：带防盗链头发 Range GET（m3u8/mp4 都吃这一套），返回 TTFB ms，不可达 null */
    suspend fun probeRoute(url: String, headers: Map<String, String>, userAgent: String?): Long? = probe(
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent ?: Http.BROWSER_UA)
            .header("Range", "bytes=0-1")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build(),
    )

    private suspend fun probe(request: Request): Long? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        runCatching {
            probeClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) (System.nanoTime() - started) / 1_000_000 else null
            }
        }.getOrNull()
    }

    /** 并发探测全部站点；key = providerID(host)，与详情页/番库的 providerID 对齐 */
    suspend fun probeAllSites(sites: List<Pair<String, String>>): Map<String, AppStore.SiteHealthEntry> = coroutineScope {
        sites.map { (_, siteURL) ->
            async {
                val latency = probeSite(siteURL)
                MacCMSApi(siteURL).providerID to AppStore.SiteHealthEntry(
                    ok = latency != null,
                    latencyMs = latency ?: 0,
                    testedAt = System.currentTimeMillis(),
                )
            }
        }.awaitAll().toMap()
    }

    // MARK: - 纯函数（JVM 可测）

    /** 全部可达站点里延迟最小者（并列取遍历序靠前者）；无可达站返回 null */
    fun fastestHost(entries: Map<String, AppStore.SiteHealthEntry>): String? =
        entries.filter { it.value.ok }.minByOrNull { it.value.latencyMs }?.key

    /** 最快线路下标（并列取靠前者）；全超时/空表返回 null */
    fun fastestRouteIndex(latencies: List<Long?>): Int? {
        var bestIndex = -1
        var bestValue = Long.MAX_VALUE
        for ((index, latency) in latencies.withIndex()) {
            if (latency != null && latency < bestValue) {
                bestIndex = index
                bestValue = latency
            }
        }
        return if (bestIndex >= 0) bestIndex else null
    }

    /** 断流自动换线的下一条线路（从当前往后循环）；单线路或已换遍其余线路（autoAttempts 达 routeCount-1）返回 null 放弃 */
    fun nextRouteOnFailure(currentIndex: Int, routeCount: Int, autoAttempts: Int): Int? {
        if (routeCount < 2) return null
        if (autoAttempts >= routeCount - 1) return null
        return (currentIndex + 1) % routeCount
    }

    /** 延迟展示：<1s 显示整毫秒，≥1s 显示一位小数秒 */
    fun formatLatency(ms: Long): String =
        if (ms < 1000) "${ms}ms" else String.format(Locale.US, "%.1fs", ms / 1000.0)
}
