package com.tyust.course.announcement

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/** Present at most one unread popup per foreground visit; all notices remain in the center. */
@Composable
fun AnnouncementReminder(canPresent: Boolean, foreground: Boolean, onOpen: (String) -> Unit, onAction: (AnnouncementManager.Action) -> Unit) {
    val context = LocalContext.current
    val feed by AnnouncementManager.state.collectAsState()
    var shownThisVisit by rememberSaveable { mutableStateOf(false) }
    var visibleKey by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(foreground) {
        if (!foreground) { visibleKey = null; shownThisVisit = false }
        else while (true) { now = System.currentTimeMillis(); delay(60_000) }
    }
    val items = remember(feed.items, now, com.tyust.course.manager.UserManager.getInstance().currentSchool?.id) { AnnouncementManager.visible(feed.items, now) }
    LaunchedEffect(items, canPresent, foreground, feed.readRevision) {
        if (!canPresent || !foreground || shownThisVisit) return@LaunchedEffect
        val candidate = items.firstOrNull { it.display == "popup" && !AnnouncementManager.isRead(context, it) } ?: return@LaunchedEffect
        delay(700)
        visibleKey = candidate.readKey
        shownThisVisit = true
    }
    val visible = items.firstOrNull { it.readKey == visibleKey && !AnnouncementManager.isRead(context, it) }
    if (visible != null && canPresent && foreground) {
        fun dismiss() { AnnouncementManager.markAsRead(context, visible); visibleKey = null }
        AnnouncementDialog(visible, ownerKey = "app-announcement", onDismiss = { dismiss() },
            onOpenCenter = { dismiss(); onOpen(visible.id) }, onAction = { dismiss(); onAction(it) })
    }
}
