package com.sparkhoward.nagomiani.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.sparkhoward.nagomiani.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 今日新番小部件（4×3）：头部（日期 + 部数 + 手动刷新）+ 今日放送列表。
 * 数据拉取在 goAsync 的 IO 协程里完成（广播窗口约 10s，网络超时 10s 兜住），
 * 列表行由 TodayListService 渲染（读 fetchToday 落好的缓存文件，onDataSetChanged 触发重载）。
 * 点行 → 条目详情（fillInIntent 合 subjectID）；点头部 → 搜索页（周历已定位今天）。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.sparkhoward.nagomiani.widget.TODAY_REFRESH"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                val result = WidgetRepo.fetchToday(context)
                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(id, buildViews(context, id, result))
                }
                if (appWidgetIds.isNotEmpty()) {
                    manager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_today_list)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            onUpdate(context, AppWidgetManager.getInstance(context), ids)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun buildViews(context: Context, appWidgetId: Int, result: WidgetRepo.TodayResult): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today)
        val cache = result.cache
        views.setTextViewText(R.id.widget_today_date, cache.dateLabel)
        views.setTextViewText(R.id.widget_today_count, if (cache.items.isEmpty()) "" else "${cache.items.size} 部")
        views.setTextViewText(
            R.id.widget_today_empty,
            if (result.fresh) context.getString(R.string.widget_today_empty)
            else context.getString(R.string.widget_today_failed),
        )

        // 头部/根级点击 → 搜索页；行点击经模板 PendingIntent（route=detail）+ fillInIntent(subjectID) → 详情
        views.setOnClickPendingIntent(R.id.widget_today_root, WidgetIntents.route(context, "search"))
        views.setOnClickPendingIntent(R.id.widget_today_header, WidgetIntents.route(context, "search"))
        views.setOnClickPendingIntent(R.id.widget_today_date, WidgetIntents.route(context, "search"))
        views.setPendingIntentTemplate(R.id.widget_today_list, WidgetIntents.route(context, "detail"))

        val refresh = Intent(context, TodayWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        }
        views.setOnClickPendingIntent(
            R.id.widget_today_refresh,
            android.app.PendingIntent.getBroadcast(
                context, appWidgetId, refresh,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val service = Intent(context, TodayListService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) // 多实例小部件的 adapter intent 需可区分
        }
        views.setRemoteAdapter(R.id.widget_today_list, service)
        views.setEmptyView(R.id.widget_today_list, R.id.widget_today_empty)
        return views
    }
}

/** 列表数据源：读缓存文件（fetchToday 写入），notifyAppWidgetViewDataChanged 后 onDataSetChanged 重载 */
class TodayListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TodayFactory(applicationContext)
}

private class TodayFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<com.sparkhoward.nagomiani.core.model.Subject> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        items = WidgetRepo.readTodayCache(context)?.items.orEmpty()
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today_item)
        val subject = items.getOrNull(position) ?: return views
        views.setTextViewText(R.id.widget_today_index, "${position + 1}")
        views.setTextViewText(R.id.widget_today_title, subject.displayName)
        val eps = subject.episodeCount
        val score = subject.rating?.score
        val sub = buildString {
            if (eps != null && eps > 0) append("$eps 集")
            if (score != null && score > 0) {
                if (isNotEmpty()) append(" · ")
                append(String.format(java.util.Locale.US, "评分 %.1f", score))
            }
        }
        views.setTextViewText(R.id.widget_today_sub, sub)
        views.setOnClickFillInIntent(
            R.id.widget_today_item,
            Intent().putExtra(WidgetIntents.EXTRA_SUBJECT_ID, subject.id),
        )
        return views
    }
}
