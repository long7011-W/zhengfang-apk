package com.tyust.course.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tyust.course.R
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Real scheduler/receiver wiring with Android system services, without a running Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduleBackgroundRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val jobs get() = context.getSystemService(JobScheduler::class.java)
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val term = "2026-2027-1"
    private val course = ScheduleCourseRecord("network:recovery", "后台课程", "老师", "A101", 1, 1, 1, "1-2周")
    private lateinit var zone: TimeZone
    private lateinit var key: CourseReminderKey
    private lateinit var scheduler: ScheduleReminderScheduler
    private var currentTime = 0L

    private fun time(day: Int, hour: Int, minute: Int): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse("2026-09-%02d %02d:%02d".format(Locale.ROOT, day, hour, minute))!!.time

    private fun setTime(day: Int, hour: Int, minute: Int) {
        currentTime = time(day, hour, minute)
    }

    private fun resetScheduler() {
        scheduler = ScheduleReminderScheduler::class.java.getDeclaredConstructor(Context::class.java, Function0::class.java)
            .apply { isAccessible = true }.newInstance(context, { currentTime })
        ScheduleReminderScheduler::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, scheduler)
    }

    private fun resetAccountProcess() {
        // Keep SharedPreferences, just discard the in-memory login and scheduler instances.
        UserManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        resetScheduler()
    }

    @Before fun setup() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        ShadowAlarmManager.setAutoSchedule(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        notifications.setNotificationsEnabled(true)
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setTime(7, 7, 0)
        val user = UserManager.getInstance().apply { init(context); clearLoginState() }
        val school = SchoolConfig("recovery-fixture", "测试学校", "recovery.invalid", "https")
        user.addCustomSchool(school)
        user.currentSchool = school
        user.studentId = "synthetic-student"
        user.saveCookieLogin("SESSION=synthetic-only")
        key = CourseReminderKey(user.currentAccountStorageKey, term, course.id)
        resetScheduler()
        scheduler.updateTimeBase(key.account, term, ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00"), mapOf(1 to "08:45")))
        scheduler.setDefaultLead(key.account, 10)
    }

    @After fun teardown() { TimeZone.setDefault(zone) }

    private fun reminderAlarm() = shadowOf(alarms).scheduledAlarms.single {
        shadowOf(it.operation).savedIntent.action == ScheduleReminderScheduler.ACTION
    }

    private fun deliver(intent: Intent) = CourseReminderReceiver().onReceive(context, intent)

    @Test fun tenMinuteReminderRestoresTheMissingSystemAlarmAndDeliversAfterProcessRecreation() {
        scheduler.setEnabled(key, course, true)
        val original = reminderAlarm()
        val broadcast = Intent(shadowOf(original.operation).savedIntent)
        assertEquals(time(7, 7, 50), original.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, original.type)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, original.windowLengthMs)
        assertTrue(original.isAllowWhileIdle)
        // OEM cleanup can remove the system entry while the PendingIntent token remains.
        alarms.cancel(requireNotNull(original.operation))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertNotNull(PendingIntent.getBroadcast(context, 0, broadcast,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        resetAccountProcess()
        setTime(7, 7, 51)
        ScheduleBackgroundRefresh.refresh(context)
        assertEquals(key.account, activeScheduleAccount())
        assertEquals(time(7, 7, 50), reminderAlarm().triggerAtMs)
        deliver(broadcast)
        val delivered = notifications.getNotification(key.storageId, 1)
        assertNotNull(delivered)
        assertEquals("后台课程 · 即将上课", delivered.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(time(14, 7, 50), reminderAlarm().triggerAtMs)
        // A delayed duplicate cannot notify again or overwrite next week's alarm extras.
        deliver(broadcast)
        assertSame(delivered, notifications.getNotification(key.storageId, 1))
        assertEquals(1, notifications.size())
        assertEquals(time(14, 7, 50), reminderAlarm().triggerAtMs)
    }

    @Test fun expiredOnlineLoginRetainsLocalRemindersButLogoutCancelsThem() {
        scheduler.setEnabled(key, course, true)
        val user = UserManager.getInstance()
        user.sessionState.expire(user.sessionState.token)
        scheduler.reconcile(restoreAlarms = true)
        assertEquals(key.account, activeScheduleAccount())
        val broadcast = Intent(shadowOf(reminderAlarm().operation).savedIntent)
        setTime(7, 7, 50)
        deliver(broadcast)
        assertEquals(1, notifications.size())
        user.logout()
        scheduler.reconcile(restoreAlarms = true)
        assertNull(activeScheduleAccount())
        assertFalse(scheduler.hasScheduledReminders())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertNull(jobs.getPendingJob(ScheduleBackgroundRefresh.JOB_ID))
        assertEquals(0, notifications.size())
    }

    @Test fun deletingTheAccountCancelsItsPendingNotificationAndRecoveryJob() {
        scheduler.setEnabled(key, course, true)
        val old = Intent(shadowOf(reminderAlarm().operation).savedIntent)
        UserManager.getInstance().let { it.deleteAccount(it.currentAccountKey) }
        resetAccountProcess()
        ScheduleBackgroundRefresh.refresh(context)
        setTime(7, 7, 50)
        deliver(old)
        assertNull(activeScheduleAccount())
        assertTrue(UserManager.getInstance().savedAccounts.isEmpty())
        assertFalse(scheduler.hasScheduledReminders())
        assertEquals(0, notifications.size())
        assertNull(jobs.getPendingJob(ScheduleBackgroundRefresh.JOB_ID))
    }

    @Test fun deletingAnotherAccountDoesNotLogOutOrCancelTheCurrentAccount() {
        scheduler.setEnabled(key, course, true)
        val user = UserManager.getInstance()
        val accountToRemove = user.currentAccountKey
        user.studentId = "second-synthetic-student"
        user.saveCookieLogin("SESSION=synthetic-second")
        val current = user.currentAccountStorageKey
        val currentKey = key.copy(account = current)
        scheduler.updateTimeBase(current, term, ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00"), mapOf(1 to "08:45")))
        scheduler.setDefaultLead(current, 10)
        scheduler.setEnabled(currentKey, course, true)
        user.deleteAccount(accountToRemove)
        resetAccountProcess()
        ScheduleBackgroundRefresh.refresh(context)
        assertEquals(current, activeScheduleAccount())
        assertEquals(1, UserManager.getInstance().savedAccounts.size)
        assertEquals(currentKey.storageId, shadowOf(reminderAlarm().operation).savedIntent.getStringExtra(ScheduleReminderScheduler.EXTRA_REMINDER_ID))
    }

    @Test fun zeroLeadReminderCanBeDeliveredDuringTheShortColdStartGrace() {
        scheduler.setEnabled(key, course, true)
        scheduler.setLead(key, course, 0)
        val broadcast = Intent(shadowOf(reminderAlarm().operation).savedIntent)
        resetAccountProcess()
        setTime(7, 8, 2)
        ScheduleBackgroundRefresh.refresh(context)
        deliver(broadcast)
        assertEquals("后台课程 · 已开始", notifications.getNotification(key.storageId, 1).extras.getString(Notification.EXTRA_TITLE))
        assertEquals(time(14, 8, 0), reminderAlarm().triggerAtMs)
    }

    @Test fun recoveryNeverReplaysAClassAtOrBeyondTheGraceBoundary() {
        scheduler.setEnabled(key, course, true)
        val broadcast = Intent(shadowOf(reminderAlarm().operation).savedIntent)
        resetAccountProcess()
        setTime(7, 8, 5)
        ScheduleBackgroundRefresh.refresh(context)
        deliver(broadcast)
        assertEquals(0, notifications.size())
        assertEquals(time(14, 7, 50), reminderAlarm().triggerAtMs)
    }

    @Test fun olderRevisionCannotFireAfterChangingTheReminder() {
        scheduler.setEnabled(key, course, true)
        val broadcast = Intent(shadowOf(reminderAlarm().operation).savedIntent)
        scheduler.setLead(key, course, 5)
        setTime(7, 7, 50)
        deliver(broadcast)
        assertEquals(0, notifications.size())
        assertEquals(time(7, 7, 55), reminderAlarm().triggerAtMs)
    }

    @Test fun recoveryJobPersistsAndDoesNotRestartItsWindowOnRepeatedUpdates() {
        scheduler.setEnabled(key, course, true)
        val job = jobs.getPendingJob(ScheduleBackgroundRefresh.JOB_ID)!!
        assertTrue(job.isPersisted)
        assertTrue(job.isPeriodic)
        assertEquals(ScheduleBackgroundRefresh.INTERVAL, job.intervalMillis)
        assertEquals(10 * 60_000L, job.flexMillis)
        assertEquals(ComponentName(context, ScheduleRefreshJobService::class.java), job.service)
        assertEquals(JobInfo.NETWORK_TYPE_NONE, job.networkType)
        ScheduleBackgroundRefresh.sync(context)
        assertSame(job, jobs.getPendingJob(ScheduleBackgroundRefresh.JOB_ID))
        scheduler.setEnabled(key, course, false)
        assertNull(jobs.getPendingJob(ScheduleBackgroundRefresh.JOB_ID))
    }

    @Test fun widgetsKeepLocalRecoveryEvenWithoutCourseReminders() {
        val widgets = shadowOf(AppWidgetManager.getInstance(context))
        widgets.createWidget(ScheduleWidgetProvider::class.java, R.layout.schedule_widget)
        ScheduleBackgroundRefresh.sync(context, reminders = false)
        assertNotNull(jobs.getPendingJob(ScheduleBackgroundRefresh.JOB_ID))
        resetAccountProcess()
        ScheduleBackgroundRefresh.refresh(context)
        assertEquals(key.account, ScheduleWidgetUpdater.activeSnapshot(context)?.account)
        val service = context.packageManager.getServiceInfo(ComponentName(context, ScheduleRefreshJobService::class.java), 0)
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
    }

    @Test fun widgetBoundaryIsPreciseAndNeverWakesTheDevice() {
        val pending = PendingIntent.getBroadcast(context, 0,
            Intent(context, ScheduleWidgetProvider::class.java).setAction(ScheduleWidgetUpdater.ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE)
        ScheduleWidgetBoundaryAlarm.schedule(context, time(7, 8, 0), pending)
        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
    }

    @Test @Config(sdk = [33])
    fun deniedExactPermissionUsesAnIdleSafeNonWakeupWidgetAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val pending = PendingIntent.getBroadcast(context, 0,
            Intent(context, ScheduleWidgetProvider::class.java).setAction(ScheduleWidgetUpdater.ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE)
        ScheduleWidgetBoundaryAlarm.schedule(context, time(7, 8, 0), pending)
        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertEquals(AlarmManager.RTC, alarm.type)
        assertTrue(alarm.isAllowWhileIdle)
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        scheduler.setEnabled(key, course, true)
        assertEquals(ReminderAvailability.NeedsPermission, scheduler.status(key).availability)
        assertFalse(scheduler.hasScheduledReminders())
    }

    @Test @Config(sdk = [33])
    fun revokedNotificationPermissionCancelsAlarmsAndPreventsDelivery() {
        scheduler.setEnabled(key, course, true)
        val broadcast = Intent(shadowOf(reminderAlarm().operation).savedIntent)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        scheduler.reconcile(restoreAlarms = true)
        assertEquals(ReminderAvailability.NeedsPermission, scheduler.status(key).availability)
        setTime(7, 7, 50)
        deliver(broadcast)
        assertEquals(0, notifications.size())
        assertFalse(scheduler.hasScheduledReminders())
        assertNull(jobs.getPendingJob(ScheduleBackgroundRefresh.JOB_ID))
    }
}
