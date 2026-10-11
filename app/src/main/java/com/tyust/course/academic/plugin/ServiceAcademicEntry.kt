package com.tyust.course.academic.plugin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.InsetGroupedSection
import com.tyust.course.ui.system.LiquidButton
import com.tyust.course.ui.system.LiquidButtonStyle

@Composable
internal fun ServiceAcademicEntry(state: ServiceAcademicRestore, busy: Boolean,
    onAuthorize: () -> Unit, onLogin: () -> Unit) {
    when (state) {
        ServiceAcademicRestore.Ready -> Unit
        ServiceAcademicRestore.Restoring -> Text("正在恢复教务登录…", Modifier.testTag("service-academic-restoring"))
        is ServiceAcademicRestore.NeedsLogin -> InsetGroupedSection(header = "教务登录需要恢复",
            footer = "此插件的授权已保留，无需重复确认。") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(state.message)
                LiquidButton(onLogin, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("service-academic-relogin"),
                    style = LiquidButtonStyle.Tinted) { Text("重新登录教务账号") }
            }
        }
        ServiceAcademicRestore.NeedsConsent -> InsetGroupedSection(header = "使用本校教务登录",
            footer = "允许并记住后，重启 App 或同账号重登会自动恢复；可在插件详情撤销。") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("首次使用需要确认。教务凭据由 App 管理，无需在插件中再次输入密码。")
                LiquidButton(onAuthorize, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("service-academic-authorize"),
                    style = LiquidButtonStyle.Tinted) { Text("授权使用本校登录") }
            }
        }
    }
}
