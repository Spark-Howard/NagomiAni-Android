package com.sparkhoward.nagomiani.core.online

import com.sparkhoward.nagomiani.core.model.CalendarDay
import java.time.LocalDate

/**
 * 放送周历（搜索页）：Bangumi /calendar 是每周放送表——每部番固定星期几更新，
 * 表面内容不随日期变，但"哪天更新"以今天为锚每天向前滚动。
 * 因此展示语义必须是「今天起未来一周」：今天 → 明天 → … → 6 天后，每天挂真实日期。
 * 旧版按"今天 → 前 6 天"回溯排序，裸"周X"标签会被读成未来几天（今天周六下面紧跟的
 * "周五/周四"实际是上周的，读成下周的），是歧义根因。
 */
object WeeklyCalendar {

    /** 周历槽位：date = 放送日真实日期（与 /calendar 的 JST 编排同区），label = 天头文案 */
    data class Slot(val date: LocalDate, val label: String, val isToday: Boolean, val day: CalendarDay)

    /** 重排为「今天 → 未来 6 天」并配日期与标签；/calendar 缺某天（接口异常）时该槽位跳过 */
    fun slots(today: LocalDate, days: List<CalendarDay>): List<Slot> = buildList {
        for (i in 0..6) {
            val date = today.plusDays(i.toLong())
            val id = ((today.dayOfWeek.value - 1 + i) % 7) + 1 // 1=周一…7=周日
            days.firstOrNull { it.weekday.id == id }?.let {
                add(Slot(date, label(date, today), i == 0, it))
            }
        }
    }

    /** 天头标签：今天/明天点明 + 「M/d 周X」，日期始终在场消除歧义 */
    fun label(date: LocalDate, today: LocalDate): String {
        val weekday = "周" + "一二三四五六日"[date.dayOfWeek.value - 1]
        val md = "${date.monthValue}/${date.dayOfMonth}"
        return when (date) {
            today -> "今天 · $md $weekday"
            today.plusDays(1) -> "明天 · $md $weekday"
            else -> "$md $weekday"
        }
    }
}
