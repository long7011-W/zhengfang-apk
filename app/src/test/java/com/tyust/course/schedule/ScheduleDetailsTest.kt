package com.tyust.course.schedule

import com.tyust.course.academic.plugin.PluginException
import com.tyust.course.academic.plugin.PluginScheduleImport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduleDetailsTest {
    private fun entry(value: String = "第一章", weeks: String = "[1,2]") = JSONObject("""{"id":"synthetic","name":"模拟课程","teacher":"老师","location":"教室","day":1,"startPeriod":1,"endPeriod":2,"weeks":$weeks} """)
        .put("details", ScheduleDetails.json(listOf(ScheduleDetail("teachingContent", "授课内容", value, listOf(1)))))
    @Test fun optionalFieldsRoundTripWithoutChangingIdentity() {
        val fields = listOf(ScheduleDetail("notes", "备注", "<img src='https://synthetic.invalid'>", listOf(1, 2)))
        assertEquals(fields, ScheduleDetails.parse(ScheduleDetails.json(fields), listOf(1, 2), true))
        val row = JSONObject("""{"kcmc":"模拟课程","xm":"老师","cdmc":"教室","xqj":"1","jcs":"1-2","zcd":"1-2周"}""")
        fun parse() = ScheduleJson.parse(JSONObject().put("kbList", JSONArray().put(row)).toString())!!.single().course
        val id = parse().id
        row.put("details", ScheduleDetails.json(fields))
        assertEquals(id, parse().id); assertEquals(fields, parse().details)
        row.put("details", JSONArray().put(JSONObject().put("invalid", true)))
        assertEquals(id, parse().id); assertTrue(parse().details.isEmpty())
    }
    @Test fun rejectsWrongShapeDuplicateIdsForeignWeeksAndOversizedText() {
        val invalid = listOf(entry().put("details", "invalid"), entry(weeks = "[2]"), entry("x".repeat(2001)),
            entry().apply { getJSONArray("details").put(getJSONArray("details").get(0)) },
            entry().put("details", ScheduleDetails.json((1..32).map { ScheduleDetail("n$it", "备注", "文".repeat(1000)) })))
        invalid.forEach { assertThrows(PluginException::class.java) { ScheduleDetails.fromEntry(it) } }
    }
    @Test fun duplicateImportsPreserveNewAndOldDetailTextAndAreIdempotent() {
        val (merged, duplicates) = PluginScheduleImport.merge(JSONArray().put(entry()), JSONArray().put(entry("第二章")))
        assertEquals(1, duplicates); assertEquals(1, merged.length())
        val details = ScheduleDetails.fromEntry(merged.getJSONObject(0))
        assertEquals(setOf("第一章", "第二章"), details.map { it.value }.toSet())
        val (again, _) = PluginScheduleImport.merge(merged, JSONArray().put(entry("第二章")))
        assertEquals(merged.toString(), again.toString())
    }
    @Test fun conflictingLessonFieldsRetainTheirIndividualWeeksThroughReminderSerialization() {
        val first = ScheduleDetail("teachingContent", "授课内容", "第一章", listOf(1))
        val later = first.copy(value = "第二章", weeks = listOf(2))
        val merged = ScheduleDetails.merge(listOf(first), listOf(later))
        assertEquals(listOf(listOf(1),listOf(2)), merged.map { it.weeks })
        val course = ScheduleCourseRecord("stable", "模拟课程", "老师", "教室", 1, 1, 2, "1-2周", details = merged)
        assertEquals(course, ReminderJson.course(ReminderJson.course(course)))
        assertEquals(course.id, course.copy(details = emptyList()).id)
    }
    @Test fun absentDetailsAreCompatibleWithOldPackages() {
        assertTrue(ScheduleDetails.fromEntry(entry().apply { remove("details") }).isEmpty())
    }
}
