package com.tyust.course.schedule

import com.tyust.course.manager.ScheduleSettingsManager.PeriodTime
import org.junit.Assert.*
import org.junit.Test

class ScheduleTimePresetsTest {
    private fun timing(offset: Int = 0, count: Int = 12) = ScheduleTiming(count, (1..count).map { period ->
        val start = 7 * 60 + (period - 1) * 50 + offset
        fun format(minutes: Int) = "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
        PeriodTime(period, format(start), format(start + 40))
    })
    private fun preset(id: String = "summer", name: String = "夏季") = ScheduleTimePreset(id, name, timing())

    @Test fun seedPreservesExistingTimingsOnceWithoutTouchingLegacySettings() {
        val prefs = MemoryPreferences()
        prefs.edit().putLong("semester_start", 123L).putString("period_times", "legacy").apply()
        val store = ScheduleTimePresetStore(prefs)
        val seeded = store.seed("a", timing())
        assertEquals("原有作息", seeded.single().name)
        assertEquals(timing(), seeded.single().timing)
        assertEquals(seeded, ScheduleTimePresetStore(prefs).seed("a", timing(30)))
        assertEquals(123L, prefs.getLong("semester_start", 0))
        assertEquals("legacy", prefs.getString("period_times", null))
        store.delete("a", seeded.single().id)
        assertTrue(store.seed("a", timing()).isEmpty())
    }

    @Test fun createEditCopyAndDeleteAreAccountIsolatedAndSurviveRestart() {
        val prefs = MemoryPreferences()
        val store = ScheduleTimePresetStore(prefs)
        store.save("a", preset())
        store.save("a", preset().copy(id = "winter", name = "冬季", timing = timing(30)))
        store.save("b", preset().copy(name = "另一个账号"))
        store.save("a", preset().copy(name = "夏季新版", timing = timing(10)))
        val restored = ScheduleTimePresetStore(prefs)
        assertEquals(listOf("夏季新版", "冬季"), restored.list("a").map { it.name })
        assertEquals("另一个账号", restored.list("b").single().name)
        restored.delete("a", "summer")
        assertEquals("winter", restored.list("a").single().id)
        assertEquals("summer", restored.list("b").single().id)
    }

    @Test fun invalidSchedulesAreRejectedWithoutReplacingTheSavedPreset() {
        val store = ScheduleTimePresetStore(MemoryPreferences())
        store.save("a", preset())
        val malformed = listOf(timing().copy(count = 7), timing().copy(times = timing().times.reversed()),
            timing().copy(times = timing().times.map { if (it.period == 6) it.copy(startTime = "25:00") else it }),
            timing().copy(times = timing().times.map { if (it.period == 6) it.copy(endTime = it.startTime) else it }),
            timing().copy(times = timing().times.map { if (it.period == 6) it.copy(startTime = "07:00") else it }),
            ScheduleTiming.from(16, timing().times))
        malformed.forEach { bad ->
            assertNotNull(bad.error())
            assertThrows(IllegalArgumentException::class.java) { store.save("a", preset().copy(timing = bad)) }
        }
        assertEquals(preset(), store.list("a").single())
        assertThrows(IllegalArgumentException::class.java) { store.save("a", preset().copy(name = " ")) }
        assertThrows(IllegalArgumentException::class.java) { store.save("a", preset().copy(name = "x".repeat(33))) }
    }

    @Test fun boundedLibraryAllowsEditingAtCapacityButNotAdding() {
        val store = ScheduleTimePresetStore(MemoryPreferences())
        repeat(ScheduleTimePresetStore.LIMIT) { store.save("a", preset(it.toString())) }
        store.save("a", preset("0", "编辑"))
        assertThrows(IllegalArgumentException::class.java) { store.save("a", preset("overflow")) }
        assertEquals(ScheduleTimePresetStore.LIMIT, store.list("a").size)
    }

