package com.tyust.course.ui.route

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemTopBar

@Composable internal fun AcademicCapabilityUnavailable(title: String, message: String, onOpenSchool: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        SystemTopBar(title = title)
        Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            SystemEmptyState(title = "当前功能不可用", message = message) {
                if (onOpenSchool != null) com.tyust.course.ui.system.SystemSecondaryButton("打开学校网页", onOpenSchool)
            }
        }
    }
}
