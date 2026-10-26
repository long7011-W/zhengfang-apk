package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyust.course.schedule.ScheduleStudyBlock
import com.tyust.course.ui.system.GlassTimeRangePickerDialog
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemDialogButton

internal val StudyDayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 「周一、周三、周五」；全选时写「每天」。 */
internal fun studyBlockDaysText(days: Set<Int>): String = when {
    days.isEmpty() -> "未选择"
    days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "周一至周五"
    days == setOf(1, 2, 3, 4) -> "周一至周四"
    else -> days.sorted().joinToString("、") { StudyDayLabels[it - 1] }
}

/** 设置页副标题：时间 + 重复的星期。 */
internal fun studyBlockSummary(block: ScheduleStudyBlock): String {
    val span = buildString {
        append(block.start)
        append("–")
        append(block.end)
        if (block.crossesMidnight) append("（次日）")
    }
    return span + " · " + studyBlockDaysText(block.days)
}

internal fun leadMinutesText(lead: Int?): String = when {
    lead == null -> "跟随全局默认"
    lead <= 0 -> "上课时通知"
    else -> "提前 $lead 分钟"
}

/**
 * 早/晚自习的名称、时间、星期与提前提醒。显示开关在设置页那一行上，
 * 这里不重复；自习没有独立的提醒总开关，提醒跟随学期提醒设置。
 */
@Composable
fun ScheduleStudySheet(
    block: ScheduleStudyBlock,
    onDismiss: () -> Unit,
    onSave: (ScheduleStudyBlock) -> Unit
) {
    var name by remember(block) { mutableStateOf(block.name) }
    var enabled by remember(block) { mutableStateOf(block.enabled) }
    var start by remember(block) { mutableStateOf(block.start) }
    var end by remember(block) { mutableStateOf(block.end) }
    var days by remember(block) { mutableStateOf(block.days) }
    var leadMinutes by remember(block) { mutableStateOf(block.leadMinutes) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showLeadPicker by remember { mutableStateOf(false) }

    if (showTimePicker) {
        GlassTimeRangePickerDialog(
            initialStart = start,
            initialEnd = end,
            minuteStep = 1,
            title = block.kind.defaultName + "时间",
            onConfirm = { from, to ->
                start = from
                end = to
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }
    if (showLeadPicker) {
        ReminderLeadPicker(
            current = leadMinutes ?: 15,
            allowDefault = true,
            onDismiss = { showLeadPicker = false },
            onSave = { leadMinutes = it }
        )
    }

    SystemDialog(onDismissRequest = onDismiss, title = { Text(block.kind.defaultName + "设置") }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(12) },
                label = { Text("名称") },
                singleLine = true
            )
            OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    start + " – " + end +
                        if (ScheduleStudyBlock.minutesOf(end) <= ScheduleStudyBlock.minutesOf(start)) "（次日）" else ""
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("在课表中显示", style = MaterialTheme.typography.bodyLarge)
                    Text("关掉后课表、组件与提醒都不出现", style = MaterialTheme.typography.bodySmall)
                }
                com.tyust.course.ui.system.LiquidSwitch(enabled, { enabled = it })
            }
            Text("重复", style = MaterialTheme.typography.labelLarge)
            // 七天始终都画出来，取消勾选之后还能点回来；等分宽度，免得「周四」被挤成两行
            (1..7).chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { day ->
                        FilterChip(
                            selected = day in days,
                            onClick = { days = if (day in days) days - day else days + day },
                            label = { Text(StudyDayLabels[day - 1], maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (days.isEmpty()) {
                Text(
                    "至少留一天，否则这段自习不会出现",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            OutlinedButton(onClick = { showLeadPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("提前提醒：" + leadMinutesText(leadMinutes))
            }
            SystemDialogButton(
                primary = true,
                enabled = days.isNotEmpty(),
                onClick = {
                    onSave(
                        block.copy(
                            enabled = enabled,
                            name = name.trim().ifBlank { block.kind.defaultName },
                            start = start,
                            end = end,
                            days = days,
                            leadMinutes = leadMinutes
                        )
                    )
                    onDismiss()
                }
            ) { Text("保存") }
        }
    }
}
