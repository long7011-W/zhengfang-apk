package com.tyust.course.schedule

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tyust.course.MainActivity
import com.tyust.course.R
import com.tyust.course.manager.UserManager
import com.tyust.course.manager.ScheduleSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ScheduleReminderScheduler private constructor(
    private val context: Context,
    private val clock: () -> Long
) : Application.ActivityLifecycleCallbacks {
    private constructor(context: Context) : this(context, System::currentTimeMillis)
    private val preferences = context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE)
    private val calendars = ScheduleCalendarStore(preferences) {
        ScheduleSettingsManager.getInstance().apply { init(context) }.getWeekStart()
    }
    /** 自习冲突判定用的完整课表快照（提醒记录只覆盖开启了提醒的课程）。 */
    private val snapshots = ScheduleCourseSnapshotStore(preferences)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    var revision by mutableIntStateOf(0)
        private set

    companion object {
        const val ACTION = "com.tyust.course.action.COURSE_REMINDER"
        const val CHANNEL = "course_reminders"
        const val EXTRA_REMINDER_ID = "course_reminder_id"
        const val EXTRA_STARTS_AT = "course_reminder_starts_at"
        const val EXTRA_REVISION = "course_reminder_revision"
        const val EXTRA_TRIGGER = "course_reminder_trigger"
        /** 闹钟迟到投递的容忍窗口：超过它说明这节课已经过半，只续排下一次，不再补发提醒。 */
        private const val LATE_DELIVERY_WINDOW = 10 * 60_000L
        @Volatile private var instance: ScheduleReminderScheduler? = null
        @JvmStatic fun get(context: Context): ScheduleReminderScheduler = instance ?: synchronized(this) {
            instance ?: ScheduleReminderScheduler(context.applicationContext).also { instance = it }
        }
    }

    fun start(application: Application) {
        if (started) return
        started = true
        // Application is created before an alarm Receiver. Restore the account before any
        // reconciliation; the default, uninitialized session would cancel every alarm.
        UserManager.getInstance().init(application)
        application.registerActivityLifecycleCallbacks(this)
        scope.launch {
            var coldStart = true
            UserManager.getInstance().sessionState.state.map { it.token.accountStorageKey to it.expired }
                .distinctUntilChanged().collect { reconcile(restoreAlarms = coldStart); coldStart = false }
        }
    }

    private fun activeAccount(): String = activeScheduleAccount().orEmpty()

    internal fun hasScheduledReminders(): Boolean = scheduledPlans().isNotEmpty()

    private fun records() = ReminderJson.decode(preferences.getString("records", null))
    private fun save(records: List<CourseReminder>) {
        preferences.edit().putString("records", ReminderJson.encode(records)).apply()
        revision++
    }

    fun find(key: CourseReminderKey): CourseReminder? = records().firstOrNull { it.key == key }
    fun encodeUndo(records: List<CourseReminder>) = ReminderJson.encode(records)
    fun restoreUndo(value: String) {
        val restore = ReminderJson.decode(value)
        val ids = restore.map { it.key.storageId }.toSet()
        save(records().filter { it.key.storageId !in ids } + restore.map { it.copy(revision = it.revision + 1) })
        reconcile()
    }

    @Synchronized
    fun setEnabled(key: CourseReminderKey, course: ScheduleCourseRecord, enabled: Boolean) {
        val current = records()
        val old = current.firstOrNull { it.key == key }
        val updated = CourseReminder(key, course, enabled, old?.leadMinutes ?: defaultLead(key.account), (old?.revision ?: 0) + 1, old?.customLead ?: false)
        save(current.filter { it.key != key } + updated)
        reconcile()
    }

    @Synchronized
    fun setSemesterEnabled(account: String, term: String, courses: List<ScheduleCourseRecord>, enabled: Boolean): Boolean {
        // A UI callback from the previous account cannot create intent for the new session.
        if (account != activeAccount() || account.isBlank() || term.isBlank() || courses.isEmpty()) return false
        val old = records()
        val updated = SemesterReminders.update(old, account, term, courses, enabled, defaultLead(account))
        // 顺手把完整课表快照留下来：批次里没开提醒的课程也要参与自习判重。
        snapshots.write(account, term, courses)
        if (updated != old) save(updated)
        reconcile()
        return true
    }

    fun semesterSummary(account: String, term: String, courses: List<ScheduleCourseRecord>): SemesterReminderSummary =
        SemesterReminders.summary(records(), account, term, courses, timeBase(account, term), permissions(), activeAccount(), clock())

    @Synchronized
    fun updateSnapshot(account: String, term: String, courses: List<ScheduleCourseRecord>) {
        if (account.isBlank() || term.isBlank()) return
        val byId = courses.associateBy { it.id }
        val old = records()
        val updated = old.mapNotNull { reminder ->
            // 自习记录由 StudyReminders 单独维护，课表快照不能把它们当「失效课程」丢掉。
            if (reminder.study != null || reminder.key.account != account || reminder.key.term != term) reminder else {
                byId[reminder.key.courseId]?.let { course ->
                    if (reminder.course == course) reminder else reminder.copy(course = course, revision = reminder.revision + 1)
                }
            }
        }
        if (updated != old) save(updated)
        snapshots.write(account, term, courses)
        reconcile()
    }

    @Synchronized
    fun updateCustomCourse(account: String, course: ScheduleCourseRecord) {
        val old = records()
        val updated = old.map { if (it.key.account == account && it.key.courseId == course.id && it.course != course)
            it.copy(course = course, revision = it.revision + 1) else it }
        if (old != updated) save(updated)
        snapshots.update(account) { list ->
            if (list.any { it.id == course.id }) list.map { if (it.id == course.id) course else it } else list + course
        }
        reconcile()
    }

    @Synchronized
    fun removeCourse(account: String, courseId: String): List<CourseReminder> {
        val old = records()
        val removed = old.filter { it.key.account == account && it.key.courseId == courseId }
        save(old - removed.toSet())
        snapshots.update(account) { list -> list.filterNot { it.id == courseId } }
        reconcile()
        return removed
    }

    @Synchronized
    fun clearAccount(account: String) {
        save(records().filter { it.key.account != account })
        snapshots.clear(account)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("calendar:$account|") }.forEach { editor.remove(it) }
        editor.remove("legacy-calendar-migrated:$account")
        editor.apply()
        reconcile()
    }

    fun timeBase(account: String, term: String): ScheduleTimeBase? = calendars.read(account, term)

    fun defaultLead(account: String): Int = preferences.getInt("default-lead:$account", 15).coerceIn(0, 1440)

    @Synchronized fun setDefaultLead(account: String, minutes: Int) {
        require(minutes in 0..1440 && account.isNotBlank())
        preferences.edit().putInt("default-lead:$account", minutes).apply()
        save(records().map { if (it.key.account == account && !it.customLead)
            it.copy(leadMinutes = minutes, revision = it.revision + 1) else it })
        reconcile()
    }

    @Synchronized fun setLead(key: CourseReminderKey, course: ScheduleCourseRecord, minutes: Int?) {
        require(minutes == null || minutes in 0..1440)
        val old = find(key) ?: CourseReminder(key, course)
        save(records().filterNot { it.key == key } + old.copy(leadMinutes = minutes ?: defaultLead(key.account),
            customLead = minutes != null, revision = old.revision + 1))
        reconcile()
    }

    fun updateAdjustments(account: String, term: String, value: ScheduleAdjustments) {
        val base = timeBase(account, term) ?: ScheduleTimeBase()
        if (calendars.write(account, term, base.copy(adjustments = value))) { revision++; reconcile() }
    }

    fun updateTimeBase(account: String, term: String, value: ScheduleTimeBase) {
        val retained = value.copy(adjustments = timeBase(account, term)?.adjustments ?: value.adjustments)
        if (!calendars.write(account, term, retained)) return
        revision++
        reconcile()
    }

    fun migrateLegacyTimeBase(account: String, currentTerm: String, value: ScheduleTimeBase) {
        if (calendars.migrateLegacy(account, currentTerm, value)) { revision++; reconcile() }
    }

    fun permissions(): ReminderPermissions {
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            (Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
        return ReminderPermissions(notifications, Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms())
    }

    fun status(key: CourseReminderKey): ReminderStatus = find(key)?.let {
        CourseReminderPlanner.plan(it, timeBase(key.account, key.term), clock(), permissions(), activeAccount())
    } ?: ReminderStatus(ReminderAvailability.Off)

    private fun alarmIntent(key: CourseReminderKey) = Intent(context, CourseReminderReceiver::class.java).apply {
        action = ACTION
        data = Uri.Builder().scheme("course-reminder").authority("alarm").appendPath(key.storageId).build()
    }

    private fun scheduledPlans(): Map<String, PlannedReminder> = runCatching {
        val json = JSONArray(preferences.getString("scheduled", "[]"))
        (0 until json.length()).mapNotNull { index -> runCatching {
            val item = json.getJSONObject(index)
            val plan = PlannedReminder(ReminderJson.reminder(item.getJSONObject("reminder")), item.getLong("trigger"), item.getLong("start"))
            plan.reminder.key.storageId to plan
        }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    private fun savePlans(plans: Map<String, PlannedReminder>) {
        preferences.edit().putString("scheduled", JSONArray().apply {
            plans.values.forEach { plan -> put(JSONObject().apply {
                put("reminder", ReminderJson.reminder(plan.reminder)); put("trigger", plan.triggerAt); put("start", plan.startsAt)
            }) }
        }.toString()).apply()
    }

    private val alarms = object : ReminderAlarmPort {
        override fun scheduled() = scheduledPlans()
        override fun exists(key: CourseReminderKey): Boolean = PendingIntent.getBroadcast(context, 0, alarmIntent(key),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
        override fun schedule(plan: PlannedReminder) {
            val key = plan.reminder.key
            val intent = alarmIntent(key).putExtra(EXTRA_REMINDER_ID, key.storageId)
                .putExtra(EXTRA_REVISION, plan.reminder.revision).putExtra(EXTRA_TRIGGER, plan.triggerAt)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pending)
            savePlans(scheduledPlans() + (key.storageId to plan))
        }
        override fun cancel(key: CourseReminderKey) {
            PendingIntent.getBroadcast(context, 0, alarmIntent(key), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarmManager.cancel(it); it.cancel()
            }
            savePlans(scheduledPlans() - key.storageId)
            NotificationManagerCompat.from(context).cancel(key.storageId, 1)
        }
    }

    @Synchronized
    fun reconcile(resetAlarms: Boolean = false, restoreAlarms: Boolean = false) {
        val before = records()
        val synced = withStudyReminders(before)
        if (synced != before) save(synced)
        val permission = permissions()
        try {
            // A PendingIntent token may outlive its AlarmManager entry after an OEM cleanup.
            // Re-register on cold start/recovery without discarding the due occurrence first.
            reconcileCourseReminders(records(), activeAccount(), { timeBase(it.account, it.term) }, permission,
                clock(), alarms, restoreAlarms = resetAlarms || restoreAlarms)
        } catch (_: SecurityException) {
            scheduledPlans().values.forEach { alarms.cancel(it.reminder.key) }
        }
        ScheduleBackgroundRefresh.sync(context, hasScheduledReminders())
        revision++
    }

    /**
     * 按「课表 + 自习配置 + 学期提醒总开关」重算自习提醒：自习和课程共用同一批闹钟，
     * 因此课程变动、删除课程、换学期都会在下次 reconcile 时把自习提醒一并对齐。
     */
    private fun withStudyReminders(reference: List<CourseReminder>): List<CourseReminder> {
        var result = reference
        for ((account, term) in reference.map { it.key.account to it.key.term }.distinct()) {
            val enabled = result.any { it.key.account == account && it.key.term == term && it.study == null && it.enabled }
            // 冲突判定用账号 + 学期的完整课表快照：新课程即使没开自己的提醒，也要参与自习判重。
            result = StudyReminders.sync(result, account, term, studyBlocks(account, term), snapshots.read(account, term),
                timeBase(account, term), enabled, defaultLead(account))
        }
        return result
    }

    private fun studyBlocks(account: String, term: String): List<ScheduleStudyBlock> =
        ScheduleSettingsManager.getInstance().apply { init(context) }.getStudyBlocks(account, term)

    /**
     * 日历本身变了（例如切换每周第一天）：已登记的闹钟必须按新日历重排，
     * 不能依赖「归一化后的日历比较」来决定是否重算。
     */
    @Synchronized
    fun reschedule(account: String, term: String): Boolean {
        if (account.isBlank() || term.isBlank()) return false
        records().filter { it.key.account == account && it.key.term == term }.forEach { alarms.cancel(it.key) }
        reconcile()
        return true
    }

    @Synchronized
    fun receive(id: String, expectedRevision: Long, expectedTrigger: Long) {
        val plan = scheduledPlans()[id] ?: return
        val record = records().firstOrNull { it.key.storageId == id } ?: return
        if (record.revision != expectedRevision || plan.triggerAt != expectedTrigger) return
        if (!isCurrentReminderOccurrence(plan, record, timeBase(record.key.account, record.key.term), permissions(), activeAccount())) {
            reconcile(); return
        }
        val now = clock()
        if (now < plan.triggerAt) return
        // An old broadcast must never replace or advance a newer pending occurrence.
        savePlans(scheduledPlans() - id)
        // Doze / OEM 清理会让闹钟晚到：迟到 10 分钟内仍然提醒（文案标注已开始），
        // 再晚说明这节课已过半，只续排下一次，不再补发。上游 canDeliverAt 的 5 分钟窗口
        // 继续用于 reconcile 判定，这里只放宽投递侧。
        if (now < plan.startsAt + LATE_DELIVERY_WINDOW) {
            val late = now >= plan.startsAt
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "课程提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                data = Uri.Builder().scheme("course-reminder").authority("open").appendPath(id).build()
                putExtra(EXTRA_REMINDER_ID, id)
                putExtra(EXTRA_STARTS_AT, plan.startsAt)
                putExtra("pageId", com.tyust.course.academic.plugin.PluginPageRegistry.SCHEDULE)
            }
            val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val course = occurrence(record, plan.startsAt)?.course ?: record.course
            val study = record.study
            val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_course_reminder)
                .setContentTitle("${course.name} · " + (if (late) "已开始上课" else if (study == null) "即将上课" else "即将开始"))
                .setContentText(
                    if (study == null) listOf(course.location, "第 ${course.startPeriod}-${course.endPeriod} 节").filter { it.isNotBlank() }.joinToString(" · ")
                    else "${study.start} – ${study.end}"
                )
                .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true).build()
            try { NotificationManagerCompat.from(context).notify(id, 1, notification) } catch (_: SecurityException) { }
        }
        reconcile()
    }

    fun findById(id: String): CourseReminder? = records().firstOrNull { it.key.storageId == id && it.key.account == activeAccount() }
    fun occurrence(reminder: CourseReminder, startsAt: Long): ScheduleOccurrence? =
        CourseReminderPlanner.occurrence(reminder, timeBase(reminder.key.account, reminder.key.term), startsAt)
    override fun onActivityResumed(activity: Activity) { reconcile() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
