package com.sparkhoward.nagomiani.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/**
 * 应用内悬浮小窗（唯一的迷你播放形态）：播放器路由退出后视频在页面角落继续播。
 * 主体拖动移位；右下角拖动缩放（宽度 140–440dp，16:9 锁定，无可见标记）；
 * 单击画面呼出控制钮（播放/暂停、展开、关闭），3.5s 自动隐藏；暂停时中央显示三角。
 * 根容器不带任何 pointerInput——小窗以外区域的触摸穿透到下层页面。
 */
@Composable
fun MiniPlayerOverlay(onExpand: () -> Unit) {
    val miniActive by PlaybackBus.miniActive.collectAsState()
    if (!miniActive) return
    // 与 PlayerScreen 同一实例（同 Activity 作用域 + 同默认 key），共享播放会话与 PlayerView
    val viewModel: PlayerViewModel =
        viewModel(viewModelStoreOwner = LocalContext.current as ViewModelStoreOwner)
    val state by viewModel.playerState.collectAsState()
    val pending by PlaybackBus.pending.collectAsState()

    // 小窗期间在详情页/缓存页点播了新集：跟随切换（start 按 resumeKey 去重）
    LaunchedEffect(pending?.resumeKey) { pending?.let { viewModel.start(it) } }

    // 控制钮：点画面呼出，2s 自动隐藏；暂停期间保持显示（否则没法关/展开）
    var controlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(2000)
            if (state.isPlaying) controlsVisible = false
        }
    }

    val density = LocalDensity.current
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var widthDp by remember { mutableStateOf(MiniSession.widthDp ?: 200.dp) }
    var offsetPx by remember { mutableStateOf(MiniSession.offsetPx) } // null = 尚未首帧定位

    val miniWidthPx = with(density) { widthDp.toPx() }
    val miniHeightPx = miniWidthPx * 9f / 16f

    // 首帧定位：右下角（会话内记得上次位置则沿用）
    LaunchedEffect(parentSize) {
        if (offsetPx == null && parentSize != IntSize.Zero) {
            offsetPx = IntOffset(
                (parentSize.width - miniWidthPx - with(density) { 12.dp.toPx() }).roundToInt().coerceAtLeast(0),
                (parentSize.height - miniHeightPx - with(density) { 12.dp.toPx() }).roundToInt().coerceAtLeast(0),
            )
        }
    }

    fun saveSession() {
        MiniSession.widthDp = widthDp
        MiniSession.offsetPx = offsetPx
    }

    Box(Modifier.fillMaxSize().onSizeChanged { parentSize = it }) {
        val off = offsetPx ?: return@Box
        val clampedX = off.x.coerceIn(0, (parentSize.width - miniWidthPx).roundToInt().coerceAtLeast(0))
        val clampedY = off.y.coerceIn(0, (parentSize.height - miniHeightPx).roundToInt().coerceAtLeast(0))

        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(clampedX, clampedY) }
                .size(width = widthDp, height = widthDp * 9 / 16)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                // 单击画面：呼出/收起控制钮；单指拖动移位；双指捏合缩放（分离放大/聚拢缩小）
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        widthDp = (widthDp * zoom).coerceIn(MIN_WIDTH, MAX_WIDTH)
                        offsetPx = offsetPx?.let {
                            IntOffset(it.x + pan.x.roundToInt(), it.y + pan.y.roundToInt())
                        }
                        // 手势过程实时记忆：小窗关闭重开保持位置/尺寸
                        MiniSession.widthDp = widthDp
                        MiniSession.offsetPx = offsetPx
                    }
                },
        ) {
            AndroidView(
                factory = { context ->
                    viewModel.obtainPlayerView(context).also { view ->
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                    }
                },
                update = { it.player = viewModel.exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )

            // 暂停角标（播放中或控制钮显示时不出现，中央位置让位给播放/暂停钮）
            if (!state.isPlaying && !controlsVisible) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "已暂停",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.Center).size(30.dp),
                )
            }

            // 控制钮（点画面呼出，自动隐藏）：关闭右上 · 播放/暂停居中 · 最大化右下
            if (controlsVisible) {
                MiniControlButton(Icons.Filled.Close, "关闭", Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    // 明确关闭：停播并清掉「回到播放中」入口，不再打扰
                    viewModel.stopPlayback()
                    PlaybackBus.pending.value = null
                    PlaybackBus.isPlaying.value = false
                    PlaybackBus.miniActive.value = false
                    saveSession()
                }
                MiniControlButton(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (state.isPlaying) "暂停" else "播放",
                    Modifier.align(Alignment.Center),
                ) { viewModel.togglePlayPause() }
                MiniControlButton(Icons.Filled.OpenInFull, "最大化", Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    controlsVisible = false
                    PlaybackBus.miniActive.value = false
                    onExpand()
                }
            }
        }
    }
}

/** 小窗角上的圆形操作钮（modifier 决定落位：关闭右上 / 播放暂停居中 / 最大化右下） */
@Composable
private fun MiniControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** 会话内记忆（进程内）：小窗位置与宽度，进出小窗不重置 */
private object MiniSession {
    var offsetPx: IntOffset? = null
    var widthDp: Dp? = null
}

private val MIN_WIDTH = 140.dp
private val MAX_WIDTH = 440.dp
