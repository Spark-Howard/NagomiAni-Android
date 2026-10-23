package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.model.CalendarDay
import com.sparkhoward.nagomiani.core.model.CalendarWeekday
import com.sparkhoward.nagomiani.core.online.WeeklyCalendar
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeeklyCalendarTest {

    // 2024-01-01 = 周一，2023-12-25 = 周一，2024-01-07 = 周日（固定锚点）
    private fun day(id: Int) = CalendarDay(weekday = CalendarWeekday(id = id))
    private val allDays = (1..7).map { day(it) }

    @Test
    fun slotsSpanBackAndForwardWeekChronologically() {
        val today = LocalDate.of(2024, 1, 1) // 周一
        val slots = WeeklyCalendar.slots(today, allDays)
        assertEquals(15, slots.size) // 近 7 + 今天 + 未来 7
        assertEquals(LocalDate.of(2023, 12, 25), slots.first().date)
        assertEquals(LocalDate.of(2024, 1, 8), slots.last().date)
        assertEquals(1, slots.first().day.weekday.id) // 12/25 也是周一：跨周同星期对齐
        assertEquals(1, slots.last().day.weekday.id)
    }

    @Test
    fun todaySitsAtIndex7WithSingleHighlight() {
        val today = LocalDate.of(2024, 1, 1)
        val slots = WeeklyCalendar.slots(today, allDays)
        assertEquals(7, slots.indexOfFirst { it.isToday }) // UI 初始滚动到的下标
        assertEquals(today, slots[7].date)
        assertEquals(1, slots.count { it.isToday })
        assertEquals(7, slots.count { it.isPast })
        assertEquals(7, slots.count { !it.isPast && !it.isToday })
    }

    @Test
    fun weekdayWrapAroundBothDirections() {
        val today = LocalDate.of(2024, 1, 7) // 周日
        val slots = WeeklyCalendar.slots(today, allDays)
        assertEquals(7, slots[7].day.weekday.id) // 今天周日
        assertEquals(6, slots[6].day.weekday.id) // 昨天周六（负数取模安全）
        assertEquals(LocalDate.of(2024, 1, 6), slots[6].date)
        assertEquals(1, slots[8].day.weekday.id) // 明天下周一（回绕）
        assertEquals(LocalDate.of(2024, 1, 8), slots[8].date)
    }

    @Test
    fun labelsCoverTodayYesterdayTomorrowAndPlainDates() {
        val today = LocalDate.of(2024, 1, 1)
        val slots = WeeklyCalendar.slots(today, allDays)
        assertEquals("今天 · 1/1 周一", slots[7].label)
        assertEquals("昨天 · 12/31 周日", slots[6].label)
        assertEquals("明天 · 1/2 周二", slots[8].label)
        assertEquals("12/26 周二", slots[1].label)
    }

    @Test
    fun twoDaySectionsAnchorTodayWithTomorrow() {
        val today = LocalDate.of(2024, 1, 1) // 周一
        val sections = WeeklyCalendar.twoDaySections(WeeklyCalendar.slots(today, allDays))
        assertEquals(8, sections.size) // 15 槽 → 今天档 + 前侧 4 档（余 1 单独）+ 后侧 3 档
        val todaySec = sections.first { sec -> sec.any { it.isToday } }
        assertEquals(2, todaySec.size)
        assertEquals(LocalDate.of(2024, 1, 1), todaySec[0].date) // 今天与明天同档
        assertEquals(LocalDate.of(2024, 1, 2), todaySec[1].date)
        // 升序贯穿所有档
        assertEquals(slotsDates(sections), slotsDates(sections).sorted())
    }

    @Test
    fun twoDaySectionsBeforeTodayPairFromNearestSide() {
        val today = LocalDate.of(2024, 1, 1)
        val sections = WeeklyCalendar.twoDaySections(WeeklyCalendar.slots(today, allDays))
        // 最早 1 天单独成档（7 天为奇数），其余两两一档
        assertEquals(1, sections[0].size)
        assertEquals(LocalDate.of(2023, 12, 25), sections[0][0].date)
        assertEquals(LocalDate.of(2023, 12, 26), sections[1][0].date)
        assertEquals(LocalDate.of(2023, 12, 27), sections[1][1].date)
    }

    private fun slotsDates(sections: List<List<com.sparkhoward.nagomiani.core.online.WeeklyCalendar.Slot>>) =
        sections.flatMap { it }.map { it.date.toEpochDay() }

    @Test
    fun missingDaysAreSkipped() {
        val today = LocalDate.of(2024, 1, 1)
        val slots = WeeklyCalendar.slots(today, listOf(day(1), day(3)))
        // 15 天窗口（-7..+7 含端点）：与今天同星期的出现 3 次（上周/今天/下周），
        // 其余星期 2 次；缺的天整槽跳过 → 周一 3 + 周三 2 = 5
        assertEquals(5, slots.size)
        assertEquals(LocalDate.of(2023, 12, 25), slots[0].date)
        assertEquals(LocalDate.of(2023, 12, 27), slots[1].date)
        assertEquals(LocalDate.of(2024, 1, 1), slots[2].date)
        assertEquals(LocalDate.of(2024, 1, 3), slots[3].date)
        assertEquals(LocalDate.of(2024, 1, 8), slots[4].date)
        assertEquals(1, slots.count { it.isToday })
    }
}
