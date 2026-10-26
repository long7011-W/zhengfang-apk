package com.tyust.course.schedule

import android.app.Application
import android.content.Context
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.ScheduleSettingsManager.PeriodTime
import com.tyust.course.manager.UserManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the production apply path and the repository's actual legacy fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class ScheduleTimePresetRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val account get() = UserManager.getInstance().currentAccountStorageKey.ifBlank { "default" }
    private val currentTerm = "2026-2027-1"

    private fun timing(offset: Int = 0) = ScheduleTiming(12, (1..12).map { period ->
        val start = 7 * 60 + (period - 1) * 50 + offset
        fun format(minutes: Int) = "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
        PeriodTime(period, format(start), format(start + 40))
    })

    @Test fun applyingAndUndoingCurrentTermPreservesOtherTermsWithMissingAndPartialClocks() {
        verifyTermIsolation(currentTerm)
    }

    @Test fun applyingAndUndoingAnotherTermPreservesCurrentTermAndOtherInheritedClocks() {
        verifyTermIsolation("2026-2027-2")
    }

    private fun verifyTermIsolation(target: String) {
        val settings = ScheduleSettingsManager(context.getSharedPreferences("preset-test-settings", Context.MODE_PRIVATE))
        settings.savePeriodTimes(timing().times)
        val calendars = ScheduleCalendarStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE))
        val presetsPrefs = context.getSharedPreferences("preset-test-library", Context.MODE_PRIVATE)
        val repository = ScheduleRepository(context, settings)
        calendars.write(account, target, ScheduleTimeBase("2026-08-31"))
        calendars.write(account, "date-only", ScheduleTimeBase("2027-02-22"))
        calendars.write(account, "partial", ScheduleTimeBase("2027-02-22", mapOf(1 to "06:40"), mapOf(1 to "07:20")))
        val otherTerms = (listOf("unset", "date-only", "partial", currentTerm) - target).distinct()
        val before = otherTerms.associateWith { repository.timeBase(account, it, currentTerm) }
        val rawBefore = otherTerms.associateWith { calendars.read(account, it) }
        val legacyBefore = settings.getPeriodTimes(account)
        fun current() = ScheduleTiming.from(12, ScheduleTiming.mergePeriodTimes(
            repository.timeBase(account, target, currentTerm), settings.getPeriodTimes(account)))
        val old = current()
        fun update(value: ScheduleTiming): Boolean {
            applyScheduleTimingToTerm(settings, value, repository.timeBase(account, target, currentTerm)) {
                assertTrue(calendars.write(account, target, it))
            }
            return true
        }
        fun assertOtherTermsUnchanged() {
            assertEquals(legacyBefore, settings.getPeriodTimes(account))
            otherTerms.forEach { term ->
                assertEquals(before[term], repository.timeBase(account, term, currentTerm))
                assertEquals(rawBefore[term], calendars.read(account, term))
            }
        }
        assertTrue(ScheduleTimePresetStore(presetsPrefs).apply(account, target, old, timing(30), ::update))
        assertEquals(timing(30), current())
        assertEquals("2026-08-31", calendars.read(account, target)!!.firstWeekDate)
        assertOtherTermsUnchanged()
        val restartedStore = ScheduleTimePresetStore(presetsPrefs)
        val undo = restartedStore.undo(account, target, current())!!
        assertTrue(update(undo.previous))
        restartedStore.clearUndo(account, target)
        assertEquals(old, current())
        assertOtherTermsUnchanged()
    }

    @Test fun overlappingLegacyClocksCanBeRepairedAndRestored() = verifyRepair("07:10", "07:40")
    @Test fun invertedLegacyClocksCanBeRepairedAndRestored() = verifyRepair("08:00", "07:40")
    @Test fun incompleteLegacyClocksCanBeRepairedAndRestored() = verifyRepair("", "08:30")
    @Test fun malformedLegacyClocksCanBeRepairedAndRestored() = verifyRepair("not-a-time", "08:30")

    private fun verifyRepair(start: String, end: String) {
        val settings = ScheduleSettingsManager(context.getSharedPreferences("preset-test-settings", Context.MODE_PRIVATE))
        val old = timing().copy(times = timing().times.map {
            if (it.period == 2) it.copy(startTime = start, endTime = end) else it
        })
        settings.savePeriodTimes(old.times)
        val repository = ScheduleRepository(context, settings)
        val calendars = ScheduleCalendarStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE))
        val prefs = context.getSharedPreferences("preset-test-library", Context.MODE_PRIVATE)
        val before = repository.timeBase(account, currentTerm, currentTerm)
        fun update(value: ScheduleTiming): Boolean {
            applyScheduleTimingToTerm(settings, value, repository.timeBase(account, currentTerm, currentTerm)) {
                assertTrue(calendars.write(account, currentTerm, it))
            }
            return true
        }
        assertNotNull(old.error())
        assertTrue(ScheduleTimePresetStore(prefs).apply(account, currentTerm, old, timing(30), ::update))
        assertEquals(timing(30).on(before), repository.timeBase(account, currentTerm, currentTerm))
        val restore = ScheduleTimePresetStore(prefs).undo(account, currentTerm, timing(30))!!.previous
        assertEquals(old, restore)
        assertTrue(update(restore))
        assertEquals(before, repository.timeBase(account, currentTerm, currentTerm))
        assertEquals(old.times, settings.getPeriodTimes(account))
    }
}
