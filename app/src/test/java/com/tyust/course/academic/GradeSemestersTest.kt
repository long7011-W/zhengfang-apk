package com.tyust.course.academic

import org.junit.Assert.*
import org.junit.Test

class GradeSemestersTest {
    private fun grade(term: String) = AcademicGrade("课程", "90", "2", "4", term = term)
    @Test fun emptyTermsAndOpaquePluginIdsArePreserved() {
        val current = AcademicTerm("uuid-new", "2026-2027 第一学期")
        val old = AcademicTerm("uuid-old", "2025-2026 第二学期")
        val terms = gradeSemesters(AcademicStudyCatalog(listOf(current, old), current), listOf(grade(old.id)))
        assertEquals(listOf(current, old), terms)
        assertEquals("2026-2027 第一学期", terms.first().name)
    }
    @Test fun gradesExtendButNeverShrinkTheCatalog() {
        val term = AcademicTerm("2026-2027-1")
        val catalog = AcademicStudyCatalog(listOf(term), term)
        assertEquals(listOf(term.id, "2025-2026-2"), gradeSemesters(catalog, listOf(grade("2025-2026-2"))).map { it.id })
        assertEquals(listOf(term), gradeSemesters(catalog, emptyList()))
        assertTrue(gradeSemesters(null, emptyList()).isEmpty())
    }
    @Test fun semesterReportsDoNotMutateOverallAndEmptyResultIsValid() {
        val overall = AcademicGradeReport(listOf(grade("A"), grade("B")))
        assertEquals(listOf(grade("B")), overall.forSemester("B").grades)
        assertEquals(2, overall.grades.size)
        assertTrue(AcademicGradeReport(emptyList()).forSemester("C").grades.isEmpty())
        assertEquals("C", AcademicGradeReport(listOf(grade(""))).forSemester("C").grades.single().term)
    }
    @Test(expected = AcademicException::class) fun anotherSemesterIsNotPresentedAsAnEmptyRequestedSemester() {
        AcademicGradeReport(listOf(grade("A"))).forSemester("B")
    }
}
