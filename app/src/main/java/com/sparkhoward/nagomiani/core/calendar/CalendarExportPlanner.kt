package com.sparkhoward.nagomiani.core.calendar

import com.sparkhoward.nagomiani.core.model.CalendarDay
import com.sparkhoward.nagomiani.core.model.Subject
import java.time.LocalDate

/**
 * 追番放送日 → 系统日历全天重复事件的规划（纯函数可 JVM 单测，today 由调用方注入）。
 *
 * 口径：
 * - 数据 = 放送表 ×「在看」收藏按 subject id 交叉（与 DailyAiringWorker 同口径）——
 *   条目出现在放送表里本身就是「正在播」的信号，不做完结判定；
 * - 放送表只有「星期几」（/calendar 条目实测不带集数），事件一律做成
 *   **无限每周重复的全天事件**——不虚构时刻、不猜完结日期；
 * - 放送日以 JST 编排（与 WeeklyCalendar 同口径），startDay 用 JST 的「今天」推算——
 *   跨天时段本地日历里的「那一天」存在几小时的固有近似，全天事件不受时刻影响。
 */
object CalendarExportPlanner {

    /** weekday id（1=周一…7=周日，/calendar 口径，与 java.time.DayOfWeek.value 一致）→ RRULE BYDAY */
    private val BYDAY = arrayOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    data class EventPlan(
        val subjectId: Int,
        val title: String,
        /** 全天事件的那一天（未开播番=首集放送日；播出中=下一个更新日） */
        val startDay: LocalDate,
        /** 每周重复规则 FREQ=WEEKLY;BYDAY=…（无限重复） */
        val rrule: String,
    )

    /** 在看收藏 × 放送表交叉：只导出「正在播的追番」的每周放送日 */
    fun plans(days: List<CalendarDay>, watchingIds: Set<Int>, today: LocalDate): List<EventPlan> =
        days.flatMap { day ->
            day.items.filter { it.id in watchingIds }.mapNotNull { planFor(it, day.weekday.id, today) }
        }

    private fun planFor(subject: Subject, weekdayId: Int, today: LocalDate): EventPlan? {
        if (weekdayId !in 1..7) return null
        val airDate = subject.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // 未开播（airDate 在未来）：DTSTART 用首集日（它天然落在正确的星期几）；
        // 播出中：下一个该星期几（含今天——今天的更新今天就有事件）
        val start = if (airDate != null && !airDate.isBefore(today)) airDate else nextOccurrence(weekdayId, today)
        return EventPlan(
            subject.id,
            "《${subject.displayName}》更新",
            start,
            "FREQ=WEEKLY;BYDAY=${BYDAY[weekdayId - 1]}",
        )
    }

    /** 下一个（含今天）[weekdayId]（1=周一…7=周日）的日期 */
    fun nextOccurrence(weekdayId: Int, today: LocalDate): LocalDate =
        today.plusDays(((weekdayId - today.dayOfWeek.value + 7) % 7).toLong())
}
