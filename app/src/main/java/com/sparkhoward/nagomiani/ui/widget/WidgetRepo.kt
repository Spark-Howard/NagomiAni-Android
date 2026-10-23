package com.sparkhoward.nagomiani.ui.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.MainActivity
import com.sparkhoward.nagomiani.core.bangumi.BangumiApi
import com.sparkhoward.nagomiani.core.history.HistoryStats
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.core.online.WeeklyCalendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * 小部件数据仓库（无生命周期的 object，照 UpdateCenter 的自建 scope 先例由 Provider 的
 * goAsync 协程里调用）：今日放送走 Bangumi /calendar（JST 锚定，复用 WeeklyCalendar），
 * 失败回退 cacheDir 缓存；观看统计读本地 DataStore（HistoryStats 纯函数）。
 */
object WidgetRepo {

    @Serializable
    data class TodayCache(val dateKey: String, val dateLabel: String, val items: List<Subject>)

    /** fetchToday 的结果：fresh=false 表示网络失败、给的是缓存兜底 */
    data class TodayResult(val cache: TodayCache, val fresh: Boolean)

    data class StatsSnapshot(val summary: HistoryStats.Summary, val week: List<HistoryStats.DaySlice>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun cacheFile(context: Context) = File(context.cacheDir, "widget_today.json")

    fun readTodayCache(context: Context): TodayCache? = runCatching {
        json.decodeFromString<TodayCache>(cacheFile(context).readText())
    }.getOrNull()

    private fun writeTodayCache(context: Context, cache: TodayCache) {
        runCatching { cacheFile(context).writeText(json.encodeToString(cache)) }
    }

    /** JST 锚定「今天」（放送表按日本时区编排，与周历同口径） */
    fun todayKeys(): Pair<String, String> {
        val today = LocalDate.now(ZoneId.of("Asia/Tokyo"))
        val weekday = "周" + "一二三四五六日"[today.dayOfWeek.value - 1]
        return today.toString() to "${today.monthValue}/${today.dayOfMonth} $weekday"
    }

    /** 拉今日放送：成功写缓存；失败（网络/超时）回退上次缓存，再没有就空结果。
     *  超时 8s：goAsync 的广播窗口约 10s，要给序列化/写缓存留余量 */
    suspend fun fetchToday(context: Context): TodayResult {
        val (dateKey, dateLabel) = todayKeys()
        val fetched = try {
            kotlinx.coroutines.withTimeoutOrNull(8_000) {
                runCatching {
                    val days = BangumiApi { null }.calendar()
                    WeeklyCalendar.slots(LocalDate.now(ZoneId.of("Asia/Tokyo")), days)
                        .firstOrNull { it.isToday }?.day?.items.orEmpty()
                }.getOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fetched != null) {
            val cache = TodayCache(dateKey, dateLabel, fetched)
            writeTodayCache(context, cache)
            return TodayResult(cache, fresh = true)
        }
        val cached = readTodayCache(context) ?: TodayCache(dateKey, dateLabel, emptyList())
        return TodayResult(cached, fresh = false)
    }

    /** 观看统计（本地 DataStore 一次性读，纯函数聚合） */
    suspend fun statsOnce(): StatsSnapshot {
        val records = AppGraph.store.historyOnce().values
        return StatsSnapshot(
            summary = HistoryStats.summarize(records),
            week = HistoryStats.last7Days(records, LocalDate.now()),
        )
    }
}

/** 小部件/快捷方式共用的 deeplink 通道：route extra 由 MainActivity 解析导航 */
object WidgetIntents {

    const val EXTRA_ROUTE = "route"
    const val EXTRA_SUBJECT_ID = "subjectID"

    fun route(context: Context, route: String, subjectID: Int? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_ROUTE, route)
            subjectID?.let { putExtra(EXTRA_SUBJECT_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            (route + subjectID).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
