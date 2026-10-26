package com.tyust.course.schedule

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.tyust.course.academic.AcademicTerm
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ClassQuietManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val term = AcademicTerm("2026-2027-1")
    private val course = ScheduleCourseRecord("quiet-fixture", "测试课", "", "", 1, 1, 1, "1周")
    private lateinit var manager: ClassQuietManager
    private lateinit var sounds: FakeSound
    private var now = 0L
    private var account = ""
    private val school = SchoolConfig("quiet-fixture", "测试学校", "quiet.invalid", "https")
    private fun time(clock: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse("2026-10-19 $clock")!!.time

    private class FakeSound : ClassQuietSoundPort {
        override var policyAccess = true
        override var fixedVolume = false
        var value = ClassQuietSound(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        var writes = 0
        var changesDndWithRinger = false
        var failFilterWrite = false
        override fun read() = value
        override fun ringer(mode: Int) {
            check(policyAccess); writes++
            value = value.copy(ringer = mode, filter = if (changesDndWithRinger) {
                if (mode == AudioManager.RINGER_MODE_SILENT) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            } else value.filter)
        }
        override fun filter(value: Int) {
            check(policyAccess && !failFilterWrite); this.value = this.value.copy(filter = value)
        }
    }

    private fun createManager() {
        manager = ClassQuietManager(context, { now }, sounds)
        ClassQuietManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, manager)
    }

    @Before fun setup() {
        ShadowAlarmManager.setAutoSchedule(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        for (name in listOf("class_quiet_settings", "class_quiet_runtime", "course_reminders", "schedule_cache", "schedule_settings"))
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        val user = UserManager.getInstance().apply { init(context); clearLoginState() }
        user.addCustomSchool(school); user.currentSchool = school; user.studentId = "synthetic-quiet"
        user.saveCookieLogin("SESSION=synthetic-only")
        account = user.currentAccountStorageKey
        sounds = FakeSound()
        now = time("07:59")
        createManager()
        saveCourses(listOf(course))
        ScheduleCalendarStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE)).write(account, term.id,
            ScheduleTimeBase("2026-10-19", mapOf(1 to "08:00", 2 to "08:30"), mapOf(1 to "08:45", 2 to "09:15")))
    }

    @After fun cleanup() {
        sounds.policyAccess = true
        manager.setEnabled(false)
        ClassQuietManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun saveCourses(courses: List<ScheduleCourseRecord>) {
        val json = JSONObject().put("kbList", JSONArray().apply { courses.forEach { course ->
            put(JSONObject().put("schedule_source_id", course.id).put("kcmc", course.name)
                .put("xqj", course.day).put("jcs", "${course.startPeriod}-${course.endPeriod}").put("zcd", course.weeks))
        } }).toString()
        ScheduleCacheStore(context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE))
            .save(account, school.id, CachedSchedule(term, term, json, false))
    }

    private fun alarm() = shadowOf(alarmManager).scheduledAlarms.singleOrNull {
        shadowOf(it.operation).savedIntent.action == ClassQuietManager.ACTION
    }

    @Test fun defaultIsOffAndAlarmsRunIndependentlyOfCourseNotifications() {
        manager.reconcile()
        assertFalse(manager.enabled); assertNull(alarm()); assertEquals(0, sounds.writes)
        manager.setEnabled(true)
        assertEquals(time("08:00"), alarm()!!.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm()!!.type)
        assertTrue(alarm()!!.isAllowWhileIdle)
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ScheduleBackgroundRefresh.JOB_ID))
        now = time("08:00"); manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_SILENT, sounds.value.ringer)
        assertEquals(time("08:45"), alarm()!!.triggerAtMs)
        now = time("08:45"); manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, sounds.value.ringer); assertNull(alarm())
    }

    @Test fun originalVibrationSurvivesProcessRestartAndRestoresAtEnd() {
        sounds.value = sounds.value.copy(ringer = AudioManager.RINGER_MODE_VIBRATE)
        now = time("08:10"); manager.setEnabled(true)
        assertEquals(AudioManager.RINGER_MODE_SILENT, sounds.value.ringer)
        createManager(); now = time("08:45"); manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, sounds.value.ringer)
    }

    @Test fun manualChangesWinForTheRestOfTheLesson() {
        now = time("08:10"); manager.setEnabled(true)
        sounds.value = sounds.value.copy(ringer = AudioManager.RINGER_MODE_VIBRATE)
        manager.reconcile()
        assertTrue(manager.status.summary.contains("手动"))
        sounds.value = sounds.value.copy(ringer = AudioManager.RINGER_MODE_SILENT)
        manager.reconcile(); now = time("08:45"); manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_SILENT, sounds.value.ringer)
    }

    @Test fun existingSilentAndDoNotDisturbSettingsAreRetained() {
        sounds.value = ClassQuietSound(AudioManager.RINGER_MODE_SILENT, NotificationManager.INTERRUPTION_FILTER_ALARMS)
        now = time("08:10"); manager.setEnabled(true); now = time("08:45"); manager.reconcile()
        assertEquals(ClassQuietSound(AudioManager.RINGER_MODE_SILENT, NotificationManager.INTERRUPTION_FILTER_ALARMS), sounds.value)
        assertEquals(0, sounds.writes)
    }

    @Test fun automaticDndChangesAreRestoredAndPartialRestorationSurvivesRecreation() {
        sounds.changesDndWithRinger = true
        sounds.value = sounds.value.copy(filter = NotificationManager.INTERRUPTION_FILTER_ALARMS)
        now = time("08:10"); manager.setEnabled(true)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, sounds.value.filter)
        manager.reconcile()
        assertFalse(manager.status.summary.contains("手动"))
        sounds.failFilterWrite = true
        now = time("08:45"); manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, sounds.value.ringer)
        assertTrue(manager.status.restorePending)
        assertNotNull(context.getSharedPreferences("class_quiet_runtime", Context.MODE_PRIVATE).getString("lease", null))
        createManager(); sounds.failFilterWrite = false; manager.reconcile()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, sounds.value.filter)
        assertFalse(manager.status.restorePending)
    }

    @Test fun manualDndChangeIsRetainedAtTheEndOfTheLesson() {
        now = time("08:10"); manager.setEnabled(true)
        sounds.value = sounds.value.copy(filter = NotificationManager.INTERRUPTION_FILTER_ALARMS)
        now = time("08:45"); manager.reconcile()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, sounds.value.filter)
        assertEquals(AudioManager.RINGER_MODE_SILENT, sounds.value.ringer)
    }

    @Test fun disablingOrLoggingOutRestoresImmediatelyAndCancelsTheAlarm() {
        now = time("08:10"); manager.setEnabled(true); manager.setEnabled(false)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, sounds.value.ringer); assertNull(alarm())
        manager.setEnabled(true); UserManager.getInstance().clearLoginState(); manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, sounds.value.ringer); assertNull(alarm())
    }

    @Test fun overlappingClassesDoNotRestoreEarlyAndRemovedLessonsRestoreImmediately() {
        saveCourses(listOf(course, course.copy(id = "overlap", startPeriod = 2, endPeriod = 2)))
        now = time("08:10"); manager.setEnabled(true); now = time("08:45"); manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_SILENT, sounds.value.ringer)
        assertEquals(time("09:15"), alarm()!!.triggerAtMs)
        saveCourses(emptyList()); manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, sounds.value.ringer); assertNull(alarm())
    }

    @Test fun revokedPolicyNeverChangesSoundAndRestorationCanResumeAfterGrant() {
        sounds.policyAccess = false; now = time("08:10"); manager.setEnabled(true)
        assertEquals(0, sounds.writes); assertNull(alarm())
        sounds.policyAccess = true; manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_SILENT, sounds.value.ringer)
        sounds.policyAccess = false; now = time("08:45"); manager.reconcile()
        assertTrue(manager.status.summary.contains("尚未恢复"))
        sounds.policyAccess = true; manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, sounds.value.ringer)
    }

    @Test fun staleBroadcastRecomputesCurrentStateAndCannotMuteAnEndedLesson() {
        manager.setEnabled(true)
        val obsolete = Intent(shadowOf(alarm()!!.operation).savedIntent)
        now = time("08:45"); manager.reconcile(); manager.receive(obsolete)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, sounds.value.ringer); assertNull(alarm())
    }

    @Test fun aFixedVolumeDeviceStaysUnchanged() {
        sounds.fixedVolume = true; now = time("08:10"); manager.setEnabled(true)
        assertEquals(0, sounds.writes); assertNull(alarm())
        assertTrue(manager.status.summary.contains("不支持"))
    }

    @Test @Config(sdk = [32, 35]) fun exactAlarmPermissionIsRequiredBeforeSilencing() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        now = time("08:10"); manager.setEnabled(true)
        assertEquals(0, sounds.writes); assertNull(alarm())
        assertFalse(manager.status.exactAlarms)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_SILENT, sounds.value.ringer)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        manager.reconcile()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, sounds.value.ringer)
    }
}
