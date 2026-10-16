package com.sparkhoward.nagomiani

import com.sparkhoward.nagomiani.core.model.CalendarDay
import com.sparkhoward.nagomiani.core.model.CalendarWeekday
import com.sparkhoward.nagomiani.core.online.WeeklyCalendar
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyCalendarTest {

    // 2024-01-01 = 周一，2024-01-07 = 周日（固定锚点）
    private fun day(id: Int) = CalendarDay(weekday = CalendarWeekday(id = id))

    @Test
    fun slotsRunForwardFromTodayWithRealDates() {
        val today = LocalDate.of(2024, 1, 1) // 周一
        val slots = WeeklyCalendar.slots(today, (1..7).map { day(it) })
        assertEquals(7, slots.size)
        assertEquals(LocalDate.of(2024, 1, 1), slots[0].date)
        assertEquals(1, slots[0].day.weekday.id)
        assertEquals(LocalDate.of(2024, 1, 7), slots[6].date)
        assertEquals(7, slots[6].day.weekday.id)
    }

    @Test
    fun slotsWrapAroundWeek() {
        val today = LocalDate.of(2024, 1, 7) // 周日
        val slots = WeeklyCalendar.slots(today, (1..7).map { day(it) })
        // 今天周日之后是下周一：id 顺序 7,1,2,…,6，日期 1/7 → 1/13
        assertEquals(7, slots[0].day.weekday.id)
        assertEquals(1, slots[1].day.weekday.id)
        assertEquals(LocalDate.of(2024, 1, 8), slots[1].date)
        assertEquals(LocalDate.of(2024, 1, 13), slots[6].date)
    }

    @Test
    fun labelsMarkTodayTomorrowAndCarryDates() {
        val today = LocalDate.of(2024, 1, 1)
        val slots = WeeklyCalendar.slots(today, (1..7).map { day(it) })
        assertEquals("今天 · 1/1 周一", slots[0].label)
        assertEquals("明天 · 1/2 周二", slots[1].label)
        assertEquals("1/7 周日", slots[6].label)
        assertTrue(slots[0].isToday)
        assertTrue(slots.drop(1).none { it.isToday })
    }

    @Test
    fun missingDaysAreSkipped() {
        val today = LocalDate.of(2024, 1, 1)
        val slots = WeeklyCalendar.slots(today, listOf(day(1), day(3)))
        assertEquals(2, slots.size)
        assertEquals(LocalDate.of(2024, 1, 1), slots[0].date)
        assertEquals(LocalDate.of(2024, 1, 3), slots[1].date)
    }
}
