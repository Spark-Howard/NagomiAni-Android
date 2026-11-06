package com.sparkhoward.nagomiani.player

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
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
import com.sparkhoward.nagomiani.core.dandanplay.DanmakuCredentialsHolder
import com.sparkhoward.nagomiani.core.dandanplay.DandanplayApi
import com.sparkhoward.nagomiani.core.maccms.OnlineRepo
import com.sparkhoward.nagomiani.core.maccms.SourceHealth
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.DanmakuComment
import com.sparkhoward.nagomiani.core.model.DanmakuMode
import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlinePlayback
import com.sparkhoward.nagomiani.core.model.OnlineShow
import com.sparkhoward.nagomiani.core.store.AppStore
import com.sparkhoward.nagomiani.core.watchparty.DiscoveredRoom
import com.sparkhoward.nagomiani.core.watchparty.LocalRoomServer
import com.sparkhoward.nagomiani.core.watchparty.PartyDiscovery
import com.sparkhoward.nagomiani.core.watchparty.PartyWire
import com.sparkhoward.nagomiani.core.watchparty.RoomClient
import com.sparkhoward.nagomiani.core.watchparty.WatchPartySync
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    // MARK: - 可观察状态（必须全部声明在 init 之前：viewModelScope 是 Main.immediate，
    // init 里 launch 的收集器会在构造期经 unconfined 事件循环同步收到 DataStore 的缓存值
    // 并写这些属性——声明在后就是 NPE 闪退，真机复现过「点在线观看进播放器即崩」）

    /** 跨集/重启记忆的倍速（init 收集器写入 + setSpeed 写入） */
    val speed = MutableStateFlow(1.0f)

    /** 连播模式：ask 征询条（默认）/ auto 播完自动切 / off 关闭 */
    val autoplayMode = MutableStateFlow("ask")

    /** 跳过片头：seriesKey → 已打点的 OP 结束秒；autoSkipOp=起点落在片头区间时自动 seek（默认关） */
    private val opMarks = MutableStateFlow<Map<String, Double>>(emptyMap())
    val autoSkipOp = MutableStateFlow(false)

    /** 画面比例：fit 适应 / zoom 裁剪 / fill 拉伸（PlayerView resizeMode） */
    val resizeMode = MutableStateFlow("fit")

    /** 顶栏信息 + 状态提示 */
    val ui = MutableStateFlow(UIState())

    // Compose 可观察：PlayerScreen 的 update 依赖它重组，否则播放器创建后视图拿不到 Surface（黑屏有声）
    var exoPlayer: ExoPlayer? by androidx.compose.runtime.mutableStateOf<ExoPlayer?>(null)
        private set

    // MARK: - 一起看连接（声明必须先于 init：init 的 collector 首句就访问 partyClient）

    data class PartyChatEntry(val nick: String, val text: String, val atPositionSeconds: Double, val isMine: Boolean)

    private val partyClient = RoomClient(viewModelScope)
    val partyState: StateFlow<RoomClient.ConnectionState> = partyClient.state

    /** 聊天记录（面板列表 + 弹幕飘字共用；只存内存，房间即会话） */
    private val _partyChat = MutableStateFlow<List<PartyChatEntry>>(emptyList())
    val partyChat: StateFlow<List<PartyChatEntry>> = _partyChat.asStateFlow()

    /** 房主正在看的集号（guest 的全局入口卡提示「去播第 N 集」用；null=未知） */
    val partyHostEpisode = MutableStateFlow<Int?>(null)

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
            // 倍速记忆：跨集/重启恢复（本机 setSpeed 写回后回流到此，幂等）
            store.playerSpeed.collect { value ->
                speed.value = value
                exoPlayer?.setPlaybackSpeed(value)
            }
        }
        viewModelScope.launch {
            store.autoplayMode.collect { autoplayMode.value = it }
        }
        viewModelScope.launch {
            store.playerResize.collect { resizeMode.value = it }
        }
        viewModelScope.launch {
            store.danmakuShowScroll.collect { s.value = s.value.copy(showScroll = it) }
        }
        viewModelScope.launch {
            store.danmakuShowTop.collect { s.value = s.value.copy(showTop = it) }
        }
        viewModelScope.launch {
            store.danmakuShowBottom.collect { s.value = s.value.copy(showBottom = it) }
        }
        viewModelScope.launch {
            store.danmakuDensity.collect { s.value = s.value.copy(density = it.toFloat()) }
        }
        viewModelScope.launch {
            store.danmakuBlockKeywords.collect { s.value = s.value.copy(blockKeywords = it) }
        }
        viewModelScope.launch {
            store.opMarks.collect {
                opMarks.value = it
                // 打点变化（打点/清除）即时反映到当前会话的跳过胶囊
                playback?.let { item -> ui.value = ui.value.copy(opEndSeconds = it[item.seriesKey]) }
            }
        }
        viewModelScope.launch {
            store.autoSkipOp.collect { autoSkipOp.value = it }
        }
        viewModelScope.launch {
            // 一起看昵称默认取 Bangumi 昵称：只在没自定义过（键为空）且已登录时一次性写入，
            // 之后用户在弹层里改过的昵称优先，不再覆盖
            if (store.watchPartyNick.first().isBlank() && auth.isLoggedIn()) {
                runCatching { BangumiApi { auth.refreshIfNeeded().accessToken }.me().nickname }
                    .onSuccess { nick -> if (nick.isNotBlank()) store.setWatchPartyNick(nick) }
            }
        }
        viewModelScope.launch {
            partyClient.events.collect { ev ->
                when (ev) {
                    is RoomClient.Event.Welcome -> onPartyWelcome(ev)
                    is RoomClient.Event.Members -> {
                        // 房主离开后顺位交接到我：接棒广播（此后 listener/心跳接管）
                        if (ev.hostId == partyState.value.selfId && !partyWasHost) {
                            partyWasHost = true
                            partyEpoch++
                            sendPartyState()
                            showStatus("房主已离开，由你接管播放")
                        }
                        partyWasHost = ev.hostId == partyState.value.selfId
                    }
                    is RoomClient.Event.HostState -> {
                        hostState = ev.state
                        hostStateAt = System.currentTimeMillis()
                        partyHostEpisode.value = ev.state.episodeNumber.takeIf { it > 0 }
                        // 房主换了集：guest 各自手动跟上（MVP 不同步换集动作，只提示）
                        val mine = playback?.episodeNumber ?: -1
                        if (mine > 0 && ev.state.episodeNumber > 0 && ev.state.episodeNumber != mine) {
                            showStatus("房主在看第 ${ev.state.episodeNumber} 集")
                        }
                    }
                    RoomClient.Event.SyncRequested -> if (isPartyHost()) sendPartyState()
                    is RoomClient.Event.Chat -> addPartyChat(ev.nick, ev.text, isMine = false)
                    is RoomClient.Event.Disconnected -> showStatus("一起看连接中断，自动重连中…")
                }
            }
        }
    }

    // MARK: - 竖屏功能面板（选集 / 倍速 / 线路 / 连播 / 缓存本集）

    /** 竖屏面板数据：本番选集列表 + 当前集号（换集时刷新） */
    data class PanelState(
        val episodes: List<OnlineEpisode> = emptyList(),
        val currentNumber: Int = 0,
    )

    val panel = MutableStateFlow(PanelState())

    /** 睡眠定时选项（UI 选中态）与定时状态（null=未启用；剩余毫秒每秒刷新供倒计时显示） */
    enum class SleepChoice(val label: String) { OFF("关"), M15("15分"), M30("30分"), M60("60分"), EPISODE_END("播完本集") }
    data class SleepTimer(val endAt: Long, val atEpisodeEnd: Boolean) {
        fun remainingMs(now: Long = System.currentTimeMillis()): Long = (endAt - now).coerceAtLeast(0)
    }

    val sleepChoice = MutableStateFlow(SleepChoice.OFF)
    val sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepRemainingMs = MutableStateFlow<Long?>(null)

    fun setSleepTimer(choice: SleepChoice) {
        sleepChoice.value = choice
        when (choice) {
            SleepChoice.OFF -> {
                sleepTimer.value = null
                sleepRemainingMs.value = null
                showStatus("已取消睡眠定时")
            }
            SleepChoice.EPISODE_END -> {
                sleepTimer.value = SleepTimer(0, atEpisodeEnd = true)
                sleepRemainingMs.value = null
                showStatus("睡眠定时：本集播完后停止")
            }
            else -> {
                val minutes = when (choice) { SleepChoice.M15 -> 15; SleepChoice.M30 -> 30; else -> 60 }
                sleepTimer.value = SleepTimer(System.currentTimeMillis() + minutes * 60_000, atEpisodeEnd = false)
                sleepRemainingMs.value = sleepTimer.value?.remainingMs()
                showStatus("睡眠定时：$minutes 分钟后暂停")
            }
        }
    }

    /** 画面比例：分段选择器直接选 / 全屏图标循环切换（fit→zoom→fill） */
    fun setResizeMode(value: String) {
        resizeMode.value = value
        viewModelScope.launch { store.setPlayerResize(value) }
    }

    fun cycleResizeMode() {
        val next = when (resizeMode.value) { "zoom" -> "fill"; "fill" -> "fit"; else -> "zoom" }
        setResizeMode(next)
        showStatus("画面比例：" + when (next) { "zoom" -> "裁剪"; "fill" -> "拉伸"; else -> "适应" })
    }

    /** 本番选集缓存（跨集复用，连播提示同源）。LRU 封顶 4 部——长番一部上千集，
     *  一晚连刷多部番时避免全量分集表无界堆积 */
    private val episodeCache = object : LinkedHashMap<String, List<OnlineEpisode>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OnlineEpisode>>): Boolean =
            size > EPISODE_CACHE_MAX_SERIES
    }

    private fun showOf(item: OnlinePlayback) = OnlineShow(
        providerID = item.seriesKey.removePrefix("online:").substringBefore(':'),
        showID = item.seriesKey.removePrefix("online:").substringAfter(':'),
        title = item.showTitle ?: "",
    )

    private suspend fun episodesFor(item: OnlinePlayback): List<OnlineEpisode> {
        // 本地视频没有片源分集表：选集/连播走 LocalMediaLibrary（见 playEpisodeNumber/offerNextEpisode）
        if (item.seriesKey.startsWith("local:")) return emptyList()
        return episodeCache.getOrPut(item.seriesKey) { onlineRepo.episodes(showOf(item)) }
    }

    /** 面板数据懒加载：进入竖屏面板或换集时刷新 */
    fun loadPanelIfNeeded() {
        val item = playback ?: return
        if (panel.value.episodes.isNotEmpty() && panel.value.currentNumber == item.episodeNumber) return
        if (item.seriesKey.startsWith("local:")) {
            // 本地：选集列表来自已解析的 meta（上一集/下一集按钮态同源）
            viewModelScope.launch {
                val eps = runCatching {
                    com.sparkhoward.nagomiani.core.local.LocalMediaLibrary.episodesFor(item.seriesKey)
                }.getOrDefault(emptyList())
                panel.value = PanelState(episodes = eps, currentNumber = item.episodeNumber)
            }
            return
        }
        viewModelScope.launch {
            val eps = runCatching { episodesFor(item) }.getOrDefault(emptyList())
            panel.value = PanelState(episodes = eps, currentNumber = item.episodeNumber)
        }
    }

    /** 点选集切换（含上一集/下一集）：preparePlayback 取流后换会话 */
    fun playEpisodeNumber(number: Int) {
        val item = playback ?: return
        if (item.seriesKey.startsWith("local:")) {
            viewModelScope.launch {
                // 本地：该集须已匹配过并持久化（playbackFor 同步查表），没播过集的先去缓存页点一次
                val next = com.sparkhoward.nagomiani.core.local.LocalMediaLibrary
                    .playbackForSeriesEpisode(item.seriesKey, number)
                if (next == null) {
                    ui.value = ui.value.copy(statusMessage = "本地媒体里没有已解析的第 $number 集")
                    return@launch
                }
                stopPlayback()
                start(next, forceRestart = next.resumeKey == item.resumeKey)
            }
            return
        }
        viewModelScope.launch {
            val eps = runCatching { episodesFor(item) }.getOrDefault(emptyList())
            val ep = eps.firstOrNull { it.number == number } ?: return@launch
            val nextPlayback = onlineRepo.preparePlayback(showOf(item), ep, item.showTitle) ?: return@launch
            stopPlayback()
            start(nextPlayback, forceRestart = nextPlayback.resumeKey == item.resumeKey)
        }
    }

    /** 倍速：persist=false 供长按 3× 快进的临时变速（不落盘，抬手还原基速——中途杀进程也不会记住 3×） */
    fun setSpeed(value: Float, persist: Boolean = true) {
        speed.value = value
        exoPlayer?.setPlaybackSpeed(value)
        if (persist) viewModelScope.launch { store.setPlayerSpeed(value) }
    }

    /** 连播模式切换（竖屏面板分段选择器） */
    fun setAutoplayMode(value: String) {
        viewModelScope.launch { store.setAutoplayMode(value) }
    }

    /** 自动跳过片头开关（竖屏面板；开=起点落在片头区间时自动 seek） */
    fun setAutoSkipOp(value: Boolean) {
        autoSkipOp.value = value
        viewModelScope.launch { store.setAutoSkipOp(value) }
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
            store.setDanmakuShowScroll(next.showScroll)
            store.setDanmakuShowTop(next.showTop)
            store.setDanmakuShowBottom(next.showBottom)
            store.setDanmakuDensity(next.density.toDouble())
            store.setDanmakuBlockKeywords(next.blockKeywords)
        }
    }

    /** 顶栏信息 + 状态提示 */
    data class UIState(
        val title: String = "",
        val statusMessage: String? = null,
        val nextOffer: NextOffer? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        /** 本番已打点的片头结束秒（null=未打点）；「跳过片头」胶囊与自动跳过共用 */
        val opEndSeconds: Double? = null,
    ) {
        data class NextOffer(val label: String)
    }

    // 播放会话参数（PlayerScreen 进入时通过 start() 注入）
    var playback: OnlinePlayback? = null
        private set

    // 共享 PlayerView：播放器路由与悬浮小窗共用同一实例（切换容器时重挂载而非重建，不闪断）
    private var sharedPlayerView: androidx.media3.ui.PlayerView? = null

    /** 取共享 PlayerView（必要时创建）；调用方负责先从旧父容器移除再挂载 */
    fun obtainPlayerView(context: Context): androidx.media3.ui.PlayerView =
        sharedPlayerView ?: androidx.media3.ui.PlayerView(context).apply {
            useController = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            player = exoPlayer
            sharedPlayerView = this
        }

    // 续播/落盘闸门（mac 版 pendingResumeTarget 同款：起播瞬间位置未达续播点不落盘）
    private var resumeTarget: Double? = null
    private var lastSaveAt = 0L
    private var lastSeekAt: Long = 0
    private var nextOfferTriggered = false
    // 断流自动兜底：本集已自动换线次数（上限=routes.size-1；手动切线重新武装、换集复位）
    private var autoFails = 0
    /** 连续 BUFFERING 起始时刻（0=未在缓冲）；≥10s 判定线路断流 */
    private var bufferingSinceMs = 0L
    // 观看历史：真实观看秒数累计（播放推进才计，seek 跳变>10s 不计），随 5s 节流落盘一起刷入
    private var watchAccumSeconds = 0.0
    private var lastWatchTickPosition = -1.0
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
        val player = exoPlayer
        val sameEpisode = !forceRestart && player != null && playback?.resumeKey == item.resumeKey
        if (sameEpisode && (player!!.playbackState == Player.STATE_READY || player!!.playbackState == Player.STATE_BUFFERING)) {
            // 同一集重进（返回重进 / 「回到播放中」/ 悬浮小窗展开）：直接续播。
            // 必须自动续播——暂停态下 Surface 重挂载不出帧（黑屏），停留暂停观感即"卡死"
            player!!.play()
            startPeriodicTasks()
            loadPanelIfNeeded()
            return
        }
        // 首播 / 换集 / 被 stop 过（IDLE/ENDED）的同集重进：完整装配（含续播点决策）
        playback = item
        // 一起看：房主换集属不连续点，guest 依据 epoch 前进硬对齐
        if (isPartyHost()) partyEpoch++
        nextOfferTriggered = false
        // 重进/重播同一集允许重新判定看完同步（否则首播已入列的 key 会挡住后续所有同步）
        markedThisSession.remove("${item.seriesKey}:${item.episodeNumber}")
        currentRouteIndex = 0 // 新会话从首选线路开始（上集切过线路时必须复位）
        autoFails = 0 // 换集复位断流自动兜底计数
        bufferingSinceMs = 0L
        watchAccumSeconds = 0.0 // 换集复位观看时长累计
        lastWatchTickPosition = -1.0
        probeRoutes(item)
        ui.value = UIState(title = item.displayTitle ?: "", isLoading = true, opEndSeconds = opMarks.value[item.seriesKey])

        val context = getApplication<Application>()
        val p = player ?: ExoPlayer.Builder(context).build().also {
            // 音频焦点：来电/其他应用播音频自动暂停让路（瞬时丢失结束后自动恢复），
            // 拔耳机暂停（becoming noisy）——平台原生行为，Media3 全权处理
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            it.setHandleAudioBecomingNoisy(true)
            it.setPlaybackSpeed(speed.value)
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
            var resumeAt = entry?.takeIf { it.isResumable }?.position ?: 0.0
            // 自动跳过片头：起点还落在已打点的片头区间内 → 直接从片尾开始（打点同系列各集通用）
            val opEnd = opMarks.value[item.seriesKey]
            val autoSkippedOp = autoSkipOp.value && opEnd != null && resumeAt < opEnd
            if (autoSkippedOp) resumeAt = opEnd
            resumeTarget = if (resumeAt > 0) resumeAt else null
            openMedia(p, item.url, item, resumeAt)
            when {
                autoSkippedOp -> ui.value = ui.value.copy(statusMessage = "已自动跳过片头", isLoading = false)
                resumeAt > 0 -> ui.value = ui.value.copy(
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
     *  外层包 CacheDataSource——已下载内容离线可播、观看时同步写入缓存。
     *  本地文件（content://，SAF 媒体库）走 DefaultDataSource，不套缓存 */
    private fun openMedia(player: ExoPlayer, url: String, item: OnlinePlayback, resumeAt: Double) {
        val isLocal = url.startsWith("content:")
        val dataSourceFactory = if (isLocal) {
            androidx.media3.datasource.DefaultDataSource.Factory(getApplication())
        } else {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(item.userAgent ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .setDefaultRequestProperties(item.httpHeaders)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
            com.sparkhoward.nagomiani.core.download.DownloadUtil
                .cacheDataSourceFactory(httpFactory)
        }

        val isHLS = !isLocal && url.substringBefore('?').contains(".m3u8")
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
            // 一起看：房主的播放/暂停变化即时广播（guest 侧同步操作走到这会因非房主而 no-op）
            sendPartyState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // 播放意图变化（用户暂停/恢复、音频焦点）也要广播：缓冲中 isPlaying 抖动≠意图变化，
            // 广播以 playWhenReady 为权威口径，guest 才不会在房主缓冲时被误暂停
            sendPartyState()
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
            // 断流自动兜底：还有没试过的线路就静默换线（保进度），换遍才显示错误 UI
            if (attemptAutoFailover("中断")) return
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
        // 一起看：房主 seek 属不连续点，epoch 自增让 guest 立即硬对齐（周期纠偏只管缓追）
        if (isPartyHost()) {
            partyEpoch++
            sendPartyState(posOverride = seconds)
        }
    }

    // MARK: - 跳过片头

    /** 打点：把当前位置记为本番片头结束秒（seriesKey 级记忆，同系列各集通用） */
    fun markOpIntro() {
        val item = playback ?: return
        val position = playerState.value.positionSeconds
        if (position < 1.0) {
            ui.value = ui.value.copy(statusMessage = "请先播放到片头结束处再打点")
            return
        }
        viewModelScope.launch {
            store.setOpMark(item.seriesKey, position)
            opMarks.value = opMarks.value + (item.seriesKey to position)
            ui.value = ui.value.copy(
                opEndSeconds = position,
                statusMessage = "已记住片头结束于 ${formatTime(position)}，同系列各集生效",
            )
        }
    }

    fun clearOpIntro() {
        val item = playback ?: return
        viewModelScope.launch {
            store.removeOpMark(item.seriesKey)
            opMarks.value = opMarks.value - item.seriesKey
            ui.value = ui.value.copy(opEndSeconds = null, statusMessage = "已清除片头打点")
        }
    }

    /** 「跳过片头」胶囊：seek 到打点位置 */
    fun skipOpIntro() {
        val end = ui.value.opEndSeconds ?: return
        seekTo(end)
    }

    /** 线路切换：同集换 URL，保留当前进度（mac 版 switchRoute 同语义）。手动切换会重新武装断流自动兜底 */
    fun switchRoute(routeIndex: Int) {
        val item = playback ?: return
        val routes = item.routes
        if (!routes.indices.contains(routeIndex) || routeIndex == currentRouteIndex) return
        autoFails = 0
        applyRoute(routeIndex)
        ui.value = ui.value.copy(statusMessage = "已切换到线路 ${routeIndex + 1}")
    }

    /** 无提示换线（手动/自动共用）：换 URL 保进度；保留暂停态（openMedia 会置 playWhenReady，
     *  不还原的话暂停中出错自动换线会让视频意外起播）；同时复位卡顿看门狗——手动换线后
     *  新线路的缓冲不继承上一条线路的停滞计时 */
    private fun applyRoute(routeIndex: Int) {
        val item = playback ?: return
        val player = exoPlayer ?: return
        val position = player.currentPosition
        val resumeOnReady = player.playWhenReady
        val updated = item.copy(url = item.routes[routeIndex])
        playback = updated
        currentRouteIndex = routeIndex
        openMedia(player, updated.url, updated, position / 1000.0)
        player.playWhenReady = resumeOnReady
        bufferingSinceMs = 0
    }

    /** 断流自动兜底：换下一条线路（循环），换遍其余线路仍失败则放弃返回 false（上限由 nextRouteOnFailure 把守） */
    private fun attemptAutoFailover(reason: String): Boolean {
        val routes = playback?.routes ?: return false
        val next = SourceHealth.nextRouteOnFailure(currentRouteIndex, routes.size, autoFails) ?: return false
        autoFails++
        val from = currentRouteIndex + 1
        applyRoute(next)
        ui.value = ui.value.copy(error = null, statusMessage = "线路$from $reason，已自动切换到线路${next + 1}")
        return true
    }

    var currentRouteIndex: Int = 0
        private set

    // MARK: - 线路测速（多线路会话进入时后台探测 TTFB，面板标注延迟与最快线路）

    /** 每条线路的探测延迟（与 routes 同序；null=探测中或超时） */
    val routeHealth = MutableStateFlow<List<Long?>>(emptyList())

    private var routeProbeJob: Job? = null

    private fun probeRoutes(item: OnlinePlayback) {
        routeProbeJob?.cancel()
        if (item.routes.size < 2) {
            routeHealth.value = emptyList()
            return
        }
        routeHealth.value = List(item.routes.size) { null }
        routeProbeJob = viewModelScope.launch {
            val results = coroutineScope {
                item.routes.map { url ->
                    async { SourceHealth.probeRoute(url, item.httpHeaders, item.userAgent) }
                }.awaitAll()
            }
            // 会话可能已换集：routes 未变才落地，避免旧集的延迟标到新集上
            if (playback?.routes == item.routes) routeHealth.value = results
        }
    }

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

                // 一起看：房主 5s 心跳广播 / guest 漂移纠偏（内部自判角色）
                tickWatchParty()

                // 睡眠定时（倒计时档）：到点暂停并清除；剩余毫秒每秒刷新供 UI 倒计时
                sleepTimer.value?.let { timer ->
                    if (!timer.atEpisodeEnd) {
                        val remaining = timer.remainingMs()
                        if (remaining <= 0) {
                            sleepTimer.value = null
                            sleepChoice.value = SleepChoice.OFF
                            sleepRemainingMs.value = null
                            player.pause()
                            ui.value = ui.value.copy(statusMessage = "睡眠时间到，已暂停播放")
                        } else {
                            sleepRemainingMs.value = remaining
                        }
                    }
                }

                // 观看历史时长累计：播放推进才计；seek 跳变（>10s）与回跳不计
                if (player.isPlaying) {
                    val delta = position - lastWatchTickPosition
                    if (lastWatchTickPosition >= 0 && delta > 0 && delta <= 10) watchAccumSeconds += delta
                }
                lastWatchTickPosition = position

                // 断流看门狗：连续 BUFFERING ≥10s 视为线路断流，走与硬错误相同的自动换线通道。
                // 只在用户意图播放（playWhenReady）时计时——暂停中卡缓冲不换线（换了也会顶掉暂停态）；
                // 手动 seek 引起的短暂缓冲通常 <10s，不会误触发
                if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) {
                        bufferingSinceMs = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - bufferingSinceMs >= 10_000) {
                        bufferingSinceMs = 0L
                        if (!attemptAutoFailover("卡顿超时")) {
                            ui.value = ui.value.copy(isLoading = false, error = "播放卡顿：各线路均超时，请检查网络或手动切换线路")
                        }
                    }
                } else {
                    bufferingSinceMs = 0L
                }

                // 落盘闸门：续播点未到达前不覆盖旧记录
                val target = resumeTarget
                if (target != null) {
                    if (position >= target - 1) resumeTarget = null else continue
                }
                if (position > 0 && System.currentTimeMillis() - lastSaveAt >= 5_000) {
                    lastSaveAt = System.currentTimeMillis()
                    store.updateResume(item.resumeKey, position, duration)
                    // 观看历史随同一节流时机落盘（不增加写盘频率）
                    flushHistory(item, position, duration)
                }

                // 看完同步（90%，≥300s，seek 后 10s 不判定）+ 连播提示（同阈值）
                if (duration >= 300 && position / duration >= 0.90) {
                    val seekFresh = System.currentTimeMillis() - lastSeekAt < 10_000
                    if (!seekFresh) {
                        if (!nextOfferTriggered) {
                            nextOfferTriggered = true
                            markWatched(item)
                            offerNextEpisode(item)
                            // 本地口径看完（不依赖登录）：历史记录置 finished
                            flushHistory(item, position, duration, keepAccum = true, finished = true)
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
            // 睡眠定时「播完本集」：到点即停（清定时 + 提示），不再连播
            sleepTimer.value?.takeIf { it.atEpisodeEnd }?.let {
                sleepTimer.value = null
                sleepChoice.value = SleepChoice.OFF
                ui.value = ui.value.copy(statusMessage = "已按睡眠定时停在片尾")
            }
            // 观看历史：EOF 是会话终点，把残余观看增量一并刷入并置 finished
            // （与 resume 的"播完即删"互补，历史不删）
            flushHistory(item, playerState.value.durationSeconds, playerState.value.durationSeconds, finished = true)
            // EOF 是确定的「看完」：无条件同步（幂等）+ 连播推进（auto 模式此时直接续播）
            markWatched(item, force = true)
            offerNextEpisode(item, atEnd = true)
        }
    }

    /** 观看历史落盘（合并语义在 AppStore）。[keepAccum]=true 用于 90% 看完标记（还在播，
     *  累计保留给后续节流）；EOF 用默认 false 把残余增量刷入后清零 */
    private suspend fun flushHistory(
        item: OnlinePlayback,
        position: Double,
        duration: Double,
        keepAccum: Boolean = false,
        finished: Boolean = false,
    ) {
        store.recordWatchProgress(
            resumeKey = item.resumeKey,
            seriesKey = item.seriesKey,
            // 本地未匹配文件的 showTitle 为 null：历史行显示文件名而不是空串
            showTitle = item.showTitle ?: item.displayTitle ?: "",
            episodeNumber = item.episodeNumber,
            episodeTitle = null,
            deltaSeconds = if (keepAccum) 0.0 else watchAccumSeconds,
            position = position,
            duration = duration,
        )
        if (!keepAccum) watchAccumSeconds = 0.0
        if (finished) store.markHistoryFinished(item.resumeKey)
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

    /** 确保条目已收藏（未收藏条目无法标记单集看过）：缺收藏时补「在看」，已有收藏状态不覆盖。
     *  查询走文档端点 /v0/users/{username}/collections/{id}（按名寻址，规格明示带 token
     *  可见私密收藏）；me() 失败（token 过期/网络错）直接跳过——不误判成未收藏去覆盖 */
    private suspend fun ensureCollected(api: BangumiApi, subjectID: Int) {
        if (subjectID in confirmedCollected) return
        val me = runCatching { api.me() }.getOrNull() ?: return
        val username = me.username.ifBlank { me.id.toString() }
        if (api.myCollectionOf(username, subjectID) != null) {
            confirmedCollected.add(subjectID)
        } else {
            api.updateCollection(subjectID, CollectionModifyPayload(type = CollectionType.DOING.raw))
            confirmedCollected.add(subjectID)
        }
    }

    /** 连播推进：off 不动作；ask 弹征询条（点「看下一集」才切）；
     *  auto 仅在真正播完（atEnd）时无征询直接续播——90% 时不切，保证看完结尾。
     *  睡眠定时启用期间一律不打扰（避免自动续播顶掉睡眠意图） */
    private fun offerNextEpisode(item: OnlinePlayback, atEnd: Boolean = false) {
        viewModelScope.launch {
            val mode = autoplayMode.value
            if (mode == "off") return@launch
            if (sleepTimer.value != null) return@launch
            if (mode == "auto" && !atEnd) return@launch
            val nextPlayback = if (item.seriesKey.startsWith("local:")) {
                // 本地：连播下一集 = 已解析过的下一集 meta（未解析过的集提示缺失）
                com.sparkhoward.nagomiani.core.local.LocalMediaLibrary
                    .playbackForSeriesEpisode(item.seriesKey, item.episodeNumber + 1)
            } else {
                val eps = runCatching { episodesFor(item) }.getOrDefault(emptyList())
                val next = eps.firstOrNull { it.number == item.episodeNumber + 1 } ?: return@launch
                onlineRepo.preparePlayback(showOf(item), next, item.showTitle)
            } ?: return@launch
            if (mode == "auto") {
                pendingNext = nextPlayback
                acceptNextEpisode()
            } else {
                ui.value = ui.value.copy(
                    nextOffer = UIState.NextOffer(label = "接下来：${nextPlayback.displayTitle ?: "第 ${nextPlayback.episodeNumber} 集"}"),
                )
                pendingNext = nextPlayback
            }
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
        // 本地文件：MD5 指纹匹配到的 episodeId 直取弹幕（null 时 controller 内部走标题匹配）
        controller.prepare(item.resumeKey, showTitle = title, episodeNumber = item.episodeNumber, episodeIdHint = item.danmakuEpisodeId)
    }

    /** 弹幕设置弹层「重新获取」：忽略内存缓存重拉本集弹幕（DanmakuController.refetch） */
    fun refetchDanmaku() {
        val item = playback ?: return
        val controller = danmaku.value ?: return
        val title = item.showTitle ?: return
        controller.refetch(item.resumeKey, title, item.episodeNumber, item.danmakuEpisodeId)
    }

    // MARK: - 一起看（WatchParty：房主权威广播 + guest 漂移纠偏 + 弹幕聊天；连接声明在类头部 init 之前）

    /** 房主最新权威状态（guest 消费）与本机接收时刻（落位推算基准，规避双端墙钟偏差） */
    private var hostState: PartyWire.State? = null
    private var hostStateAt = 0L
    private var appliedHostEpoch = -1L
    private var partyEpoch = 0L
    private var partyLastBeatAt = 0L
    /** 当前是否房主身份（Welcome/Members 维护，交接/重连判定用） */
    private var partyWasHost = false
    /** 入房时的期望角色：重连后若发现房主位落回自己（原房间已散）则提示退出重进 */
    private var partyExpectedRole: RoomClient.Role? = null
    /** guest 纠偏中的临时速度标志（退出纠偏档后还原基速；不写倍速记忆） */
    private var partySpeedActive = false

    private fun isPartyHost(): Boolean =
        partyState.value.phase == RoomClient.Phase.JOINED && partyState.value.role == RoomClient.Role.HOST

    private fun isPartyJoined(): Boolean = partyState.value.phase == RoomClient.Phase.JOINED

    /** 创建房间：填了服务器地址→连自建中继；留空→房主本机开局（内嵌 WS 中继 + NSD 广播，
     *  同一 WiFi 的朋友自动看到房间）。昵称记忆到 AppStore（默认已从 Bangumi 拉取） */
    fun createWatchParty(serverUrl: String, nick: String) {
        partyExpectedRole = RoomClient.Role.HOST
        viewModelScope.launch {
            store.setWatchPartyNick(nick)
            if (serverUrl.isNotBlank()) store.setWatchPartyServer(serverUrl)
        }
        if (serverUrl.isBlank()) {
            val code = WatchPartySync.generateRoomCode()
            val server = LocalRoomServer(code, LocalRoomServer.pickFreePort())
            runCatching {
                server.start()
                obtainDiscovery().register(code, server.address.port, nick)
            }.onFailure {
                runCatching { server.stop(0) }
                showStatus("本机开局失败：${it.message ?: "未知错误"}，可填服务器地址重试")
                partyExpectedRole = null
                return
            }
            localServer = server
            android.util.Log.i("WatchParty", "local room start code=$code port=${server.address.port}")
            partyClient.connect("ws://127.0.0.1:${server.address.port}", code, nick)
            return
        }
        partyClient.connect(serverUrl, WatchPartySync.generateRoomCode(), nick)
    }

    /** 加入房间：填了服务器地址→连自建中继；留空→先在附近发现列表里按码找房主（局域网免填），
     *  找不到再提示。房间码 6 位自动大写规整 */
    fun joinWatchParty(serverUrl: String, code: String, nick: String) {
        val normalized = WatchPartySync.normalizeRoomCode(code) ?: run {
            showStatus("房间码格式不对：应为 6 位字母数字")
            return
        }
        partyExpectedRole = RoomClient.Role.GUEST
        viewModelScope.launch {
            store.setWatchPartyNick(nick)
            if (serverUrl.isNotBlank()) store.setWatchPartyServer(serverUrl)
        }
        if (serverUrl.isBlank()) {
            val found = nearbyRooms.value.firstOrNull { it.roomCode == normalized }
            if (found == null) {
                partyExpectedRole = null
                showStatus("附近没有找到房间 $normalized：请确认房主已创建并连同一 WiFi，或填服务器地址")
                return
            }
            partyClient.connect("ws://${found.host}:${found.port}", normalized, nick)
            return
        }
        partyClient.connect(serverUrl, normalized, nick)
    }

    /** 点击附近房间卡片加入（房间码取自 NSD 广播，无需输入） */
    fun joinDiscoveredRoom(room: DiscoveredRoom, nick: String) {
        partyExpectedRole = RoomClient.Role.GUEST
        viewModelScope.launch { store.setWatchPartyNick(nick) }
        partyClient.connect("ws://${room.host}:${room.port}", room.roomCode, nick)
    }

    fun leaveWatchParty(message: String? = null) {
        partyClient.close("leave")
        stopLocalRoom()
        stopNearbyDiscovery()
        hostState = null
        appliedHostEpoch = -1L
        partyExpectedRole = null
        partyWasHost = false
        partySpeedActive = false
        partyHostEpisode.value = null
        _partyChat.value = emptyList()
        if (message != null) showStatus(message)
    }

    // MARK: - 一起看局域网模式（房主内嵌中继 + NSD 发现；实例全生命周期复用一个 PartyDiscovery）

    private var localServer: LocalRoomServer? = null
    private var discovery: PartyDiscovery? = null
    private var discoveryJob: Job? = null

    private fun obtainDiscovery(): PartyDiscovery =
        discovery ?: PartyDiscovery(getApplication()).also { discovery = it }

    /** 附近发现到的房间（弹层「附近房间」列表） */
    private val _nearbyRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    val nearbyRooms: StateFlow<List<DiscoveredRoom>> = _nearbyRooms.asStateFlow()

    /** 弹层打开期间扫描附近房间（UI 进入/离开时调用） */
    fun startNearbyDiscovery() {
        if (discoveryJob != null || isPartyJoined()) return
        val d = obtainDiscovery()
        discoveryJob = viewModelScope.launch {
            d.rooms.collect { _nearbyRooms.value = it }
        }
        d.startDiscovering()
    }

    fun stopNearbyDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discovery?.stopDiscovering()
        _nearbyRooms.value = emptyList()
    }

    private fun stopLocalRoom() {
        localServer?.let { server ->
            runCatching { server.stop(0) }
            discovery?.unregister()
        }
        localServer = null
    }

    fun sendPartyChat(text: String) {
        val body = text.trim()
        if (body.isEmpty() || !isPartyJoined()) return
        addPartyChat(partyState.value.nick.ifBlank { "我" }, body, isMine = true)
        partyClient.sendChat(body)
    }

    /** 收到的聊天同时以弹幕样式飘出（PlayerScreen 的弹幕覆盖层取用合并） */
    private fun addPartyChat(nick: String, text: String, isMine: Boolean) {
        val atPos = exoPlayer?.currentPosition?.div(1000.0) ?: playerState.value.positionSeconds
        _partyChat.value = (_partyChat.value + PartyChatEntry(nick, text, atPos, isMine)).takeLast(100)
    }

    /** UI 弹幕覆盖层合并用：聊天 = 出现时刻的滚动弹幕（樱粉点缀，与主题 accent 同色） */
    fun partyChatAsDanmaku(): List<DanmakuComment> = _partyChat.value.map {
        DanmakuComment(
            time = it.atPositionSeconds,
            mode = DanmakuMode.SCROLL,
            color = PARTY_CHAT_COLOR,
            text = "${it.nick}：${it.text}",
        )
    }

    private fun onPartyWelcome(ev: RoomClient.Event.Welcome) {
        appliedHostEpoch = -1L
        hostState = null
        partyHostEpisode.value = null
        partyWasHost = ev.selfId == ev.hostId
        if (ev.selfId == ev.hostId) {
            if (partyExpectedRole == RoomClient.Role.GUEST) {
                // guest 重连后房间被重建、房主位落回自己：原房间已散（或房间码输错新建了空房）
                leaveWatchParty("房间不存在或已解散，请核对房间码后重新加入")
                return
            }
            // 房主：入房即广播一次（尚未开播时 exoPlayer 为空则跳过，开播后 listener/心跳接管）
            partyEpoch++
            sendPartyState()
            showStatus("一起看：房间已创建，把房间码分享给朋友吧")
        } else {
            partyClient.requestSync()
            showStatus("一起看：已加入房间，跟随房主播放")
        }
    }

    /** 房主广播当前权威状态；[posOverride] 供 seek 后位置未推进时使用。
     *  playing 用 playWhenReady（播放意图）而非 isPlaying——缓冲中 isPlaying 短暂变 false，
     *  若照播会让 guest 全体暂停且因「尊重本地暂停」守卫再也起不来 */
    private fun sendPartyState(posOverride: Double? = null) {
        if (!isPartyHost()) return
        val player = exoPlayer ?: return
        val state = PartyWire.State(
            playing = player.playWhenReady,
            pos = posOverride ?: (player.currentPosition / 1000.0),
            wall = System.currentTimeMillis(),
            epoch = partyEpoch,
            episodeNumber = playback?.episodeNumber ?: 0,
            subjectId = playback?.boundSubjectID,
        )
        partyClient.sendState(state)
    }

    /** guest 漂移纠偏（周期任务 1Hz 调用）；房主侧做 5s 心跳广播 */
    private fun tickWatchParty() {
        if (partyState.value.phase != RoomClient.Phase.JOINED) return
        val player = exoPlayer ?: return
        if (isPartyHost()) {
            if (System.currentTimeMillis() - partyLastBeatAt >= 5_000) {
                partyLastBeatAt = System.currentTimeMillis()
                sendPartyState()
            }
            return
        }
        val hs = hostState ?: return
        // 房主换集后 guest 未跟上：该状态属于另一集，不消费 epoch 也不对齐
        // （否则 guest 的旧集会被 seek 回 0 重播）；guest 手动切到同集后由 epoch 差自然硬对齐
        val myEp = playback?.episodeNumber ?: -1
        if (hs.episodeNumber > 0 && myEp != hs.episodeNumber) return
        if (!hs.playing) {
            // 房主暂停：guest 一次性跟随暂停（之后用户自己的暂停/恢复不被强制）
            if (player.isPlaying) player.pause()
            resetPartySpeed()
            return
        }
        // 房主播放中：guest 用户主动暂停（playWhenReady=false）或来电让路时尊重本地意图；缓冲中等自然起播
        if (!player.playWhenReady || !player.isPlaying) return
        // 落位基准用「本机收到 state 的时刻」而非 state.wall——双端墙钟有偏差时直接相减会
        // 跳出任意大的偏移；rtt/2 补偿房主→本机的传输延迟（rtt 未知时为 0，误差落在死区内）
        val target = WatchPartySync.targetPosition(
            statePosSeconds = hs.pos,
            stateWallMs = hostStateAt,
            nowMs = System.currentTimeMillis() + partyClient.rttMs / 2,
            playing = hs.playing,
        )
        if (hs.epoch > appliedHostEpoch) {
            // epoch 前进（房主 seek/换集/刚广播对齐）：硬对齐落位
            appliedHostEpoch = hs.epoch
            seekTo(target)
            resetPartySpeed()
            return
        }
        val decision = WatchPartySync.decide(player.currentPosition / 1000.0, target)
        when (decision.action) {
            WatchPartySync.Action.NONE -> resetPartySpeed()
            WatchPartySync.Action.ADJUST_SPEED -> {
                partySpeedActive = true
                player.setPlaybackSpeed(decision.speed)
            }
            WatchPartySync.Action.SEEK -> {
                seekTo(decision.targetSeconds)
                resetPartySpeed()
            }
        }
    }

    private fun resetPartySpeed() {
        if (!partySpeedActive) return
        partySpeedActive = false
        exoPlayer?.setPlaybackSpeed(speed.value)
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

    // MARK: - 截图分享卡

    /** 截图那一刻的定格素材：干净帧（表面尺寸）+ 弹幕快照（同尺寸）+ 视频内容区（裁黑边）+ 元数据 */
    data class ShareCapture(
        val frame: Bitmap,
        val overlay: Bitmap?,
        val contentRect: PixelRect,
        val meta: ShareCardMeta,
    )

    var shareCapture by androidx.compose.runtime.mutableStateOf<ShareCapture?>(null)
        private set
    var shareCard by androidx.compose.runtime.mutableStateOf<Bitmap?>(null)
        private set
    var shareBusy by androidx.compose.runtime.mutableStateOf(false)
        private set
    /** 保存相册进行中（防双击存两张） */
    var shareSaving by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var composeJob: Job? = null

    /** 截当前画面（主线程 PixelCopy 只拷视频表面，不含控制条/手势 HUD；弹幕快照同步先取）。
     *  成功后 shareCapture 就位，PlayerScreen 据此展开分享弹层 */
    fun captureForShare(danmakuView: DanmakuOverlayView?) {
        val item = playback ?: return
        val player = exoPlayer ?: return
        val playerView = sharedPlayerView ?: return
        if (shareBusy) return
        val surface = playerView.videoSurfaceView
        val surfaceW = surface?.width ?: 0
        val surfaceH = surface?.height ?: 0
        if (surfaceW <= 0 || surfaceH <= 0) {
            showStatus("画面还未就绪，稍后再试")
            return
        }
        shareBusy = true
        val meta = ShareCardMeta(
            showTitle = item.showTitle ?: "",
            episodeNumber = item.episodeNumber,
            episodeTitle = item.displayTitle,
            positionSeconds = player.currentPosition / 1000.0,
            durationSeconds = if (player.duration == C.TIME_UNSET) 0.0 else player.duration / 1000.0,
        )
        val contentRect = ShareCardMath.videoContentRect(surfaceW, surfaceH, player.videoSize.width, player.videoSize.height)
        val overlay = danmakuView?.snapshotOverlay()
        when (surface) {
            is TextureView -> { // 兜底（默认配置为 SurfaceView，通常不会走到）
                shareBusy = false
                shareCapture = surface.bitmap?.let { ShareCapture(it, overlay, contentRect, meta) }
            }
            is SurfaceView -> {
                val out = Bitmap.createBitmap(surfaceW, surfaceH, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(surface, out, { result ->
                        shareBusy = false
                        if (result == PixelCopy.SUCCESS) {
                            shareCapture = ShareCapture(out, overlay, contentRect, meta)
                        } else {
                            showStatus("截图失败（$result），请稍后再试")
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    shareBusy = false
                    showStatus("截图失败：${e.message ?: "未知错误"}")
                }
            }
            else -> {
                shareBusy = false
                showStatus("截图失败：画面类型不支持")
            }
        }
    }

    /** 按样式/弹幕开关/配文合成输出图（Default 线程；连续切换时取消上一次合成） */
    fun composeShareCard(style: ShareCardStyle, withDanmaku: Boolean, caption: String) {
        val capture = shareCapture ?: return
        if (shareBusy) return
        composeJob?.cancel()
        composeJob = viewModelScope.launch {
            shareCard = withContext(Dispatchers.Default) {
                ShareCardComposer.compose(capture.frame, capture.overlay, capture.contentRect, capture.meta, style, withDanmaku, caption)
            }
        }
    }

    /** 把当前输出图写为 PNG 并返回 FileProvider URI（系统分享面板用）；写前清掉旧分享文件 */
    fun shareCardUri(): Uri? {
        val card = shareCard ?: return null
        val context = getApplication<Application>()
        return runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val meta = shareCapture?.meta
            val name = ShareCardMath.fileName(meta?.showTitle ?: "video", meta?.episodeNumber ?: 0, System.currentTimeMillis())
            val file = File(dir, "$name.png")
            file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure {
            android.util.Log.w("NagomiShare", "write share card failed", it)
        }.getOrNull()
    }

    /** 保存当前输出图到系统相册 Pictures/NagomiAni（API 29+ 免权限；26-28 由调用方先申请存储权限） */
    fun saveCardToGallery() {
        val card = shareCard ?: return
        if (shareSaving) return
        shareSaving = true
        val context = getApplication<Application>()
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val meta = shareCapture?.meta
                    val name = ShareCardMath.fileName(meta?.showTitle ?: "video", meta?.episodeNumber ?: 0, System.currentTimeMillis())
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NagomiAni")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("相册写入被拒绝")
                    resolver.openOutputStream(uri)?.use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        ?: error("相册写入被拒绝")
                    if (Build.VERSION.SDK_INT >= 29) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    "已保存到相册 ✓"
                }.getOrElse {
                    android.util.Log.w("NagomiShare", "save card failed", it)
                    "保存失败：${it.message ?: "未知错误"}"
                }
            }
            shareSaving = false
            showStatus(message)
        }
    }

    fun showStatus(message: String) {
        ui.value = ui.value.copy(statusMessage = message)
    }

    /** 关闭分享弹层：清掉定格素材与输出图（截图素材 ~20MB 不能常驻；位图不 recycle，交 GC 回收避免与绘制竞态） */
    fun dismissShareSheet() {
        composeJob?.cancel()
        shareCapture = null
        shareCard = null
    }

    // MARK: - 生命周期

    fun releaseIfIdle() {
        // 离开播放器页：暂停但不销毁（返回续看），由 onCleared 终极回收
        exoPlayer?.pause()
    }

    fun stopPlayback() {
        routeProbeJob?.cancel()
        exoPlayer?.stop()
    }

    override fun onCleared() {
        periodicJob?.cancel()
        routeProbeJob?.cancel()
        partyClient.close("vm cleared")
        stopLocalRoom()
        stopNearbyDiscovery()
        discovery?.shutdown()
        exoPlayer?.release()
        exoPlayer = null
        sharedPlayerView = null
        PlaybackBus.isPlaying.value = false
        super.onCleared()
    }

    companion object {
        private const val EPISODE_CACHE_MAX_SERIES = 4

        /** 一起看聊天弹幕的颜色（樱粉，与主题 accent/DanmakuSettings 默认自定义色同值） */
        val PARTY_CHAT_COLOR: UInt = 0xEC6A88u

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
