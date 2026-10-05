package com.sparkhoward.nagomiani

import android.app.Application
import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.sparkhoward.nagomiani.core.Http
import com.sparkhoward.nagomiani.core.store.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/** 全局单例容器：Context 相关服务在这里组装（轻量做法，暂不引 DI 框架） */
object AppGraph {
    lateinit var store: AppStore
        private set
    lateinit var appContext: android.content.Context
        private set

    fun init(app: Application) {
        if (!::store.isInitialized) {
            store = AppStore(app)
            appContext = app
        }
    }
}

class NagomiApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        applyNetworkSettings()
        createDownloadChannel()
    }

    /** 离线缓存下载服务的前台通知渠道 */
    private fun createDownloadChannel() {
        val channel = android.app.NotificationChannel(
            com.sparkhoward.nagomiani.player.NagomiDownloadService.CHANNEL_ID,
            "离线缓存",
            android.app.NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 代理设置变化即时生效：OkHttp（API/片源/弹幕）+ WebView（OAuth 授权页）+ Coil（封面） */
    private fun applyNetworkSettings() {
        val executor = Executor { it.run() }
        CoroutineScope(Dispatchers.Default).launch {
            AppGraph.store.networkProxyRaw.collect { raw ->
                val cfg = parseProxy(raw)
                Http.proxyConfig = cfg
                applyWebViewProxy(cfg, executor)
            }
        }
    }

    private fun parseProxy(raw: String): Http.ProxyConfig? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val idx = text.lastIndexOf(':')
        if (idx <= 0) return null
        val host = text.substring(0, idx).trim()
        val port = text.substring(idx + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return Http.ProxyConfig(host, port)
    }

    private fun applyWebViewProxy(cfg: Http.ProxyConfig?, executor: Executor) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        try {
            val controller = ProxyController.getInstance()
            if (cfg == null) {
                controller.clearProxyOverride(executor, Runnable { })
            } else {
                // WebView 与主进程不同网络栈：代理规则只接受 host:port 字符串，由代理端负责解析。
                // OAuth 回调走本机 127.0.0.1:8123，必须绕过代理直连 loopback
                controller.setProxyOverride(
                    ProxyConfig.Builder()
                        .addProxyRule("${cfg.host}:${cfg.port}")
                        .addBypassRule("127.0.0.1")
                        .addBypassRule("localhost")
                        .build(),
                    executor,
                    Runnable { },
                )
            }
        } catch (_: Exception) {
        }
    }

    /** Coil 封面图（lain.bgm.tv 同样被污染）走同一套 DNS/代理栈 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Http.client }
            .crossfade(true)
            .build()
}
