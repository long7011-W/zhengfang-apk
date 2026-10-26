package com.tyust.course.schedule

/** Overlapping lessons form one quiet interval; real breaks remain separate. */
internal data class ClassQuietWindow(val startsAt: Long, val endsAt: Long)

internal data class ClassQuietPlan(
    val active: ClassQuietWindow? = null,
    val nextChangeAt: Long? = null,
    val hasTimes: Boolean = false
)

internal object ClassQuietPlanner {
    fun windows(snapshot: ScheduleSnapshot): List<ClassQuietWindow> {
        val result = mutableListOf<ClassQuietWindow>()
        for (lesson in ScheduleOccurrenceResolver.resolve(snapshot.courses, snapshot.timeBase)) {
            val last = result.lastOrNull()
            if (last != null && lesson.startsAt <= last.endsAt) {
                result[result.lastIndex] = last.copy(endsAt = maxOf(last.endsAt, lesson.endsAt))
            } else result += ClassQuietWindow(lesson.startsAt, lesson.endsAt)
        }
        return result
    }

    fun plan(snapshot: ScheduleSnapshot?, now: Long): ClassQuietPlan {
        if (snapshot == null) return ClassQuietPlan()
        val windows = windows(snapshot)
        val active = windows.firstOrNull { now >= it.startsAt && now < it.endsAt }
        return ClassQuietPlan(active, active?.endsAt ?: windows.firstOrNull { it.startsAt > now }?.startsAt,
            windows.isNotEmpty())
    }
}
