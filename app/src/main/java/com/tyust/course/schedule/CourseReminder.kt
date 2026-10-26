package com.tyust.course.schedule

import java.util.Calendar
import java.util.TimeZone

data class CourseReminderKey(val account: String, val term: String, val courseId: String) {
    val storageId: String get() = ScheduleIdentity.digest(listOf(account, term, courseId).joinToString("\u001f"))
}

data class CourseReminder(val key: CourseReminderKey, val course: ScheduleCourseRecord, val enabled: Boolean = false,
    val leadMinutes: Int = 15, val revision: Long = 1L, val customLead: Boolean = false,
    val study: StudyReminderSpec? = null)

/**
 * 自习提醒的附加信息：这条记录代表「某个星期几的一段自习」，时间来自自习配置而不是节次表。
 * [blockedWeeks] 是被课程占掉、因此不排提醒的教学周。
 */
data class StudyReminderSpec(val kind: ScheduleStudyKind, val day: Int, val name: String,
    val start: String = kind.defaultStart, val end: String = kind.defaultEnd,
    val blockedWeeks: Set<Int> = emptySet()) {
    val block: ScheduleStudyBlock
        get() = ScheduleStudyBlock(kind, enabled = true, name = name, start = start, end = end, days = setOf(day))
}

data class ScheduleTimeBase(val firstWeekDate: String = "", val periodStarts: Map<Int, String> = emptyMap(),
    val periodEnds: Map<Int, String> = emptyMap(), val adjustments: ScheduleAdjustments = ScheduleAdjustments()) {
    companion object {
        fun dateFromMillis(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
            if (millis <= 0) return ""
            val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
            return "%04d-%02d-%02d".format(java.util.Locale.ROOT, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
        }
    }
}

data class ReminderPermissions(val notifications: Boolean, val exactAlarms: Boolean) {
    val available: Boolean get() = notifications && exactAlarms
}

enum class ReminderAvailability { Off, NeedsPermission, NeedsTime, InvalidWeeks, Scheduled, NoUpcoming, InactiveAccount }

data class PlannedReminder(val reminder: CourseReminder, val triggerAt: Long, val startsAt: Long)

/** A short delivery grace handles a cold process and zero-minute reminders, never old history. */
internal fun PlannedReminder.canDeliverAt(now: Long): Boolean =
    now >= triggerAt && now < startsAt + 5 * 60_000L
data class ReminderStatus(val availability: ReminderAvailability, val next: PlannedReminder? = null)

object CourseReminderPlanner {
    fun occurrence(reminder: CourseReminder, timeBase: ScheduleTimeBase?, startsAt: Long,
        zone: TimeZone = TimeZone.getDefault()): ScheduleOccurrence? =
        if (reminder.study != null) studyOccurrences(reminder, timeBase, zone).firstOrNull { it.startsAt == startsAt }
        else ScheduleOccurrenceResolver.resolve(listOf(reminder.course), timeBase, zone, requireEnd = false).firstOrNull { it.startsAt == startsAt }

    /** 自习在各教学周的时段；被课程占掉的教学周（[StudyReminderSpec.blockedWeeks]）不排提醒。 */
    fun studyOccurrences(reminder: CourseReminder, timeBase: ScheduleTimeBase?,
        zone: TimeZone = TimeZone.getDefault()): List<ScheduleOccurrence> {
        val spec = reminder.study ?: return emptyList()
        val anchor = timeBase?.firstWeekDate ?: return emptyList()
        val block = spec.block
        val result = mutableListOf<ScheduleOccurrence>()
        for (week in 1..ScheduleMaxWeeks) {
            if (week in spec.blockedWeeks) continue
            val day = ScheduleDates.date(anchor, week, spec.day, zone) ?: continue
            val span = block.spanOn(day, zone) ?: continue
            result.add(ScheduleOccurrence(reminder.course, span.first, span.second,
                ScheduleTimeBase.dateFromMillis(span.first, zone)))
        }
        return result
    }

    fun plan(reminder: CourseReminder, timeBase: ScheduleTimeBase?, now: Long, permissions: ReminderPermissions,
        activeAccount: String, zone: TimeZone = TimeZone.getDefault()): ReminderStatus {
        if (!reminder.enabled) return ReminderStatus(ReminderAvailability.Off)
        if (reminder.key.account != activeAccount || activeAccount.isBlank()) return ReminderStatus(ReminderAvailability.InactiveAccount)
        if (!permissions.available) return ReminderStatus(ReminderAvailability.NeedsPermission)
        val study = reminder.study
        if (study != null) {
            if (study.day !in 1..7 || reminder.leadMinutes !in 0..1440) return ReminderStatus(ReminderAvailability.NeedsTime)
            if (ScheduleDates.firstWeekDate(timeBase?.firstWeekDate ?: "", zone) == null)
                return ReminderStatus(ReminderAvailability.NeedsTime)
            for (occurrence in studyOccurrences(reminder, timeBase, zone)) {
                val trigger = occurrence.startsAt - reminder.leadMinutes * 60_000L
                if (trigger > now) return ReminderStatus(ReminderAvailability.Scheduled, PlannedReminder(reminder, trigger, occurrence.startsAt))
            }
            return ReminderStatus(ReminderAvailability.NoUpcoming)
        }
        val course = reminder.course
        val weeks = ScheduleWeeks.parse(course.weeks)
        if (!weeks.valid) return ReminderStatus(ReminderAvailability.InvalidWeeks)
        val date = timeBase?.firstWeekDate?.split('-')?.map { it.toIntOrNull() }
        val time = timeBase?.periodStarts?.get(course.startPeriod)?.split(':')?.map { it.toIntOrNull() }
        if (date?.size != 3 || date.any { it == null } || time?.size != 2 || time.any { it == null } ||
            time[0] !in 0..23 || time[1] !in 0..59 || course.day !in 1..7 ||
            course.startPeriod < 1 || course.endPeriod < course.startPeriod || reminder.leadMinutes !in 0..1440) {
            return ReminderStatus(ReminderAvailability.NeedsTime)
        }
        if (ScheduleDates.firstWeekDate(timeBase.firstWeekDate, zone) == null)
            return ReminderStatus(ReminderAvailability.NeedsTime)
        for (occurrence in ScheduleOccurrenceResolver.resolve(listOf(course), timeBase, zone, requireEnd = false)) {
            val startsAt = occurrence.startsAt
            val trigger = startsAt - reminder.leadMinutes * 60_000L
            if (trigger > now) return ReminderStatus(ReminderAvailability.Scheduled, PlannedReminder(reminder, trigger, startsAt))
        }
        return ReminderStatus(ReminderAvailability.NoUpcoming)
    }
}

interface ReminderAlarmPort {
    fun scheduled(): Map<String, PlannedReminder>
    fun exists(key: CourseReminderKey): Boolean
    fun schedule(plan: PlannedReminder)
    fun cancel(key: CourseReminderKey)
}

/** Only a previously scheduled, unchanged occurrence may survive until its broadcast arrives. */
fun isCurrentReminderOccurrence(plan: PlannedReminder, reminder: CourseReminder, timeBase: ScheduleTimeBase?,
    permissions: ReminderPermissions, activeAccount: String, zone: TimeZone = TimeZone.getDefault()): Boolean =
    plan.reminder == reminder && CourseReminderPlanner.plan(reminder, timeBase, plan.triggerAt - 1,
        permissions, activeAccount, zone).next == plan

/** Repeated reconciliation has no side effects when the desired alarms already exist. */
fun reconcileCourseReminders(reminders: List<CourseReminder>, activeAccount: String,
    timeBase: (CourseReminderKey) -> ScheduleTimeBase?, permissions: ReminderPermissions, now: Long,
    alarms: ReminderAlarmPort, zone: TimeZone = TimeZone.getDefault(), restoreAlarms: Boolean = false): Map<String, ReminderStatus> {
    val existing = alarms.scheduled()
    val status = reminders.associate { reminder ->
        val base = timeBase(reminder.key)
        val pending = existing[reminder.key.storageId]
        val due = pending?.takeIf { it.canDeliverAt(now) &&
            isCurrentReminderOccurrence(it, reminder, base, permissions, activeAccount, zone) &&
            (restoreAlarms || alarms.exists(reminder.key)) }
        // A resume/cold-start can run before Android delivers an already due alarm. Do not
        // replace its PendingIntent extras with the next week's occurrence in that window.
        reminder.key.storageId to (due?.let { ReminderStatus(ReminderAvailability.Scheduled, it) }
            ?: CourseReminderPlanner.plan(reminder, base, now, permissions, activeAccount, zone))
    }
    val desired = status.mapNotNull { (id, state) -> state.next?.let { id to it } }.toMap()
    existing.filterKeys { it !in desired }.values.forEach { alarms.cancel(it.reminder.key) }
    desired.forEach { (id, plan) ->
        if (restoreAlarms || existing[id] != plan || !alarms.exists(plan.reminder.key)) alarms.schedule(plan)
    }
    return status
}
