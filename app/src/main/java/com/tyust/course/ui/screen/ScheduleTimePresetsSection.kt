package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.ScheduleSettingsManager.PeriodTime
import com.tyust.course.schedule.ScheduleTimePreset
import com.tyust.course.schedule.ScheduleTimePresetStore
import com.tyust.course.schedule.ScheduleTiming
import com.tyust.course.ui.system.*
import java.util.UUID

@Composable
internal fun ScheduleTimePresetsSection(
    store: ScheduleTimePresetStore, account: String, term: String, current: ScheduleTiming,
    canApply: Boolean,
    onApply: (ScheduleTiming) -> Boolean
) {
    var revision by remember(account, term) { mutableIntStateOf(0) }
    var presets by remember(account) { mutableStateOf(store.list(account)) }
    var editing by remember(account, term) { mutableStateOf<ScheduleTimePreset?>(null) }
    var selected by remember(account, term) { mutableStateOf<ScheduleTimePreset?>(null) }
    var confirmation by remember(account, term) { mutableStateOf<ScheduleTimePreset?>(null) }
    var undoConfirmation by remember(account, term) { mutableStateOf(false) }
    var deleteConfirmation by remember(account, term) { mutableStateOf<ScheduleTimePreset?>(null) }
    var message by remember(account, term) { mutableStateOf<String?>(null) }
    LaunchedEffect(account, current, revision) {
        runCatching { store.seed(account, current) }
            .onSuccess { presets = it }.onFailure { message = "无法读取或保存作息，请重试" }
    }
    val undo = remember(account, term, current, revision) { store.undo(account, term, current) }
    fun action(block: () -> Unit) {
        runCatching(block).onFailure {
            message = it.message ?: "操作未完成，请重试"
            confirmation = null; undoConfirmation = false; deleteConfirmation = null
        }
        revision++
    }
    fun draft(name: String, timing: ScheduleTiming) = ScheduleTimePreset(UUID.randomUUID().toString(), name, timing)

    InsetGroupedSection(header = "作息预设") {
        InsetGroupedRow(title = "当前作息", subtitle =
            (presets.firstOrNull { it.timing == current }?.name ?: "自定义作息") + " · ${current.count} 节")
        InsetGroupedRow(title = "保存当前作息", subtitle = "复制为预设，之后可以编辑或切换",
            enabled = presets.size < ScheduleTimePresetStore.LIMIT,
            onClick = { editing = draft("", current) })
        InsetGroupedRow(title = "新建作息", subtitle = "最多保存 ${ScheduleTimePresetStore.LIMIT} 套，只保存在当前账号",
            enabled = presets.size < ScheduleTimePresetStore.LIMIT,
            onClick = { editing = draft("", ScheduleTiming.from(current.count, emptyList())) })
        presets.forEach { preset ->
            InsetGroupedRow(title = preset.name, subtitle = "${preset.timing.count} 节 · 查看、编辑或应用",
                onClick = { selected = preset })
        }
        if (undo != null) InsetGroupedRow(title = "撤回上次应用", subtitle = "恢复此学期应用前的作息",
            enabled = canApply,
            onClick = { undoConfirmation = true })
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
    }
    editing?.let { draft ->
        ScheduleTimePresetEditor(draft, onDismiss = { editing = null }, onSave = { preset ->
            store.save(account, preset)
            revision++; editing = null; message = null
        })
    }
    selected?.let { preset ->
        SystemDialog(onDismissRequest = { selected = null }, scrollContent = true,
            title = { Text(preset.name) }) {
            Text("${preset.timing.count} 节 · 应用到 ${term.ifBlank { "学期尚未加载" }}")
            preset.timing.times.forEach { Text("第 ${it.period} 节  ${it.startTime}–${it.endTime}") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemDialogButton(primary = true, enabled = canApply && term.isNotBlank(), onClick = { confirmation = preset; selected = null }) { Text("应用这套作息") }
                SystemDialogButton(onClick = { editing = preset; selected = null }) { Text("编辑预设") }
                SystemDialogButton(enabled = presets.size < ScheduleTimePresetStore.LIMIT,
                    onClick = { editing = draft(preset.name.take(28) + " 副本", preset.timing); selected = null }) { Text("复制预设") }
                SystemDialogButton(destructive = true, onClick = { deleteConfirmation = preset; selected = null }) { Text("删除预设") }
            }
        }
    }
    confirmation?.let { preset ->
        SystemConfirmDialog(title = "应用「${preset.name}」？",
            text = "$term · 每天 ${preset.timing.count} 节\n替换本学期节次时间，并将此账号每天节数设为 ${preset.timing.count}。开学日期和课程周次保持不变。课前提醒按新时间重排。超出节数的课程不会删除，但可能不显示在周课表。",
            confirmText = "确认应用", onDismiss = { confirmation = null }, onConfirm = {
                action {
                    check(canApply) { "学期尚未加载，请重新打开课表设置" }
                    if (current != preset.timing) {
                        check(store.apply(account, term, current, preset.timing, onApply)) {
                            "账号或学期已改变，请重新打开课表设置"
                        }
                    }
                    confirmation = null; message = null
                }
            })
    }
    if (undoConfirmation && undo != null) SystemConfirmDialog(title = "撤回上次应用？",
        text = "恢复 $term 的上一套作息，并重新安排课前提醒。", confirmText = "恢复作息",
        onDismiss = { undoConfirmation = false }, onConfirm = {
            action {
                check(canApply) { "学期尚未加载，请重新打开课表设置" }
                check(store.undo(account, term, current) == undo) { "作息已改变，请重新打开设置" }
                check(onApply(undo.previous)) { "账号或学期已改变，请重新打开课表设置" }
                store.clearUndo(account, term); undoConfirmation = false; message = null
            }
        })
    deleteConfirmation?.let { preset -> SystemConfirmDialog(title = "删除「${preset.name}」？",
        text = "仅删除预设，当前课表继续使用已应用的时间。", confirmText = "删除预设",
        onDismiss = { deleteConfirmation = null }, onConfirm = {
            action { store.delete(account, preset.id); deleteConfirmation = null; message = null }
        }) }
}

