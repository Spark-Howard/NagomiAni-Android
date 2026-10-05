package com.sparkhoward.nagomiani.core.download

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.sparkhoward.nagomiani.AppGraph
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * 番剧离线缓存基座：SimpleCache + DownloadManager 单例（两者都必须全进程唯一）。
 * 播放侧用 [cacheDataSourceFactory] 实现"边播边存 + 已下载内容离线可播"；
 * 下载侧通过 NagomiDownloadService 后台下载。
 */
object DownloadUtil {

    /** @param title 番剧标题；@param coverURL 封面（缓存列表展示用，随记录持久化；旧记录可能缺封面） */
    data class ItemState(val state: Int, val percent: Float, val title: String? = null, val coverURL: String? = null) {
        val isCompleted: Boolean get() = state == Download.STATE_COMPLETED
        val isActive: Boolean get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED
    }

    /** 分段下载并发线程数：并发越高聚合速度越快（源站按连接限速时尤甚），8 是速度与占用的折中 */
    private const val SEGMENT_POOL_SIZE = 8

    /** resumeKey(=下载 id) → 状态/进度 */
    val states = MutableStateFlow<Map<String, ItemState>>(emptyMap())

    /** 下载记录元数据（request.data 载荷）：旧记录为纯文本标题，新记录为 JSON（标题+封面） */
    @kotlinx.serialization.Serializable
    private data class DownloadMeta(val title: String = "", val cover: String? = null)

    private val metaJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun metaOf(download: Download): DownloadMeta =
        runCatching { metaJson.decodeFromString<DownloadMeta>(String(download.request.data)) }
            .getOrElse { DownloadMeta(title = String(download.request.data), cover = null) }

    /** 通知栏等处的展示名（解析记录元数据；旧记录为纯文本标题，空白回退"视频"） */
    fun displayTitleOf(download: Download): String = metaOf(download).title.ifBlank { "视频" }

    private fun encodeMeta(title: String, coverURL: String?): ByteArray =
        runCatching { metaJson.encodeToString(DownloadMeta(title, coverURL)).toByteArray() }
            .getOrDefault(title.toByteArray())

    @Volatile private var managerRef: DownloadManager? = null
    @Volatile private var cacheRef: SimpleCache? = null

    private fun cacheDir(context: Context): File =
        context.getExternalFilesDir(null)?.let { File(it, "downloads") } ?: File(context.filesDir, "downloads")

    fun cache(context: Context): SimpleCache {
        cacheRef?.let { return it }
        synchronized(this) {
            cacheRef?.let { return it }
            val c = SimpleCache(
                cacheDir(context.applicationContext),
                NoOpCacheEvictor(),
                StandaloneDatabaseProvider(context.applicationContext),
            )
            cacheRef = c
            return c
        }
    }

