package com.tyust.course.schedule

import org.json.JSONArray
import org.json.JSONObject

/** Display-only metadata. It never contributes to course identity or loads external resources. */
data class ScheduleDetail(val id: String, val label: String, val value: String, val weeks: List<Int>? = null)

object ScheduleDetails {
    fun json(details: List<ScheduleDetail>) = JSONArray(details.map { field ->
        JSONObject().put("id", field.id).put("label", field.label).put("value", field.value)
            .apply { field.weeks?.let { put("weeks", JSONArray(it)) } }
    })
    fun parse(value: JSONArray?, weeks: Collection<Int>, strict: Boolean = false): List<ScheduleDetail> {
        if (value == null) return emptyList()
        val parsed = runCatching {
            require(value.length() <= 32 && value.toString().toByteArray().size <= 16384)
            val ids = mutableSetOf<String>()
            (0 until value.length()).map { index ->
                val f = value.getJSONObject(index)
                require(f.keys().asSequence().all { it in setOf("id", "label", "value", "weeks") })
                val id = f.get("id") as String; val label = f.get("label") as String; val text = f.get("value") as String
                require(id.length in 1..64 && ids.add(id) && label.isNotBlank() && label.length <= 40 && text.length <= 2000)
                val selected = if (f.has("weeks")) f.getJSONArray("weeks").let { w ->
                    (0 until w.length()).map { require(w.get(it) is Number && w.getDouble(it) == w.getInt(it).toDouble()); w.getInt(it) }
                        .also { require(it.isNotEmpty() && it.distinct().size == it.size && weeks.containsAll(it)) }
                } else null
                ScheduleDetail(id, label, text, selected)
            }
        }
        if (strict) return parsed.getOrElse { throw com.tyust.course.academic.plugin.PluginException(
            com.tyust.course.academic.plugin.PluginErrorCode.VALIDATION_FAILED, "课程扩展字段格式或范围无效") }
        return parsed.getOrDefault(emptyList())
    }
    fun fromEntry(entry: JSONObject, strict: Boolean = true): List<ScheduleDetail> {
        if (strict && entry.has("details") && entry.opt("details") !is JSONArray) throw com.tyust.course.academic.plugin.PluginException(com.tyust.course.academic.plugin.PluginErrorCode.VALIDATION_FAILED, "课程扩展字段必须为列表")
        return parse(entry.optJSONArray("details"), entry.optJSONArray("weeks")?.let { w -> (0 until w.length()).map { w.getInt(it) } }.orEmpty(), strict)
    }
    fun merge(a: List<ScheduleDetail>, b: List<ScheduleDetail>): List<ScheduleDetail> {
        val result = a.toMutableList()
        for (field in b) {
            // Preserve different lesson values as separate week-filterable fields. Reimporting
            // the original field must match its retained variant, not append its text again.
            if (result.any { it.copy(id = field.id) == field }) continue
            var id = field.id
            if (result.any { it.id == id }) {
                val suffix = com.tyust.course.academic.plugin.PluginJson.sha256(json(listOf(field)).toString().toByteArray()).take(16)
                val base = field.id.take(40) + "-" + suffix
                id = base
                var collision = 0
                while (result.any { it.id == id }) { id = base + "-" + ++collision }
            }
            result.add(field.copy(id = id))
        }
        return result
    }
}
