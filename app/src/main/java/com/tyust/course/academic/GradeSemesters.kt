package com.tyust.course.academic

import java.util.Calendar

internal fun currentGradeTerm(date: Calendar = Calendar.getInstance()): AcademicTerm {
    val month = date.get(Calendar.MONTH) + 1
    val year = date.get(Calendar.YEAR) - if (month >= 8) 0 else 1
    val semester = if (month in 2..7) 2 else 1
    return AcademicTerm("$year-${year + 1}-$semester", "$year–${year + 1} 学年 第 $semester 学期")
}

private fun standardGradeKey(term: AcademicTerm): Pair<Int, Int>? {
    val match = Regex("^(\\d{4})-(\\d{4})-([123])$").matchEntire(term.id)
    if (match != null) {
        val year = match.groupValues[1].toInt()
        if (match.groupValues[2].toInt() == year + 1) return year to match.groupValues[3].toInt()
    }
    return if (term.year in 1900..2300 && term.semester in 1..3) term.year to term.semester else null
}

/** Opt-in only: never infer a school's query format from its protocol name. */
internal fun gradeSemesters(catalog: AcademicStudyCatalog?, grades: List<AcademicGrade>,
    standardFormat: Boolean = false, date: Calendar = Calendar.getInstance()): List<AcademicTerm> {
    val known = catalog?.let { it.terms + it.currentTerm }.orEmpty().distinctBy { it.id }
    val additional = grades.map { it.term }.filter { it.isNotBlank() && known.none { term -> term.id == it } }
        .distinct().sortedDescending().map { AcademicTerm(it) }
    if (!standardFormat) return known + additional
    val current = currentGradeTerm(date)
    val evidenced = grades.map { it.term }.toSet()
    val school = (known + additional).filter {
        val key = standardGradeKey(it)
        it.id in evidenced || key == null || key.first <= current.year
    }
    val builtins = (current.year downTo current.year - 7).flatMap { year ->
        (2 downTo 1).filter { year < current.year || it <= current.semester }.map { semester ->
            AcademicTerm("$year-${year + 1}-$semester", "$year–${year + 1} 学年 第 $semester 学期")
        }
    }
    // Prefer school labels and original query IDs; keep grade-proven distinct IDs intact.
    val keys = school.mapNotNull(::standardGradeKey).toSet()
    return (school + builtins.filter { standardGradeKey(it) !in keys }).distinctBy { it.id }
        .sortedWith(compareByDescending<AcademicTerm> { standardGradeKey(it)?.first ?: 0 }
            .thenByDescending { standardGradeKey(it)?.second ?: 0 })
}

internal fun initialGradeSemester(terms: List<AcademicTerm>, catalog: AcademicStudyCatalog?,
    standardFormat: Boolean, date: Calendar = Calendar.getInstance()): String {
    val current = currentGradeTerm(date)
    return catalog?.currentTerm?.takeIf { candidate -> terms.any { it.id == candidate.id } &&
        (!standardFormat || standardGradeKey(candidate)?.let { it.first < current.year ||
            it.first == current.year && it.second <= current.semester } != false) }?.id
        ?: terms.firstOrNull { standardFormat && standardGradeKey(it) == standardGradeKey(current) }?.id
        ?: terms.firstOrNull()?.id.orEmpty()
}

internal fun AcademicGradeReport.forSemester(id: String): AcademicGradeReport {
    if (grades.isNotEmpty() && grades.none { it.term.isBlank() || it.term == id })
        throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校返回了其他学期的成绩，请刷新重试")
    return copy(grades = grades.filter { it.term.isBlank() || it.term == id }
        .map { if (it.term.isBlank()) it.copy(term = id) else it })
}
