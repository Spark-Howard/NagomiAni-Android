package com.sparkhoward.nagomiani.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** 应用级共享 HTTP 客户端（各 API 自带专属头再按请求覆盖） */
object Http {

    /** Bangumi 要求可识别的 UA（封禁通用 UA），与 mac 版一致 */
    const val BANGUMI_UA =
        "Spark-Howard/NagomiAni/1.0.0 (Android) (https://github.com/Spark-Howard/NagomiAni)"

    /** 片源站抓取伪装浏览器 UA（防盗链/风控），与 mac 版相同 */
    const val BROWSER_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val BGM_ZONE = "bgm.tv" // 覆盖 bgm.tv / api.bgm.tv / lain.bgm.tv 等

    /**
     * bgm.tv 整域托管在 Cloudflare；国内网络普遍对该域 DNS 污染（解析到无关 IP）。
     * 下面是 zone 当前分配的 anycast 边缘 IP——带正确 SNI 连任意边缘节点都能服务，
     * 作为系统 DNS 与 DoH 都失败时的最后兜底。
     */
    private val bgmZoneFallback: List<InetAddress> = listOf(
        InetAddress.getByName("104.26.8.23"),
        InetAddress.getByName("104.26.9.23"),
        InetAddress.getByName("172.67.73.67"),
    )

    private val dohBootstrapHosts = listOf(InetAddress.getByName("1.1.1.1"))

    /** DoH 直连 1.1.1.1（证书含 IP SAN，自身不依赖 DNS）；国内部分网络会阻断，失败后熔断 5 分钟 */
    private val doh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .callTimeout(6, TimeUnit.SECONDS)
                    .build(),
            )
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(dohBootstrapHosts)
            .build()
    }

    @Volatile private var dohDisabledUntil = 0L

    private fun dohLookup(host: String): List<InetAddress>? {
        if (System.currentTimeMillis() < dohDisabledUntil) return null
        return try {
            doh.lookup(host)
        } catch (e: Exception) {
            dohDisabledUntil = System.currentTimeMillis() + 5 * 60_000
            null
        }
    }

    /** 分层 DNS：bgm 系域名（必被污染）DoH 优先、边缘 IP 兜底；
     *  其他域名系统 DNS 优先、DoH 结果垫底（运营商劫持返回假 IP 时，连接失败会自动顺延到真 IP） */
    private class AppDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val inBgmZone = hostname == BGM_ZONE || hostname.endsWith(".$BGM_ZONE")
            if (inBgmZone) return dohLookup(hostname) ?: bgmZoneFallback
            val system = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                emptyList()
            }
            val doh = dohLookup(hostname).orEmpty().filter { it !in system }
            return when {
                system.isNotEmpty() -> system + doh
                doh.isNotEmpty() -> doh
                else -> throw java.net.UnknownHostException(hostname)
            }
        }
    }

    /** 应用内代理配置（mac 版等价物：macOS 靠系统代理；安卓无系统级方案，改为应用内设置） */
    data class ProxyConfig(val host: String, val port: Int)

    @Volatile var proxyConfig: ProxyConfig? = null

    private val dynamicProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val cfg = proxyConfig ?: return listOf(Proxy.NO_PROXY)
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(cfg.host, cfg.port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(AppDns())
        .proxySelector(dynamicProxySelector)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor(okhttp3.logging.HttpLoggingInterceptor { msg ->
            try {
                android.util.Log.d("NagomiHttp", msg)
            } catch (_: Throwable) {
                println(msg) // JVM 单元测试环境无 android.util.Log
            }
        }.setLevel(okhttp3.logging.HttpLoggingInterceptor.Level.BASIC))
        .build()
}
