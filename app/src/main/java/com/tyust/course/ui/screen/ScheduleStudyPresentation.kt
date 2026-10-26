package com.tyust.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.schedule.ScheduleStudyBlock
import com.tyust.course.schedule.ScheduleStudyKind
import com.tyust.course.schedule.ScheduleStudyPlanner
import com.tyust.course.schedule.ScheduleStudySlot
import com.tyust.course.schedule.StudySpan

/** 周视图里自习行的固定行高（早上一条在最上，晚上一条在最下）。 */
internal val scheduleStudyRowHeight: Dp = 44.dp

/** 早自习用晨蓝，晚自习用暮紫，和课程卡片区分得开。 */
internal fun studyAccent(kind: ScheduleStudyKind): Color = when (kind) {
    ScheduleStudyKind.Morning -> Color(0xFF2F6BFF)
    ScheduleStudyKind.Evening -> Color(0xFF7C5CFF)
}

/** 把某天的课程折算成分钟占用，供自习冲突判定用。 */
internal fun studySpansOf(
    day: Int,
    courses: List<ScheduleCourseUi>,
    times: List<PeriodTimeUi>
): List<StudySpan> = courses.filter { it.day == day }.mapNotNull { course ->
    val start = times.firstOrNull { it.period == course.startPeriod }?.startTime ?: return@mapNotNull null
    val minutes = start.split(':').let { parts ->
        (parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) * 60 +
            (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    val endMinutes = times.firstOrNull { it.period == course.endPeriod }?.endTime?.let { value ->
        ScheduleStudyBlock.minutesOf(value)
    } ?: return@mapNotNull null
    if (endMinutes > minutes) minutes to endMinutes else null
}

/**
 * 某天要显示的自习。跨零点的自习要看**真实次日**的课，而次日可能落在下一周，
 * 也可能因为「隐藏周末」而不在可见列里，所以次日占用必须由调用方按日期算好传进来。
 */
internal fun studySlotsFor(
    day: Int,
    blocks: List<ScheduleStudyBlock>,
    dayCourses: List<ScheduleCourseUi>,
    times: List<PeriodTimeUi>,
    nextDaySpans: List<StudySpan> = emptyList()
): List<ScheduleStudySlot> = ScheduleStudyPlanner.slots(
    day = day,
    blocks = blocks,
    daySpans = studySpansOf(day, dayCourses, times),
    nextDaySpans = nextDaySpans
)

/**
 * 真实次日的课程占用：[order] 必须是完整的 7 天顺序（不受「隐藏周末」影响），
 * 走到一周最后一天时次日算下一周，因此跨周与调课都跟着课表本身走。
 */
internal fun nextDayStudySpans(
    day: Int,
    week: Int,
    order: List<Int>,
    courses: List<ScheduleCourseUi>,
    times: List<PeriodTimeUi>
): List<StudySpan> {
    val index = order.indexOf(day)
    if (index < 0) return emptyList()
    val wraps = index == order.lastIndex
    val nextDay = if (wraps) order.first() else order[index + 1]
    val nextWeek = if (wraps) week + 1 else week
    return studySpansOf(nextDay, courses.filter { isInWeek(it.weeks, nextWeek) }, times)
}

/** 周视图里的一整行自习：左侧时间槽 + 每天一格。 */
@Composable
internal fun ScheduleStudyWeekRow(
    kind: ScheduleStudyKind,
    title: String,
    timeText: String,
    columns: List<Int>,
    columnWidthPx: Int,
    timeColumnWidth: Dp,
    tint: Color,
    timeColumnTint: Color,
    slots: Map<Int, ScheduleStudySlot?>,
    onClick: () -> Unit
) {
    val accent = studyAccent(kind)
    val density = LocalDensity.current
    val cellWidth = with(density) { columnWidthPx.toDp() }
    // 连成一条时优先写全名；位置不够就只写第一个字（早自习 → 早）。
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    fun runLabel(span: Int): String {
        val available = with(density) { (cellWidth * span).toPx() - 6.dp.toPx() }
        val full = measurer.measure(AnnotatedString(title), style = labelStyle, maxLines = 1).size.width
        return if (full <= available) title else title.take(1)
    }
    // 连续有自习的日子连成一条；被课程压掉的那天自然断开。
    val runs = buildList {
        var start = -1
        columns.forEachIndexed { index, day ->
            val present = slots[day] != null
            if (present && start < 0) start = index
            if (start >= 0 && (!present || index == columns.lastIndex)) {
                add(start to (if (present) index else index - 1))
                start = -1
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(tint)
            .border(0.5.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(24.dp))
            .testTag("schedule-study-row-${kind.storageKey}")
    ) {
        Row(Modifier.fillMaxWidth().height(scheduleStudyRowHeight)) {
            Column(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .fillMaxHeight()
                    .background(timeColumnTint),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title.take(1),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.matchParentSize()) {
                    var cursor = 0
                    runs.forEach { (start, end) ->
                        if (start > cursor) Spacer(Modifier.width(cellWidth * (start - cursor)))
                        Box(
                            modifier = Modifier
                                .width(cellWidth * (end - start + 1))
                                .fillMaxHeight()
                                .padding(horizontal = 2.dp, vertical = 8.dp)
                                .testTag("schedule-study-cell-${kind.storageKey}-${columns[start]}")
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = scheduleCardColor(accent, 0.16f),
                                shape = RoundedCornerShape(10.dp),
                                border = scheduleCardBorder(accent)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = runLabel(end - start + 1),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        color = accent
                                    )
                                }
                            }
                        }
                        cursor = end + 1
                    }
                }
                // 每一格仍然可以点开编辑面板。
                Row(Modifier.matchParentSize()) {
                    columns.forEach { day ->
                        Box(
                            modifier = Modifier
                                .width(cellWidth)
                                .fillMaxHeight()
                                .clickable(onClick = onClick)
                        )
                    }
                }
            }
        }
    }
}

/** 日视图里的自习条目：矮时间条，点一下打开编辑面板。 */
@Composable
internal fun ScheduleDayStudy(
    slot: ScheduleStudySlot,
    compact: Boolean,
    onClick: () -> Unit
) {
    val accent = studyAccent(slot.kind)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule-day-study-${slot.kind.storageKey}")
            .clickable(onClick = onClick),
        color = scheduleCardColor(accent, 0.16f),
        shape = RoundedCornerShape(18.dp),
        border = scheduleCardBorder(accent)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(56.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = slot.startText,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = slot.endLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!compact) {
                    Text(
                        text = "自习",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(if (compact) 26.dp else 34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = slot.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (slot.crossesMidnight) "自习 · ${slot.rangeText}" else "自习 · ${slot.startText}–${slot.endText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
