package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.history.HistoryStats
import com.sparkhoward.nagomiani.core.store.AppStore.HistoryRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryStatsTest {

    private val zone = ZoneId.of("Asia/Tokyo") // 固定时区，测试不受运行环境左右

    // 2024-01-07 是周日；用它当「今天」
    private val today = LocalDate.of(2024, 1, 7)
    private val todayMillis = today.atStartOfDay(zone).plusHours(20).toInstant().toEpochMilli()
    private val yesterdayMillis = today.minusDays(1).atStartOfDay(zone).plusHours(21).toInstant().toEpochMilli()
    private val lastWeekMillis = today.minusDays(6).atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()

    private fun record(
        seriesKey: String,
        episode: Int,
        seconds: Double,
        at: Long,
        finished: Boolean = false,
        title: String = seriesKey,
    ) = HistoryRecord(
        seriesKey = seriesKey,
        providerID = "p",
        showID = seriesKey,
        showTitle = title,
        episodeNumber = episode,
        watchSeconds = seconds,
        finished = finished,
        firstWatchedAt = at,
        lastWatchedAt = at,
    )

    // MARK: - summarize

    @Test
    fun summarizeDedupsShowsAndCountsFinishedAndDays() {
        val records = listOf(
            record("a", 1, 1200.0, todayMillis),
            record("a", 2, 1200.0, todayMillis),
            record("b", 1, 600.0, yesterdayMillis, finished = true),
        )
        val s = HistoryStats.summarize(records, zone)
        assertEquals(3000.0, s.totalSeconds)
        assertEquals(2, s.showCount)
        assertEquals(3, s.episodeCount)
        assertEquals(1, s.finishedEpisodes)
        assertEquals(2, s.watchedDays)
    }

    @Test
    fun summarizeEmpty() {
        val s = HistoryStats.summarize(emptyList(), zone)
        assertEquals(0.0, s.totalSeconds)
        assertEquals(0, s.showCount)
        assertEquals(0, s.watchedDays)
    }

    // MARK: - last7Days

    @Test
    fun last7DaysBucketsLocalDatesAndKeepsEmptyDays() {
        val slices = HistoryStats.last7Days(
            listOf(record("a", 1, 600.0, todayMillis), record("b", 1, 300.0, lastWeekMillis)),
            today,
            zone,
        )
        assertEquals(7, slices.size)
        assertEquals(today, slices.last().date)
        assertTrue(slices.last().isToday)
        assertEquals(600.0, slices.last().seconds)
        // 6 天前的记录正好落在窗口第一格（窗口 = 今天-6 … 今天）
        assertEquals(300.0, slices.first().seconds)
        // 中间没有任何观看的天是 0（柱状图空档保留）
        assertEquals(0.0, slices[3].seconds)
    }

    // MARK: - yearReport

    @Test
    fun yearReportFiltersByYearAndRanksTopShows() {
        val inYearMillis = today.atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli()
        val prevYearMillis = today.minusDays(10).atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli() // 2023-12-28
        val records = listOf(
            record("a", 1, 3600.0, inYearMillis, title = "番A"),
            record("a", 2, 1800.0, inYearMillis, title = "番A"),
            record("b", 1, 7200.0, inYearMillis, finished = true, title = "番B"),
            record("c", 1, 999.0, prevYearMillis, title = "去年番"),
        )
        val report = HistoryStats.yearReport(records, 2024, zone)
        assertEquals(2024, report.year)
        assertEquals(12600.0, report.totalSeconds) // 只算 2024
        assertEquals(1, report.watchedDays)
        assertEquals(1, report.finishedEpisodes)
        assertEquals(2, report.topShows.size)
        assertEquals("番B", report.topShows[0].title) // 7200 > 5400
        assertEquals(2, report.topShows[1].episodeCount) // 同番集数合计
    }

    @Test
    fun availableYearsDescendingAndAlwaysContainsCurrentYear() {
        val prevYearMillis = today.minusDays(10).atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli()
        val years = HistoryStats.availableYears(
            listOf(record("a", 1, 60.0, prevYearMillis)),
            zone,
            currentYear = 2024,
        )
        assertEquals(listOf(2024, 2023), years)
    }

    // MARK: - dailySeconds / heatLevel（年度热力图）

    @Test
    fun dailySecondsAggregatesByDayAndFiltersYear() {
        val inYear = today.atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli() // 2024-01-07
        val otherYear = today.minusDays(10).atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli() // 2023-12-28
        val records = listOf(
            record("a", 1, 600.0, inYear),
            record("a", 2, 300.0, inYear),
            record("b", 1, 0.0, inYear, finished = true), // 回填记录：0 秒但算有记录
            record("c", 1, 999.0, otherYear),
        )
        val days = HistoryStats.dailySeconds(records, 2024, zone)
        assertEquals(1, days.size)
        val heat = days[today]!!
        assertEquals(900.0, heat.seconds)
        assertEquals(3, heat.count)
    }

    @Test
    fun heatLevelsZeroToFour() {
        val max = 1000.0
        assertEquals(0, HistoryStats.heatLevel(null, max))
        assertEquals(1, HistoryStats.heatLevel(HistoryStats.DayHeat(0.0, 1), max))
        assertEquals(2, HistoryStats.heatLevel(HistoryStats.DayHeat(100.0, 1), max))
        assertEquals(3, HistoryStats.heatLevel(HistoryStats.DayHeat(600.0, 1), max))
        assertEquals(4, HistoryStats.heatLevel(HistoryStats.DayHeat(900.0, 1), max))
        assertEquals(4, HistoryStats.heatLevel(HistoryStats.DayHeat(500.0, 1), 0.0)) // max=0 不除零
    }

    // MARK: - formatDuration

    @Test
    fun durationFormats() {
        assertEquals("0分钟", HistoryStats.formatDuration(0.0))
        assertEquals("<1分钟", HistoryStats.formatDuration(30.0))
        assertEquals("18分钟", HistoryStats.formatDuration((18 * 60 + 12).toDouble()))
        assertEquals("1小时", HistoryStats.formatDuration(3600.0))
        assertEquals("2小时24分", HistoryStats.formatDuration((2 * 3600 + 24 * 60 + 30).toDouble()))
    }
}
