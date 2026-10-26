package com.tyust.course.schedule

import java.util.TimeZone

/**
 * 把自习配置翻译成与课程同构的提醒记录。
 *
 * 一条记录代表「某个星期几的一段自习」，与课程提醒共用同一条流水线
 * （`reconcileCourseReminders` 按 revision / trigger 去重、取消多余闹钟），
 * 因此改时间、改星期、关显示开关自然落到「生成 / 更新 / 取消」三种结果上。
 *
 * 与课程有交集的那一周不排提醒（[StudyReminderSpec.blockedWeeks]）：判定用的是按真实
 * 日期展开的 occurrence，所以跨零点、跨周与调课都跟着课表本身走，端点相接不算冲突。
 * 冲突判定必须喂**完整课表快照**（[conflictCourses]），不能只看开启了提醒的课程。
 *
 * 记录里的 `course` 只是流水线载体：名字用自习名，节次留空，周次铺满整学期。
 */
object StudyReminders {
    /** 自习提醒记录 id：同一天固定一条，便于「更新」而不是重复堆积。 */
    fun courseId(kind: ScheduleStudyKind, day: Int): String = "study:${kind.storageKey}:$day"

    /**
     * 冲突判定用哪个课程集合：以账号 + 学期的**完整课表快照**为准（新课程即使没开它自己的
     * 提醒，也要参与自习判重），再把提醒记录里那份更新过的副本并进去（例如撤销删除的自定义
     * 课程）；没有快照时（冷启动早期）退回提醒记录里的课程。
     */
    fun conflictCourses(snapshot: List<ScheduleCourseRecord>?, records: List<CourseReminder>,
        account: String, term: String): List<ScheduleCourseRecord> {
        val fromRecords = records.filter { it.key.account == account && it.key.term == term && it.study == null }.map { it.course }
        if (snapshot == null) return fromRecords
        val byId = LinkedHashMap<String, ScheduleCourseRecord>()
        snapshot.forEach { byId[it.id] = it }
        fromRecords.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    /** 调度侧入口：用完整课表快照重算自习提醒。 */
    fun sync(current: List<CourseReminder>, account: String, term: String, blocks: List<ScheduleStudyBlock>,
        snapshot: List<ScheduleCourseRecord>?, timeBase: ScheduleTimeBase?, enabled: Boolean,
        defaultLead: Int = 15, zone: TimeZone = TimeZone.getDefault()): List<CourseReminder> =
        update(current, account, term, blocks, conflictCourses(snapshot, current, account, term), timeBase,
            enabled, defaultLead, zone)

    /**
     * 重算某账号 + 某学期的自习提醒。未开启（学期提醒总开关或自习显示开关）时只做删除，
     * 与课程记录互不影响，也不会碰其它账号/学期的记录。
     */
    fun update(current: List<CourseReminder>, account: String, term: String,
        blocks: List<ScheduleStudyBlock>, courses: List<ScheduleCourseRecord>, timeBase: ScheduleTimeBase?,
        enabled: Boolean, defaultLead: Int = 15, zone: TimeZone = TimeZone.getDefault()): List<CourseReminder> {
        if (account.isBlank() || term.isBlank()) return current
        val kept = current.filterNot { it.key.account == account && it.key.term == term && it.study != null }
        if (!enabled) return kept
        val busy = ScheduleOccurrenceResolver.resolve(courses, timeBase, zone, requireEnd = false)
        val anchor = timeBase?.firstWeekDate
        val added = mutableListOf<CourseReminder>()
        for (block in blocks) {
            if (!block.enabled) continue
            for (day in 1..7) {
                if (!block.occursOn(day)) continue
                val blockedWeeks = mutableSetOf<Int>()
                var freeWeeks = 0
                for (week in 1..ScheduleMaxWeeks) {
                    val date = anchor?.let { ScheduleDates.date(it, week, day, zone) } ?: continue
                    val span = block.spanOn(date, zone) ?: continue
                    if (ScheduleStudyPlanner.blocked(span, busy)) blockedWeeks.add(week) else freeWeeks++
                }
                if (freeWeeks == 0) continue
                val id = courseId(block.kind, day)
                val key = CourseReminderKey(account, term, id)
                val old = current.firstOrNull { it.key == key }
                // 提前量只认最新配置：block.leadMinutes == null 表示跟随全局默认，必须丢掉旧覆盖。
                val candidate = CourseReminder(key, record(block, day), enabled = true,
                    leadMinutes = block.leadMinutes ?: defaultLead,
                    revision = old?.revision ?: 1L,
                    customLead = block.leadMinutes != null,
                    study = StudyReminderSpec(block.kind, day, block.name, block.start, block.end, blockedWeeks))
                // 与既有记录一致时原样复用，避免每次 reconcile 都改 revision。
                added += if (old != null && old.copy(revision = 0L) == candidate.copy(revision = 0L)) old
                else candidate.copy(revision = (old?.revision ?: 0L) + 1)
            }
        }
        return kept + added
    }

    private fun record(block: ScheduleStudyBlock, day: Int) = ScheduleCourseRecord(
        id = courseId(block.kind, day), name = block.name, teacher = "", location = "",
        day = day, startPeriod = 0, endPeriod = 0, weeks = "1-$ScheduleMaxWeeks", custom = false)
}
