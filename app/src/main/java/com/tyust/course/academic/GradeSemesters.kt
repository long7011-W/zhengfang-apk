package com.tyust.course.academic

/** Keep opaque plugin IDs and school labels; a term with no grades is still selectable. */
internal fun gradeSemesters(catalog: AcademicStudyCatalog?, grades: List<AcademicGrade>): List<AcademicTerm> {
    val known = catalog?.let { it.terms + it.currentTerm }.orEmpty().distinctBy { it.id }
    val additional = grades.map { it.term }.filter { it.isNotBlank() && known.none { term -> term.id == it } }
        .distinct().sortedDescending().map { AcademicTerm(it) }
    return known + additional
}

internal fun AcademicGradeReport.forSemester(id: String): AcademicGradeReport {
    if (grades.isNotEmpty() && grades.none { it.term.isBlank() || it.term == id })
        throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校返回了其他学期的成绩，请刷新重试")
    return copy(grades = grades.filter { it.term.isBlank() || it.term == id }
        .map { if (it.term.isBlank()) it.copy(term = id) else it })
}
