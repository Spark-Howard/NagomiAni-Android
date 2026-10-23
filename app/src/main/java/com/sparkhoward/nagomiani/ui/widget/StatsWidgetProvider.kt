package com.sparkhoward.nagomiani.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.sparkhoward.nagomiani.R
import com.sparkhoward.nagomiani.core.history.HistoryStats
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 观看统计小部件（4×2）：三列概览（总时长/看过番数/看完集数）+ 近 7 日柱状图。
 * 数据本地 DataStore（无网络），goAsync + IO 读一次即可；
 * 柱状图 Canvas 位图直接引用 Compose 侧 NagomiColors（与应用内 WeekBars 同视觉）。
 * 点任意处 → 历史页。
 */
class StatsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.sparkhoward.nagomiani.widget.STATS_REFRESH"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 渲染单个实例的 RemoteViews——onUpdate 与 WidgetRefresher 后台刷新共用 */
        fun buildViews(context: Context, appWidgetId: Int, summary: HistoryStats.Summary, bars: Bitmap): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_stats)
            views.setTextViewText(R.id.widget_stats_total, HistoryStats.formatDuration(summary.totalSeconds))
            views.setTextViewText(R.id.widget_stats_shows, "${summary.showCount} 部")
            views.setTextViewText(R.id.widget_stats_finished, "${summary.finishedEpisodes} 集")
            views.setImageViewBitmap(R.id.widget_stats_bars, bars)
            views.setOnClickPendingIntent(R.id.widget_stats_root, WidgetIntents.route(context, "history"))
            val refresh = Intent(context, StatsWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            views.setOnClickPendingIntent(
                R.id.widget_stats_refresh,
                android.app.PendingIntent.getBroadcast(
                    context, appWidgetId, refresh,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return views
        }

        /** 近 7 日迷你柱状图：透明底位图（深浅色卡片上都成立），今天樱粉高亮、其余 25% 粉圆角柱 */
        fun drawWeekBars(context: Context, week: List<HistoryStats.DaySlice>): Bitmap {
            val density = context.resources.displayMetrics.density
            val w = (240 * density).toInt().coerceAtLeast(1)
            val h = (52 * density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val maxSeconds = HistoryStats.barScale(week)
            val slot = w / 7f
            val barWidth = slot * 0.5f
            val minBar = 3 * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            week.forEachIndexed { index, slice ->
                val frac = if (maxSeconds > 0) (slice.seconds / maxSeconds).toFloat() else 0f
                val barHeight = when {
                    frac > 0f -> (h * frac).coerceAtLeast(minBar)
                    else -> minBar * 0.6f // 空档日也画一条浅浅的基线
                }
                val cx = slot * index + slot / 2f
                val rect = RectF(cx - barWidth / 2f, h - barHeight, cx + barWidth / 2f, h.toFloat())
                paint.color = if (slice.isToday) NagomiColors.accent.toArgb()
                else NagomiColors.accent.copy(alpha = 0.25f).toArgb()
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
            }
            return bitmap
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                val snapshot = WidgetRepo.statsOnce()
                val bars = drawWeekBars(context, snapshot.week)
                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(id, buildViews(context, id, snapshot.summary, bars))
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            // 手动刷新交 WorkManager：不受广播 10s 窗口限制，进程被冻结后任务也会在解冻时补跑
            WidgetRefreshWorker.refreshNow(context.applicationContext)
        } else {
            super.onReceive(context, intent)
        }
    }
}