    fun downloadManager(context: Context): DownloadManager {
        managerRef?.let { return it }
        synchronized(this) {
            managerRef?.let { return it }
            val app = context.applicationContext
            val manager = DownloadManager(
                app,
                StandaloneDatabaseProvider(app),
                cache(app),
                // 下载的上游数据源：与播放同款请求头（部分站点要求 Referer/UA）
                androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent(com.sparkhoward.nagomiani.core.Http.BROWSER_UA)
                    .setAllowCrossProtocolRedirects(true),
                // 该池同时是分段下载的并发度：SegmentDownloader 把所有分段提交到这里执行，
                // 实测源站并发近线性扩展（4 路聚合 ≈ 串行的 5.5 倍），默认 3 太保守
                java.util.concurrent.Executors.newFixedThreadPool(SEGMENT_POOL_SIZE),
            )
            manager.addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(manager: DownloadManager, download: Download, exception: Exception?) {
                        states.update {
                            val meta = metaOf(download)
                            it + (download.request.id to ItemState(download.state, download.percentDownloaded, meta.title, meta.cover))
                        }
                    }

                    override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                        states.update { it - download.request.id }
                    }
                },
            )
            managerRef = manager
            refreshStates(manager)
            startTicker()
            return manager
        }
    }

    /** 启动时把下载索引载入内存（库已存在的记录） */
    private fun refreshStates(manager: DownloadManager) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val map = HashMap<String, ItemState>()
            runCatching {
                manager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        val d = cursor.download
                        val meta = metaOf(d)
                        map[d.request.id] = ItemState(d.state, d.percentDownloaded, meta.title, meta.cover)
                    }
                }
            }
            states.value = map
        }
    }

    /**
     * 从内存中的活动下载同步实时进度到 [states]。
     * 必须轮询：media3 的下载线程只把百分比写进共享 DownloadProgress（volatile），
     * onDownloadChanged 仅在状态/内容长度变化时回调——纯靠监听器列表进度会一直停在初始值，
     * 而前台通知是服务每秒重发才看起来在动。有活动下载时由 [startTicker] 每秒调用。
     */
    fun syncLiveStates() {
        val manager = managerRef ?: return
        val live = HashMap<String, ItemState>()
        runCatching {
            for (d in manager.currentDownloads) {
                // REMOVING 是移除过程中的瞬态：onDownloadRemoved 已把条目撤下列表，不回灌
                if (d.state == Download.STATE_REMOVING) continue
                val meta = metaOf(d)
                live[d.request.id] = ItemState(d.state, d.percentDownloaded, meta.title, meta.cover)
            }
        }.onSuccess { if (live.isNotEmpty()) states.value = states.value + live }
    }

    /** 有活动下载时每秒轮询一次实时进度（活动下载由状态回调驱动，轮询只补百分比细粒度变化） */
    private fun startTicker() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (states.value.values.any { it.isActive }) syncLiveStates()
            }
        }
    }

    /** 播放侧数据源工厂：缓存命中走本地、未命中走网络并写入缓存（错误时跳过缓存直连） */
    fun cacheDataSourceFactory(upstream: androidx.media3.datasource.DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(AppGraph.appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** 添加一集的下载任务（id = resumeKey；标题+封面写入记录，供缓存列表展示） */
    fun addDownload(id: String, url: String, title: String, coverURL: String? = null) {
        val request = DownloadRequest.Builder(id, android.net.Uri.parse(url))
            .setData(encodeMeta(title, coverURL))
            .build()
        // foreground 必须为 true：让服务以 startForeground 拉起、进程被系统钉住——
        // 传 false 时服务只是普通后台服务，App 一切后台进程就被冻结，下载停摆（通知虽在却不走）
        com.sparkhoward.nagomiani.player.NagomiDownloadService.sendAddDownload(AppGraph.appContext, request, true)
    }

    fun removeDownload(id: String) {
        com.sparkhoward.nagomiani.player.NagomiDownloadService.sendRemoveDownload(AppGraph.appContext, id, false)
    }

    /**
     * 拉起下载服务（ACTION_INIT）：载入下载索引（缓存列表/角标才有数据）并恢复上次中断的下载。
     * 管理器初始为暂停态，resumeDownloads 由服务负责——进程重启后服务不在，列表会一直是空的。
     * 必须在前台调用（MainActivity/缓存页入口）；后台启动前台服务会被系统拒绝，故吞掉异常兜底。
     */
    fun ensureServiceStarted(context: Context) {
        runCatching {
            androidx.media3.exoplayer.offline.DownloadService.startForeground(
                context.applicationContext,
                com.sparkhoward.nagomiani.player.NagomiDownloadService::class.java,
            )
        }
    }

    /**
     * 装配某条下载记录的离线可播参数（缓存页点按已缓存集用）：
     * URL 用下载记录里的原始地址——播放侧 CacheDataSource 按 URL 命中本地缓存，
     * 已下内容离线可播，缺的段落自动联网补。
     */
    fun playbackFor(resumeKey: String): com.sparkhoward.nagomiani.core.model.OnlinePlayback? {
        val manager = managerRef ?: return null
        val download = runCatching {
            manager.currentDownloads.firstOrNull { it.request.id == resumeKey }
                ?: manager.downloadIndex.getDownload(resumeKey)
        }.getOrNull() ?: return null
        val url = download.request.uri.toString()
        val number = resumeKey.substringAfterLast(':').toIntOrNull() ?: return null
        val title = metaOf(download).title.takeIf { it.isNotBlank() }
        val origin = runCatching { java.net.URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault("")
        return com.sparkhoward.nagomiani.core.model.OnlinePlayback(
            url = url,
            displayTitle = listOfNotNull(title?.takeIf { it.isNotBlank() }, "第 $number 集").joinToString(" ").ifEmpty { null },
            seriesKey = resumeKey.substringBeforeLast(':'),
            episodeNumber = number,
            resumeKey = resumeKey,
            httpHeaders = if (origin.isNotEmpty()) mapOf("Referer" to origin) else emptyMap(),
            userAgent = com.sparkhoward.nagomiani.core.Http.BROWSER_UA,
            routes = listOf(url),
            showTitle = title,
        )
    }
}
