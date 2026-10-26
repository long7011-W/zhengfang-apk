package com.tyust.course.schedule

import android.content.SharedPreferences
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.ScheduleSettingsManager.PeriodTime
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A reusable copy of timing settings; it never contains a semester date or courses. */
data class ScheduleTiming(val count: Int, val times: List<PeriodTime>) {
    /** Old editor state is a restore snapshot, even if its clocks overlap or are malformed. */
    fun snapshotError(): String? {
        if (count !in ScheduleSettingsManager.PERIOD_COUNT_MIN..ScheduleSettingsManager.PERIOD_COUNT_MAX)
            return "每天节数应为 8–16 节"
        if (times.map { it.period } != (1..count).toList()) return "请按顺序设置每一节课"
        return null
    }

    fun error(requireComplete: Boolean = true): String? {
        snapshotError()?.let { return it }
        var previousEnd = -1
        for (time in times) {
            if (!requireComplete && time.startTime.isBlank() && time.endTime.isBlank()) continue
            val start = minutes(time.startTime) ?: return "请设置第 ${time.period} 节的开始时间（HH:mm）"
            val end = minutes(time.endTime) ?: return "请设置第 ${time.period} 节的结束时间（HH:mm）"
            if (end <= start) return "第 ${time.period} 节结束时间须晚于开始时间"
            if (start < previousEnd) return "第 ${time.period} 节与上一节时间重叠"
            previousEnd = end
        }
        return null
    }

    fun on(base: ScheduleTimeBase): ScheduleTimeBase = base.copy(
        periodStarts = times.associate { it.period to it.startTime },
        periodEnds = times.associate { it.period to it.endTime }
    )

    companion object {
        /** Keep term-only rows (including periods 13–16) when overlaying the legacy timetable. */
        fun mergePeriodTimes(base: ScheduleTimeBase?, legacy: List<PeriodTime>): List<PeriodTime> {
            val indexed = legacy.associateBy { it.period }
            return (indexed.keys + base?.periodStarts.orEmpty().keys + base?.periodEnds.orEmpty().keys)
                .sorted().map { period -> PeriodTime(period,
                    base?.periodStarts?.get(period) ?: indexed[period]?.startTime.orEmpty(),
                    base?.periodEnds?.get(period) ?: indexed[period]?.endTime.orEmpty()) }
        }

        fun from(count: Int, times: List<PeriodTime>): ScheduleTiming {
            val indexed = times.associateBy { it.period }
            return ScheduleTiming(count, (1..count).map { indexed[it] ?: PeriodTime(it, "", "") })
        }
        private fun minutes(value: String): Int? {
            if (!Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)) return null
            return value.substring(0, 2).toInt() * 60 + value.substring(3).toInt()
        }
    }
}

/** Apply and undo share this path; other semesters keep inheriting the unchanged legacy clocks. */
internal fun applyScheduleTimingToTerm(settings: ScheduleSettingsManager, timing: ScheduleTiming,
    base: ScheduleTimeBase, writeCalendar: (ScheduleTimeBase) -> Unit) {
    require(timing.snapshotError() == null) { timing.snapshotError().orEmpty() }
    writeCalendar(timing.on(base))
    // Count already is account-wide in the app. Do not also overwrite account-wide period times.
    settings.periodCount = timing.count
}

data class ScheduleTimePreset(val id: String, val name: String, val timing: ScheduleTiming)
data class ScheduleTimingUndo(val previous: ScheduleTiming, val applied: ScheduleTiming)

/** Account-scoped preset library, with one undo record per target semester. */
class ScheduleTimePresetStore(private val preferences: SharedPreferences) {
    companion object { const val LIMIT = 12; const val NAME_LIMIT = 32 }
    private fun key(account: String) = "time-presets:" + JSONArray().put(account).toString()
    private fun undoKey(account: String, term: String) = "time-presets-undo:" + JSONArray().put(account).put(term).toString()

    fun list(account: String): List<ScheduleTimePreset> {
        if (account.isBlank()) return emptyList()
        return runCatching { read(account) }.getOrDefault(emptyList())
    }

