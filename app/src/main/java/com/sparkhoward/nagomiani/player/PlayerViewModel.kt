package com.sparkhoward.nagomiani.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.bangumi.BangumiError
import com.sparkhoward.nagomiani.core.dandanplay.DandanplayApi
import com.sparkhoward.nagomiani.core.maccms.OnlineRepo
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlinePlayback
import com.sparkhoward.nagomiani.core.model.OnlineShow
import com.sparkhoward.nagomiani.core.store.AppStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 播放器 ViewModel：与 mac 版 PlayerModel 同语义——
 * 断点续播（<15s 不续 / 距尾 30s 视为看完 / 5s 节流落盘）、看完同步双触发：
 * 90% 停留判定（≥300s、seek 后 10s 不判定、拖动重新武装）+ EOF 无条件必同步，
 * 失败离线入队 200 条、90%/EOF 连播征询条、线路切换保留进度。
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    val store get() = AppGraph.store
    private val onlineRepo = OnlineRepo()
    private val auth = BangumiAuth(app)

    val danmaku = MutableStateFlow<DanmakuController?>(null)

    /** 弹幕设置（从 AppStore 载入，改动即时生效并持久化） */
    val danmakuSettings = MutableStateFlow(DanmakuSettings())

    init {
        val s = danmakuSettings
        viewModelScope.launch {
            // 一次性迁移：旧字号预设（18/22/28）整体偏大，按 0.7 缩到新刻度（22 → ~15）
            if (!store.danmakuFontScaled.first()) {
                store.setDanmakuFontSize((store.danmakuFontSize.first() * 0.7).coerceIn(10.0, 18.0))
                store.markDanmakuFontScaled()
            }
        }
        viewModelScope.launch {
            store.danmakuEnabled.collect { s.value = s.value.copy(enabled = it) }
        }
        viewModelScope.launch {
            store.danmakuFontSize.collect { s.value = s.value.copy(fontSize = it.toFloat()) }
        }
        viewModelScope.launch {
            store.danmakuColorMode.collect { mode ->
                s.value = s.value.copy(colorMode = DanmakuSettings.ColorMode.entries.firstOrNull { it.name == mode } ?: DanmakuSettings.ColorMode.ORIGINAL)
            }
        }
        viewModelScope.launch {
            store.danmakuCustomColor.collect { s.value = s.value.copy(customColor = it.toUInt() and 0xFFFFFFu) }
        }
        viewModelScope.launch {
            store.danmakuOpacity.collect { s.value = s.value.copy(opacity = it.toFloat()) }
        }
        viewModelScope.launch {
            store.autoplayNext.collect { autoplayNext.value = it }
        }
    }

    // MARK: - 竖屏功能面板（选集 / 倍速 / 线路 / 连播 / 缓存本集）

    /** 竖屏面板数据：本番选集列表 + 当前集号（换集时刷新） */
    data class PanelState(
        val episodes: List<OnlineEpisode> = emptyList(),
        val currentNumber: Int = 0,
    )

    val panel = MutableStateFlow(PanelState())
    val speed = MutableStateFlow(1.0f)
    val autoplayNext = MutableStateFlow(true)

    /** 本番选集缓存（跨集复用，连播提示同源） */
    private val episodeCache = HashMap<String, List<OnlineEpisode>>()

    private fun showOf(item: OnlinePlayback) = OnlineShow(
        providerID = item.seriesKey.removePrefix("online:").substringBefore(':'),
        showID = item.seriesKey.removePrefix("online:").substringAfter(':'),
        title = item.showTitle ?: "",
    )

    private suspend fun episodesFor(item: OnlinePlayback): List<OnlineEpisode> =
        episodeCache.getOrPut(item.seriesKey) { onlineRepo.episodes(showOf(item)) }

    /** 面板数据懒加载：进入竖屏面板或换集时刷新 */
    fun loadPanelIfNeeded() {
        val item = playback ?: return
        if (panel.value.episodes.isNotEmpty() && panel.value.currentNumber == item.episodeNumber) return
        viewModelScope.launch {
            val eps = runCatching { episodesFor(item) }.getOrDefault(emptyList())
            panel.value = PanelState(episodes = eps, currentNumber = item.episodeNumber)
        }
    }

    /** 点选集切换（含上一集/下一集）：preparePlayback 取流后换会话 */
    fun playEpisodeNumber(number: Int) {
        val item = playback ?: return
        viewModelScope.launch {
            val eps = runCatching { episodesFor(item) }.getOrDefault(emptyList())
            val ep = eps.firstOrNull { it.number == number } ?: return@launch
            val nextPlayback = onlineRepo.preparePlayback(showOf(item), ep, item.showTitle) ?: return@launch
            stopPlayback()
            start(nextPlayback, forceRestart = nextPlayback.resumeKey == item.resumeKey)
        }
    }

    fun setSpeed(value: Float) {
        speed.value = value
        exoPlayer?.setPlaybackSpeed(value)
    }

    fun toggleAutoplayNext() {
        viewModelScope.launch { store.setAutoplayNext(!autoplayNext.value) }
    }

    /** 缓存当前正在播的这集（id=resumeKey，与详情页缓存同路；封面从已注册番剧解析） */
    fun cacheCurrentEpisode() {
        val item = playback ?: return
        viewModelScope.launch {
            val cover = runCatching {
                store.knownShow(item.seriesKey)?.coverURL ?: store.cloudEntry(item.seriesKey)?.coverURL
            }.getOrNull()
            com.sparkhoward.nagomiani.core.download.DownloadUtil.addDownload(item.resumeKey, item.url, item.showTitle ?: "", cover)
            ui.value = ui.value.copy(statusMessage = "已加入离线缓存")
        }
    }

    /** 更新弹幕设置（UI 弹层调用：即时生效 + 持久化） */
    fun updateDanmakuSettings(transform: (DanmakuSettings) -> DanmakuSettings) {
        val next = transform(danmakuSettings.value)
        danmakuSettings.value = next
        viewModelScope.launch {
            store.setDanmakuEnabled(next.enabled)
            store.setDanmakuFontSize(next.fontSize.toDouble())
            store.setDanmakuColorMode(next.colorMode.name)
            store.setDanmakuCustomColor(next.customColor.toInt())
            store.setDanmakuOpacity(next.opacity.toDouble())
        }
    }

    /** 顶栏信息 + 状态提示 */
    data class UIState(
        val title: String = "",
        val statusMessage: String? = null,
        val nextOffer: NextOffer? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
    ) {
        data class NextOffer(val label: String)
    }

    val ui = MutableStateFlow(UIState())

    // 播放会话参数（PlayerScreen 进入时通过 start() 注入）
    var playback: OnlinePlayback? = null
        private set

    // Compose 可观察：PlayerScreen 的 update 依赖它重组，否则播放器创建后视图拿不到 Surface（黑屏有声）
    var exoPlayer: ExoPlayer? by androidx.compose.runtime.mutableStateOf<ExoPlayer?>(null)
        private set

    // 续播/落盘闸门（mac 版 pendingResumeTarget 同款：起播瞬间位置未达续播点不落盘）
    private var resumeTarget: Double? = null
    private var lastSaveAt = 0L
    private var lastSeekAt: Long = 0
    private var nextOfferTriggered = false
    private var markedThisSession = HashSet<String>()
    private val confirmedCollected = HashSet<Int>()
    private var periodicJob: Job? = null

    /** ExoPlayer 事件桥（PlayerScreen 的 Compose 状态用） */
    data class PlayerState(
        val isPlaying: Boolean = false,
        val positionSeconds: Double = 0.0,
        val durationSeconds: Double = 0.0,
        val isBuffering: Boolean = false,
        val ended: Boolean = false,
    )

    val playerState = MutableStateFlow(PlayerState())

    fun start(item: OnlinePlayback, forceRestart: Boolean = false) {
        if (!forceRestart && playback?.resumeKey == item.resumeKey && exoPlayer != null) return
        playback = item
        nextOfferTriggered = false
        // 重进/重播同一集允许重新判定看完同步（否则首播已入列的 key 会挡住后续所有同步）
        markedThisSession.remove("${item.seriesKey}:${item.episodeNumber}")
        currentRouteIndex = 0 // 新会话从首选线路开始（上集切过线路时必须复位）
        ui.value = UIState(title = item.displayTitle ?: "", isLoading = true)

        val context = getApplication<Application>()
        val player = exoPlayer ?: ExoPlayer.Builder(context).build().also {
            exoPlayer = it
            it.addListener(playerListener)
        }

        // 弹幕控制器整个会话只建一次（跨集保留内存缓存；凭据缺失则弹幕静默停用）
        if (danmaku.value == null) {
            val credentials = DanmakuCredentialsHolder.load(context)
            danmaku.value = credentials?.let { DanmakuController(DandanplayApi(it.first, it.second)) }
        }

        viewModelScope.launch {
            // 断点续播决策（与 mac 版 ResumePolicy 一致）
            val entry = store.resumeEntry(item.resumeKey)
            val resumeAt = entry?.takeIf { it.isResumable }?.position ?: 0.0
            resumeTarget = if (resumeAt > 0) resumeAt else null
            openMedia(player, item.url, item, resumeAt)
            if (resumeAt > 0) {
                ui.value = ui.value.copy(
                    statusMessage = "已从 ${formatTime(resumeAt)} 继续播放",
                    isLoading = false,
                )
            }
            prepareDanmaku(item)
            flushPendingMarksIfNeeded()
        }
        startPeriodicTasks()
        loadPanelIfNeeded()
    }

    /** 组装 MediaSource：HLS 用 HlsMediaSource，MP4 用渐进式；请求头/UA 按片源要求带；
     *  外层包 CacheDataSource——已下载内容离线可播、观看时同步写入缓存 */
    private fun openMedia(player: ExoPlayer, url: String, item: OnlinePlayback, resumeAt: Double) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(item.userAgent ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .setDefaultRequestProperties(item.httpHeaders)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory = com.sparkhoward.nagomiani.core.download.DownloadUtil
            .cacheDataSourceFactory(httpFactory)

        val isHLS = url.substringBefore('?').contains(".m3u8")
        val mediaItem = MediaItem.Builder().setUri(url).build()
        val mediaSource: MediaSource = if (isHLS) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
        player.setMediaSource(mediaSource, (resumeAt * 1000).toLong())
        player.prepare()
        player.playWhenReady = true
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackBus.isPlaying.value = isPlaying
            pushPlayerState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                ui.value = ui.value.copy(isLoading = false)
            }
            if (playbackState == Player.STATE_ENDED) {
                onEnded()
            }
            pushPlayerState()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            ui.value = ui.value.copy(isLoading = false, error = "播放出错：${error.message ?: "未知错误"}")
        }
    }

    private fun pushPlayerState() {
        val player = exoPlayer ?: return
        playerState.value = PlayerState(
            isPlaying = player.isPlaying,
            positionSeconds = player.currentPosition / 1000.0,
            durationSeconds = if (player.duration == C.TIME_UNSET) 0.0 else player.duration / 1000.0,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            ended = player.playbackState == Player.STATE_ENDED,
        )
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) {
                // 播完重播：复位看完/连播判定，重看可再次触发同步
                player.seekTo(0)
                nextOfferTriggered = false
                playback?.let { markedThisSession.remove("${it.seriesKey}:${it.episodeNumber}") }
            }
            player.play()
        }
    }

    /** 顶栏提示条超时后由 PlayerScreen 调用 */
    fun clearStatusMessage() {
        if (ui.value.statusMessage != null) ui.value = ui.value.copy(statusMessage = null)
    }

    fun seekTo(seconds: Double) {
        lastSeekAt = System.currentTimeMillis()
        // 主动拖动重新武装看完判定——否则断点续播落在 90% 后的隐形判定会消费掉
        // 一次性标记，用户后续「拉到最后+看10秒」全部被静默跳过
        nextOfferTriggered = false
        playback?.let { markedThisSession.remove("${it.seriesKey}:${it.episodeNumber}") }
        exoPlayer?.seekTo((seconds * 1000).toLong())
    }

    /** 线路切换：同集换 URL，保留当前进度（mac 版 switchRoute 同语义） */
    fun switchRoute(routeIndex: Int) {
        val item = playback ?: return
        val player = exoPlayer ?: return
        val routes = item.routes
        if (!routes.indices.contains(routeIndex) || routeIndex == currentRouteIndex) return
        val position = player.currentPosition
        val updated = item.copy(url = routes[routeIndex])
        playback = updated
        currentRouteIndex = routeIndex
        openMedia(player, updated.url, updated, position / 1000.0)
        ui.value = ui.value.copy(statusMessage = "已切换到线路 ${routeIndex + 1}")
    }

    var currentRouteIndex: Int = 0
        private set

    // MARK: - 周期任务：位置落盘（5s 节流）+ 看完判定 + 连播提示

    private fun startPeriodicTasks() {
        periodicJob?.cancel()
        periodicJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val player = exoPlayer ?: continue
                val item = playback ?: continue
                val position = player.currentPosition / 1000.0
                val duration = if (player.duration == C.TIME_UNSET) 0.0 else player.duration / 1000.0
                playerState.value = playerState.value.copy(positionSeconds = position, durationSeconds = duration)

                // 落盘闸门：续播点未到达前不覆盖旧记录
                val target = resumeTarget
                if (target != null) {
                    if (position >= target - 1) resumeTarget = null else continue
                }
                if (position > 0 && System.currentTimeMillis() - lastSaveAt >= 5_000) {
                    lastSaveAt = System.currentTimeMillis()
                    store.updateResume(item.resumeKey, position, duration)
                }

                // 看完同步（90%，≥300s，seek 后 10s 不判定）+ 连播提示（同阈值）
                if (duration >= 300 && position / duration >= 0.90) {
                    val seekFresh = System.currentTimeMillis() - lastSeekAt < 10_000
                    if (!seekFresh) {
                        if (!nextOfferTriggered) {
                            nextOfferTriggered = true
                            markWatched(item)
                            offerNextEpisode(item)
                        }
                    }
                }
            }
        }
    }

    private fun onEnded() {
        val item = playback ?: return
        viewModelScope.launch {
            // 播完清记录（下次从头）
            store.removeResume(item.resumeKey)
            // EOF 是确定的「看完」：无条件同步（幂等）+ 无条件提供下一集，
            // 不受 90% 一次性判定影响——顺着正常看完永远会同步
            markWatched(item, force = true)
            offerNextEpisode(item)
        }
    }

    /** 看完同步：有绑定→PATCH watched；失败→离线队列（下次进播放器页时冲刷）。
     *  [force] = EOF 等确定性看完时刻，绕过会话内去重无条件重试；
     *  成功才记入已同步集合，失败移除——90%/EOF/重看随时可重试。
     *  失败提示 = 失败步骤 + bgm 返回的可读原因，便于在真机上定位 */
    private fun markWatched(item: OnlinePlayback, force: Boolean = false) {
        val key = "${item.seriesKey}:${item.episodeNumber}"
        if (!force && !markedThisSession.add(key)) return
        viewModelScope.launch {
            val subjectID = item.boundSubjectID ?: store.bindingSubjectID(item.seriesKey)
            if (subjectID == null) return@launch
            val api = BangumiApi { auth.refreshIfNeeded().accessToken }
            var step = "获取分集列表"
            try {
                val ep = api.allEpisodes(subjectID)
                    .firstOrNull { kotlin.math.round(it.sort).toInt() == item.episodeNumber }
                if (ep == null) {
                    ui.value = ui.value.copy(statusMessage = "在 Bangumi 上未找到第 ${item.episodeNumber} 集，跳过同步")
                    return@launch
                }
                step = "确认收藏"
                ensureCollected(api, subjectID)
                step = "标记看过"
                try {
                    api.markEpisodes(subjectID, listOf(ep.id))
                } catch (e: Exception) {
                    // 400 常见于「条目未收藏」：补「在看」收藏后重试一次
                    val recovered = if (e is BangumiError.Http && e.code == 400) runCatching {
                        api.updateCollection(subjectID, CollectionModifyPayload(type = CollectionType.DOING.raw))
                        api.markEpisodes(subjectID, listOf(ep.id))
                    }.isSuccess else false
                    if (!recovered) throw e
                }
                markedThisSession.add(key)
                ui.value = ui.value.copy(statusMessage = "已同步：第 ${item.episodeNumber} 集标记为看过 ✓")
            } catch (e: Exception) {
                markedThisSession.remove(key) // 失败：EOF / 重看时仍可重试
                store.enqueuePendingMark(subjectID, item.episodeNumber)
                android.util.Log.w("NagomiSync", "markWatched subject=$subjectID ep=${item.episodeNumber} step=$step", e)
                ui.value = ui.value.copy(
                    statusMessage = "同步失败[$step]：${BangumiError.describe(e)}（已记入离线队列）",
                )
            }
        }
    }

    /** 确保条目已收藏（未收藏条目无法标记单集看过）：缺收藏时补「在看」，已有收藏状态不覆盖 */
    private suspend fun ensureCollected(api: BangumiApi, subjectID: Int) {
        if (subjectID in confirmedCollected) return
        if (api.myCollectionOf(subjectID) != null) {
            confirmedCollected.add(subjectID)
        } else {
            api.updateCollection(subjectID, CollectionModifyPayload(type = CollectionType.DOING.raw))
            confirmedCollected.add(subjectID)
        }
    }

    /** 连播征询：解析下一集（同番 number+1），弹提示条，用户点"看下一集"才切；连播开关关闭时不提示 */
    private fun offerNextEpisode(item: OnlinePlayback) {
        viewModelScope.launch {
            if (!autoplayNext.value) return@launch
            val eps = runCatching { episodesFor(item) }.getOrDefault(emptyList())
            val next = eps.firstOrNull { it.number == item.episodeNumber + 1 } ?: return@launch
            val nextPlayback = onlineRepo.preparePlayback(showOf(item), next, item.showTitle) ?: return@launch
            ui.value = ui.value.copy(nextOffer = UIState.NextOffer(label = "接下来：${next.displayTitle ?: "第 ${next.number} 集"}"))
            pendingNext = nextPlayback
        }
    }

    private var pendingNext: OnlinePlayback? = null

    fun acceptNextEpisode() {
        val next = pendingNext ?: return
        pendingNext = null
        ui.value = ui.value.copy(nextOffer = null)
        stopPlayback()
        start(next)
    }

    fun dismissNextEpisode() {
        pendingNext = null
        ui.value = ui.value.copy(nextOffer = null)
    }

    // MARK: - 弹幕

    private suspend fun prepareDanmaku(item: OnlinePlayback) {
        val controller = danmaku.value ?: return
        val title = item.showTitle ?: return
        controller.prepare(item.resumeKey, showTitle = title, episodeNumber = item.episodeNumber)
    }

    // MARK: - 离线补同步

    private suspend fun flushPendingMarksIfNeeded() {
        val pending = store.pendingMarks.first()
        if (pending.isEmpty()) return
        if (!auth.isLoggedIn()) return
        try {
            val api = BangumiApi { auth.refreshIfNeeded().accessToken }
            val remaining = ArrayList<AppStore.PendingMark>()
            var firstError: String? = null
            for (mark in pending) {
                var step = "确认收藏"
                try {
                    ensureCollected(api, mark.subjectID)
                    step = "获取分集列表"
                    val ep = api.allEpisodes(mark.subjectID)
                        .firstOrNull { kotlin.math.round(it.sort).toInt() == mark.episodeNumber }
                    step = "标记看过"
                    if (ep != null) api.markEpisodes(mark.subjectID, listOf(ep.id))
                } catch (e: Exception) {
                    remaining += mark // 仍失败：留在队列
                    if (firstError == null) {
                        firstError = "[第${mark.episodeNumber}集·$step] ${BangumiError.describe(e)}"
                        android.util.Log.w(
                            "NagomiSync",
                            "flush mark subject=${mark.subjectID} ep=${mark.episodeNumber} step=$step", e,
                        )
                    }
                }
                delay(300) // 尊重限频
            }
            store.replacePendingMarks(remaining)
            when {
                remaining.isEmpty() ->
                    ui.value = ui.value.copy(statusMessage = "已补同步 ${pending.size} 条离线观看记录 ✓")
                remaining.size < pending.size ->
                    ui.value = ui.value.copy(statusMessage = "已补同步 ${pending.size - remaining.size} 条，${remaining.size} 条待重试")
                else ->
                    ui.value = ui.value.copy(statusMessage = "补同步失败：${firstError ?: "未知错误"}（保留待重试）")
            }
        } catch (_: Exception) {
        }
    }

    // MARK: - 生命周期

    fun releaseIfIdle() {
        // 离开播放器页：暂停但不销毁（返回续看），由 onCleared 终极回收
        exoPlayer?.pause()
    }

    fun stopPlayback() {
        exoPlayer?.stop()
    }

    override fun onCleared() {
        periodicJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        PlaybackBus.isPlaying.value = false
        super.onCleared()
    }

    companion object {
        fun formatTime(seconds: Double): String {
            if (!seconds.isFinite() || seconds < 0) return "0:00"
            val total = seconds.toLong()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}

/** 内置弹幕凭据（assets/danmaku-credentials.bin，XOR 0x5A，与 mac 版同构） */
object DanmakuCredentialsHolder {
    fun load(context: android.content.Context): Pair<String, String>? = runCatching {
        val raw = context.assets.open("danmaku-credentials.bin").use { it.readBytes() }
            .map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        val text = String(raw, Charsets.UTF_8)
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.size >= 2) lines[0].trim() to lines[1].trim() else null
    }.getOrNull()
}