    @Test fun applyChangesOnlyPeriodTimesAndRetainsWeekAnchorAndAdjustments() {
        val adjustments = ScheduleAdjustments()
        val old = ScheduleTimeBase("2026-08-31", mapOf(1 to "08:00", 16 to "22:00"), mapOf(1 to "08:45"), adjustments)
        val next = timing(30).on(old)
        assertEquals(old.firstWeekDate, next.firstWeekDate)
        assertSame(adjustments, next.adjustments)
        assertEquals(timing(30).times.associate { it.period to it.startTime }, next.periodStarts)
        assertFalse(next.periodStarts.containsKey(16))
    }

    @Test fun undoSurvivesRestartAndIsScopedToTheAppliedAccountAndTerm() {
        val prefs = MemoryPreferences()
        val store = ScheduleTimePresetStore(prefs)
        store.rememberApply("a", "term-1", timing(), timing(30))
        val restored = ScheduleTimePresetStore(prefs)
        assertEquals(timing(), restored.undo("a", "term-1", timing(30))?.previous)
        assertNull(restored.undo("b", "term-1", timing(30)))
        assertNull(restored.undo("a", "term-2", timing(30)))
        assertNull(restored.undo("a", "term-1", timing(31)))
        restored.clearUndo("a", "term-1")
        assertNull(restored.undo("a", "term-1", timing(30)))
    }

    @Test fun undoCanRestorePreviouslyUnsetPeriodsButPresetsMustBeComplete() {
        val empty = ScheduleTiming.from(16, emptyList())
        assertNull(empty.error(false))
        assertNotNull(empty.error())
        val store = ScheduleTimePresetStore(MemoryPreferences())
        store.rememberApply("a", "term", empty, timing(count = 16))
        assertEquals(empty, store.undo("a", "term", timing(count = 16))?.previous)
        assertTrue(store.seed("a", empty).isEmpty())
    }

    @Test fun validPresetRepairsInvalidOldClocksAndUndoPreservesTheirExactValuesAfterRestart() {
        val invalidClocks = listOf("07:10" to "07:40", "08:00" to "07:40",
            "" to "08:30", "not-a-time" to "08:30")
        invalidClocks.forEach { (start, end) ->
            val old = timing().copy(times = timing().times.map {
                if (it.period == 2) it.copy(startTime = start, endTime = end) else it
            })
            assertNotNull(old.error())
            assertNull(old.snapshotError())
            val prefs = MemoryPreferences()
            var active = old
            assertTrue(ScheduleTimePresetStore(prefs).apply("a", "term", old, timing(30)) {
                active = it; true
            })
            assertEquals(timing(30), active)
            val restore = ScheduleTimePresetStore(prefs).undo("a", "term", active)!!.previous
            assertEquals(old, restore)
            assertEquals(old.times.associate { it.period to it.startTime }, restore.on(ScheduleTimeBase()).periodStarts)
            assertTrue(ScheduleTimePresetStore(prefs).seed("a", old).isEmpty())
        }
    }

