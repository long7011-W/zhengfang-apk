package com.tyust.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自习判重用的课表快照：按账号 + 学期隔离，并随自定义课程增删就地维护。 */
class ScheduleCourseSnapshotStoreTest {
    private val account = "acct-a"
    private val term = "2026-2027-1"
    private val monday = ScheduleCourseRecord("custom:mon", "周一课", "", "", 1, 1, 2, "1-25周")

    private fun store(preferences: MemoryPreferences = MemoryPreferences()) =
        preferences to ScheduleCourseSnapshotStore(preferences)

    @Test
    fun `snapshot is isolated per account and term`() {
        val (_, store) = store()
        store.write(account, term, listOf(monday))
        assertEquals(listOf(monday), store.read(account, term))
        assertNull(store.read(account, "2026-2027-2"))
        assertNull(store.read("acct-b", term))
    }

    @Test
    fun `writing a blank account or term is ignored`() {
        val (_, store) = store()
        store.write("", term, listOf(monday))
        store.write(account, "", listOf(monday))
        assertNull(store.read("", term))
        assertNull(store.read(account, ""))
    }

    @Test
    fun `custom course edits rewrite every term snapshot of that account only`() {
        val (_, store) = store()
        store.write(account, term, listOf(monday))
        store.write(account, "2026-2027-2", listOf(monday))
        store.write("acct-b", term, listOf(monday))

        store.update(account) { list -> list.map { if (it.id == monday.id) it.copy(teacher = "王老师") else it } }
        assertEquals("王老师", store.read(account, term)!!.single().teacher)
        assertEquals("王老师", store.read(account, "2026-2027-2")!!.single().teacher)
        assertEquals("", store.read("acct-b", term)!!.single().teacher)

        store.update(account) { list -> list.filterNot { it.id == monday.id } }
        assertTrue(store.read(account, term)!!.isEmpty())
        assertTrue(store.read(account, "2026-2027-2")!!.isEmpty())
        assertEquals(1, store.read("acct-b", term)!!.size)
    }

    @Test
    fun `a new custom course is appended to the snapshot`() {
        val (_, store) = store()
        store.write(account, term, listOf(monday))
        val added = ScheduleCourseRecord("custom:tue", "周二课", "", "", 2, 3, 4, "1-25周")
        store.update(account) { list -> if (list.any { it.id == added.id }) list else list + added }
        assertEquals(listOf("custom:mon", "custom:tue"), store.read(account, term)!!.map { it.id })
        // 幂等：重复 upsert 不会堆积。
        store.update(account) { list -> if (list.any { it.id == added.id }) list else list + added }
        assertEquals(2, store.read(account, term)!!.size)
    }

    @Test
    fun `clear removes only that account snapshots`() {
        val (_, store) = store()
        store.write(account, term, listOf(monday))
        store.write("acct-b", term, listOf(monday))
        store.clear(account)
        assertNull(store.read(account, term))
        assertEquals(1, store.read("acct-b", term)!!.size)
    }
}
