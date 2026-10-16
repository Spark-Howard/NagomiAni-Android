package com.sparkhoward.nagomiani.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextUtils.TruncateAt
import androidx.compose.ui.graphics.toArgb
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import kotlin.math.roundToInt

/** 分享输出样式：樱花分享卡（番名/集数/进度/配文）/ 纯净截图（只有画面本身） */
enum class ShareCardStyle { SAKURA, CLEAN }

/** 截图那一刻定格的播放元数据（分享卡文案用） */
data class ShareCardMeta(
    val showTitle: String,
    val episodeNumber: Int,
    val episodeTitle: String?,
    val positionSeconds: Double,
    val durationSeconds: Double,
)

/** 纯 Kotlin 像素矩形（内容区计算走 JVM 单测，不引入 android.graphics.Rect） */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** 纯函数（JVM 可测），与 DanmakuLayout 同款「measure 注入」范式 */
object ShareCardMath {

    /**
     * PlayerView 默认 FIT 缩放下的视频内容区（表面坐标系）：视频等比完整放入表面、居中，余量即黑边。
     * 视频尺寸未知（起播前为 0）时视为铺满整个表面。
     */
    fun videoContentRect(surfaceW: Int, surfaceH: Int, videoW: Int, videoH: Int): PixelRect {
        if (surfaceW <= 0 || surfaceH <= 0) return PixelRect(0, 0, 0, 0)
        if (videoW <= 0 || videoH <= 0) return PixelRect(0, 0, surfaceW, surfaceH)
        val scale = minOf(surfaceW.toFloat() / videoW, surfaceH.toFloat() / videoH)
        val contentW = (videoW * scale).toInt().coerceIn(1, surfaceW)
        val contentH = (videoH * scale).toInt().coerceIn(1, surfaceH)
        val left = (surfaceW - contentW) / 2
        val top = (surfaceH - contentH) / 2
        return PixelRect(left, top, left + contentW, top + contentH)
    }

    /** 分享/保存文件名（不含扩展名）：路径非法字符与空白压成单下划线，番名截 40 字符 */
    fun fileName(showTitle: String, episodeNumber: Int, timestampMillis: Long): String {
        val safe = showTitle.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_')
        val base = safe.take(40).ifBlank { "video" }
        return "NagomiAni_${base}_EP${episodeNumber}_$timestampMillis"
    }

    /** 按字符贪心折行（中日文无空格分词），超出 maxLines 时末行截断加省略号 */
    fun wrapText(text: String, maxWidth: Float, maxLines: Int, measure: (String) -> Float): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || maxLines < 1) return emptyList()
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var overflow = false
        for (ch in trimmed) {
            current.append(ch)
            if (measure(current.toString()) > maxWidth) {
                current.deleteCharAt(current.length - 1)
                if (lines.size == maxLines - 1) {
                    overflow = true
                    break
                }
                if (current.isNotEmpty()) lines += current.toString()
                current.setLength(0)
                current.append(ch)
            }
        }
        if (overflow) {
            var last = current.toString()
            while (last.isNotEmpty() && measure("$last…") > maxWidth) last = last.dropLast(1)
            lines += "$last…"
        } else if (current.isNotEmpty()) {
            lines += current.toString()
        }
        return lines
    }
}

/**
 * 截图分享卡合成：PixelCopy 视频帧 + 弹幕快照 → 樱花分享卡 / 纯净截图。
 * 设计 token 与 Compose 视觉同源（NagomiColors 经 toArgb 进原生绘制）；
 * 合成在 Dispatchers.Default 执行，不得触碰任何 UI 对象。
 */
object ShareCardComposer {

    private const val CARD_WIDTH = 1080
    private const val MARGIN = 56f
    private const val IMAGE_CORNER = 36f
    private const val HAIRLINE_WIDTH = 3f
    private const val PROGRESS_HEIGHT = 10f

    private val accent = NagomiColors.accent.toArgb()
    private val accentSoft = NagomiColors.accentSoft.toArgb()
    private val pageBg = NagomiColors.pageBackgroundLight.toArgb()
    private val textPrimary = NagomiColors.textPrimaryLight.toArgb()
    private val textSecondary = NagomiColors.textSecondaryLight.toArgb()
    private val hairline = NagomiColors.hairlineLight.toArgb()

    fun compose(
        frame: Bitmap,
        overlay: Bitmap?,
        contentRect: PixelRect,
        meta: ShareCardMeta,
        style: ShareCardStyle,
        withDanmaku: Boolean,
        caption: String,
    ): Bitmap {
        val content = cropContent(frame, overlay, contentRect, withDanmaku)
        return when (style) {
            ShareCardStyle.CLEAN -> content
            ShareCardStyle.SAKURA -> {
                val card = composeSakura(content, meta, caption)
                if (content !== frame) content.recycle() // 中间裁剪帧用完即弃（frame 原件要留给下次重合成）
                card
            }
        }
    }

    /** 裁出视频内容区（去黑边）；带弹幕时把弹幕快照按内容区映射叠加（两者同为表面坐标系） */
    private fun cropContent(frame: Bitmap, overlay: Bitmap?, contentRect: PixelRect, withDanmaku: Boolean): Bitmap {
        val left = contentRect.left.coerceIn(0, maxOf(frame.width - 1, 0))
        val top = contentRect.top.coerceIn(0, maxOf(frame.height - 1, 0))
        val right = contentRect.right.coerceIn(left + 1, frame.width)
        val bottom = contentRect.bottom.coerceIn(top + 1, frame.height)
        val w = right - left
        val h = bottom - top
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val src = Rect(left, top, right, bottom)
        val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(frame, src, dst, paint)
        if (withDanmaku && overlay != null) canvas.drawBitmap(overlay, src, dst, paint)
        return out
    }

