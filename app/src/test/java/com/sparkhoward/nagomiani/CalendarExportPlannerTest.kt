package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.calendar.CalendarExportPlanner
import com.sparkhoward.nagomiani.core.model.CalendarDay
import com.sparkhoward.nagomiani.core.model.CalendarWeekday
import com.sparkhoward.nagomiani.core.model.Subject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 追番放送日 → 系统日历全天重复事件的规划纯逻辑（锚点 2024-01-01=周一） */
class CalendarExportPlannerTest {

    private val today = LocalDate.of(2024, 1, 1) // 周一

    private fun day(weekdayId: Int, vararg items: Subject) =
        CalendarDay(weekday = CalendarWeekday(id = weekdayId), items = items.toList())

    private fun subject(id: Int, eps: Int? = null, airDate: String? = null, name: String = "番剧$id") =
        Subject(id = id, name = name, eps = eps, airDate = airDate)

    @Test
    fun watchingCrossFiltersCalendarItems() {
        val plans = CalendarExportPlanner.plans(
            listOf(day(1, subject(1), subject(2))),
            watchingIds = setOf(2),
            today = today,
        )
        assertEquals(listOf(2), plans.map { it.subjectId })
        assertEquals("《番剧2》更新", plans[0].title)
    }

    @Test
    fun bydayMapsWeekdayId() {
        val p = CalendarExportPlanner.plans(listOf(day(7, subject(1))), setOf(1), today)[0]
        assertEquals("FREQ=WEEKLY;BYDAY=SU", p.rrule)
    }

    @Test
    fun airingTodayStartsToday() {
        // 下一个更新日含今天：今天的更新今天就有事件
        val p = CalendarExportPlanner.plans(listOf(day(1, subject(1))), setOf(1), today)[0]
        assertEquals(today, p.startDay)
    }

    @Test
    fun startDayWrapsAroundWeek() {
        // 今天周日、番在周一放 → 下一个周一是明天
        val sunday = LocalDate.of(2024, 1, 7)
        val p = CalendarExportPlanner.plans(listOf(day(1, subject(1))), setOf(1), sunday)[0]
        assertEquals(LocalDate.of(2024, 1, 8), p.startDay)
    }

    @Test
    fun unairedStartsAtAirDate() {
        // 未开播（airDate 在未来）：startDay = 首集放送日
        val p = CalendarExportPlanner.plans(
            listOf(day(2, subject(1, airDate = "2024-01-09"))), setOf(1), today,
        )[0]
        assertEquals(LocalDate.of(2024, 1, 9), p.startDay)
        assertEquals("FREQ=WEEKLY;BYDAY=TU", p.rrule)
    }

    @Test
    fun longRunningShowExportsInfiniteWeekly() {
        // 长青番（航海王式，airDate 多年前仍在播）：下一个更新日起无限每周重复
        val p = CalendarExportPlanner.plans(
            listOf(day(3, subject(1, airDate = "1999-10-20"))), setOf(1), today,
        )[0]
        assertEquals(LocalDate.of(2024, 1, 3), p.startDay) // 周一 → 周三
        assertEquals("FREQ=WEEKLY;BYDAY=WE", p.rrule)
    }

    @Test
    fun ignoresEpisodeCountsInInput() {
        // /calendar 条目实测不带集数：即使模型里带了 eps/total_episodes 也不用——
        // 不猜完结日期，一律无限重复（放送表里出现 = 正在播的信号）
        val p = CalendarExportPlanner.plans(
            listOf(day(1, subject(1, eps = 12, airDate = "2023-10-02"))), setOf(1), today,
        )[0]
        assertEquals(today, p.startDay)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", p.rrule)
    }

    @Test
    fun nextOccurrenceIncludesToday() {
        assertEquals(today, CalendarExportPlanner.nextOccurrence(1, today))
        // 周一 → 下一个周日是本周日（1/7）
        assertEquals(LocalDate.of(2024, 1, 7), CalendarExportPlanner.nextOccurrence(7, today))
    }
}
