package com.tyust.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 复审 6 个复现场景里与提醒调度相关的第 5、6 条。 */
class StudyRemindersTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val account = "account1"
    private val term = "2026-2027-1"
    private val allowed = ReminderPermissions(true, true)
    /** 2026-09-07 是周一；第 1 节 08:10–09:45、第 9 节 18:15–19:00。 */
    private val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:10", 9 to "18:15"), mapOf(1 to "09:45", 9 to "19:00"))
    private val course = ScheduleCourseRecord("custom:one", "数学", "", "A101", 1, 1, 2, "1-25周")
    private val morning = ScheduleStudyBlock.default(ScheduleStudyKind.Morning).copy(enabled = true)
    /** 晚自习 18:20–20:45 正好压住第 9 节 18:15–19:00，用来造真实冲突。 */
    private val evening = ScheduleStudyBlock(ScheduleStudyKind.Evening, enabled = true, name = "晚自习",
        start = "18:20", end = "20:45", days = setOf(1, 2, 3, 4))
    private val eveningClash = ScheduleCourseRecord("custom:clash", "晚课", "", "", 1, 9, 9, "1-25周")

    private fun time(day: Int, hour: Int, minute: Int) = Calendar.getInstance(zone).apply {
        clear(); set(2026, Calendar.SEPTEMBER, day, hour, minute)
    }.timeInMillis

    @Test fun `study and course each get their own alarm`() {
        val alarms = FakeAlarms()
        val courses = SemesterReminders.update(emptyList(), account, term, listOf(course), enabled = true, defaultLead = 15)
        val records = StudyReminders.update(courses, account, term, listOf(morning), listOf(course), base,
            enabled = true, defaultLead = 15, zone = zone)
        assertEquals(5, records.count { it.study != null })
        // 周一~周五各有一条 06:54 的自习，外加 08:10 的课程：自习不再是漏掉的那一半。
        val status = reconcileCourseReminders(records, account, { base }, allowed, time(7, 6, 0), alarms, zone)
        assertEquals(6, alarms.plans.size)
        assertEquals(5, status.values.count { it.next?.reminder?.study != null })
        assertEquals(time(7, 6, 39), alarms.plans.values.first { it.reminder.study?.day == 1 }.triggerAt)
        assertEquals(time(7, 7, 55), alarms.plans.values.single { it.reminder.study == null }.triggerAt)
    }

    @Test fun `a study window swallowed by a course every week never schedules`() {
        // 每周一第 9 节都压在晚自习上：周一整条不生成，另外三天照常。
        val records = StudyReminders.update(emptyList(), account, term, listOf(evening), listOf(eveningClash), base,
            enabled = true, defaultLead = 15, zone = zone)
        assertEquals(setOf(2, 3, 4), records.map { it.study!!.day }.toSet())
    }

    @Test fun `only the conflicting weeks are skipped`() {
        val clash = eveningClash.copy(weeks = "1-3周")
        val record = StudyReminders.update(emptyList(), account, term, listOf(evening.copy(days = setOf(1))), listOf(clash),
            base, enabled = true, defaultLead = 15, zone = zone).single()
        assertEquals(setOf(1, 2, 3), record.study!!.blockedWeeks)
        // 第 1–3 周周一被压掉，闹钟落到第 4 周周一 18:20 − 15 分钟 = 18:05。
        val next = CourseReminderPlanner.plan(record, base, time(7, 6, 0), allowed, account, zone).next!!
        assertEquals(time(28, 18, 5), next.triggerAt)
    }

    @Test fun `turning the semester switch off removes study records and alarms`() {
        val alarms = FakeAlarms()
        val courses = SemesterReminders.update(emptyList(), account, term, listOf(course), enabled = true, defaultLead = 15)
        val records = StudyReminders.update(courses, account, term, listOf(morning), listOf(course), base,
            enabled = true, defaultLead = 15, zone = zone)
        reconcileCourseReminders(records, account, { base }, allowed, time(7, 6, 0), alarms, zone)
        assertEquals(6, alarms.plans.size)

        val off = StudyReminders.update(records, account, term, listOf(morning), listOf(course), base,
            enabled = false, defaultLead = 15, zone = zone)
        assertTrue(off.none { it.study != null })
        val status = reconcileCourseReminders(off, account, { base }, allowed, time(7, 6, 0), alarms, zone)
        assertEquals(1, alarms.plans.size)                                  // 只剩课程那条
        assertTrue(status.values.none { it.next?.reminder?.study != null })
    }

    @Test fun `study records are scoped to one account and term`() {
        val other = StudyReminders.update(emptyList(), "account2", "2026-2027-2",
            listOf(morning), emptyList(), base, enabled = true, defaultLead = 15, zone = zone)
        val merged = StudyReminders.update(other, account, term, listOf(morning), emptyList(), base,
            enabled = true, defaultLead = 15, zone = zone)
        assertEquals(5, merged.count { it.key.account == "account2" })
        assertEquals(5, merged.count { it.key.account == account && it.key.term == term })
        // 关掉 account1 的自习不能动 account2 的记录。
        val after = StudyReminders.update(merged, account, term, listOf(morning), emptyList(), base,
            enabled = false, defaultLead = 15, zone = zone)
        assertEquals(5, after.count { it.key.account == "account2" })
        assertEquals(0, after.count { it.key.account == account })
    }

    @Test fun `repeated syncs keep the record and its revision stable`() {
        val once = StudyReminders.update(emptyList(), account, term, listOf(morning), emptyList(), base,
            enabled = true, defaultLead = 15, zone = zone)
        val twice = StudyReminders.update(once, account, term, listOf(morning), emptyList(), base,
            enabled = true, defaultLead = 15, zone = zone)
        assertEquals(once, twice)
        // 只有真的改了配置才产生新 revision（提醒链与 prefs 写入靠它收敛）。
        val moved = StudyReminders.update(twice, account, term, listOf(morning.copy(start = "06:40")), emptyList(),
            base, enabled = true, defaultLead = 15, zone = zone)
        assertNotEquals(twice, moved)
        assertTrue(moved.all { record -> once.first { it.key == record.key }.revision < record.revision })
    }

    @Test fun `per block lead overrides the semester default`() {
        val custom = morning.copy(leadMinutes = 5)
        val records = StudyReminders.update(emptyList(), account, term, listOf(custom), emptyList(), base,
            enabled = true, defaultLead = 30, zone = zone)
        assertTrue(records.all { it.leadMinutes == 5 && it.customLead })
        val inherited = StudyReminders.update(emptyList(), account, term, listOf(morning), emptyList(), base,
            enabled = true, defaultLead = 30, zone = zone)
        assertTrue(inherited.all { it.leadMinutes == 30 && !it.customLead })
    }

    @Test fun `a forced re-run after the week start moved rewrites the registered alarm`() {
        val alarms = FakeAlarms()
        // 周期日（周日）的自习：改用「周日为首日」后第 1 周从 9-06 开始，这一天的自习整体前移一周。
        val sunday = ScheduleStudyBlock(ScheduleStudyKind.Evening, enabled = true, name = "晚自习",
            start = "18:20", end = "20:45", days = setOf(7))
        val records = StudyReminders.update(emptyList(), account, term, listOf(sunday), emptyList(), base,
            enabled = true, defaultLead = 15, zone = zone)
        reconcileCourseReminders(records, account, { base }, allowed, time(1, 6, 0), alarms, zone)
        val before = alarms.plans.values.single().triggerAt
        assertEquals(time(13, 18, 5), before)

        // 归一化后的日历可能被判成「没变」，所以调度侧显式重排（ScheduleReminderScheduler.reschedule 即此路径）。
        reconcileCourseReminders(records, account, { base.copy(firstWeekDate = "2026-09-06") }, allowed,
            time(1, 6, 0), alarms, zone)
        assertEquals(before - 7 * 86_400_000L, alarms.plans.values.single().triggerAt)
    }

    /** 评审第二轮 P1：判重必须看完整课表，不能只看「开了提醒的课程」。 */
    @Test fun `a new course in the snapshot must block an existing study reminder`() {
        val tuesday = ScheduleCourseRecord("custom:tue", "周二课", "", "", 2, 1, 2, "1-25周")
        val records = SemesterReminders.update(emptyList(), account, term, listOf(tuesday), enabled = true, defaultLead = 15)
        val study = ScheduleStudyBlock(ScheduleStudyKind.Morning, enabled = true, name = "早自习",
            start = "08:10", end = "09:10", days = setOf(1))
        // 刷新课表后新增的周一 08:00–08:45 课程：它没有自己的提醒，但必须压掉第 1–3 周的自习。
        val added = ScheduleCourseRecord("custom:mon", "周一新课", "", "", 1, 1, 1, "1-3周")

        // 旧行为：只从提醒记录反推课程 → 判重看不到新课程，本周自习照排。
        val withoutSnapshot = StudyReminders.sync(records, account, term, listOf(study), null, base,
            enabled = true, defaultLead = 15, zone = zone)
        assertTrue(withoutSnapshot.single { it.study != null }.study!!.blockedWeeks.isEmpty())
        assertEquals(time(7, 7, 55), CourseReminderPlanner.plan(withoutSnapshot.single { it.study != null }, base,
            time(6, 20, 0), allowed, account, zone).next!!.triggerAt)

        // 修复后：完整课表快照参与判重，闹钟推到第 4 周。
        val synced = StudyReminders.sync(records, account, term, listOf(study), listOf(tuesday, added), base,
            enabled = true, defaultLead = 15, zone = zone)
        assertEquals(setOf(1, 2, 3), synced.single { it.study != null }.study!!.blockedWeeks)
        assertEquals(time(28, 7, 55), CourseReminderPlanner.plan(synced.single { it.study != null }, base,
            time(6, 20, 0), allowed, account, zone).next!!.triggerAt)
        // 记录里更新过的副本（例如撤销删除的自定义课程）也要并进判重集合。
        val restored = tuesday.copy(id = "custom:restored", day = 1, weeks = "5周")
        val withRestoredRecord = StudyReminders.conflictCourses(listOf(tuesday), SemesterReminders.update(
            emptyList(), account, term, listOf(restored), enabled = true, defaultLead = 15), account, term)
        assertEquals(setOf("custom:tue", "custom:restored"), withRestoredRecord.map { it.id }.toSet())
    }

    /** 评审第二轮 P2：改回「跟随全局默认」必须丢掉旧的单条覆盖。 */
    @Test fun `changing the lead back to the default replaces the old override`() {
        val custom = StudyReminders.update(emptyList(), account, term, listOf(morning.copy(leadMinutes = 5)), emptyList(),
            base, enabled = true, defaultLead = 15, zone = zone)
        assertTrue(custom.all { it.leadMinutes == 5 && it.customLead })
        val back = StudyReminders.update(custom, account, term, listOf(morning), emptyList(), base,
            enabled = true, defaultLead = 15, zone = zone)
        assertTrue(back.all { it.leadMinutes == 15 && !it.customLead })
        // 回到「跟随全局默认」之后再改全局默认也要跟着走。
        val global = StudyReminders.update(back, account, term, listOf(morning), emptyList(), base,
            enabled = true, defaultLead = 25, zone = zone)
        assertTrue(global.all { it.leadMinutes == 25 && !it.customLead })
    }

    /** 快照用 ReminderJson 持久化，格式必须能原样往返。 */
    @Test fun `course snapshots round trip through the reminder json`() {
        val courses = listOf(course, course.copy(id = "custom:two", day = 3, weeks = "1-16周(单)"),
            ScheduleCourseRecord("custom:three", "体育", "王老师", "操场", 5, 3, 4, "1-25周", custom = true))
        assertEquals(courses, ReminderJson.courses(ReminderJson.courses(courses)))
        assertNull(ReminderJson.courses(null))
        assertNull(ReminderJson.courses("not json"))
    }

    private class FakeAlarms : ReminderAlarmPort {
        val plans = mutableMapOf<String, PlannedReminder>()
        override fun scheduled() = plans.toMap()
        override fun exists(key: CourseReminderKey) = key.storageId in plans
        override fun schedule(plan: PlannedReminder) { plans[plan.reminder.key.storageId] = plan }
        override fun cancel(key: CourseReminderKey) { plans.remove(key.storageId) }
    }
}