    @Test fun invalidNewPresetAndMalformedSnapshotNeverReachTheApplyCallback() {
        val store = ScheduleTimePresetStore(MemoryPreferences())
        val invalid = timing().copy(times = timing().times.map {
            if (it.period == 2) it.copy(startTime = "07:10") else it
        })
        var called = false
        assertThrows(IllegalArgumentException::class.java) {
            store.apply("a", "term", timing(), invalid) { called = true; true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.apply("a", "term", timing().copy(times = timing().times.reversed()), timing(30)) {
                called = true; true
            }
        }
        assertFalse(called)
        assertNull(store.undo("a", "term", timing(30)))
    }

    @Test fun editingADraftDoesNotChangeTheActiveCalendarOrSavedPreset() {
        val store = ScheduleTimePresetStore(MemoryPreferences())
        store.save("a", preset())
        val active = timing().on(ScheduleTimeBase("2026-08-31"))
        val draft = store.list("a").single().copy(timing = timing(30))
        store.save("a", draft)
        assertEquals("07:00", active.periodStarts[1])
        assertEquals("07:30", store.list("a").single().timing.times[0].startTime)
        assertNull(store.undo("a", "term", timing()))
    }

    @Test fun termOverlayKeepsAllSixteenRowsAndExplicitlyClearedTimes() {
        val base = timing(count = 16).on(ScheduleTimeBase())
        val merged = ScheduleTiming.mergePeriodTimes(base, timing().times)
        assertEquals(timing(count = 16).times, merged)
        val cleared = base.copy(periodStarts = base.periodStarts + (16 to ""))
        assertEquals("", ScheduleTiming.mergePeriodTimes(cleared, merged).last().startTime)
        assertEquals(timing().times, ScheduleTiming.mergePeriodTimes(null, timing().times))
    }

    @Test fun rejectedOrFailedApplyPreservesThePreviousUndoAndActiveCalendar() {
        val store = ScheduleTimePresetStore(MemoryPreferences())
        store.rememberApply("a", "term", timing(), timing(30))
        assertFalse(store.apply("a", "term", timing(30), timing(60)) { false })
        assertEquals(timing(), store.undo("a", "term", timing(30))?.previous)
        assertThrows(IllegalStateException::class.java) {
            store.apply("a", "term", timing(30), timing(60)) { error("host failure") }
        }
        assertEquals(timing(), store.undo("a", "term", timing(30))?.previous)
    }

    @Test fun corruptedLibraryCannotBeSilentlyOverwritten() {
        val prefs = MemoryPreferences()
        val store = ScheduleTimePresetStore(prefs)
        store.save("a", preset())
        val key = prefs.all.keys.single()
        prefs.edit().putString(key, "invalid json").apply()
        assertThrows(IllegalStateException::class.java) { store.save("a", preset("new")) }
        assertThrows(IllegalStateException::class.java) { store.delete("a", "summer") }
        assertEquals("invalid json", prefs.getString(key, null))
    }

    @Test fun persistedPresetReschedulesRemindersAndKeepsDatesCoursesAndOtherTerms() {
        val prefs = MemoryPreferences()
        val calendars = ScheduleCalendarStore(prefs)
        val presets = ScheduleTimePresetStore(prefs)
        val adjustments = ScheduleAdjustments(days = listOf(ScheduleDayOverride("2026-09-01")))
        val old = timing().on(ScheduleTimeBase("2026-08-31", adjustments = adjustments))
        calendars.write("a", "term", old)
        calendars.write("a", "other-term", old)
        calendars.write("b", "term", old)
        val course = ScheduleCourseRecord("synthetic-course", "测试课程", "教师", "教室", 1, 6, 7, "1-16")
        val reminder = CourseReminder(CourseReminderKey("a", "term", course.id), course, enabled = true)
        val zone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        fun plan() = CourseReminderPlanner.plan(reminder, calendars.read("a", "term"), 0,
            ReminderPermissions(true, true), "a", zone).next!!
        val before = plan()
        assertTrue(presets.apply("a", "term", timing(), timing(30)) { next ->
            calendars.write("a", "term", next.on(calendars.read("a", "term")!!)); true
        })
        val after = plan()
        assertEquals(30 * 60_000L, after.startsAt - before.startsAt)
        assertEquals(30 * 60_000L, after.triggerAt - before.triggerAt)
        assertEquals(course, after.reminder.course)
        assertEquals(adjustments, calendars.read("a", "term")!!.adjustments)
        assertEquals(old.firstWeekDate, calendars.read("a", "term")!!.firstWeekDate)
        assertEquals(old, calendars.read("a", "other-term"))
        assertEquals(old, calendars.read("b", "term"))
        val restore = ScheduleTimePresetStore(prefs).undo("a", "term", timing(30))!!.previous
        calendars.write("a", "term", restore.on(calendars.read("a", "term")!!))
        assertEquals(before, plan())
    }
}
