package com.sparkhoward.nagomiani.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.View
import com.sparkhoward.nagomiani.core.danmaku.DanmakuLayout
import com.sparkhoward.nagomiani.core.danmaku.DanmakuTrackItem
import com.sparkhoward.nagomiani.core.model.DanmakuComment
import com.sparkhoward.nagomiani.core.model.DanmakuMode

/**
 * 弹幕覆盖层：Choreographer 逐帧驱动 + Canvas 直接绘制（与 mac 版"常驻逐帧直接赋值"同管线语义）。
 * - 引擎时间 ~1Hz → 墙钟补齐帧间增量；锚点陈旧（暂停恢复/卡顿）不外推（钳制 1.5s）
 * - 暂停且时间未变 → 完全冻结（不重绘不消失）
 * - 滚动区占上方 75%，底部留字幕/控制条
 */
class DanmakuOverlayView(context: Context) : View(context) {

    private var comments: List<DanmakuComment> = emptyList()
    private var assigned: List<DanmakuTrackItem> = emptyList()
    private var settings = DanmakuSettings()
    var timeProvider: (() -> Double)? = null
    var isPlayingProvider: (() -> Boolean)? = null

    private var lastEngineTime: Double = -1.0
    private var lastWallNanos: Long = 0
    private var lastRenderedTime: Double = -1.0

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val strokePaint = Paint(textPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 0, 0, 0)
    }

    /** 每条弹幕的绘制缓存（paint 状态 + 宽度），换设置/换内容时失效 */
    private data class DrawState(val textPaint: Paint, val strokePaint: Paint, val width: Float)
    private var drawCache: MutableMap<DanmakuComment, DrawState> = HashMap()

    private var choreographer: Choreographer? = null
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step()
            choreographer?.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        choreographer = Choreographer.getInstance().also { it.postFrameCallback(frameCallback) }
    }

    fun stop() {
        running = false
        choreographer?.removeFrameCallback(frameCallback)
    }

    fun setComments(items: List<DanmakuComment>) {
        if (items === comments) return // PlayerScreen 每次重组都会调用，未变化时不重建车道
        comments = items
        rebuild()
    }

    fun setSettings(value: DanmakuSettings) {
        if (value == settings) return // 未变化时不clear画笔缓存/不重建
        settings = value
        drawCache.clear()
        rebuild()
    }

    private fun rebuild() {
        assigned = if (settings.enabled) {
            DanmakuLayout.assignLanes(
                comments = comments,
                laneCount = laneCount(),
                screenWidth = width.toFloat(),
                fontSize = scaledFontSize(),
                measure = { text, size ->
                    val probe = Paint(textPaint).apply { textSize = size }
                    probe.measureText(text)
                },
            )
        } else emptyList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private val density: Float get() = resources.displayMetrics.density
    private fun scaledFontSize(): Float = settings.fontSize * density

    private fun laneCount(): Int =
        maxOf(1, (height * DanmakuLayout.scrollAreaRatio / laneHeight()).toInt())

    private fun laneHeight(): Float = scaledFontSize() + 12f * density

    /** 锚点外推钳制：暂停恢复一瞬/卡顿时锚点陈旧，不再补差值（与 mac 版一致） */
    private val maxExtrapolation = 1.5

    private fun step() {
        val engineTime = timeProvider?.invoke() ?: return
        val playing = isPlayingProvider?.invoke() ?: false
        if (!playing && engineTime == lastRenderedTime) return // 暂停冻结
        if (engineTime != lastEngineTime) {
            lastEngineTime = engineTime
            lastWallNanos = System.nanoTime()
        }
        val t = if (!playing) engineTime else {
            val wall = (System.nanoTime() - lastWallNanos) / 1_000_000_000.0
            if (wall <= maxExtrapolation) engineTime + wall else engineTime
        }
        lastRenderedTime = t
        invalidate()
        pendingRenderTime = t
    }

    private var pendingRenderTime: Double = 0.0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!settings.enabled || assigned.isEmpty()) return
        val t = pendingRenderTime
        val densityScale = density
        for (item in assigned) {
            val elapsed = t - item.comment.time
            val duration = when (item.comment.mode) {
                DanmakuMode.SCROLL -> DanmakuLayout.travelDuration
                DanmakuMode.TOP, DanmakuMode.BOTTOM -> DanmakuLayout.stackDuration
            }
            if (elapsed < 0 || elapsed > duration) continue
            val draw = drawStateFor(item)
            val x: Float
            val y: Float
            when (item.comment.mode) {
                DanmakuMode.SCROLL -> {
                    x = (width + draw.width) * (1.0 - elapsed / DanmakuLayout.travelDuration).toFloat() - draw.width / 2f
                    y = item.lane * laneHeight() + laneHeight() / 2f + 4f * densityScale
                }
                DanmakuMode.TOP -> {
                    x = width / 2f
                    y = item.lane * laneHeight() + laneHeight() / 2f + 4f * densityScale
                }
                DanmakuMode.BOTTOM -> {
                    x = width / 2f
                    y = height - (item.lane + 1) * laneHeight() + laneHeight() / 2f - 4f * densityScale
                }
            }
            // 基线校正：y 为车道中心，文字以中心对齐绘制
            val baseline = y + (draw.textPaint.fontMetrics.bottom - draw.textPaint.fontMetrics.top) / 2f - draw.textPaint.fontMetrics.bottom
            val px = scaledFontSize()
            draw.strokePaint.textSize = px
            draw.textPaint.textSize = px
            canvas.drawText(item.comment.text, x, baseline, draw.strokePaint)
            canvas.drawText(item.comment.text, x, baseline, draw.textPaint)
        }
    }

    private fun drawStateFor(item: DanmakuTrackItem): DrawState {
        return drawCache.getOrPut(item.comment) {
            val colorInt = when (settings.colorMode) {
                DanmakuSettings.ColorMode.ORIGINAL -> Color.rgb(
                    ((item.comment.color shr 16) and 0xFFu).toInt(),
                    ((item.comment.color shr 8) and 0xFFu).toInt(),
                    (item.comment.color and 0xFFu).toInt(),
                )
                DanmakuSettings.ColorMode.WHITE -> Color.WHITE
                DanmakuSettings.ColorMode.CUSTOM -> Color.rgb(
                    ((settings.customColor shr 16) and 0xFFu).toInt(),
                    ((settings.customColor shr 8) and 0xFFu).toInt(),
                    (settings.customColor and 0xFFu).toInt(),
                )
            }
            val fill = Paint(textPaint).apply {
                textSize = scaledFontSize()
                color = colorInt
                alpha = (settings.opacity * 255).toInt().coerceIn(0, 255)
                textAlign = Paint.Align.CENTER
            }
            val stroke = Paint(fill).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.argb(140, 0, 0, 0)
                alpha = ((settings.opacity * 255).toInt() * 55 / 100).coerceIn(0, 255)
            }
            DrawState(fill, stroke, fill.measureText(item.comment.text))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
