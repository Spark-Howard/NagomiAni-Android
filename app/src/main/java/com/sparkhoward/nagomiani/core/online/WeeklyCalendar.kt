package com.sparkhoward.nagomiani.core.online

import com.sparkhoward.nagomiani.core.model.CalendarDay
import java.time.LocalDate

/**
 * 放送周历（搜索页）：Bangumi /calendar 是每周放送表——每部番固定星期几更新，
 * 表面内容不随日期变，但"哪天更新"以今天为锚每天向前滚动。
 * 展示窗口 = 今天往前 7 天 + 往后 7 天（近一周已放送 + 未来一周待放送，共 15 槽），
 * 按日期升序排列；UI 初始滚动定位到「今天」——打开即见今天的新番，上滑看近几天
 * 更新过的、下滑看未来几天待放的。
 * 天头标签带真实日期（今天 · 1/1 周一 / 昨天 · … / 12/30 周六），杜绝裸星期歧义。
 */
object WeeklyCalendar {

    const val BACK_DAYS = 7
    const val FORWARD_DAYS = 7

    /** 周历槽位：isPast 供 UI 弱化已放送的天头（今天高亮、未来常规、过去次级色） */
    data class Slot(
        val date: LocalDate,
        val label: String,
        val isToday: Boolean,
        val isPast: Boolean,
        val day: CalendarDay,
    )

    /** 近 7 天 + 未来 7 天窗口（日期升序）；/calendar 缺某天（接口异常）时该槽位跳过 */
    fun slots(today: LocalDate, days: List<CalendarDay>): List<Slot> = buildList {
        for (i in -BACK_DAYS..FORWARD_DAYS) {
            val date = today.plusDays(i.toLong())
            val id = (((today.dayOfWeek.value - 1 + i) % 7 + 7) % 7) + 1 // 1=周一…7=周日（负数安全取模）
            days.firstOrNull { it.weekday.id == id }?.let {
                add(Slot(date, label(date, today), i == 0, i < 0, it))
            }
        }
    }

    /** 天头标签：今天/昨天/明天点明 + 「M/d 周X」，日期始终在场消除歧义 */
    fun label(date: LocalDate, today: LocalDate): String {
        val weekday = "周" + "一二三四五六日"[date.dayOfWeek.value - 1]
        val md = "${date.monthValue}/${date.dayOfMonth}"
        return when (date) {
            today -> "今天 · $md $weekday"
            today.plusDays(1) -> "明天 · $md $weekday"
            today.minusDays(1) -> "昨天 · $md $weekday"
            else -> "$md $weekday"
        }
    }
}
