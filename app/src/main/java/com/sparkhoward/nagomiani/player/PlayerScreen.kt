package com.sparkhoward.nagomiani.player

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSecondaryButton
import kotlinx.coroutines.delay

/**
 * 播放器页（黑底影院）：
 * 画面(media3 PlayerView) → 弹幕覆盖层 → 手势层（点击暂停/呼出控制条）→ 控制条/状态条。
 * 竖屏进场：顶部 16:9 视频区 + 下方功能面板（上一集/下一集、倍速、线路、连播、缓存本集、选集）；
 * 点全屏按钮切横屏沉浸（隐藏状态栏/导航栏，刘海全贴边），再点或系统返回退回竖屏；离开页面一律恢复。
 * 控制条 3s 自动隐藏（紧凑双行）；离开页面自动暂停（与 mac 版切页暂停一致）。
 */
@Composable
fun PlayerScreen(onClose: () -> Unit, viewModel: PlayerViewModel = viewModel()) {
    // 响应式观察 pending：点播装配完成（或"回到播放中"）时自动开演
    val pending by PlaybackBus.pending.collectAsState()
    LaunchedEffect(pending?.resumeKey) { pending?.let { viewModel.start(it) } }
    DisposableEffect(Unit) {
        onDispose {
            // 返回即关闭：清除播放会话，"正在播放"入口条同帧消失（不再滞留）
            PlaybackBus.pending.value = null
            PlaybackBus.isPlaying.value = false
            viewModel.releaseIfIdle()
        }
    }

    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var isFullscreen by remember { mutableStateOf(false) }
    // 进页面时的系统状态快照，任何时刻离开（含全屏中系统返回）都还原
    val originalOrientation = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    val originalCutoutMode = remember {
        if (Build.VERSION.SDK_INT >= 28) activity?.window?.attributes?.layoutInDisplayCutoutMode else null
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

    // 全屏时系统返回先退全屏，而不是直接退出播放器
    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    val ui by viewModel.ui.collectAsState()
    val state by viewModel.playerState.collectAsState()
    val danmakuController by viewModel.danmaku.collectAsState()
    val danmakuSettings by viewModel.danmakuSettings.collectAsState()
    val danmakuState by (danmakuController?.state?.collectAsState() ?: remember { mutableStateOf(DanmakuController.State()) })
    // 弹幕控制器（外层声明：覆盖层与设置弹层分处两层布局，都要用）
    val controller = danmakuController

    var controlsVisible by remember { mutableStateOf(true) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf(0.0) }
    var showDanmakuSheet by remember { mutableStateOf(false) }

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

    // 竖屏用应用主题背景（与全 App 一致），全屏保持纯黑影院
    // 弹幕设置半透明列表：非模态覆盖层——不加遮罩，上方视频与弹幕完全不受影响；
    // 点列表外任意处或「完成」收起
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(if (isFullscreen) Color.Black else MaterialTheme.colorScheme.background)
        ) {
            // 视频区：全屏占满整屏（横屏沉浸）；竖屏为顶部 16:9
            Box(
                Modifier.then(
                    if (isFullscreen) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                ),
            ) {
            // 视频画面
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = viewModel.exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )

            // 弹幕覆盖层
            if (controller != null) {
                AndroidView(
                    factory = { context ->
                        DanmakuOverlayView(context).apply { start() }
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

            // 手势层：点击 = 切换控制条显隐
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controlsVisible = !controlsVisible },
            )

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

            // 状态提示条（续播/同步消息，4s 自动消失）
            val message = ui.statusMessage
            if (message != null && controlsVisible) {
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

            // 连播征询条（95%/EOF 弹出，点"看下一集"才切）
            ui.nextOffer?.let { offer ->
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
                            .height(26.dp),
                    )
                    Text(PlayerViewModel.formatTime(state.durationSeconds), color = Color.White, fontSize = 10.sp)
                    // 线路切换仅横屏展示（竖屏移至下方功能面板）
                    if (isFullscreen) {
                        viewModel.playback?.routes.orEmpty().forEachIndexed { index, _ ->
                            Text(
                                "线路${index + 1}",
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
        if (!isFullscreen) {
            PortraitPanel(
                viewModel = viewModel,
                panel = viewModel.panel.collectAsState().value,
                speed = viewModel.speed.collectAsState().value,
                autoplayNext = viewModel.autoplayNext.collectAsState().value,
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
                    onDismiss = { showDanmakuSheet = false },
                )
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
                        .clickable { viewModel.setSpeed(value) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // 线路切换（多线路时）
        if (routes.size > 1) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("线路", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                routes.forEachIndexed { index, _ ->
                    Text(
                        "线路${index + 1}",
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
