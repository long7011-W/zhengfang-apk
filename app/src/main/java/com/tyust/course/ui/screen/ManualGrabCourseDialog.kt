package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.*

data class ManualGrabCourseInput(val name: String, val section: String, val teacher: String, val time: String)

/** Shared by the plugin queue and the legacy runner, including their time constraints. */
@Composable
fun ManualGrabCourseDialog(enabled: Boolean = true, onDismiss: () -> Unit, onAdd: (ManualGrabCourseInput) -> Boolean) {
    var name by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    var teacher by rememberSaveable { mutableStateOf("") }
    var weekday by rememberSaveable { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableIntStateOf(0) }
    val weekdays = listOf("不限", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val periods = listOf("不限", "1-2节", "3-4节", "5-6节", "7-8节", "9-10节", "11-12节")
    SystemDialog(onDismissRequest = onDismiss, title = { Text("添加课程到队列") },
        confirmButton = {
            SystemPrimaryButton("添加", modifier = Modifier.fillMaxWidth().testTag("manual-course-add"),
                enabled = enabled && name.isNotBlank(), onClick = {
                    val time = listOfNotNull(weekdays[weekday].takeIf { weekday > 0 }, periods[period].takeIf { period > 0 }).joinToString(" ")
                    if (onAdd(ManualGrabCourseInput(name.trim(), section.trim(), teacher.trim(), time))) onDismiss()
                })
        }, dismissButton = {
            SystemSecondaryButton("取消", onDismiss, Modifier.fillMaxWidth())
        }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("manual-course-fields"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SchoolFormField("课程名称", name, { name = it }, placeholder = "例如：高等数学", modifier = Modifier.fillMaxWidth())
            SchoolFormField("教学班（选填）", section, { section = it }, placeholder = "例如：篮球0003",
                helper = "留空则不限；填写后只匹配符合条件的教学班", modifier = Modifier.fillMaxWidth())
            SchoolFormField("教师（选填）", teacher, { teacher = it }, placeholder = "例如：张老师", modifier = Modifier.fillMaxWidth())
            Text("上课时间（选填）", style = MaterialTheme.typography.labelMedium)
            val picker: @Composable (Boolean, Modifier) -> Unit = { day, modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (day) "周几" else "节次", style = MaterialTheme.typography.labelMedium)
                    SystemPicker(options = if (day) weekdays else periods, selectedIndex = if (day) weekday else period,
                        onSelect = { if (day) weekday = it else period = it },
                        modifier = Modifier.fillMaxWidth().testTag(if (day) "manual-weekday" else "manual-period"), label = "")
                }
            }
            if (LocalDensity.current.fontScale > 1.3f) {
                picker(true, Modifier.fillMaxWidth()); picker(false, Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                picker(true, Modifier.weight(1f)); picker(false, Modifier.weight(1f))
            }
        }
    }
}
