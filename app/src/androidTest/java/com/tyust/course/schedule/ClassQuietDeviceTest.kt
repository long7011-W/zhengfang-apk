package com.tyust.course.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.academic.AcademicStudyReader
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Requires an isolated .classquiettest build and explicit sound-control opt-in. */
@RunWith(AndroidJUnit4::class)
class ClassQuietDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val manager get() = ClassQuietManager.get(context)
    private val school = SchoolConfig("quiet-device-fixture", "本地静音测试", "quiet.invalid", "https")
    private val events = JSONArray()
    private var original: ClassQuietSound? = null
    private var music = -1
    private var alarm = -1
    private var account = ""

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, timeout: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    private fun event(name: String) {
        events.put(JSONObject().put("event", name).put("at", System.currentTimeMillis())
            .put("ringer", audio.ringerMode).put("filter", notifications.currentInterruptionFilter)
            .put("musicVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("alarmVolume", audio.getStreamVolume(AudioManager.STREAM_ALARM))
            .put("summary", manager.status.summary))
    }

    @Before fun isolatedPackageAndExplicitOptIn() {
        assumeTrue(context.packageName.endsWith(".classquiettest"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSoundChanges") == "true")
        assertTrue("Grant DND access only to the isolated test app", notifications.isNotificationPolicyAccessGranted)
        assertTrue(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        original = ClassQuietSound(audio.ringerMode, notifications.currentInterruptionFilter)
        music = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        alarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        main {
            manager.setEnabled(false)
            val user = UserManager.getInstance().apply { init(context); clearLoginState() }
            user.addCustomSchool(school)
            user.currentSchool = school
            user.studentId = "synthetic-quiet-device"
            user.saveCookieLogin("SESSION=synthetic-only")
            account = user.currentAccountStorageKey
        }
        normal()
        event("baseline")
    }

    @After fun restoreDeviceSound() {
        val baseline = original ?: return
        try {
            main { manager.setEnabled(false); UserManager.getInstance().clearLoginState() }
        } finally {
            audio.ringerMode = baseline.ringer
            notifications.setInterruptionFilter(baseline.filter)
            await("Restore pre-test ringer and DND") {
                audio.ringerMode == baseline.ringer && notifications.currentInterruptionFilter == baseline.filter
            }
            assertEquals("Do not alter media volume", music, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("Do not alter alarm volume", alarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            event("baseline-restored")
            val output = File(context.getExternalFilesDir(null), "class-quiet-validation").apply { mkdirs() }
            File(output, "events-${System.currentTimeMillis()}.json").writeText(events.toString(2))
        }
    }

    private fun normal() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        await("Normal sound baseline") {
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        }
        SystemClock.sleep(250)
    }

    private fun timetable(start: Long, end: Long) {
        val day = Calendar.getInstance().apply { timeInMillis = start }
        val weekday = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        val first = day.clone() as Calendar
        first.add(Calendar.DATE, 1 - weekday)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(first.time)
        val clock = SimpleDateFormat("HH:mm", Locale.ROOT)
        val term = AcademicStudyReader.calendarTerm()
        val json = JSONObject().put("kbList", JSONArray().put(JSONObject()
            .put("schedule_source_id", "quiet-device-course").put("kcmc", "本地测试课程")
            .put("xqj", weekday).put("jcs", "1-1").put("zcd", "1周"))).toString()
        main {
            ScheduleCacheStore(context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE))
                .save(account, school.id, CachedSchedule(term, term, json, false))
            ScheduleCalendarStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE)).write(
                account, term.id, ScheduleTimeBase(date, mapOf(1 to clock.format(start)), mapOf(1 to clock.format(end))))
            manager.reconcile()
        }
        SystemClock.sleep(250)
    }

    @Test fun realSoundRestoresOriginalModesAndRespectsManualChanges() {
        val now = System.currentTimeMillis()
        val day = Calendar.getInstance()
        assumeTrue(day.get(Calendar.HOUR_OF_DAY) in 1..22)
        timetable(now - 60_000, now + 180_000)
        main { manager.setEnabled(true) }
        await("Silence real device") { audio.ringerMode == AudioManager.RINGER_MODE_SILENT }
        SystemClock.sleep(500)
        event("normal-to-silent")
        assertFalse("Own DND change must not count as a manual override", manager.status.summary.contains("手动"))
        assertEquals(music, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(alarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        main { manager.setEnabled(false) }
        await("Restore normal after disabling") {
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        }
        event("disable-restores-normal")

        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        await("Vibrate baseline") { audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE }
        SystemClock.sleep(250)
        main { manager.setEnabled(true) }
        await("Vibrate to silent") { audio.ringerMode == AudioManager.RINGER_MODE_SILENT }
        SystemClock.sleep(500)
        event("vibrate-to-silent")
        main { manager.setEnabled(false) }
        await("Restore vibration") { audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE }
        event("disable-restores-vibration")

        normal()
        main { manager.setEnabled(true) }
        await("Silence before manual override") { audio.ringerMode == AudioManager.RINGER_MODE_SILENT }
        SystemClock.sleep(500)
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        await("Observe manual override") { manager.status.summary.contains("手动") }
        main { manager.setEnabled(false) }
        assertEquals("Respect manual vibration", AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        event("manual-vibration-retained")

        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        await("Silent baseline") { audio.ringerMode == AudioManager.RINGER_MODE_SILENT }
        SystemClock.sleep(300)
        main { manager.setEnabled(true) }
        main { manager.setEnabled(false) }
        assertEquals("Never unmute pre-existing silence", AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
        event("existing-silence-retained")
    }

    @Test fun actualExactAlarmsSilenceAtStartAndRestoreAtEndWithoutUiOrPollingReconcile() {
        val now = System.currentTimeMillis()
        val start = ((now + 15_000) / 60_000 + 1) * 60_000
        val end = start + 120_000
        assumeTrue(Calendar.getInstance().apply { timeInMillis = end }.get(Calendar.DAY_OF_YEAR) ==
            Calendar.getInstance().get(Calendar.DAY_OF_YEAR))
        timetable(start, end)
        main { manager.setEnabled(true) }
        assertEquals("Wait for start without changing sound early", AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        event("waiting-for-exact-start")
        await("Real exact alarm silences at class start", start - System.currentTimeMillis() + 15_000) {
            audio.ringerMode == AudioManager.RINGER_MODE_SILENT
        }
        assertTrue("Never silence before scheduled start", System.currentTimeMillis() >= start)
        event("exact-start-silent")
        await("Real exact alarm restores after class", end - System.currentTimeMillis() + 20_000) {
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        }
        assertTrue("Never restore before scheduled end", System.currentTimeMillis() >= end)
        event("exact-end-restored")
        assertTrue("End boundary clears sound ownership",
            context.getSharedPreferences("class_quiet_runtime", Context.MODE_PRIVATE).getString("lease", null) == null)
    }
}
