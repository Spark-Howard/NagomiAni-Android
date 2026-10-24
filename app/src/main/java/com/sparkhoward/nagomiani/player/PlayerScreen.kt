package com.sparkhoward.nagomiani.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import com.sparkhoward.nagomiani.ui.theme.minimumTouchTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowCircleRight
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sparkhoward.nagomiani.core.maccms.SourceHealth
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSecondaryButton
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器页（黑底影院）：
 * 画面(media3 PlayerView) → 弹幕覆盖层 → 手势层 → 控制条/状态条。
 * 竖屏进场：顶部 16:9 视频区 + 下方功能面板（上一集/下一集、倍速、线路、连播、缓存本集、选集）；
 * 点全屏按钮切横屏沉浸（隐藏状态栏/导航栏，刘海全贴边），再点或系统返回退回竖屏；离开页面一律恢复。
 * 播放手势竖屏/全屏一致（作用于视频区）：单击控制条、双击播放/暂停、横拖滑进度、
 * 长按 3× 快进、右/左半侧上下滑调音量/亮度。
 * 悬浮小窗（唯一迷你形态）：控制条按钮退出本页、视频在页面角落继续播
 * （拖动移位/双指缩放，见 MiniPlayerOverlay）；回桌面时自动切系统画中画保留到桌面（见 MainActivity）。
 * VM 按 Activity 作用域：路由退出/进小窗后播放会话与共享 PlayerView 都存活。
 * 控制条 3s 自动隐藏（紧凑双行）；离开页面自动暂停（与 mac 版切页暂停一致）。
 */
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        viewModelStoreOwner = androidx.compose.ui.platform.LocalContext.current as ViewModelStoreOwner,
    ),
) {
    // 响应式观察 pending：点播装配完成（或"回到播放中"）时自动开演
    val pending by PlaybackBus.pending.collectAsState()
    LaunchedEffect(pending?.resumeKey) { pending?.let { viewModel.start(it) } }
    DisposableEffect(Unit) {
        // 从悬浮小窗展开回本页：结束小窗态（共享 PlayerView 重挂载回这里）。
        // 并清画中画残留：小窗里划掉 × 会销毁 Activity，系统不保证回调 mode-changed，
        // 单例里的 inPip 若残留 true，下次进播放页会误以画中画布局渲染（无控制条、无手势）
        PlaybackBus.miniActive.value = false
        PlaybackBus.inPip.value = false
        onDispose {
            // 返回不销毁播放会话：pending 保留最后一次播放参数（PlaybackBus 契约），
            // 底部导航各页由此显示「回到播放中」入口条；重进按 resumeKey 去重/续播。
            // 进悬浮小窗时不暂停（视频在小窗里继续放）
            if (!PlaybackBus.miniActive.value) {
                PlaybackBus.isPlaying.value = false
                viewModel.releaseIfIdle()
            }
            // 分享弹层随本页销毁：定格素材（~20MB 位图）挂的是 Activity 级 VM，不清会常驻
            viewModel.dismissShareSheet()
        }
    }

    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var isFullscreen by remember { mutableStateOf(false) }

    // 系统画中画中：按沉浸布局渲染（视频铺满、控制条隐藏、单击切播放/暂停、拖动手势停用）
    val inPip by PlaybackBus.inPip.collectAsState()
    val immersive = isFullscreen || inPip
    // 进页面时的系统状态快照，任何时刻离开（含全屏中系统返回）都还原
    val originalOrientation = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    val originalCutoutMode = remember {
        if (Build.VERSION.SDK_INT >= 28) activity?.window?.attributes?.layoutInDisplayCutoutMode else null
    }
    // 进页时的窗口亮度（-1 = 跟随系统），离开播放器时还原
    val originalBrightness = remember {
        activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    DisposableEffect(Unit) {
        onDispose {
            // 还原手势亮度调节对窗口的改动（单 Activity 应用，不还原会带出播放器外）
            activity?.window?.attributes = activity?.window?.attributes?.also {
                it.screenBrightness = originalBrightness
            }
        }
    }

    // 方向 + 沉浸式系统栏：竖屏=系统栏可见；全屏=横屏 + 状态栏/导航栏隐藏（侧滑临时呼出）
    DisposableEffect(isFullscreen) {
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (activity != null && window != null && insetsController != null) {
            if (isFullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                if (Build.VERSION.SDK_INT >= 28) {
                    window.attributes = window.attributes.also {
                        it.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.requestedOrientation = originalOrientation
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28 && originalCutoutMode != null && window != null) {
                window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = originalCutoutMode }
            }
        }
    }

    // 全屏时系统返回先退全屏，而不是直接退出播放器（画中画中的返回交给系统处理）
    BackHandler(enabled = isFullscreen && !inPip) { isFullscreen = false }

    val ui by viewModel.ui.collectAsState()
    val state by viewModel.playerState.collectAsState()
    val routeHealth by viewModel.routeHealth.collectAsState()
    val danmakuController by viewModel.danmaku.collectAsState()
    val danmakuSettings by viewModel.danmakuSettings.collectAsState()
    val danmakuState by (danmakuController?.state?.collectAsState() ?: remember { mutableStateOf(DanmakuController.State()) })
    // 弹幕控制器（外层声明：覆盖层与设置弹层分处两层布局，都要用）
    val controller = danmakuController

    // 播放中屏幕常亮：暂停或离开播放器页（dispose）时交还系统超时；
    // 小窗态本页已销毁、画中画由系统接管，均不受影响
    DisposableEffect(state.isPlaying) {
        val window = activity?.window
        if (state.isPlaying) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf(0.0) }
    var showDanmakuSheet by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }

    // 弹幕覆盖层引用：截图分享时快照当前屏上的弹幕画面
    var danmakuOverlayView by remember { mutableStateOf<DanmakuOverlayView?>(null) }

    // 截图完成（定格素材就位）自动展开分享弹层；失败走状态提示条不弹层
    val shareCapture = viewModel.shareCapture
    LaunchedEffect(shareCapture) { if (shareCapture != null) showShareSheet = true }

    // 播放手势：进度/音量/亮度 HUD 与长按倍速状态
    val haptics = LocalHapticFeedback.current
    var seekHud by remember { mutableStateOf<SeekHud?>(null) }
    var volumeHud by remember { mutableStateOf<Float?>(null) }
    var brightnessHud by remember { mutableStateOf<Float?>(null) }
    var boosting by remember { mutableStateOf(false) }
    var boostBaseSpeed by remember { mutableStateOf(1f) }
    val audioManager = remember { activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    // 控制条自动隐藏（3s，播放中才隐藏）
    LaunchedEffect(controlsVisible, state.isPlaying, state.isBuffering, ui.isLoading, ui.nextOffer) {
        if (controlsVisible && state.isPlaying && !state.isBuffering && !ui.isLoading && ui.nextOffer == null) {
            delay(3000)
            controlsVisible = false
        }
    }

    // 状态提示条 4s 自动消失
    LaunchedEffect(ui.statusMessage) {
        if (ui.statusMessage != null) {
            delay(4000)
            viewModel.clearStatusMessage()
        }
    }

    // 连播征询条弹出：轻触感提示「有下一集可看」（不看控制条是否可见）
    LaunchedEffect(ui.nextOffer) {
        if (ui.nextOffer != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // 竖屏用应用主题背景（与全 App 一致），全屏保持纯黑影院
    // 弹幕设置半透明列表：非模态覆盖层——不加遮罩，上方视频与弹幕完全不受影响；
    // 点列表外任意处或「完成」收起
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(if (immersive) Color.Black else MaterialTheme.colorScheme.background)
        ) {
            // 视频区：全屏沉浸与画中画占满；竖屏为顶部 16:9
            Box(
                Modifier.then(
                    if (immersive) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                ),
            ) {
            // 视频画面：与悬浮小窗共用同一 PlayerView 实例（换容器重挂载，不重建）
            AndroidView(
                factory = { context ->
                    viewModel.obtainPlayerView(context).also { view ->
                        (view.parent as? ViewGroup)?.removeView(view)
                    }
                },
                update = { it.player = viewModel.exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )

            // 弹幕覆盖层
            if (controller != null) {
                AndroidView(
                    factory = { context ->
                        DanmakuOverlayView(context).apply { start() }.also { danmakuOverlayView = it }
                    },
                    update = { view ->
                        view.timeProvider = { viewModel.playerState.value.positionSeconds }
                        view.isPlayingProvider = { viewModel.playerState.value.isPlaying }
                        view.setSettings(danmakuSettings)
                        view.setComments(danmakuState.comments)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 手势层（竖屏/全屏一致，作用于视频区）：单击切控制条 + 双击播放/暂停 + 横拖滑进度 +
            // 长按 3× 快进 + 右半侧上下滑调音量 / 左半侧上下滑调亮度
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            // 画中画内单击直接切播放/暂停（没有控制条可呼出）
                            onTap = { if (inPip) viewModel.togglePlayPause() else controlsVisible = !controlsVisible },
                            onDoubleTap = { viewModel.togglePlayPause() },
                            onLongPress = {
                                if (!inPip && state.isPlaying && !boosting) {
                                    boostBaseSpeed = viewModel.speed.value
                                    boosting = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // 临时变速不落盘（persist=false），抬手还原基速
                                    viewModel.setSpeed(3f, persist = false)
                                }
                            },
                            onPress = {
                                tryAwaitRelease()
                                // 长按快进在抬手时还原用户倍速（快进中离开页面由 dispose 兜底）
                                if (boosting) {
                                    boosting = false
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.setSpeed(boostBaseSpeed, persist = false)
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (inPip) {
                                // 画中画尺寸太小，不启用拖动（进度/音量/亮度 HUD 放不下）；等所有指针抬起
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.none { it.pressed }) break
                                }
                                return@awaitEachGesture
                            }
                            // 竖屏/全屏切换不会重启 pointerInput(Unit)，尺寸必须每次手势实时读取
                            val boxSize = size
                            var mode = 0 // 0 待定 / 1 进度 / 2 音量 / 3 亮度
                            var totalX = 0f
                            var totalY = 0f
                            var seekStart = 0.0
                            var volumeStart = 0
                            var volumeMax = 1
                            var brightnessStart = 0.5f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed } ?: break
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y
                                if (mode == 0) {
                                    val absX = abs(totalX)
                                    val absY = abs(totalY)
                                    if (absX > touchSlop || absY > touchSlop) {
                                        mode = when {
                                            absX > absY && state.durationSeconds >= 1.0 -> 1
                                            absY > absX && down.position.x >= boxSize.width / 2f && audioManager != null -> 2
                                            absY > absX && activity?.window != null -> 3
                                            else -> 0 // 横拖但时长未知：不进入任何模式
                                        }
                                        when (mode) {
                                            1 -> {
                                                seekStart = if (isSeeking) seekPreview else state.positionSeconds
                                                isSeeking = true
                                                controlsVisible = false
                                            }
                                            2 -> {
                                                volumeMax = audioManager!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                                volumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                            }
                                            3 -> brightnessStart = currentBrightness(activity?.window)
                                        }
                                    }
                                }
                                if (mode != 0) {
                                    when (mode) {
                                        1 -> {
                                            val duration = state.durationSeconds
                                            val target = (seekStart + totalX / boxSize.width * duration)
                                                .coerceIn(0.0, duration)
                                            seekPreview = target
                                            seekHud = SeekHud(target - seekStart, target)
                                        }
                                        2 -> {
                                            val target = volumeStart + (-totalY / boxSize.height * volumeMax).roundToInt()
                                                .coerceIn(0, volumeMax)
                                            audioManager!!.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                            volumeHud = if (volumeMax > 0) target / volumeMax.toFloat() else 0f
                                        }
                                        else -> {
                                            val target = (brightnessStart - totalY / boxSize.height)
                                                .coerceIn(0.01f, 1f)
                                            activity?.window?.attributes = activity?.window?.attributes
                                                ?.also { it.screenBrightness = target }
                                            brightnessHud = target
                                        }
                                    }
                                    change.consume()
                                }
                            }
                            // 抬手/取消：进度应用、收起 HUD
                            when (mode) {
                                1 -> {
                                    viewModel.seekTo(seekPreview)
                                    isSeeking = false
                                    seekHud = null
                                }
                                2 -> volumeHud = null
                                3 -> brightnessHud = null
                            }
                        }
                    },
            )

            // 手势 HUD：进度预览（中央）
            seekHud?.let { hud ->
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (hud.delta >= 0) "▶▶" else "◀◀",
                        color = NagomiColors.accent,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "${formatSeekDelta(hud.delta)} / ${PlayerViewModel.formatTime(hud.target)}",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }

            // 手势 HUD：长按 3× 快进（中央偏上；竖屏视频区矮，偏移收小避免出画）
            if (boosting) {
                Text(
                    "3× 快进中 ▶▶▶",
                    color = NagomiColors.accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = if (isFullscreen) 110.dp else 80.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            // 手势 HUD：音量（右侧）/ 亮度（左侧）竖条（竖屏视频区矮，竖条收短）
            volumeHud?.let { fraction ->
                GestureHudBar(
                    fraction = fraction,
                    icon = { tint, size ->
                        Icon(Icons.Filled.VolumeUp, "音量", tint = tint, modifier = Modifier.size(size))
                    },
                    barHeight = if (isFullscreen) 140.dp else 120.dp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp),
                )
            }
            brightnessHud?.let { fraction ->
                GestureHudBar(
                    fraction = fraction,
                    icon = { tint, size ->
                        Icon(Icons.Filled.Brightness6, "亮度", tint = tint, modifier = Modifier.size(size))
                    },
                    barHeight = if (isFullscreen) 140.dp else 120.dp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp),
                )
            }

            // 加载/错误状态（pending 未就位 = 点播仍在取流装配）
            if (pending == null || ui.isLoading || state.isBuffering) {
                CircularProgressIndicator(
                    color = NagomiColors.accent,
                    modifier = Modifier.align(Alignment.Center).size(44.dp),
                )
            }
            ui.error?.let { error ->
                Text(
                    error,
                    color = Color(0xFFFF7A8E),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(12.dp),
                )
            }

            // 状态提示条（续播/同步消息，4s 自动消失）：不看控制条是否可见——
            // 看完同步的成功/失败提示多发生在控制条已隐藏时，必须让它露出来（画中画内不显示）
            val message = ui.statusMessage
            if (message != null && !inPip) {
                Text(
                    message,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp)
                        .background(Color.Black.copy(alpha = 0.65f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // 顶栏（返回 + 标题）：贴视频区最顶
            if (controlsVisible) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (isFullscreen) isFullscreen = false else onClose() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        ui.title,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 连播征询条（95%/EOF 弹出，点"看下一集"才切；画中画内不显示）
            if (!inPip) ui.nextOffer?.let { offer ->
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 84.dp)
                        .background(Color(0xCC2A2124), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(offer.label, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.size(10.dp))
                    Icon(
                        Icons.Filled.ArrowCircleRight,
                        "看下一集",
                        tint = NagomiColors.accentDark,
                        modifier = Modifier
                            .size(30.dp)
                            .clickable { viewModel.acceptNextEpisode() },
                    )
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        Icons.Filled.Close,
                        "不再提示",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { viewModel.dismissNextEpisode() },
                    )
                }
            }

            // 底部控制条（单行：弹幕 + 时间 + 进度 + [线路] + 播放 + 全屏）
            if (controlsVisible) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val displayTime = if (isSeeking) seekPreview else state.positionSeconds
                    val danmakuOn = danmakuState.phase == DanmakuController.Phase.LOADED
                    Text(
                        "弹",
                        color = if (danmakuOn) NagomiColors.accentDark else Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { showDanmakuSheet = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(PlayerViewModel.formatTime(displayTime), color = Color.White, fontSize = 10.sp)
                    Slider(
                        value = (if (isSeeking) seekPreview else state.positionSeconds).toFloat(),
                        onValueChange = { value ->
                            isSeeking = true
                            seekPreview = value.toDouble()
                        },
                        onValueChangeFinished = {
                            viewModel.seekTo(seekPreview)
                            isSeeking = false
                        },
                        valueRange = 0f..maxOf(state.durationSeconds, 1.0).toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = NagomiColors.accent,
                            activeTrackColor = NagomiColors.accent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .heightIn(min = 26.dp),
                    )
                    Text(PlayerViewModel.formatTime(state.durationSeconds), color = Color.White, fontSize = 10.sp)
                    // 线路切换仅横屏展示（竖屏移至下方功能面板）；⚡ = 探测延迟最低的线路
                    if (isFullscreen) {
                        viewModel.playback?.routes.orEmpty().forEachIndexed { index, _ ->
                            Text(
                                routeLabel(index, routeHealth),
                                color = if (index == viewModel.currentRouteIndex) NagomiColors.accentDark else Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clickable { viewModel.switchRoute(index) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.togglePlayPause() }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // 截图分享：定格当前画面（干净帧 + 弹幕快照），弹层里选样式预览/保存/分享
                    IconButton(onClick = { viewModel.captureForShare(danmakuOverlayView) }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = "截图分享",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // 应用内悬浮小窗：退出本页、视频在页面角落继续播（拖动移位/拖角缩放）
                    IconButton(onClick = onMinimize, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.PictureInPicture,
                            contentDescription = "小窗播放",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = { isFullscreen = !isFullscreen }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = "全屏",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // 竖屏功能面板：视频下方的黑色区域（选集 / 倍速 / 线路 / 连播 / 缓存本集）
        if (!immersive) {
            PortraitPanel(
                viewModel = viewModel,
                panel = viewModel.panel.collectAsState().value,
                speed = viewModel.speed.collectAsState().value,
                autoplayNext = viewModel.autoplayNext.collectAsState().value,
                routeHealth = routeHealth,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }

        // 弹幕设置半透明列表：非模态覆盖层——不加遮罩，上方视频与弹幕完全不受影响；
        // 点列表外任意处或「完成」收起
        AnimatedVisibility(
            visible = showDanmakuSheet && controller != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showDanmakuSheet = false },
                contentAlignment = Alignment.BottomCenter,
            ) {
                DanmakuSettingsSheet(
                    controller = controller!!,
                    settings = danmakuSettings,
                    onUpdate = { transform -> viewModel.updateDanmakuSettings(transform) },
                    onRefetch = { viewModel.refetchDanmaku() },
                    onDismiss = { showDanmakuSheet = false },
                )
            }
        }

        // 截图分享弹层：与弹幕设置同款非模态覆盖层；关闭后延迟清定格素材，让滑出动画播完
        AnimatedVisibility(
            visible = showShareSheet,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showShareSheet = false },
                contentAlignment = Alignment.BottomCenter,
            ) {
                ShareCardSheet(
                    viewModel = viewModel,
                    onDismiss = { showShareSheet = false },
                )
            }
        }
        if (!showShareSheet) {
            LaunchedEffect(Unit) {
                delay(300)
                viewModel.dismissShareSheet()
            }
        }
    }
}

/** 竖屏下面板：任何集数下都由常驻组件（上下集/倍速/线路/连播/缓存）撑住版面，选集网格集数≥2 才出现 */
@Composable
private fun PortraitPanel(
    viewModel: PlayerViewModel,
    panel: PlayerViewModel.PanelState,
    speed: Float,
    autoplayNext: Boolean,
    routeHealth: List<Long?>,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.loadPanelIfNeeded() }
    val routes = viewModel.playback?.routes.orEmpty()
    if (viewModel.playback == null) return
    val nextTitle = panel.episodes.firstOrNull { it.number == panel.currentNumber + 1 }?.displayTitle

    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // 信息行：番名 + 当前集/总集数 + 下一集预览
        Text(
            viewModel.playback?.showTitle ?: "",
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append("第 ${panel.currentNumber} 集")
                if (panel.episodes.isNotEmpty()) append(" / 共 ${panel.episodes.size} 集")
                if (!nextTitle.isNullOrBlank()) append(" · 下一集：$nextTitle")
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )

        // 上一集 / 下一集
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelButton(
                "⏮ 上一集",
                enabled = panel.episodes.any { it.number == panel.currentNumber - 1 },
                modifier = Modifier.weight(1f),
            ) { viewModel.playEpisodeNumber(panel.currentNumber - 1) }
            PanelButton(
                "下一集 ⏭",
                enabled = panel.episodes.any { it.number == panel.currentNumber + 1 },
                modifier = Modifier.weight(1f),
            ) { viewModel.playEpisodeNumber(panel.currentNumber + 1) }
        }

        // 倍速
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("倍速", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            listOf(0.75f to "0.75×", 1.0f to "1.0×", 1.25f to "1.25×", 1.5f to "1.5×", 2.0f to "2.0×").forEach { (value, label) ->
                Text(
                    label,
                    color = if (speed == value) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .minimumTouchTarget()
                        .clickable { viewModel.setSpeed(value) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // 线路切换（多线路时；⚡最快 + API/CDN TTFB 延迟标注）
        if (routes.size > 1) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("线路", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                routes.forEachIndexed { index, _ ->
                    Text(
                        routeLabel(index, routeHealth),
                        color = if (index == viewModel.currentRouteIndex) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { viewModel.switchRoute(index) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // 连播开关 + 缓存本集
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("自动连播", fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = autoplayNext,
                onCheckedChange = { viewModel.toggleAutoplayNext() },
                colors = SwitchDefaults.colors(checkedTrackColor = NagomiColors.accent),
                modifier = Modifier.height(26.dp),
            )
            Spacer(Modifier.weight(1f))
            NagomiSecondaryButton("缓存本集") { viewModel.cacheCurrentEpisode() }
        }

        // 选集：横向滑动条，可展开为网格（≥2 集才显示）
        if (panel.episodes.size >= 2) {
            var expanded by remember { mutableStateOf(false) }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选集 · 共 ${panel.episodes.size} 集",
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    color = NagomiColors.accent,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
            if (expanded) {
                // 固定宽度方框（与收起态一致），切换展开时方框尺寸不变、只有行数增加，避免抖动
                panel.episodes.chunked(6).forEach { rowEps ->
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowEps.forEach { ep ->
                            EpisodeChip(
                                ep.number,
                                isCurrent = ep.number == panel.currentNumber,
                                modifier = Modifier.width(48.dp),
                            ) { viewModel.playEpisodeNumber(ep.number) }
                        }
                    }
                }
            } else {
                LazyRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(panel.episodes) { ep ->
                        EpisodeChip(
                            ep.number,
                            isCurrent = ep.number == panel.currentNumber,
                            modifier = Modifier.width(48.dp),
                        ) { viewModel.playEpisodeNumber(ep.number) }
                    }
                }
            }
        }
    }
}

/** 面板按钮（上一集/下一集） */
@Composable
private fun PanelButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/** 选集小方框（当前集高亮） */
@Composable
private fun EpisodeChip(number: Int, isCurrent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isCurrent) NagomiColors.accent
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - 播放手势（进度/音量/亮度）类型与工具

/** 进度拖动 HUD：delta = 相对起点的偏移秒数（负 = 回退），target = 目标时间点 */
private data class SeekHud(val delta: Double, val target: Double)

private fun formatSeekDelta(delta: Double): String {
    val sign = if (delta >= 0) "+" else "-"
    return sign + PlayerViewModel.formatTime(abs(delta))
}

/** 线路标签：⚡ 前缀 = 探测延迟最低的线路；有探测结果时附 TTFB（探测中/单线路不显示） */
private fun routeLabel(index: Int, routeHealth: List<Long?>): String {
    val fastest = SourceHealth.fastestRouteIndex(routeHealth)
    return buildString {
        if (index == fastest) append("⚡")
        append("线路${index + 1}")
        routeHealth.getOrNull(index)?.let { append(" · ${SourceHealth.formatLatency(it)}") }
    }
}

/** 当前窗口亮度；从未设置过（-1）时读系统亮度，再失败取 0.5 */
private fun currentBrightness(window: Window?): Float {
    if (window == null) return 0.5f
    window.attributes.screenBrightness.takeIf { it in 0.01f..1f }?.let { return it }
    return try {
        (Settings.System.getInt(window.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f)
            .coerceIn(0.05f, 1f)
    } catch (e: Exception) {
        0.5f
    }
}

/** 音量/亮度 HUD：竖向指示条 + 图标 + 百分比 */
@Composable
private fun GestureHudBar(
    fraction: Float,
    icon: @Composable (Color, androidx.compose.ui.unit.Dp) -> Unit,
    barHeight: androidx.compose.ui.unit.Dp = 140.dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(barHeight).width(6.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(999.dp)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(barHeight * fraction.coerceIn(0f, 1f))
                    .background(NagomiColors.accent, RoundedCornerShape(999.dp)),
            )
        }
        Spacer(Modifier.height(8.dp))
        icon(Color.White, 18.dp)
        Spacer(Modifier.height(4.dp))
        Text("${(fraction * 100).roundToInt()}%", color = Color.White, fontSize = 11.sp)
    }
}