    private fun read(account: String): List<ScheduleTimePreset> {
        val raw = preferences.getString(key(account), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            require(array.length() <= LIMIT)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                ScheduleTimePreset(item.getString("id"), item.getString("name"), decode(item.getJSONObject("timing")))
            }.also { items ->
                require(items.map { it.id }.distinct().size == items.size)
                require(items.all { it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= NAME_LIMIT && it.timing.error() == null })
            }
        } catch (error: Exception) {
            throw IllegalStateException("已保存的作息无法读取，原数据已保留", error)
        }
    }

    /** Preserve a valid existing timetable once, without changing the active settings. */
    fun seed(account: String, current: ScheduleTiming): List<ScheduleTimePreset> {
        require(account.isNotBlank())
        if (!preferences.contains(key(account)) && current.error() == null)
            save(account, ScheduleTimePreset(UUID.randomUUID().toString(), "原有作息", current))
        return read(account)
    }

    fun save(account: String, preset: ScheduleTimePreset) {
        require(account.isNotBlank() && preset.id.isNotBlank())
        require(preset.name.isNotBlank() && preset.name.length <= NAME_LIMIT)
        require(preset.timing.error() == null) { preset.timing.error().orEmpty() }
        val old = read(account)
        require(old.any { it.id == preset.id } || old.size < LIMIT) { "最多保存 $LIMIT 套作息" }
        write(account, if (old.any { it.id == preset.id }) old.map { if (it.id == preset.id) preset else it } else old + preset)
    }

    fun delete(account: String, id: String) = write(account, read(account).filterNot { it.id == id })

    /** Keep the previous undo if the host rejects an outdated account/semester or fails to apply. */
    fun apply(account: String, term: String, previous: ScheduleTiming, next: ScheduleTiming,
        update: (ScheduleTiming) -> Boolean): Boolean {
        val undoKey = undoKey(account, term)
        val oldUndo = preferences.getString(undoKey, null)
        rememberApply(account, term, previous, next)
        fun restoreUndo() {
            check(preferences.edit().putString(undoKey, oldUndo).commit()) { "撤回记录未保存，请重试" }
        }
        return try {
            update(next).also { if (!it) restoreUndo() }
        } catch (error: Exception) {
            restoreUndo()
            throw error
        }
    }

    private fun write(account: String, presets: List<ScheduleTimePreset>) {
        require(account.isNotBlank())
        check(preferences.edit().putString(key(account), JSONArray().apply {
            presets.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("timing", encode(it.timing))) }
        }.toString()).commit()) { "作息未保存，请重试" }
    }

    fun rememberApply(account: String, term: String, previous: ScheduleTiming, applied: ScheduleTiming) {
        require(account.isNotBlank() && term.isNotBlank())
        require(previous.snapshotError() == null) { previous.snapshotError().orEmpty() }
        require(applied.error() == null) { applied.error().orEmpty() }
        check(preferences.edit().putString(undoKey(account, term), JSONObject()
            .put("previous", encode(previous)).put("applied", encode(applied)).toString()).commit())
    }

    /** A later manual edit invalidates undo rather than silently overwriting that edit. */
    fun undo(account: String, term: String, current: ScheduleTiming): ScheduleTimingUndo? {
        if (account.isBlank() || term.isBlank()) return null
        return preferences.getString(undoKey(account, term), null)?.let { raw -> runCatching {
            val json = JSONObject(raw)
            ScheduleTimingUndo(decode(json.getJSONObject("previous")), decode(json.getJSONObject("applied")))
                .takeIf { it.applied == current && it.applied.error() == null && it.previous.snapshotError() == null }
        }.getOrNull() }
    }

    fun clearUndo(account: String, term: String) {
        check(preferences.edit().remove(undoKey(account, term)).commit())
    }

    private fun encode(timing: ScheduleTiming): JSONObject = JSONObject().put("count", timing.count).put("times", JSONArray().apply {
        timing.times.forEach { put(JSONObject().put("period", it.period).put("start", it.startTime).put("end", it.endTime)) }
    })

    private fun decode(json: JSONObject): ScheduleTiming {
        val array = json.getJSONArray("times")
        require(array.length() <= ScheduleSettingsManager.PERIOD_COUNT_MAX)
        return ScheduleTiming(json.getInt("count"), (0 until array.length()).map { i ->
            val time = array.getJSONObject(i)
            PeriodTime(time.getInt("period"), time.getString("start"), time.getString("end"))
        })
    }
}