    private fun composeSakura(content: Bitmap, meta: ShareCardMeta, caption: String): Bitmap {
        val imageW = CARD_WIDTH - MARGIN * 2
        val imageH = (content.height * (imageW / content.width.toFloat())).toInt().coerceAtLeast(1)

        val titlePaint = textPaint(46f, textPrimary).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val episodePaint = textPaint(30f, textSecondary)
        val captionPaint = textPaint(36f, textPrimary)
        val footerPaint = textPaint(26f, textSecondary)
        val watermarkPaint = textPaint(26f, accent).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

        val title = ellipsize(meta.showTitle, titlePaint)
        val episode = buildString {
            append("第 ${meta.episodeNumber} 集")
            val epTitle = meta.episodeTitle?.trim()
            if (!epTitle.isNullOrBlank() && epTitle != "第 ${meta.episodeNumber} 集") append(" · $epTitle")
        }.let { ellipsize(it, episodePaint) }
        val captionLines = ShareCardMath.wrapText(caption, imageW, 2) { captionPaint.measureText(it) }
        val hasProgress = meta.durationSeconds > 0

        // 高度预算（自上而下累计，避免两遍布局）
        var y = MARGIN + imageH
        y += 44f
        y += lineHeight(titlePaint)
        y += 12f
        y += lineHeight(episodePaint)
        if (captionLines.isNotEmpty()) y += 24f + lineHeight(captionPaint) * captionLines.size
        if (hasProgress) y += 36f + PROGRESS_HEIGHT
        y += 18f
        y += maxOf(lineHeight(footerPaint), lineHeight(watermarkPaint))
        y += MARGIN

        val out = Bitmap.createBitmap(CARD_WIDTH, y.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(pageBg)
        drawRoundedImage(canvas, content, MARGIN, MARGIN, imageW, imageH.toFloat())

        var ty = MARGIN + imageH + 44f
        ty = drawTextTop(canvas, title, titlePaint, MARGIN, ty)
        ty += 12f
        ty = drawTextTop(canvas, episode, episodePaint, MARGIN, ty)
        if (captionLines.isNotEmpty()) {
            ty += 24f
            for (line in captionLines) ty = drawTextTop(canvas, line, captionPaint, MARGIN, ty)
        }
        if (hasProgress) {
            ty += 36f
            val track = RectF(MARGIN, ty, MARGIN + imageW, ty + PROGRESS_HEIGHT)
            canvas.drawRoundRect(track, PROGRESS_HEIGHT / 2f, PROGRESS_HEIGHT / 2f, fillPaint(accentSoft))
            val fraction = (meta.positionSeconds / meta.durationSeconds).toFloat().coerceIn(0f, 1f)
            if (fraction > 0f) {
                val fill = RectF(MARGIN, ty, MARGIN + imageW * fraction, ty + PROGRESS_HEIGHT)
                canvas.drawRoundRect(fill, PROGRESS_HEIGHT / 2f, PROGRESS_HEIGHT / 2f, fillPaint(accent))
            }
            ty += PROGRESS_HEIGHT
        }
        ty += 18f
        val baseline = ty - maxOf(footerPaint.fontMetrics.ascent, watermarkPaint.fontMetrics.ascent)
        val percent = ((meta.positionSeconds / meta.durationSeconds).coerceIn(0.0, 1.0) * 100).roundToInt()
        val timeText = "${PlayerViewModel.formatTime(meta.positionSeconds)} / " +
            "${PlayerViewModel.formatTime(meta.durationSeconds)} · 已看 $percent%"
        canvas.drawText(timeText, MARGIN, baseline, footerPaint)
        val watermark = "🌸 NagomiAni"
        canvas.drawText(watermark, CARD_WIDTH - MARGIN - watermarkPaint.measureText(watermark), baseline, watermarkPaint)
        return out
    }

    /** 内容帧：圆角裁剪 + 粉描边（NagomiCard 1dp 15% 粉线的放大复刻） */
    private fun drawRoundedImage(canvas: Canvas, bmp: Bitmap, left: Float, top: Float, w: Float, h: Float) {
        val dst = RectF(left, top, left + w, top + h)
        val path = Path().apply { addRoundRect(dst, IMAGE_CORNER, IMAGE_CORNER, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restoreToCount(save)
        val stroke = RectF(dst).apply { inset(HAIRLINE_WIDTH / 2f, HAIRLINE_WIDTH / 2f) }
        canvas.drawRoundRect(stroke, IMAGE_CORNER, IMAGE_CORNER, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = HAIRLINE_WIDTH
            color = hairline
        })
    }

    private fun drawTextTop(canvas: Canvas, text: String, paint: TextPaint, x: Float, top: Float): Float {
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
        return top + lineHeight(paint)
    }

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.descent - paint.fontMetrics.ascent

    private fun textPaint(size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun ellipsize(text: String, paint: TextPaint): String =
        TextUtils.ellipsize(text, paint, CARD_WIDTH - MARGIN * 2, TruncateAt.END).toString()
}
