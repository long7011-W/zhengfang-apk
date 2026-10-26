package com.tyust.course.schedule

import com.tyust.course.manager.ScheduleSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleStudyBlockTest {
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-10-12 是周一。 */
    private fun dayOf(monthDay: Int): Calendar = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.OCTOBER, monthDay, 12, 0, 0)
    }

    private fun minutesOf(year: Int, month: Int, monthDay: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, monthDay, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `study defaults match the agreed windows and start off`() {
        val morning = ScheduleStudyBlock.default(ScheduleStudyKind.Morning)
        assertEquals("早自习", morning.name)
        assertEquals("06:54", morning.start)
        assertEquals("07:20", morning.end)
        assertEquals(setOf(1, 2, 3, 4, 5), morning.days)
        assertFalse(morning.enabled)

        val evening = ScheduleStudyBlock.default(ScheduleStudyKind.Evening)
        assertEquals("晚自习", evening.name)
        assertEquals("18:20", evening.start)
        assertEquals("20:45", evening.end)
        assertEquals(setOf(1, 2, 3, 4), evening.days)
        assertFalse(evening.enabled)

        assertNull(morning.leadMinutes)
        assertEquals(listOf(ScheduleStudyKind.Morning, ScheduleStudyKind.Evening), ScheduleStudyBlock.defaults().map { it.kind })
    }

    @Test
    fun `a block that ends before it starts belongs to the day it begins`() {
        val block = ScheduleStudyBlock(
            kind = ScheduleStudyKind.Evening,
            start = "22:00",
            end = "02:00",
            days = setOf(1)
        )
        assertTrue(block.crossesMidnight)
        assertEquals(240, block.durationMinutes)

        val span = block.spanOn(dayOf(12))!!
        assertEquals(minutesOf(2026, Calendar.OCTOBER, 12, 22, 0), span.first)
        assertEquals(minutesOf(2026, Calendar.OCTOBER, 13, 2, 0), span.second)

        // 周二没勾，跨零点的这段不算在周二
        assertNull(block.spanOn(dayOf(13)))
    }

    @Test
    fun `study blocks only show up on the selected weekdays`() {
        val morning = ScheduleStudyBlock.default(ScheduleStudyKind.Morning)
        assertTrue(morning.occursOn(1))
        assertTrue(morning.occursOn(5))
        assertFalse(morning.occursOn(6))
        assertFalse(morning.occursOn(7))
        assertEquals(26, morning.durationMinutes)

        val saturday = dayOf(17)
        assertEquals(Calendar.SATURDAY, saturday.get(Calendar.DAY_OF_WEEK))
        assertNull(morning.spanOn(saturday))
        assertTrue(ScheduleStudyBlock.default(ScheduleStudyKind.Evening).let { it.copy(days = setOf(6)).spanOn(saturday) } != null)
    }

    @Test
    fun `any real overlap with a course counts and touching ends do not`() {
        val evening = ScheduleStudyBlock.default(ScheduleStudyKind.Evening)
        // 完全被课程盖住
        assertTrue(evening.overlaps(minutesOf(2026, Calendar.OCTOBER, 12, 19, 0), minutesOf(2026, Calendar.OCTOBER, 12, 20, 0), zone))
        // 只有一小段重叠
        assertTrue(evening.overlaps(minutesOf(2026, Calendar.OCTOBER, 12, 20, 30), minutesOf(2026, Calendar.OCTOBER, 12, 21, 30), zone))
        // 端点相接不算
        assertFalse(evening.overlaps(minutesOf(2026, Calendar.OCTOBER, 12, 16, 0), minutesOf(2026, Calendar.OCTOBER, 12, 18, 20), zone))
        assertFalse(evening.overlaps(minutesOf(2026, Calendar.OCTOBER, 12, 20, 45), minutesOf(2026, Calendar.OCTOBER, 12, 22, 0), zone))
        // 白天的课不碰晚自习
        assertFalse(evening.overlaps(minutesOf(2026, Calendar.OCTOBER, 12, 8, 10), minutesOf(2026, Calendar.OCTOBER, 12, 9, 45), zone))
        // 周五没有晚自习
        assertFalse(evening.overlaps(minutesOf(2026, Calendar.OCTOBER, 16, 19, 0), minutesOf(2026, Calendar.OCTOBER, 16, 20, 0), zone))
    }

    @Test
    fun `study blocks survive an encode decode round trip`() {
        val blocks = listOf(
            ScheduleStudyBlock.default(ScheduleStudyKind.Morning).copy(enabled = true, name = "早读", days = setOf(1, 3, 5), leadMinutes = 25),
            ScheduleStudyBlock.default(ScheduleStudyKind.Evening).copy(start = "21:00", end = "22:30", days = setOf(2), leadMinutes = 0)
        )
        val restored = ScheduleStudyBlock.decode(ScheduleStudyBlock.encode(blocks))
        assertEquals(blocks, restored)
    }

    @Test
    fun `a missing or broken archive falls back to one default per kind`() {
        ScheduleStudyBlock.decode(null).let { blocks ->
            assertEquals(2, blocks.size)
            assertEquals("早自习", blocks[0].name)
            assertFalse(blocks[0].enabled)
        }
        ScheduleStudyBlock.decode("not json").let { blocks ->
            assertEquals(ScheduleStudyBlock.defaults(), blocks)
        }
        // 少了一条（或存档里只有垃圾）也要补齐另一种自习
        val partial = ScheduleStudyBlock.decode("""[{"kind":"morning","enabled":true,"name":"早读","start":"06:30","end":"07:00","days":[1,2]}]""")
        assertEquals(2, partial.size)
        assertEquals("早读", partial[0].name)
        assertTrue(partial[0].enabled)
        assertEquals(setOf(1, 2), partial[0].days)
        assertEquals(ScheduleStudyBlock.default(ScheduleStudyKind.Evening), partial[1])
    }

    @Test
    fun `study blocks are stored per account and one kind never overwrites the other`() {
        val manager = ScheduleSettingsManager(MemoryPreferences())
        manager.saveStudyBlock(
            ScheduleStudyBlock.default(ScheduleStudyKind.Morning).copy(enabled = true, name = "早读"),
            "acct-a"
        )
        val stored = manager.getStudyBlock(ScheduleStudyKind.Morning, "acct-a")
        assertTrue(stored.enabled)
        assertEquals("早读", stored.name)

        // 同账号的晚自习保持默认
        assertEquals(ScheduleStudyBlock.default(ScheduleStudyKind.Evening), manager.getStudyBlock(ScheduleStudyKind.Evening, "acct-a"))
        // 另一个账号不受影响
        assertEquals(ScheduleStudyBlock.default(ScheduleStudyKind.Morning), manager.getStudyBlock(ScheduleStudyKind.Morning, "acct-b"))

        manager.saveStudyBlock(ScheduleStudyBlock.default(ScheduleStudyKind.Evening).copy(enabled = true), "acct-b")
        assertTrue(manager.getStudyBlock(ScheduleStudyKind.Evening, "acct-b").enabled)
        assertFalse(manager.getStudyBlock(ScheduleStudyKind.Evening, "acct-a").enabled)
        assertEquals("早读", manager.getStudyBlock(ScheduleStudyKind.Morning, "acct-a").name)
    }

    /** 复审 P2-c：同账号切到下学期不能覆盖本学期的自习配置。 */
    @Test
    fun `study blocks are stored per account and term`() {
        val manager = ScheduleSettingsManager(MemoryPreferences())
        manager.saveStudyBlock(
            ScheduleStudyBlock.default(ScheduleStudyKind.Morning).copy(enabled = true, name = "本学期早读"),
            "acct-a", "2026-2027-1"
        )
        // 下学期：默认值，不被本学期覆盖
        assertEquals(ScheduleStudyBlock.default(ScheduleStudyKind.Morning),
            manager.getStudyBlock(ScheduleStudyKind.Morning, "acct-a", "2026-2027-2"))

        manager.saveStudyBlock(
            ScheduleStudyBlock.default(ScheduleStudyKind.Morning).copy(enabled = true, name = "下学期早读", start = "07:00"),
            "acct-a", "2026-2027-2"
        )
        assertEquals("下学期早读", manager.getStudyBlock(ScheduleStudyKind.Morning, "acct-a", "2026-2027-2").name)
        // 本学期的配置与另一个账号都不受影响
        assertEquals("本学期早读", manager.getStudyBlock(ScheduleStudyKind.Morning, "acct-a", "2026-2027-1").name)
        assertEquals(ScheduleStudyBlock.default(ScheduleStudyKind.Morning),
            manager.getStudyBlock(ScheduleStudyKind.Morning, "acct-b", "2026-2027-1"))
    }

    /** 老存档只按账号存：首次按学期读取要迁过来，不能丢配置。 */
    @Test
    fun `legacy account scoped study blocks migrate to the term scoped key`() {
        val preferences = MemoryPreferences()
        preferences.edit().putString("study_blocks_acct-a",
            ScheduleStudyBlock.encode(listOf(ScheduleStudyBlock.default(ScheduleStudyKind.Morning).copy(enabled = true, name = "旧早读")))).apply()
        val manager = ScheduleSettingsManager(preferences)
        assertEquals("旧早读", manager.getStudyBlock(ScheduleStudyKind.Morning, "acct-a", "2026-2027-1").name)
        assertFalse(preferences.contains("study_blocks_acct-a"))
    }
}
