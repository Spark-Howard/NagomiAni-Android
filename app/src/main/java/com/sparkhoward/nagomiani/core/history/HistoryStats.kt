package com.sparkhoward.nagomiani.core.history

import com.sparkhoward.nagomiani.core.store.AppStore.HistoryRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 观看统计（纯函数，JVM 可测）。时间分桶一律用户本地时区——「我什么时候看的」。
 * 口径：总时长=Σ watchSeconds（回填的旧记录为 0 不虚构）；看完=本地 finished 标记（90%/EOF）。
 */
object HistoryStats {

    data class Summary(
        val totalSeconds: Double,
        val showCount: Int,
        val episodeCount: Int,
        val finishedEpisodes: Int,
        val watchedDays: Int,
    )

    /** 单日切片（近 7 日柱状图用） */
    data class DaySlice(val date: LocalDate, val seconds: Double, val isToday: Boolean)

    /** 年度报告 */
    data class YearReport(
        val year: Int,
        val totalSeconds: Double,
        val watchedDays: Int,
        val finishedEpisodes: Int,
        val topShows: List<TopShow>,
    )

    data class TopShow(
        val seriesKey: String,
        val title: String,
        val coverURL: String?,
        val seconds: Double,
        val episodeCount: Int,
    )

    fun summarize(records: Collection<HistoryRecord>, zone: ZoneId = ZoneId.systemDefault()): Summary = Summary(
        totalSeconds = records.sumOf { it.watchSeconds },
        showCount = records.map { it.seriesKey }.distinct().size,
        episodeCount = records.size,
        finishedEpisodes = records.count { it.finished },
        watchedDays = records.map { dayOf(it.lastWatchedAt, zone) }.distinct().size,
    )

    /** 近 7 日（含今天）逐日观看秒数；无观看的天为 0——柱状图要画出空档 */
    fun last7Days(records: Collection<HistoryRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<DaySlice> {
        val byDay = HashMap<LocalDate, Double>()
        for (r in records) byDay.merge(dayOf(r.lastWatchedAt, zone), r.watchSeconds, Double::plus)
        return (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            DaySlice(date, byDay[date] ?: 0.0, date == today)
        }
    }

    /** 年度报告：该年内观看的记录聚合 + TOP 番（按时长，同番集数合计） */
    fun yearReport(records: Collection<HistoryRecord>, year: Int, zone: ZoneId = ZoneId.systemDefault()): YearReport {
        val inYear = records.filter { dayOf(it.lastWatchedAt, zone).year == year }
        val tops = inYear.groupBy { it.seriesKey }.values.map { shows ->
            val head = shows.maxBy { it.lastWatchedAt }
            TopShow(
                seriesKey = head.seriesKey,
                title = head.showTitle.ifBlank { "未知番剧" },
                coverURL = head.coverURL,
                seconds = shows.sumOf { it.watchSeconds },
                episodeCount = shows.size,
            )
        }.sortedByDescending { it.seconds }
        return YearReport(
            year = year,
            totalSeconds = inYear.sumOf { it.watchSeconds },
            watchedDays = inYear.map { dayOf(it.lastWatchedAt, zone) }.distinct().size,
            finishedEpisodes = inYear.count { it.finished },
            topShows = tops.take(3),
        )
    }

    /** 有记录的年份降序（年度报告的年份选择器） */
    fun availableYears(records: Collection<HistoryRecord>, zone: ZoneId = ZoneId.systemDefault(), currentYear: Int): List<Int> {
        val years = records.map { dayOf(it.lastWatchedAt, zone).year }.distinct() + currentYear
        return years.distinct().sortedDescending()
    }

    /** 年度热力图的单日格子：seconds=当日观看秒数；count=当日记录数（回填的 0 秒旧记录 count>0） */
    data class DayHeat(val seconds: Double, val count: Int)

    /** 全年逐日聚合（热力图）：key=该年有观看或记录的日期；当年外的记录忽略 */
    fun dailySeconds(records: Collection<HistoryRecord>, year: Int, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, DayHeat> {
        val byDay = HashMap<LocalDate, DayHeat>()
        for (r in records) {
            val date = dayOf(r.lastWatchedAt, zone)
            if (date.year != year) continue
            val old = byDay[date]
            byDay[date] = DayHeat((old?.seconds ?: 0.0) + r.watchSeconds, (old?.count ?: 0) + 1)
        }
        return byDay
    }

    /** 热力图色阶：0=无记录 / 1=有记录但 0 秒（回填旧数据）/ 2-4 按秒数三分 [maxSeconds]（低/中/高） */
    fun heatLevel(heat: DayHeat?, maxSeconds: Double): Int = when {
        heat == null -> 0
        heat.seconds <= 0.0 -> 1
        maxSeconds <= 0.0 -> 4
        else -> 2 + ((heat.seconds / maxSeconds) * 3).toInt().coerceIn(0, 2) // 2..4
    }

    /** 时长格式化："<1分钟" / "18分钟" / "2小时24分" */
    fun formatDuration(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0) return "0分钟"
        val minutes = floor(seconds / 60).toInt()
        if (minutes < 1) return "<1分钟"
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) if (m > 0) "${h}小时${m}分" else "${h}小时" else "${m}分钟"
    }

    /** 柱高刻度：方便 UI 把秒数映射为柱高（全 0 时返回 0 避免除零） */
    fun barScale(slices: List<DaySlice>): Double =
        slices.maxOfOrNull { it.seconds }?.takeIf { it > 0 } ?: 0.0

    /** 分钟取整展示（柱顶数值） */
    fun minutesOf(seconds: Double): Int = (seconds / 60).roundToInt()

    private fun dayOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
