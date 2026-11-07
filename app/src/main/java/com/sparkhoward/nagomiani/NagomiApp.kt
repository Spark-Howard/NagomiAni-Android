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
        createUpdateNotifyChannel()
        createDailyAiringChannel()
        // 小部件后台刷新周期任务（幂等注册，KEEP 不重置周期；重启后 WorkManager 自动恢复）
        com.sparkhoward.nagomiani.ui.widget.WidgetRefreshWorker.ensureScheduled(this)
        if (BuildConfig.DEBUG) installMainThreadWatchdog()
    }

    /** debug 构建的主线程卡死看门狗：3s 无响应即把主线程栈打进 logcat（ANR 前就能定位阻塞点）。
     *  探测 = 往主线程 post 置位标记，后台线程轮询超时未置位即采样 dump */
    private fun installMainThreadWatchdog() {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val mainThread = android.os.Looper.getMainLooper().thread
        val probe = Runnable { watchdog.probeAcknowledged = true }
        Thread({
            while (true) {
                watchdog.probeAcknowledged = false
                mainHandler.post(probe)
                Thread.sleep(3_000)
                if (!watchdog.probeAcknowledged) {
                    val stack = mainThread.stackTrace.joinToString("\n") { "    at $it" }
                    android.util.Log.e("ANR_WATCHDOG", "主线程 3s 无响应，堆栈：\n$stack")
                }
                // 等下一轮前的余量，避免把正常忙盘认成卡死
                Thread.sleep(1_000)
            }
        }, "anr-watchdog").apply { isDaemon = true }.start()
    }

    private val watchdog = object { var probeAcknowledged = false }


    /** 离线缓存下载服务的前台通知渠道 */
    private fun createDownloadChannel() {
        val channel = android.app.NotificationChannel(
            com.sparkhoward.nagomiani.player.NagomiDownloadService.CHANNEL_ID,
            "离线缓存",
            android.app.NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 追番更新提醒的通知渠道（有声音、不弹横幅） */
    private fun createUpdateNotifyChannel() {
        val channel = android.app.NotificationChannel(
            com.sparkhoward.nagomiani.core.notify.EpisodeNotify.CHANNEL_ID,
            "追番更新",
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        )
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 今日放送提醒渠道（DailyAiringNotifier：放送表 × 在看收藏） */
    private fun createDailyAiringChannel() {
        val channel = android.app.NotificationChannel(
            com.sparkhoward.nagomiani.core.notify.DailyAiringNotifier.CHANNEL_ID,
            getString(com.sparkhoward.nagomiani.R.string.notify_channel_name),
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
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
