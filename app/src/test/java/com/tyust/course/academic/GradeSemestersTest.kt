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

    @Test fun builtinChoicesAreImmediateAndNeverIncludeFutureSemesters() {
        val date = java.util.GregorianCalendar(2026, 8, 30)
        val terms = gradeSemesters(null, emptyList(), true, date)
        assertEquals(15, terms.size)
        assertEquals("2026-2027-1", terms.first().id)
        assertEquals("2019-2020-1", terms.last().id)
        assertFalse(terms.any { it.id == "2026-2027-2" })
    }
    @Test fun augustBoundaryChangesTheAcademicYear() {
        assertEquals("2025-2026-2", currentGradeTerm(java.util.GregorianCalendar(2026, 6, 31)).id)
        assertEquals("2026-2027-1", currentGradeTerm(java.util.GregorianCalendar(2026, 7, 1)).id)
        assertEquals("2026-2027-2", currentGradeTerm(java.util.GregorianCalendar(2027, 1, 1)).id)
    }
    @Test fun futureWebsiteYearsAreHiddenButActualGradesArePreserved() {
        val future = AcademicTerm("2033-2034-1")
        val catalog = AcademicStudyCatalog(listOf(future), future)
        val date = java.util.GregorianCalendar(2026, 8, 30)
        assertFalse(gradeSemesters(catalog, emptyList(), true, date).any { it.id == future.id })
        assertTrue(gradeSemesters(catalog, listOf(grade(future.id)), true, date).any { it.id == future.id })
        assertEquals("2026-2027-1", initialGradeSemester(gradeSemesters(catalog, listOf(grade(future.id)), true, date), catalog, true, date))
    }
    @Test fun schoolIdsLabelsAndSummerTermsWinWithoutTouchingSchedule() {
        val school = AcademicTerm("school-current", "学校秋季", 2026, 1)
        val summer = AcademicTerm("2025-2026-3", "短学期", 2025, 3)
        val catalog = AcademicStudyCatalog(listOf(school, summer, school), school)
        val date = java.util.GregorianCalendar(2026, 8, 30)
        val terms = gradeSemesters(catalog, emptyList(), true, date)
        assertEquals(school, terms.first())
        assertFalse(terms.any { it.id == "2026-2027-1" })
        assertTrue(summer in terms)
        assertEquals(1, terms.count { it.id == school.id })
        assertEquals(school.id, initialGradeSemester(terms, catalog, true, date))
        assertEquals(3, catalog.terms.size)
    }
    @Test fun oldProvidersNeverReceiveGuessedIdsEvenWithYearMetadata() {
        val school = AcademicTerm("opaque", "学校学期", 2026, 1)
        assertEquals(listOf(school), gradeSemesters(AcademicStudyCatalog(listOf(school), school), emptyList()))
    }
}
