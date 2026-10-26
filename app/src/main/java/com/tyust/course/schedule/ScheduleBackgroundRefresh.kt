package com.tyust.course.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.tyust.course.manager.UserManager

/** System-owned, local-only recovery; no foreground process or network connection is needed. */
internal object ScheduleBackgroundRefresh {
    const val JOB_ID = 480_033
    const val INTERVAL = 30 * 60_000L

    fun sync(context: Context, reminders: Boolean = ScheduleReminderScheduler.get(context).hasScheduledReminders()) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        val widgets = AppWidgetManager.getInstance(context)
        val needed = reminders || ClassQuietManager.get(context).needsBackgroundRefresh || ScheduleWidgetStyle.entries.any {
            widgets.getAppWidgetIds(ComponentName(context, it.provider)).isNotEmpty()
        }
        if (!needed) { jobs.cancel(JOB_ID); return }
        val component = ComponentName(context, ScheduleRefreshJobService::class.java)
        val existing = jobs.getPendingJob(JOB_ID)
        // Frequent UI/cache changes must not keep restarting the periodic job's window.
        if (existing?.service == component && existing.isPersisted && existing.intervalMillis == INTERVAL) return
        jobs.schedule(JobInfo.Builder(JOB_ID, component).setPersisted(true)
            .setPeriodic(INTERVAL, 10 * 60_000L).build())
    }

    fun refresh(context: Context) {
        UserManager.getInstance().init(context)
        ScheduleReminderScheduler.get(context).reconcile(restoreAlarms = true)
        ClassQuietManager.get(context).reconcile()
        ScheduleWidgetUpdater.update(context)
    }
}

class ScheduleRefreshJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        try { ScheduleBackgroundRefresh.refresh(this) }
        catch (failure: RuntimeException) { Log.w("ScheduleRefresh", "Local refresh failed: ${failure.javaClass.simpleName}") }
        return false
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}

internal object ScheduleWidgetBoundaryAlarm {
    fun schedule(context: Context, at: Long, pending: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try { alarms.setExact(AlarmManager.RTC, at, pending); return }
            catch (_: SecurityException) { /* Permission can change after the check. */ }
        }
        // The widget does not wake a sleeping device, and missing exact permission is allowed.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, pending)
    }
}
