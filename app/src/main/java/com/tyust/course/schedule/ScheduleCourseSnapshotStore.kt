package com.tyust.course.schedule

import android.content.SharedPreferences

/**
 * 账号 + 学期的完整课表快照，供自习冲突判定使用。
 *
 * 提醒记录只覆盖「开启了提醒」的课程，而自习判重必须看整张课表：刷新课表新增一门没开提醒
 * 的课程，同样应该压掉与之重叠的自习。快照在课表同步时整体写入，自定义课程增删时就地维护。
 */
internal class ScheduleCourseSnapshotStore(private val preferences: SharedPreferences) {
    private fun key(account: String, term: String): String = "snapshot:$account|$term"

    fun read(account: String, term: String): List<ScheduleCourseRecord>? =
        preferences.getString(key(account, term), null)?.let { ReminderJson.courses(it) }

    fun write(account: String, term: String, courses: List<ScheduleCourseRecord>) {
        if (account.isBlank() || term.isBlank()) return
        val json = ReminderJson.courses(courses)
        if (preferences.getString(key(account, term), null) == json) return
        preferences.edit().putString(key(account, term), json).apply()
    }

    /** 自定义课程的增删只影响一个账号：逐学期快照就地维护。 */
    fun update(account: String, transform: (List<ScheduleCourseRecord>) -> List<ScheduleCourseRecord>) {
        if (account.isBlank()) return
        for (stored in preferences.all.keys.filter { it.startsWith("snapshot:$account|") }) {
            val courses = preferences.getString(stored, null)?.let { ReminderJson.courses(it) } ?: continue
            val updated = transform(courses)
            if (updated != courses) preferences.edit().putString(stored, ReminderJson.courses(updated)).apply()
        }
    }

    fun clear(account: String) {
        if (account.isBlank()) return
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("snapshot:$account|") }.forEach { editor.remove(it) }
        editor.apply()
    }
}
