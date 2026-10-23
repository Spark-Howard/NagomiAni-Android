package com.sparkhoward.nagomiani.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sparkhoward.nagomiani.R
import java.util.concurrent.TimeUnit

/**
 * 小部件后台刷新（WorkManager）：不开应用也能更新「今日新番」与「观看统计」。
 * 之前的两条路都依赖应用进程活着：updatePeriodMillis 系统回调在各省电策略下普遍被吞，
 * 刷新按钮的广播在 ROM 冻结应用后会被挂起、直到下次打开应用才执行——用户看到的正是这个。
 * 周期 Worker 在后台进程直接拉数据并 updateAppWidget；任务持久化在 WorkManager 库里，
 * 重启后依然生效。激进 ROM 的深度冻结仍会推迟执行（需给 App 加自启动/无限制白名单）。
 */
object WidgetRefresher {

    /** 刷新所有已添加的小部件实例；两类都没添加时不发网络请求 */
    suspend fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val todayIds = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        if (todayIds.isNotEmpty()) {
            val result = WidgetRepo.fetchToday(context)
            todayIds.forEach { id -> manager.updateAppWidget(id, TodayWidgetProvider.buildViews(context, id, result)) }
            manager.notifyAppWidgetViewDataChanged(todayIds, R.id.widget_today_list)
        }
        val statsIds = manager.getAppWidgetIds(ComponentName(context, StatsWidgetProvider::class.java))
        if (statsIds.isNotEmpty()) {
            val snapshot = WidgetRepo.statsOnce()
            val bars = StatsWidgetProvider.drawWeekBars(context, snapshot.week)
            statsIds.forEach { id -> manager.updateAppWidget(id, StatsWidgetProvider.buildViews(context, id, snapshot.summary, bars)) }
        }
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        WidgetRefresher.refreshAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "widget_refresh"
        private const val ONCE_NAME = "widget_refresh_now"

        /** 周期 1h + 联网约束：JST 跨天（东八区 23 点）最迟 1h 内滚到新的一天 */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** 立即补一次（刷新按钮/进程启动时；REPLACE 防连点堆积） */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
