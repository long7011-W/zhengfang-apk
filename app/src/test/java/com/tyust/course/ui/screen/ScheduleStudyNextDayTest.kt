package com.tyust.course.ui.screen

import androidx.compose.ui.graphics.Color
import com.tyust.course.schedule.ScheduleStudyBlock
import com.tyust.course.schedule.ScheduleStudyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复审 6 个复现场景里与「跨夜自习 vs 真实次日课程」相关的第 1–4 条：
 * 次日永远是自习发生日的下一天，可能落在下一周，也可能因为「隐藏周末」而不在可见列里。
 */
class ScheduleStudyNextDayTest {
    private val fullOrder = listOf(1, 2, 3, 4, 5, 6, 7)
    /** 旧实现「隐藏周末」后用的窄顺序：周五的次日会被算成周一。 */
    private val weekdaysOnly = listOf(1, 2, 3, 4, 5)
    private val times = listOf(
        PeriodTimeUi(1, "00:30", "01:30"),
        PeriodTimeUi(9, "18:15", "19:00"),
        PeriodTimeUi(11, "19:55", "20:40")
    )

    private fun course(day: Int, weeks: String, period: Int, end: Int) = ScheduleCourseUi(
        name = "测试课", teacher = "", location = "", day = day,
        startPeriod = period, endPeriod = end, weeks = weeks, color = Color.Unspecified)

    @Test
    fun `hiding the weekend must not hide the real next day`() {
        val courses = listOf(course(5, "1", 9, 11), course(6, "1", 1, 1))
        val overnight = ScheduleStudyBlock(ScheduleStudyKind.Evening, enabled = true, name = "晚自习",
            start = "22:00", end = "01:00", days = setOf(5))

        // 周六 00:30–01:30 落在周五 22:00–次日 01:00 的自习里 → 整段取消（显示/隐藏周末结果一致）。
        assertEquals(listOf(30 to 90), nextDayStudySpans(5, 1, fullOrder, courses, times))
        assertTrue(studySlotsFor(5, listOf(overnight), courses, times,
            nextDayStudySpans(5, 1, fullOrder, courses, times)).isEmpty())

        // 反证：把「隐藏周末后的下一列」当次日（旧实现）会漏掉周六的课，冲突就漏判了。
        assertTrue(nextDayStudySpans(5, 1, weekdaysOnly, courses, times).isEmpty())
        assertEquals(1, studySlotsFor(5, listOf(overnight), courses, times,
            nextDayStudySpans(5, 1, weekdaysOnly, courses, times)).size)
    }

    @Test
    fun `the day after the last day of a week belongs to the next week`() {
        val sunday = ScheduleStudyBlock(ScheduleStudyKind.Evening, enabled = true, name = "晚自习",
            start = "22:00", end = "01:00", days = setOf(7))
        val week1Monday = course(1, "1", 1, 1)
        val week2Monday = course(1, "2", 1, 1)

        // 第 1 周周日 22:00 跨到第 2 周周一：只有下一周的 00:30 有课，也必须取消。
        val intoNextWeek = nextDayStudySpans(7, 1, fullOrder, listOf(week1Monday, week2Monday), times)
        assertEquals(listOf(30 to 90), intoNextWeek)
        assertTrue(studySlotsFor(7, listOf(sunday), emptyList(), times, intoNextWeek).isEmpty())

        // 只有第 1 周周一有课时，第 1 周周日的自习要保留：次日那一周没有课。
        val sameWeekOnly = nextDayStudySpans(7, 1, fullOrder, listOf(week1Monday), times)
        assertTrue(sameWeekOnly.isEmpty())
        val kept = studySlotsFor(7, listOf(sunday), emptyList(), times, sameWeekOnly)
        assertEquals(1, kept.size)
        assertEquals("22:00", kept.single().startText)
        assertEquals("01:00", kept.single().endText)
    }

    @Test
    fun `a same day clash still cancels the study`() {
        val courses = listOf(course(5, "1", 9, 11))
        val overnight = ScheduleStudyBlock(ScheduleStudyKind.Evening, enabled = true, name = "晚自习",
            start = "18:20", end = "20:45", days = setOf(5))
        assertTrue(studySlotsFor(5, listOf(overnight), courses, times, emptyList()).isEmpty())
    }
}
