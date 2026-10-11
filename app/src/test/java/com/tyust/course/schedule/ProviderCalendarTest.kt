package com.tyust.course.schedule

import com.tyust.course.manager.ScheduleSettingsManager
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ProviderCalendarTest {
    private fun numberedCalendar(vararg numbers: Int) = JSONObject().put("periods", JSONArray(numbers.map {
        JSONObject().put("number", it).put("start", "08:00").put("end", "08:45")
    }))

    @Test fun tenPeriodCalendarResizesDefaultAndPublishesChange() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        assertEquals(12, manager.periodCount)
        val revision = manager.revision
        manager.applyProviderCalendar("default", numberedCalendar(*(1..10).toList().toIntArray()))
        assertEquals(10, manager.periodCount)
        assertTrue(manager.revision > revision)
        assertEquals(10, ScheduleSettingsManager(prefs).periodCount)
    }

    @Test fun maximumPeriodNotArrayLengthUpdatesAcrossSyncAndAccounts() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        manager.applyProviderCalendar("default", numberedCalendar(10, 1, 4))
        assertEquals(10, manager.periodCount)
        manager.applyProviderCalendar("other", numberedCalendar(1, 14))
        assertEquals(10, manager.periodCount)
        assertEquals(14, prefs.getInt("period_count_other", 0))
        ScheduleSettingsManager(prefs).applyProviderCalendar("default", numberedCalendar(1, 11))
        assertEquals(11, manager.periodCount)
        assertEquals(14, prefs.getInt("period_count_other", 0))
    }

    @Test fun savingEvenTheSamePeriodCountStopsProviderUpdatesAfterReload() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        manager.applyProviderCalendar("default", numberedCalendar(1, 10))
        manager.periodCount = manager.periodCount
        ScheduleSettingsManager(prefs).applyProviderCalendar("default", numberedCalendar(1, 14))
        assertEquals(10, manager.periodCount)
        manager.periodCount = 16
        manager.applyProviderCalendar("default", numberedCalendar(1, 10))
        assertEquals(16, manager.periodCount)
    }

    @Test fun legacyAndScopedManualCountsRemainUserChoices() {
        val prefs = MemoryPreferences()
        prefs.edit().putInt("period_count", 9).apply()
        val manager = ScheduleSettingsManager(prefs)
        manager.applyProviderCalendar("default", numberedCalendar(1, 10))
        assertEquals(9, manager.periodCount) // Migrates only after preserving the old value.
        manager.applyProviderCalendar("default", numberedCalendar(1, 14))
        assertEquals(9, manager.periodCount)
        prefs.edit().putInt("period_count_other", 13).apply()
        manager.applyProviderCalendar("other", numberedCalendar(1, 10))
        assertEquals(13, prefs.getInt("period_count_other", 0))
    }

    @Test fun providerCountUsesSupportedRangeAndIgnoresEmptyOrInvalidNumbers() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        manager.applyProviderCalendar("default", numberedCalendar(1, 6))
        assertEquals(8, manager.periodCount)
        manager.applyProviderCalendar("default", numberedCalendar(1, 30))
        assertEquals(16, manager.periodCount)
        manager.applyProviderCalendar("default", numberedCalendar())
        assertEquals(16, manager.periodCount)
        manager.applyProviderCalendar("default", numberedCalendar(0, 10))
        assertEquals(16, manager.periodCount)
    }

    @Test fun manualTimesAndAutomaticCountHaveIndependentOwnership() {
        val manager = ScheduleSettingsManager(MemoryPreferences())
        manager.savePeriodTimes(listOf(ScheduleSettingsManager.PeriodTime(1, "09:30", "10:15")))
        manager.applyProviderCalendar("default", numberedCalendar(1, 10))
        assertEquals(10, manager.periodCount)
        assertEquals("09:30", manager.getPeriodTimes().single().startTime)
    }

    private fun calendar(date: String, time: String) = JSONObject("""{"startDate":"$date","periods":[{"number":1,"start":"$time","end":"10:00"}]}""")

    @Test fun providerUpdatesApplyUntilUserSavesAndSurviveReload() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        manager.applyProviderCalendar("default", calendar("2026-09-01", "08:00"))
        val original = manager.semesterStartDate
        manager.applyProviderCalendar("default", calendar("2026-09-07", "09:00"))
        assertNotEquals(original, manager.semesterStartDate)
        assertEquals("09:00", manager.getPeriodTimes().single().startTime)
        // Saving the same date is still an explicit user choice.
        manager.semesterStartDate = manager.semesterStartDate
        val chosen = manager.semesterStartDate
        manager.savePeriodTimes(listOf(ScheduleSettingsManager.PeriodTime(1, "09:30", "10:15")))
        val reloaded = ScheduleSettingsManager(prefs)
        reloaded.applyProviderCalendar("default", calendar("2027-02-01", "07:00"))
        assertEquals(chosen, reloaded.semesterStartDate)
        assertEquals("09:30", reloaded.getPeriodTimes().single().startTime)
        reloaded.applyProviderCalendar("other", calendar("2027-02-01", "07:00"))
        assertEquals("07:00", reloaded.getPeriodTimes("other").single().startTime)
        assertEquals("09:30", reloaded.getPeriodTimes().single().startTime)
    }

    @Test fun legacyManualValuesAreNotOverwrittenBeforeMigration() {
        val prefs = MemoryPreferences()
        prefs.edit().putLong("semester_start", 1234L).putString("period_times", """[{"period":1,"start":"11:00","end":"11:45"}]""").apply()
        val manager = ScheduleSettingsManager(prefs)
        manager.applyProviderCalendar("default", calendar("2026-09-01", "08:00"))
        assertEquals(1234L, manager.semesterStartDate)
        assertEquals("11:00", manager.getPeriodTimes().single().startTime)
    }
}
