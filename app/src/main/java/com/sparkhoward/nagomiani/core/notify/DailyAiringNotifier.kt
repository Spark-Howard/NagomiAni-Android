package com.sparkhoward.nagomiani.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.MainActivity
import com.sparkhoward.nagomiani.R
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.bangumi.BangumiAuth
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.online.WeeklyCalendar
import com.sparkhoward.nagomiani.ui.widget.WidgetIntents
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 今日更新提醒（纯官方数据：放送表 × 在看收藏，与已删除的片源集数比对无关系）：
 * JST 今天的 calendar 条目与「在看」收藏按 subject id 交叉，命中则发一条系统通知
 * （每天最多一次，JST 日期串做基线）。未登录 / 开关关闭 / 通知权限未授予全部静默跳过。
 */
object DailyAiringNotifier {

    const val CHANNEL_ID = "daily_airing"
    private const val WORK_NAME = "daily_airing_check"
    private const val NOTIFICATION_ID = 2001

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** App 启动时调用：注册每日定时检查（幂等，KEEP 策略不重置周期） */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyAiringWorker>(24, TimeUnit.HOURS)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** App 启动时补一次检查（定时任务落在深夜时的白昼兜底；日期基线保证同天只响一次） */
    fun checkNow(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<DailyAiringWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("${WORK_NAME}_now", androidx.work.ExistingWorkPolicy.KEEP, request)
    }

    /** 通知权限是否已授予（API 33+ 运行时权限；低版本看渠道开关） */
    fun notificationsEnabled(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** 发通知（点击进搜索页，周历定位今天）；返回是否真的发出（无权限 = false，基线不记，授权后当天还能补提醒） */
    fun notifyAiring(context: Context, titles: List<String>): Boolean {
        if (!notificationsEnabled(context)) return false
        val text = buildString {
            titles.take(5).forEachIndexed { index, title ->
                if (index > 0) append("、")
                append("《$title》")
            }
            if (titles.size > 5) append(" 等 ${titles.size} 部")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(WidgetIntents.route(context, "search"))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }
}

/** 每日检查 Worker：放送表 × 在看收藏交叉（Worker 里 AppGraph.store 可直接用，Application 先于 Worker 初始化） */
class DailyAiringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = AppGraph.store
        if (!store.dailyAiringEnabled.first()) return Result.success()

        // JST 日期串做基线（与放送表同口径，跨天时段不错位）
        val dateKey = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()
        if (store.dailyAiringLastDateOnce() == dateKey) return Result.success() // 今天已提醒过

        val titles = coroutineScope {
            try {
                val auth = BangumiAuth(context)
                if (!auth.isLoggedIn()) return@coroutineScope emptyList()
                val authed = BangumiApi { auth.refreshIfNeeded().accessToken }
                val me = authed.me()
                val username = me.username.ifBlank { me.id.toString() }
                val watching = authed.allCollections(username, CollectionType.DOING)
                    .map { it.subjectId }.toHashSet()
                val today = LocalDate.now(ZoneId.of("Asia/Tokyo"))
                WeeklyCalendar.slots(today, authed.calendar())
                    .firstOrNull { it.isToday }
                    ?.day?.items
                    ?.filter { it.id in watching }
                    ?.map { it.displayName }
                    .orEmpty()
            } catch (e: Exception) {
                android.util.Log.w("DailyAiring", "check failed", e)
                emptyList()
            }
        }
        if (titles.isNotEmpty() && DailyAiringNotifier.notifyAiring(context, titles)) {
            // 只有真的发出去了才记基线——权限未授予时跳过，授权后当天还能补提醒
            store.setDailyAiringLastDate(dateKey)
        }
        return Result.success()
    }
}
