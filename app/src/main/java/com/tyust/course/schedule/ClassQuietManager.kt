package com.tyust.course.schedule

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

data class ClassQuietStatus(
    val enabled: Boolean = false,
    val policyAccess: Boolean = false,
    val exactAlarms: Boolean = true,
    val fixedVolume: Boolean = false,
    val restorePending: Boolean = false,
    val summary: String = "按课表静音，下课恢复原来的声音模式"
)

internal data class ClassQuietSound(val ringer: Int, val filter: Int)

internal interface ClassQuietSoundPort {
    val policyAccess: Boolean
    val fixedVolume: Boolean
    fun read(): ClassQuietSound
    fun ringer(mode: Int)
    fun filter(value: Int)
}

private class AndroidClassQuietSound(context: Context) : ClassQuietSoundPort {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    override val policyAccess get() = notifications.isNotificationPolicyAccessGranted
    override val fixedVolume get() = audio.isVolumeFixed
    override fun read() = ClassQuietSound(audio.ringerMode, notifications.currentInterruptionFilter)
    override fun ringer(mode: Int) { audio.ringerMode = mode }
    override fun filter(value: Int) { notifications.setInterruptionFilter(value) }
}

/** Original sound is committed before changing the device and survives process death/reboots. */
private data class ClassQuietLease(
    val account: String, val window: ClassQuietWindow, val original: ClassQuietSound,
    val appliedFilter: Int? = null, val overridden: Boolean = false, val failed: Boolean = false,
    val restoring: Boolean = false
) {
    fun json() = JSONObject().put("account", account).put("start", window.startsAt).put("end", window.endsAt)
        .put("ringer", original.ringer).put("filter", original.filter).put("appliedFilter", appliedFilter)
        .put("overridden", overridden).put("failed", failed).put("restoring", restoring).toString()

    companion object {
        fun read(value: String?): ClassQuietLease? = runCatching {
            val row = JSONObject(value ?: return null)
            ClassQuietLease(row.getString("account"), ClassQuietWindow(row.getLong("start"), row.getLong("end")),
                ClassQuietSound(row.getInt("ringer"), row.getInt("filter")),
                if (row.has("appliedFilter") && !row.isNull("appliedFilter")) row.getInt("appliedFilter") else null,
                row.optBoolean("overridden"), row.optBoolean("failed"), row.optBoolean("restoring"))
                .takeIf { it.account.isNotBlank() && it.window.endsAt > it.window.startsAt && it.original.ringer in 0..2 }
        }.getOrNull()
    }
}

