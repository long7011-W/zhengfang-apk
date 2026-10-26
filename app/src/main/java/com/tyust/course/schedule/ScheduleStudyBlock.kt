package com.tyust.course.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** 早自习 / 晚自习：各自最多一条，可单独开关、改名、勾选星期。 */
enum class ScheduleStudyKind(
    val storageKey: String,
    val defaultName: String,
    val defaultStart: String,
    val defaultEnd: String,
    val defaultDays: Set<Int>
) {
    Morning("morning", "早自习", "06:54", "07:20", setOf(1, 2, 3, 4, 5)),
    Evening("evening", "晚自习", "18:20", "20:45", setOf(1, 2, 3, 4));

    companion object {
        fun of(key: String?): ScheduleStudyKind? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * 一段自习时间。`end` 不晚于 `start` 表示跨零点，整天算在开始那天。
 * [enabled] 只管课表是否显示；提醒跟随学期提醒总开关，没有单独的提醒开关。
 * `leadMinutes == null` 表示跟随全局默认提前量。
 */
data class ScheduleStudyBlock(
    val kind: ScheduleStudyKind,
    val enabled: Boolean = false,
    val name: String = kind.defaultName,
    val start: String = kind.defaultStart,
    val end: String = kind.defaultEnd,
    val days: Set<Int> = kind.defaultDays,
    val leadMinutes: Int? = null
) {
    val startMinutes: Int get() = minutesOf(start)
    val endMinutes: Int get() = minutesOf(end)
    val crossesMidnight: Boolean get() = endMinutes <= startMinutes

    /** 跨零点按 结束+24h-开始 计；等长视为整天。 */
    val durationMinutes: Int
        get() = ((endMinutes - startMinutes + MinutesPerDay) % MinutesPerDay).let { if (it == 0) MinutesPerDay else it }

    fun occursOn(isoDay: Int): Boolean = isoDay in days

    /** 某天的起止毫秒（跨零点时结束落在次日）。当天未勾选返回 null。 */
    fun spanOn(day: Calendar, zone: TimeZone = day.timeZone): Pair<Long, Long>? {
        if (!occursOn(ScheduleDates.isoDayOfWeek(day.get(Calendar.DAY_OF_WEEK)))) return null
        val start = Calendar.getInstance(zone).apply {
            timeInMillis = day.timeInMillis
            set(Calendar.HOUR_OF_DAY, startMinutes / 60)
            set(Calendar.MINUTE, startMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MINUTE, durationMinutes) }
        return start.timeInMillis to end.timeInMillis
    }

    /** 与一段课程时间有真实交集才算重叠（端点相接不算）。[zone] 决定这段毫秒落在哪一天。 */
    fun overlaps(from: Long, to: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val day = Calendar.getInstance(zone).apply { timeInMillis = from }
        val span = spanOn(day, zone) ?: return false
        return span.first < to && from < span.second
    }

    companion object {
        const val MinutesPerDay = 24 * 60

        fun default(kind: ScheduleStudyKind) = ScheduleStudyBlock(kind)
        fun defaults(): List<ScheduleStudyBlock> = ScheduleStudyKind.entries.map { ScheduleStudyBlock(it) }

        fun minutesOf(value: String): Int {
            val parts = value.trim().split(':')
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
        }

        fun format(minutes: Int): String {
            val wrapped = ((minutes % MinutesPerDay) + MinutesPerDay) % MinutesPerDay
            return "%02d:%02d".format(wrapped / 60, wrapped % 60)
        }

        fun encode(blocks: List<ScheduleStudyBlock>): String {
            val array = JSONArray()
            blocks.forEach { block ->
                array.put(JSONObject().apply {
                    put("kind", block.kind.storageKey)
                    put("enabled", block.enabled)
                    put("name", block.name)
                    put("start", block.start)
                    put("end", block.end)
                    put("days", JSONArray(block.days.sorted()))
                    block.leadMinutes?.let { put("lead", it) }
                })
            }
            return array.toString()
        }

        /** 存档缺失或损坏时回落到默认值，两种自习永远各有一条。 */
        fun decode(text: String?): List<ScheduleStudyBlock> {
            if (text.isNullOrBlank()) return defaults()
            val stored = runCatching { JSONArray(text) }.getOrNull() ?: return defaults()
            val byKind = mutableMapOf<ScheduleStudyKind, ScheduleStudyBlock>()
            for (index in 0 until stored.length()) {
                val item = stored.optJSONObject(index) ?: continue
                val kind = ScheduleStudyKind.of(item.optString("kind")) ?: continue
                val days = item.optJSONArray("days")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optInt(it).takeIf { day -> day in 1..7 } }.toSet()
                } ?: kind.defaultDays
                byKind[kind] = ScheduleStudyBlock(
                    kind = kind,
                    enabled = item.optBoolean("enabled", false),
                    name = item.optString("name").ifBlank { kind.defaultName },
                    start = item.optString("start").ifBlank { kind.defaultStart },
                    end = item.optString("end").ifBlank { kind.defaultEnd },
                    days = days,
                    leadMinutes = if (item.has("lead")) item.optInt("lead").coerceIn(0, 1440) else null
                )
            }
            return ScheduleStudyKind.entries.map { byKind[it] ?: default(it) }
        }
    }
}
