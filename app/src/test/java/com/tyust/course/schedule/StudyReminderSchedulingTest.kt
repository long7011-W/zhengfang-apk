package com.tyust.course.schedule

import android.app.Application
import android.content.Context
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.ui.route.restoreDeletedCustomCourse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 调度器接线：课表快照 → 自习判重，以及自定义课程保存、删除、撤销的生产调用链。
 * 即使课程没有自己的提醒记录，它也必须在完整快照中参与冲突判定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class StudyReminderSchedulingTest {
    private val account = "acct-" + UUID.randomUUID()
    private val term = "2026-2027-1"
    private val studyKey = CourseReminderKey(account, term, StudyReminders.courseId(ScheduleStudyKind.Morning, 1))
    private val tuesday = ScheduleCourseRecord("custom:tue", "周二课", "", "", 2, 1, 2, "1-25周")
    private val context: Context get() = RuntimeEnvironment.getApplication()
    // Robolectric 每例重建 Application；调度器也需独立，避免单例保留上例的 SharedPreferences。
    private val scheduler by lazy {
        ScheduleReminderScheduler::class.java.getDeclaredConstructor(Context::class.java).apply {
            isAccessible = true
        }.newInstance(context)
    }

    @Before
    fun setUp() {
        ScheduleSettingsManager.getInstance().apply { init(context) }.saveStudyBlock(
            ScheduleStudyBlock(ScheduleStudyKind.Morning, enabled = true, name = "早自习",
                start = "08:10", end = "09:10", days = setOf(1)), account, term)
        scheduler.updateTimeBase(account, term, ScheduleTimeBase("2026-09-07", mapOf(1 to "08:10"), mapOf(1 to "09:45")))
    }

    @Test
    fun `a new course in the snapshot must block an existing study reminder`() {
        // 只有周二那门课开了提醒，它同时让自习跟着本学期提醒总开关生效。
        scheduler.setEnabled(CourseReminderKey(account, term, tuesday.id), tuesday, true)
        scheduler.updateSnapshot(account, term, listOf(tuesday))
        assertNotNull(scheduler.find(studyKey))
        assertTrue(scheduler.find(studyKey)!!.study!!.blockedWeeks.isEmpty())

        // 刷新课表后新增一门周一 08:00–08:45 的课：它没有提醒记录，但必须压掉第 1–3 周的自习。
        val added = ScheduleCourseRecord("custom:mon", "周一新课", "", "", 1, 1, 1, "1-3周")
        scheduler.updateSnapshot(account, term, listOf(tuesday, added))
        assertEquals(setOf(1, 2, 3), scheduler.find(studyKey)!!.study!!.blockedWeeks)
    }

    @Test
    fun restoringAnUnremindedCustomCourseMustRestoreItsStudyConflict() {
        val settings = ScheduleSettingsManager.getInstance().apply { init(context) }
        scheduler.updateTimeBase(account, term, ScheduleTimeBase("2026-09-07",
            mapOf(1 to "08:00"), mapOf(1 to "08:45")))
        scheduler.setEnabled(CourseReminderKey(account, term, tuesday.id), tuesday, true)
        scheduler.updateSnapshot(account, term, listOf(tuesday))
        assertTrue(scheduler.find(studyKey)!!.study!!.blockedWeeks.isEmpty())

        // 与编辑器保存相同：新课加入设置和完整快照，但不创建它自己的提醒。
        val custom = ScheduleSettingsManager.CustomCourse("unreminded", "周一自定义课", "A101", "老师", 1, 1, 1, "1周")
        val record = ScheduleCourseRecord("custom:${custom.id}", custom.name, custom.teacher,
            custom.location, custom.day, custom.startPeriod, custom.endPeriod, custom.weeks, true)
        val courseKey = CourseReminderKey(account, term, record.id)
        settings.updateCustomCourse(custom, account)
        scheduler.updateCustomCourse(account, record)
        assertNull(scheduler.find(courseKey))
        assertEquals(setOf(1), scheduler.find(studyKey)!!.study!!.blockedWeeks)

        // 与详情删除相同：课程 JSON 和提醒撤销载荷分开保存，后者在该边界为空。
        val deletedCourseJson = ReminderJson.course(record).toString()
        val removed = scheduler.removeCourse(account, record.id)
        assertTrue(removed.isEmpty())
        val deletedRemindersJson = scheduler.encodeUndo(removed)
        settings.removeCustomCourse(custom.id, account)
        assertTrue(settings.getCustomCourses(account).isEmpty())
        assertTrue(scheduler.find(studyKey)!!.study!!.blockedWeeks.isEmpty())

        // 直接调用撤销按钮使用的生产恢复链，不能只在测试里补一次快照刷新。
        restoreDeletedCustomCourse(settings, scheduler, account,
            ReminderJson.course(JSONObject(deletedCourseJson)), deletedRemindersJson)
        assertEquals(custom, settings.getCustomCourses(account).single())
        assertNull(scheduler.find(courseKey))
        assertEquals(setOf(1), scheduler.find(studyKey)!!.study!!.blockedWeeks)
        scheduler.reconcile()
        val restoredStudy = scheduler.find(studyKey)!!
        assertEquals(setOf(1), restoredStudy.study!!.blockedWeeks)
        val occurrences = CourseReminderPlanner.studyOccurrences(restoredStudy, scheduler.timeBase(account, term))
        assertEquals(ScheduleMaxWeeks - 1, occurrences.size)
        assertEquals("2026-09-14", occurrences.first().originalDate)
    }

    @Test
    fun restoringCustomCourseUpdatesEverySemesterSnapshotWithoutTouchingOtherAccounts() {
        val settings = ScheduleSettingsManager.getInstance().apply { init(context) }
        val snapshots = ScheduleCourseSnapshotStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE))
        val nextTerm = "2026-2027-2"
        val otherAccount = "$account-other"
        val custom = ScheduleSettingsManager.CustomCourse("shared-id", "自定义课", "A101", "老师", 1, 1, 1, "1-3周")
        val record = ScheduleCourseRecord("custom:${custom.id}", custom.name, custom.teacher,
            custom.location, custom.day, custom.startPeriod, custom.endPeriod, custom.weeks, true)
        val otherRecord = record.copy(name = "另一账号的课", day = 3)
        scheduler.updateSnapshot(account, term, listOf(tuesday))
        scheduler.updateSnapshot(account, nextTerm, listOf(tuesday.copy(weeks = "2-4周")))
        scheduler.updateSnapshot(otherAccount, term, listOf(otherRecord))
        settings.updateCustomCourse(custom, account)
        scheduler.updateCustomCourse(account, record)
        val original = snapshots.read(account, term)
        val nextOriginal = snapshots.read(account, nextTerm)
        val otherOriginal = snapshots.read(otherAccount, term)

        val undo = scheduler.encodeUndo(scheduler.removeCourse(account, record.id))
        settings.removeCustomCourse(custom.id, account)
        assertTrue(snapshots.read(account, term)!!.none { it.id == record.id })
        assertTrue(snapshots.read(account, nextTerm)!!.none { it.id == record.id })
        assertEquals(otherOriginal, snapshots.read(otherAccount, term))

        restoreDeletedCustomCourse(settings, scheduler, account, record, undo)
        assertEquals(original, snapshots.read(account, term))
        assertEquals(nextOriginal, snapshots.read(account, nextTerm))
        assertEquals(otherOriginal, snapshots.read(otherAccount, term))
        // 再次恢复仍按 ID 更新，不能向任一学期追加重复课程。
        restoreDeletedCustomCourse(settings, scheduler, account, record, undo)
        assertEquals(original, snapshots.read(account, term))
        assertEquals(nextOriginal, snapshots.read(account, nextTerm))
    }

    @Test
    fun restoringARemindedCustomCourseKeepsItsReminderSettingsAndStudyConflict() {
        val settings = ScheduleSettingsManager.getInstance().apply { init(context) }
        val custom = ScheduleSettingsManager.CustomCourse("reminded", "周一课", "A101", "老师", 1, 1, 1, "1周")
        val record = ScheduleCourseRecord("custom:${custom.id}", custom.name, custom.teacher,
            custom.location, custom.day, custom.startPeriod, custom.endPeriod, custom.weeks, true)
        val courseKey = CourseReminderKey(account, term, record.id)
        scheduler.setEnabled(CourseReminderKey(account, term, tuesday.id), tuesday, true)
        scheduler.updateSnapshot(account, term, listOf(tuesday))
        settings.updateCustomCourse(custom, account)
        scheduler.updateCustomCourse(account, record)
        scheduler.setEnabled(courseKey, record, true)
        scheduler.setLead(courseKey, record, 30)
        val original = scheduler.find(courseKey)!!
        assertEquals(setOf(1), scheduler.find(studyKey)!!.study!!.blockedWeeks)

        val undo = scheduler.encodeUndo(scheduler.removeCourse(account, record.id))
        settings.removeCustomCourse(custom.id, account)
        assertNull(scheduler.find(courseKey))
        assertTrue(scheduler.find(studyKey)!!.study!!.blockedWeeks.isEmpty())
        restoreDeletedCustomCourse(settings, scheduler, account, record, undo)
        assertEquals(original.copy(revision = original.revision + 1), scheduler.find(courseKey))
        assertEquals(setOf(1), scheduler.find(studyKey)!!.study!!.blockedWeeks)
        val snapshots = ScheduleCourseSnapshotStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE))
        assertEquals(record, snapshots.read(account, term)!!.single { it.id == record.id })
        assertEquals(custom, settings.getCustomCourses(account).single())
    }

    @Test
    fun `removing the clashing course frees the study reminder again`() {
        val clash = ScheduleCourseRecord("custom:mon", "周一新课", "", "", 1, 1, 1, "1-25周")
        scheduler.setEnabled(CourseReminderKey(account, term, tuesday.id), tuesday, true)
        scheduler.updateSnapshot(account, term, listOf(tuesday, clash))
        // 每周都被压住 → 不生成自习提醒。
        assertNull(scheduler.find(studyKey))

        scheduler.removeCourse(account, clash.id)
        assertNotNull(scheduler.find(studyKey))
        assertTrue(scheduler.find(studyKey)!!.study!!.blockedWeeks.isEmpty())
    }
}
