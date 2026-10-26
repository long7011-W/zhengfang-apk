package com.tyust.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleStudyPlannerTest {

    /** 08:10–09:45 的一节课。 */
    private val morningCourse: StudySpan = 490 to 585

    private val morning = ScheduleStudyBlock(
        kind = ScheduleStudyKind.Morning, enabled = true, days = setOf(1, 2, 3, 4, 5)
    )

    private val evening = ScheduleStudyBlock(
        kind = ScheduleStudyKind.Evening, enabled = true, start = "18:20", end = "20:45", days = setOf(1)
    )

    @Test
    fun `study stays hidden while its switch is off`() {
        val off = morning.copy(enabled = false)
        assertNull(ScheduleStudyPlanner.slot(off, emptyList()))
    }

    @Test
    fun `study without any course that day is shown with its own window`() {
        val slot = ScheduleStudyPlanner.slot(morning, emptyList())
        assertNotNull(slot)
        assertEquals("早自习", slot!!.name)
        assertEquals("06:54 – 07:20", slot.rangeText)
        assertEquals(414, slot.startMinutes)
        assertEquals(440, slot.endMinutes)
    }

    @Test
    fun `a course that merely touches the study edge does not cancel it`() {
        // 课程 07:20 才开始，和 06:54–07:20 的早自习只是端点相接。
        assertNotNull(ScheduleStudyPlanner.slot(morning, listOf(440 to 585)))
        // 课程 06:54 结束，同样不算重叠。
        assertNotNull(ScheduleStudyPlanner.slot(morning, listOf(300 to 414)))
    }

    @Test
    fun `a partially overlapping course cancels the whole study block`() {
        // 课程 07:00–07:50 压住早自习的后半段。
        assertNull(ScheduleStudyPlanner.slot(morning, listOf(420 to 470)))
        // 课程 06:00–07:00 压住前半段。
        assertNull(ScheduleStudyPlanner.slot(morning, listOf(360 to 420)))
    }

    @Test
    fun `a course swallowing the whole study block cancels it`() {
        assertNull(ScheduleStudyPlanner.slot(morning, listOf(300 to 700)))
    }

    @Test
    fun `only the overlapping course matters, not the other courses of the day`() {
        assertNotNull(ScheduleStudyPlanner.slot(morning, listOf(morningCourse, 840 to 930)))
    }

    @Test
    fun `a study that starts exactly when a course ends stays visible`() {
        // 09:45 下课，09:45 开始的课间安排不受影响。
        val after = ScheduleStudyBlock(
            kind = ScheduleStudyKind.Morning, enabled = true, name = "课间自习",
            start = "09:45", end = "10:00", days = setOf(1)
        )
        assertNotNull(ScheduleStudyPlanner.slot(after, listOf(morningCourse)))
    }

    @Test
    fun `an overnight study is cancelled by a course after midnight`() {
        val overnight = evening.copy(start = "22:00", end = "01:00")
        assertEquals("22:00 – 01:00（次日）", ScheduleStudyPlanner.slot(overnight, emptyList())!!.rangeText)
        // 次日 00:30–02:00 的课压住跨零点的那一段 → 整段取消。
        assertNull(ScheduleStudyPlanner.slot(overnight, emptyList(), listOf(30 to 120)))
        // 次日 08:10 的课在自习结束之后，不影响它。
        assertNotNull(ScheduleStudyPlanner.slot(overnight, emptyList(), listOf(morningCourse)))
    }

    @Test
    fun `a study keeps its length`() {
        val slot = ScheduleStudyPlanner.slot(evening, emptyList())!!
        assertEquals(145, slot.endMinutes - slot.startMinutes)
        assertTrue(!slot.crossesMidnight)
    }

    @Test
    fun `days that are not ticked produce no slot`() {
        // 早自习默认周一到周五；周日不出现（那天课表照常，只是没勾周日）。
        assertTrue(ScheduleStudyPlanner.slots(7, listOf(morning), listOf(840 to 930)).isEmpty())
        assertEquals(1, ScheduleStudyPlanner.slots(3, listOf(morning), listOf(840 to 930)).size)
    }

    @Test
    fun `morning and evening coexist and sort by start time`() {
        val slots = ScheduleStudyPlanner.slots(1, listOf(evening, morning), emptyList())
        assertEquals(listOf("早自习", "晚自习"), slots.map { it.name })
    }

    @Test
    fun `a day with a course in the evening keeps the morning study only`() {
        // 18:15–20:40 的军事理论盖住晚自习。
        val slots = ScheduleStudyPlanner.slots(1, listOf(morning, evening), listOf(1095 to 1240))
        assertEquals(listOf("早自习"), slots.map { it.name })
    }
}
