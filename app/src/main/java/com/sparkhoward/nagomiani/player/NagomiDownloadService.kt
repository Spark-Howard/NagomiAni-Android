package com.sparkhoward.nagomiani.player

import android.app.Notification
import android.content.Context
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.sparkhoward.nagomiani.core.download.DownloadUtil

/** 番剧离线缓存下载服务（前台通知显示进度；重启后由 PlatformScheduler 恢复队列） */
class NagomiDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
) {
    override fun getDownloadManager(): DownloadManager = DownloadUtil.downloadManager(this)

    override fun getScheduler(): Scheduler? = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, action: Int): Notification {
        // 通知标题给出"在缓存什么"：番名 + 集号 + 进度 + 排队数（media3 默认只显示第一条且不含集号）
        val downloading = downloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val message = downloading.firstOrNull()?.let { first ->
            buildString {
                append("正在缓存 ", DownloadUtil.displayTitleOf(first),
                    " 第", first.request.id.substringAfterLast(':'), "集 ",
                    first.percentDownloaded.toInt().coerceIn(0, 100), "%")
                if (downloading.size > 1) append(" 等", downloading.size, "集")
                val queued = downloading.count { it.state == Download.STATE_QUEUED }
                if (queued > 0) append(" · 排队 ", queued, " 集")
            }
        }
        return DownloadNotificationHelper(this, CHANNEL_ID).buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            message,
            downloads,
            action,
        )
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val JOB_ID = 1
        const val CHANNEL_ID = "nagomi_download"

        fun sendAddDownload(context: Context, request: DownloadRequest, foreground: Boolean) =
            DownloadService.sendAddDownload(context, NagomiDownloadService::class.java, request, foreground)

        fun sendRemoveDownload(context: Context, id: String, foreground: Boolean) =
            DownloadService.sendRemoveDownload(context, NagomiDownloadService::class.java, id, foreground)
    }
}
