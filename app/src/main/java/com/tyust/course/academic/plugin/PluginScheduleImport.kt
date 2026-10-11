package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject

/** Content identity makes importing the same timetable twice harmless. */
object PluginScheduleImport {
    fun merge(existing: JSONArray, incoming: JSONArray): Pair<JSONArray, Int> {
        val seen = linkedMapOf<String, JSONObject>()
        fun key(item: JSONObject): String = listOf("name", "teacher", "location", "day", "startPeriod", "endPeriod")
            .joinToString("\u0000") { item.optString(it).trim().replace(Regex("\\s+"), " ") } + "\u0000" +
            item.getJSONArray("weeks").let { weeks -> (0 until weeks.length()).map { weeks.getInt(it) }.distinct().sorted().joinToString(",") }
        PluginJson.objects(existing).forEach { seen[key(it)] = JSONObject(it.toString()) }
        var duplicates = 0
        PluginJson.objects(incoming).forEach { item ->
            val identity = key(item)
            val prior = seen[identity]
            if (prior != null) {
                duplicates++
                val merged = com.tyust.course.schedule.ScheduleDetails.merge(com.tyust.course.schedule.ScheduleDetails.fromEntry(prior), com.tyust.course.schedule.ScheduleDetails.fromEntry(item))
                prior.put("details", com.tyust.course.schedule.ScheduleDetails.json(merged))
                com.tyust.course.schedule.ScheduleDetails.fromEntry(prior) // A merge must retain the contract limits too.
            } else seen[identity] = JSONObject(item.toString())
        }
        return JSONArray(seen.values.toList()) to duplicates
    }
}
