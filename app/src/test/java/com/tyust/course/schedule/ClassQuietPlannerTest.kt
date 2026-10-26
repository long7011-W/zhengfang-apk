package com.tyust.course.schedule

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ClassQuietPlannerTest {
    private fun time(day: String, clock: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse("$day $clock")!!.time
    private val course = ScheduleCourseRecord("fixture", "测试课", "", "", 1, 1, 1, "1-2周")
    private val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00", 2 to "08:30", 3 to "10:00"),
        mapOf(1 to "08:45", 2 to "09:15", 3 to "10:45"))
    private fun snapshot(courses: List<ScheduleCourseRecord> = listOf(course), calendar: ScheduleTimeBase = base) =
        ScheduleSnapshot("account", "school", "2026-2027-1", courses, calendar, 0, true)

    @Test fun halfOpenBoundariesMuteAtStartAndRestoreAtEnd() {
        val data = snapshot()
        assertEquals(time("2026-09-07", "08:00"), ClassQuietPlanner.plan(data, time("2026-09-07", "07:59")).nextChangeAt)
        assertNotNull(ClassQuietPlanner.plan(data, time("2026-09-07", "08:00")).active)
        val after = ClassQuietPlanner.plan(data, time("2026-09-07", "08:45"))
        assertNull(after.active)
        assertEquals(time("2026-09-14", "08:00"), after.nextChangeAt)
    }

    @Test fun overlapsMergeWhileRealBreaksRestoreSound() {
        val data = snapshot(listOf(course, course.copy(id = "overlap", startPeriod = 2, endPeriod = 2),
            course.copy(id = "later", startPeriod = 3, endPeriod = 3)))
        assertEquals(time("2026-09-07", "09:15"), ClassQuietPlanner.plan(data, time("2026-09-07", "08:45")).nextChangeAt)
        assertNull(ClassQuietPlanner.plan(data, time("2026-09-07", "09:30")).active)
        assertEquals(time("2026-09-07", "10:00"), ClassQuietPlanner.plan(data, time("2026-09-07", "09:30")).nextChangeAt)
    }

    @Test fun cancellationsAndMakeUpLessonsUseTheSharedResolver() {
        val adjustments = ScheduleAdjustments(days = listOf(ScheduleDayOverride("2026-09-07")),
            lessons = listOf(ScheduleLessonOverride(course.id, "2026-09-07", targetDate = "2026-09-08")))
        val data = snapshot(calendar = base.copy(adjustments = adjustments))
        assertNull(ClassQuietPlanner.plan(data, time("2026-09-07", "08:15")).active)
        assertNotNull(ClassQuietPlanner.plan(data, time("2026-09-08", "08:15")).active)
    }

    @Test fun oddWeeksDoNotMuteOnEvenWeeks() {
        val data = snapshot(listOf(course.copy(weeks = "1-5周(单)")))
        assertNull(ClassQuietPlanner.plan(data, time("2026-09-14", "08:15")).active)
        assertEquals(time("2026-09-21", "08:00"), ClassQuietPlanner.plan(data, time("2026-09-14", "08:15")).nextChangeAt)
    }

    @Test fun missingTimesNeverInventAQuietWindow() {
        assertFalse(ClassQuietPlanner.plan(snapshot(calendar = base.copy(periodEnds = emptyMap())), 0).hasTimes)
        assertNull(ClassQuietPlanner.plan(snapshot(calendar = base.copy(firstWeekDate = "")), 0).nextChangeAt)
        assertEquals(ClassQuietPlan(), ClassQuietPlanner.plan(null, 0))
    }
}
