package com.sparkhoward.nagomiani.core.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.time.ZoneId

/**
 * 系统日历写入（CalendarContract）：追番放送 → 全天每周重复事件。
 * 权限（READ_CALENDAR + WRITE_CALENDAR）由 UI 层先行请求；重复导出按 subjectId upsert
 * （先删旧事件再插，天然防重复），并删除「已不在本次清单里」的旧导出——导出即与在看列表同步。
 */
object CalendarExport {

    data class Outcome(val calendarName: String, val written: Int, val eventIds: Map<Int, Long>)

    /** 写入全部事件计划，返回最终 subjectID → eventId 映射（调用方存 AppStore 供下次 upsert）。
     *  无可写日历抛 IllegalStateException；ContentResolver 是 IPC，调用方需在 IO 线程执行 */
    fun export(
        context: Context,
        plans: List<CalendarExportPlanner.EventPlan>,
        existing: Map<Int, Long>,
    ): Outcome {
        val resolver = context.contentResolver
        val (calendarId, calendarName) = firstWritableCalendar(context)
            ?: throw IllegalStateException("未找到可写入的系统日历")

        val eventIds = HashMap(existing)
        // 清掉已不在在看清单里的旧导出（取消在看后重新导出，日历同步删除）
        val planIds = plans.map { it.subjectId }.toHashSet()
        for ((subjectId, eventId) in existing) {
            if (subjectId !in planIds) {
                resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
                eventIds.remove(subjectId)
            }
        }
        var written = 0
        for (plan in plans) {
            // upsert：同一番先删旧事件再插（标题/RRULE 变更随之同步）
            eventIds[plan.subjectId]?.let {
                resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, it), null, null)
            }
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, plan.title)
                put(Events.DESCRIPTION, "来自 NagomiAni 追番放送表\nhttps://bgm.tv/subject/${plan.subjectId}")
                put(Events.DTSTART, plan.startDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                // 全天每周重复：RFC 5545 重复事件用 DURATION 而非 DTEND
                put(Events.ALL_DAY, 1)
                put(Events.DURATION, "P1D")
                put(Events.RRULE, plan.rrule)
                put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            }
            val uri = resolver.insert(Events.CONTENT_URI, values) ?: continue
            eventIds[plan.subjectId] = ContentUris.parseId(uri)
            written++
        }
        return Outcome(calendarName, written, eventIds)
    }

    /** 第一个可写的可见日历（contributor 及以上访问级别） */
    private fun firstWritableCalendar(context: Context): Pair<Long, String>? {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.VISIBLE,
        )
        context.contentResolver.query(Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Calendars._ID)
            val nameCol = c.getColumnIndexOrThrow(Calendars.CALENDAR_DISPLAY_NAME)
            val accessCol = c.getColumnIndexOrThrow(Calendars.CALENDAR_ACCESS_LEVEL)
            val visibleCol = c.getColumnIndexOrThrow(Calendars.VISIBLE)
            while (c.moveToNext()) {
                if (c.getInt(visibleCol) != 1) continue
                if (c.getInt(accessCol) < Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                return c.getLong(idCol) to (c.getString(nameCol) ?: "日历")
            }
        }
        return null
    }
}
