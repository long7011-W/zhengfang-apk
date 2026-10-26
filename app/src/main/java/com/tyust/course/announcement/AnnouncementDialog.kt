package com.tyust.course.announcement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import com.tyust.course.ui.system.GlassToaster
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemDialogButton

@Composable
internal fun AnnouncementContent(announcement: AnnouncementManager.Announcement) {
    val uriHandler = LocalUriHandler.current
    if (announcement.coverUrl.isNotBlank()) AsyncImage(
        model = announcement.coverUrl, contentDescription = "公告封面",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp))
    )
    if (announcement.summary.isNotBlank()) Text(announcement.summary,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (announcement.contentType == "markdown") {
        val safeContent = remember(announcement.content) { safeAnnouncementMarkdown(announcement.content) }
        Material3RichText(Modifier.fillMaxWidth()) {
            Markdown(safeContent, onLinkClicked = { url ->
                if (AnnouncementManager.validPublicUrl(url)) runCatching { uriHandler.openUri(url) }
                    .onFailure { GlassToaster.show("无法打开此链接") }
                else GlassToaster.show("此链接暂不支持打开")
            })
        }
    } else Text(announcement.content, style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun AnnouncementActions(announcement: AnnouncementManager.Announcement, onAction: (AnnouncementManager.Action) -> Unit) {
    announcement.actions.forEachIndexed { index, action ->
        SystemDialogButton(onClick = { onAction(action) }, primary = index == 0, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
    }
}

@Composable
fun AnnouncementDialog(
    announcement: AnnouncementManager.Announcement,
    onDismiss: () -> Unit,
    onAction: (AnnouncementManager.Action) -> Unit = {},
    onOpenCenter: (() -> Unit)? = null,
    ownerKey: String? = null
) {
    SystemDialog(ownerKey = ownerKey, onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Campaign, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
        title = { Text(announcement.title, style = MaterialTheme.typography.titleLarge) },
        confirmButton = { SystemDialogButton(primary = true, onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("我知道了") } },
        dismissButton = onOpenCenter?.let { open -> { SystemDialogButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("打开公告中心") } } }
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AnnouncementContent(announcement)
            AnnouncementActions(announcement, onAction)
        }
    }
}
