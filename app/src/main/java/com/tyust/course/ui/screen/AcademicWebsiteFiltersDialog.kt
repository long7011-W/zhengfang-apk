package com.tyust.course.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyust.course.academic.CourseFilters
import com.tyust.course.ui.system.*

@Composable
internal fun AcademicWebsiteFiltersDialog(definitions: CourseFilters?, error: String, loading: Boolean,
    values: Map<String, List<String>>, onValuesChange: (Map<String, List<String>>) -> Unit,
    onDismiss: () -> Unit, onRetry: () -> Unit, onApply: () -> Unit, onClear: () -> Unit) {
    SystemDialog(onDismissRequest = onDismiss, title = { Text("课程筛选") },
        confirmButton = { SystemPrimaryButton(text = "应用", enabled = definitions != null && !loading, onClick = onApply) },
        dismissButton = { SystemSecondaryButton(text = "清空", onClick = onClear) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                loading -> Text("正在读取学校筛选条件…")
                error.isNotBlank() -> { Text("筛选条件加载失败：$error"); SystemSecondaryButton(text = "重试", onClick = onRetry) }
                definitions == null -> Text("选课未开放，暂无法获取筛选条件")
                definitions.groups.isEmpty() -> Text("学校当前页面没有提供筛选条件，可使用关键词搜索。")
                else -> definitions.groups.forEach { group ->
                    if (group.kind == "text") SchoolFormField(group.label, values[group.id]?.firstOrNull().orEmpty(),
                        { onValuesChange(values + (group.id to listOf(it))) })
                    else {
                        Text(group.label, style = MaterialTheme.typography.titleSmall)
                        group.options.forEach { option ->
                            val selected = option.value in values[group.id].orEmpty()
                            fun toggle() { onValuesChange(values + (group.id to if (selected) values[group.id].orEmpty() - option.value else values[group.id].orEmpty() + option.value)) }
                            Row(Modifier.fillMaxWidth().clickable { toggle() }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = selected, onCheckedChange = { toggle() })
                                Text(option.label, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Text("应用后重新查询学校课程列表。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