@Composable
private fun ScheduleTimePresetEditor(initial: ScheduleTimePreset, onDismiss: () -> Unit, onSave: (ScheduleTimePreset) -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var timing by remember(initial.id) { mutableStateOf(initial.timing) }
    var editingPeriod by remember { mutableStateOf<PeriodTime?>(null) }
    var showCount by remember { mutableStateOf(false) }
    var saveError by remember(initial.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(name, timing) { saveError = null }
    val error = timing.error() ?: if (name.trim().isBlank()) "请填写预设名称" else null
    SystemDialog(onDismissRequest = onDismiss, scrollContent = true, title = { Text("编辑作息预设") },
        confirmButton = { SystemDialogButton(primary = true, enabled = error == null,
            onClick = {
                runCatching { onSave(initial.copy(name = name.trim(), timing = timing)) }
                    .onFailure { saveError = it.message ?: "作息未保存，请重试" }
            }) { Text("保存预设") } },
        dismissButton = { SystemDialogButton(onClick = onDismiss) { Text("取消") } }) {
        Text("保存只更新预设。需要切换课表时，请另行选择应用。")
        GlassFormField(name, { name = it.take(ScheduleTimePresetStore.NAME_LIMIT) }, "预设名称", placeholder = "例如：夏季作息")
        InsetGroupedRow(title = "每天节数", subtitle = "${timing.count} 节", onClick = { showCount = true })
        timing.times.forEach { time ->
            InsetGroupedRow(title = "第 ${time.period} 节", subtitle =
                if (time.startTime.isBlank() || time.endTime.isBlank()) "未设置" else "${time.startTime}–${time.endTime}",
                onClick = { editingPeriod = time })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (showCount) GlassOptionWheelDialog(title = "每天节数",
        options = (ScheduleSettingsManager.PERIOD_COUNT_MIN..ScheduleSettingsManager.PERIOD_COUNT_MAX).map { "$it 节" },
        selectedIndex = timing.count - ScheduleSettingsManager.PERIOD_COUNT_MIN,
        onDismiss = { showCount = false }, onConfirm = { index ->
            timing = ScheduleTiming.from(index + ScheduleSettingsManager.PERIOD_COUNT_MIN, timing.times); showCount = false
        })
    editingPeriod?.let { time -> GlassTimeRangePickerDialog(title = "预设第 ${time.period} 节", minuteStep = 1,
        initialStart = time.startTime.ifBlank { "08:00" }, initialEnd = time.endTime.ifBlank { "08:45" },
        onDismiss = { editingPeriod = null }, onConfirm = { start, end ->
            timing = timing.copy(times = timing.times.map { if (it.period == time.period) PeriodTime(it.period, start, end) else it })
            editingPeriod = null
        }) }
}