class ClassQuietManager internal constructor(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sound: ClassQuietSoundPort = AndroidClassQuietSound(context)
) : Application.ActivityLifecycleCallbacks {
    private val settings = context.getSharedPreferences("class_quiet_settings", Context.MODE_PRIVATE)
    // Device sound ownership must never be restored from cloud backup onto another device.
    private val runtime = context.getSharedPreferences("class_quiet_runtime", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val observed = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()
    private var started = false
    private var reconciling = false
    private val refresh = Runnable { reconcile() }
    var status by mutableStateOf(ClassQuietStatus(enabled = enabled))
        private set
    val enabled get() = settings.getBoolean("enabled", false)
    internal val needsBackgroundRefresh get() = enabled || lease() != null

    companion object {
        const val ACTION = "com.tyust.course.action.CLASS_QUIET_BOUNDARY"
        private const val EXTRA_AT = "class_quiet_at"
        @Volatile private var instance: ClassQuietManager? = null
        @JvmStatic fun get(context: Context): ClassQuietManager = instance ?: synchronized(this) {
            instance ?: ClassQuietManager(context.applicationContext).also { instance = it }
        }
    }

    fun start(application: Application) {
        if (started) return
        started = true
        UserManager.getInstance().init(context)
        application.registerActivityLifecycleCallbacks(this)
        for (name in listOf("schedule_cache", "schedule_settings", "course_reminders")) {
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (name != "course_reminders" || key == null || key.startsWith("calendar:")) requestRefresh()
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            observed += preferences to listener
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { requestRefresh() }
        }, IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            UserManager.getInstance().sessionState.state.map { it.token.accountStorageKey to it.expired }
                .distinctUntilChanged().collect { reconcile() }
        }
        reconcile()
    }

    private fun requestRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 100)
    }

    fun setEnabled(value: Boolean) {
        if (!settings.edit().putBoolean("enabled", value).commit()) return
        reconcile()
    }

    private fun lease() = ClassQuietLease.read(runtime.getString("lease", null))
    private fun save(value: ClassQuietLease) = runtime.edit().putString("lease", value.json()).commit()
    private fun clearLease() { runtime.edit().remove("lease").commit() }

    private fun pending(at: Long? = null): PendingIntent? {
        val intent = Intent(context, ClassQuietReceiver::class.java).setAction(ACTION)
            .setData(Uri.parse("class-quiet://boundary"))
        if (at != null) intent.putExtra(EXTRA_AT, at)
        return PendingIntent.getBroadcast(context, 0, intent,
            (if (at == null) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT) or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAlarm() {
        pending()?.let { alarms.cancel(it); it.cancel() }
        runtime.edit().remove("next_at").apply()
    }

    private fun schedule(at: Long?): Boolean {
        cancelAlarm()
        if (at == null) return true
        val token = requireNotNull(pending(at))
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, token)
            check(runtime.edit().putLong("next_at", at).commit())
            return true
        } catch (_: SecurityException) {
            token.cancel()
            // Permission may have been revoked between checking it and scheduling the restore.
            val restored = restore()
            status = status.copy(exactAlarms = false, restorePending = !restored,
                summary = if (restored) "已暂停，请允许闹钟和提醒后重试" else "原声音模式尚未恢复，请允许勿扰控制或手动调整")
            return false
        }
    }

    internal fun receive(intent: Intent) {
        if (intent.action == ACTION && intent.getLongExtra(EXTRA_AT, -1) != runtime.getLong("next_at", -2)) return
        reconcile()
    }

    /** Only restore sound that is still ours; a manual change wins for the rest of this lesson. */
    private fun restore(): Boolean {
        val old = lease() ?: return true
        if (old.overridden || old.original.ringer == AudioManager.RINGER_MODE_SILENT) { clearLease(); return true }
        return try {
            val current = sound.read()
            if (old.restoring && current == old.original) { clearLease(); return true }
            if ((current.ringer != AudioManager.RINGER_MODE_SILENT &&
                    !(old.restoring && current.ringer == old.original.ringer)) ||
                (old.appliedFilter != null && current.filter != old.appliedFilter &&
                    !(old.restoring && current.filter == old.original.filter))) {
                clearLease(); true
            } else {
                // A partial restore must remain retryable even after the ringer has changed.
                if (!save(old.copy(restoring = true))) return false
                if (current.ringer != old.original.ringer) sound.ringer(old.original.ringer)
                val afterRinger = sound.read()
                if (!save(old.copy(restoring = true, appliedFilter = afterRinger.filter))) return false
                // Some devices change DND together with silent mode. Restore that change too.
                if (afterRinger.filter != old.original.filter)
                    sound.filter(old.original.filter)
                if (sound.read() == old.original) { clearLease(); true } else false
            }
        } catch (_: RuntimeException) { false }
    }

    @Synchronized fun reconcile() {
        if (reconciling) return
        reconciling = true
        try {
            val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
            status = ClassQuietStatus(enabled, sound.policyAccess, exact, sound.fixedVolume)
            val user = UserManager.getInstance()
            val account = activeScheduleAccount(user)
            val snapshot = account?.let { key -> user.currentSchool?.let { ScheduleRepository(context).snapshot(key, it.id) } }
            val now = clock()
            val plan = ClassQuietPlanner.plan(snapshot, now)
            val ready = enabled && account != null && status.policyAccess && exact && !status.fixedVolume
            val active = plan.active.takeIf { ready }
            var current = lease()
            val same = current != null && !current.restoring && active != null && current.account == account &&
                active.startsAt < current.window.endsAt && current.window.startsAt < active.endsAt
            if (current != null && !same) {
                if (!restore()) {
                    cancelAlarm()
                    status = status.copy(restorePending = true, summary = "原声音模式尚未恢复，请允许勿扰控制或手动调整")
                    return
                }
                current = null
            }
            // Arm the end boundary before muting, so a killed process can still restore sound.
            if (!schedule(plan.nextChangeAt.takeIf { ready })) return
            if (active != null) {
                if (current == null) {
                    current = ClassQuietLease(requireNotNull(account), active, sound.read())
                    if (!save(current)) { cancelAlarm(); status = status.copy(summary = "无法保存恢复信息，尚未切换静音"); return }
                    try {
                        if (current.original.ringer != AudioManager.RINGER_MODE_SILENT) sound.ringer(AudioManager.RINGER_MODE_SILENT)
                        val applied = sound.read()
                        current = current.copy(appliedFilter = applied.filter, failed = applied.ringer != AudioManager.RINGER_MODE_SILENT)
                    } catch (_: RuntimeException) { current = current.copy(failed = true) }
                } else {
                    val actual = sound.read()
                    current = current.copy(window = active, overridden = current.overridden ||
                        actual.ringer != AudioManager.RINGER_MODE_SILENT ||
                        current.appliedFilter != null && actual.filter != current.appliedFilter)
                }
                save(current)
                status = status.copy(summary = when {
                    current.failed -> "系统未能切换静音，请检查声音设置"
                    current.overridden -> "本次课程保留你手动调整的声音模式"
                    else -> "上课静音中 · ${formatTime(active.endsAt)} 恢复原模式"
                })
            } else status = status.copy(summary = when {
                !enabled -> "按课表静音，下课恢复原来的声音模式"
                !status.policyAccess -> "等待授权勿扰控制"
                !exact -> "等待授权闹钟和提醒"
                status.fixedVolume -> "此设备不支持调整铃声模式"
                account == null -> "登录账号后按本学期课表运行"
                snapshot?.courses.isNullOrEmpty() -> "暂无课程，请先同步或添加课表"
                !plan.hasTimes -> "请先在课表设置中补全开学日期、周次和节次时间"
                plan.nextChangeAt == null -> "本学期已无后续课程"
                else -> "已开启 · 下次上课 ${formatTime(plan.nextChangeAt)}"
            })
        } catch (_: RuntimeException) {
            cancelAlarm()
            val restored = restore()
            status = status.copy(restorePending = !restored, summary = if (restored)
                "已暂停，请检查课表和声音权限后重试" else "原声音模式尚未恢复，请允许勿扰控制或手动调整")
        } finally {
            reconciling = false
            ScheduleBackgroundRefresh.sync(context)
        }
    }

    private fun formatTime(at: Long) = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(java.util.Date(at))
    override fun onActivityResumed(activity: Activity) { reconcile() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

class ClassQuietReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UserManager.getInstance().init(context)
        ClassQuietManager.get(context).receive(intent)
    }
}
