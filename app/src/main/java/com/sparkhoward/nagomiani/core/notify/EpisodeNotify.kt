package com.sparkhoward.nagomiani.core.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.MainActivity
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.maccms.MacCMSApi
import com.sparkhoward.nagomiani.core.maccms.OnlineRepo
import com.sparkhoward.nagomiani.core.online.WeeklyAggregator
import com.sparkhoward.nagomiani.core.store.AppStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 追番更新提醒：WorkManager 每日一次比对云端番库各片源的总集数，
 * 出现比「已看基线」和「已通知基线」都多的新集批次 → 发系统通知（点按回番库页）。
 * 两份基线独立：通知不推进「已看基线」（番库角标行为不变），用户展开番库才视为看过。
 */
object EpisodeNotify {
    const val CHANNEL_ID = "episode_updates"
    const val EXTRA_OPEN_TAB = "openTab"
    const val OPEN_TAB_LIBRARY = "library"

    private const val UNIQUE_WORK = "episode-update-check"

    /** 应用启动时调用：注册每日定时检查（KEEP：已注册不重排，重复调用幂等） */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<EpisodeNotifyWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 通知点按意图：回番库页（新实例走 onCreate、已存在走 onNewIntent，两路统一进 NavEvents） */
    internal fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TAB, OPEN_TAB_LIBRARY)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/**
 * 每日更新检查 Worker。站点解析与番库页 buildCloudRows 同路（search → episodes）；
 * 单站失败静默跳过等下个周期重试，不能让一部坏源毁掉整轮提醒。
 * 部分国产 ROM 会推迟后台任务——不弹窗索要电池白名单，能提醒多少算多少。
 */
class EpisodeNotifyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /** 一次检测到的更新：entry=番库条目，totalEps=片源当前总集数，newEps=新增数，latestAired=已开播的最新集号 */
    data class Update(val entry: AppStore.CloudEntry, val totalEps: Int, val newEps: Int, val latestAired: Int)

    override suspend fun doWork(): Result {
        val store = AppGraph.store
        if (!store.updateNotifyEnabled.first()) return Result.success()
        val entries = store.cloudLibraryOnce().take(MAX_CHECK_ENTRIES)
        if (entries.isEmpty()) return Result.success()
        val repo = OnlineRepo()
        val notified = store.lastNotifiedCountsOnce()

        val updates = ArrayList<Update>()
        for (entry in entries) {
            // 无基线不判新（lastSeenEpisodeCount=0 表示从未展开/播种过，与番库角标同语义）——
            // 否则新收藏的番会被首轮检查误报成「新增 N 集」
            if (entry.lastSeenEpisodeCount <= 0) continue
            val site = repo.siteURLForProvider(entry.providerID) ?: continue
            val eps = runCatching {
                MacCMSApi(site).search(entry.title).firstOrNull()?.let { repo.episodes(it) }
            }.getOrNull() ?: continue
            val total = eps.size
            if (total <= entry.lastSeenEpisodeCount) continue
            if (total <= (notified[entry.seriesKey] ?: entry.lastSeenEpisodeCount)) continue

            // 「更新到第 N 集」按官方放送日滤掉未开播的占位集（站源常提前挂先行/错标内容）；
            // Bangumi 分集表是公开端点，无需登录；查询失败时退回原始最大集号
            var latestAired = eps.maxOfOrNull { it.number } ?: total
            store.bindingSubjectID(entry.seriesKey)?.let { bound ->
                runCatching {
                    val unaired = WeeklyAggregator.unairedAirdates(
                        BangumiApi(tokenProvider = { null }).allEpisodes(bound),
                        LocalDate.now(ZoneId.of("Asia/Tokyo")),
                    ).keys
                    eps.filter { it.number !in unaired }.maxOfOrNull { it.number }?.let { latestAired = it }
                }
            }
            updates += Update(entry, total, total - entry.lastSeenEpisodeCount, latestAired)
        }
        if (updates.isEmpty()) return Result.success()

        // 先记「已通知基线」再发：同一批新集只提醒一次（无通知权限时也不每日重复打扰，
        // 之后的新批次照常提醒）
        store.recordNotifiedCounts(updates.associate { it.entry.seriesKey to it.totalEps })
        postNotifications(updates)
        return Result.success()
    }

    private fun postNotifications(updates: List<Update>) {
        val context = applicationContext
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (updates.size > MAX_INDIVIDUAL_NOTIFICATIONS) {
            // 更新条数多时合并成一条摘要，避免通知栏刷屏
            val names = updates.joinToString("\n") { "《${it.entry.title}》· 新增 ${it.newEps} 集" }
            manager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification(context, updates.size, names))
            return
        }
        updates.forEachIndexed { index, update ->
            manager.notify(NOTIFICATION_ID_BASE + index, updateNotification(context, update))
        }
    }

    private fun updateNotification(context: Context, update: Update) =
        NotificationCompat.Builder(context, EpisodeNotify.CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setColor(ACCENT)
            .setContentTitle("《${update.entry.title}》有更新")
            .setContentText("更新到第 ${update.latestAired} 集，新增 ${update.newEps} 集")
            .setContentIntent(EpisodeNotify.contentIntent(context))
            .setAutoCancel(true)
            .build()

    private fun summaryNotification(context: Context, count: Int, names: String) =
        NotificationCompat.Builder(context, EpisodeNotify.CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setColor(ACCENT)
            .setContentTitle("追番更新")
            .setContentText("$count 部追的番有更新")
            .setStyle(NotificationCompat.BigTextStyle().bigText(names))
            .setContentIntent(EpisodeNotify.contentIntent(context))
            .setAutoCancel(true)
            .build()

    companion object {
        /** 单轮最多检查的番库条目数（Worker 有运行时长上限，长列表下个周期自然轮到） */
        private const val MAX_CHECK_ENTRIES = 50
        private const val MAX_INDIVIDUAL_NOTIFICATIONS = 3
        /** 通知 ID 固定高位段：与下载服务前台通知（ID=1）等系统占用区隔开 */
        private const val NOTIFICATION_ID_BASE = 1_000_000
        private const val SUMMARY_NOTIFICATION_ID = 1_000_001
        private const val ACCENT = 0xFFEC6A88.toInt() // 樱粉 accent，与 App 主题一致
    }
}
