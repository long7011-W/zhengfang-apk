package com.tyust.course.schedule

/** 一段课程占用，`[first, second)` 半开区间，单位是当天第几分钟。 */
typealias StudySpan = Pair<Int, Int>

/**
 * 课表上要画的一段自习。[startMinutes] / [endMinutes] 以当天 0 点为基准，
 * 跨零点时 [endMinutes] 会大于 1440（次日）。
 */
data class ScheduleStudySlot(
    val kind: ScheduleStudyKind,
    val name: String,
    val startMinutes: Int,
    val endMinutes: Int
) {
    val startText: String get() = ScheduleStudyBlock.format(startMinutes)
    val endText: String get() = ScheduleStudyBlock.format(endMinutes)
    val crossesMidnight: Boolean get() = endMinutes > ScheduleStudyBlock.MinutesPerDay
    val endLabel: String get() = if (crossesMidnight) "$endText（次日）" else endText
    val rangeText: String get() = "$startText – $endLabel"
}

/**
 * 自习在课表上的可见性：开启了、当天勾了，并且**与当天任意课程没有交集**。
 * 只要被课程压住一部分，这一段自习当天就整段静默取消（不显示、不提醒、不计统计）。
 */
object ScheduleStudyPlanner {

    /** 端点相接不算重叠：课程 07:20 下课，06:54–07:20 的早自习照常出现。 */
    fun overlaps(start: Int, end: Int, span: StudySpan): Boolean =
        start < span.second && span.first < end

    /**
     * 绝对时段版本，用于日历计算（提醒、统计）：课程按真实日期展开成 occurrence，
     * 因此跨零点、跨周与调课都由同一份数据决定，端点相接同样不算重叠。
     */
    fun blocked(span: Pair<Long, Long>, courses: List<ScheduleOccurrence>): Boolean =
        courses.any { it.startsAt < span.second && span.first < it.endsAt }

    /**
     * @param daySpans 当天课程的占用区间
     * @param nextDaySpans 次日课程的占用区间，用于跨零点的自习
     * @return 该日应该画出来的自习；被课程压住或未开启时返回 null
     */
    fun slot(
        block: ScheduleStudyBlock,
        daySpans: List<StudySpan>,
        nextDaySpans: List<StudySpan> = emptyList()
    ): ScheduleStudySlot? {
        if (!block.enabled) return null
        val start = block.startMinutes
        val end = start + block.durationMinutes
        val clash = daySpans.any { overlaps(start, end, it) } ||
            nextDaySpans.any { overlaps(start, end, it.first + ScheduleStudyBlock.MinutesPerDay to
                it.second + ScheduleStudyBlock.MinutesPerDay) }
        if (clash) return null
        return ScheduleStudySlot(block.kind, block.name, start, end)
    }

    /** 同一天可能有早晚两条；未勾选该星期几的直接不出现。 */
    fun slots(
        day: Int,
        blocks: List<ScheduleStudyBlock>,
        daySpans: List<StudySpan>,
        nextDaySpans: List<StudySpan> = emptyList()
    ): List<ScheduleStudySlot> = blocks
        .filter { it.occursOn(day) }
        .mapNotNull { slot(it, daySpans, nextDaySpans) }
        .sortedBy { it.startMinutes }
}
