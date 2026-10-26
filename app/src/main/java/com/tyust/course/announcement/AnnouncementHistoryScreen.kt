package com.tyust.course.announcement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyust.course.ui.system.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val announcementCategories = linkedMapOf("general" to "综合公告", "update" to "版本与功能", "maintenance" to "维护通知", "guide" to "使用指南", "event" to "活动通知")
private fun noticeTime(value: Long) = if (value == 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(value))

@Composable
fun AnnouncementHistoryScreen(onBack: () -> Unit, onAction: (AnnouncementManager.Action) -> Unit = {}, initialAnnouncementId: String? = null) {
    val context = LocalContext.current
    val feed by AnnouncementManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable(initialAnnouncementId) { mutableStateOf(initialAnnouncementId) }
    val listState = rememberLazyListState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { AnnouncementManager.refresh(force = true) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000) } }
    val announcements = remember(feed.items, now, com.tyust.course.manager.UserManager.getInstance().currentSchool?.id) { AnnouncementManager.visible(feed.items, now) }
    val selected = announcements.firstOrNull { it.id == selectedId }
    val readKeys = remember(announcements, feed.readRevision) { announcements.filter { AnnouncementManager.isRead(context, it) }.map { it.readKey }.toSet() }
    val shown = remember(announcements, query, unreadOnly, category, readKeys) {
        announcements.filter { (!unreadOnly || it.readKey !in readKeys) && (category.isBlank() || it.category == category) &&
            (query.isBlank() || "${it.title}\n${it.summary}\n${it.content}".contains(query.trim(), ignoreCase = true)) }
    }
    LaunchedEffect(selected?.readKey) { selected?.let { if (!AnnouncementManager.isRead(context, it)) AnnouncementManager.markAsRead(context, it) } }
    BackHandler(selected != null) { selectedId = null }
    Scaffold(containerColor = Color.Transparent, topBar = {
        SystemTopBar(if (selected != null) "公告详情" else "公告中心", if (selected != null) announcementCategories[selected.category].orEmpty() else "功能动态与使用提醒",
            navigationIcon = { SystemIconButton(Icons.AutoMirrored.Outlined.ArrowBack, if (selected != null) "返回公告列表" else "返回设置", { if (selected != null) selectedId = null else onBack() }) },
            actions = {
                if (selected == null) {
                    SystemIconButton(Icons.Outlined.DoneAll, "全部已读", { AnnouncementManager.markAllRead(context) }, enabled = announcements.any { it.readKey !in readKeys })
                    SystemIconButton(Icons.Outlined.Refresh, "刷新公告", { scope.launch { AnnouncementManager.refresh(force = true) } }, enabled = !feed.loading)
                }
            })
    }) { padding ->
        if (selected != null) {
            LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item { Text(selected.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                if (selected.startsAt > 0) item { Text(noticeTime(selected.startsAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { AnnouncementContent(selected) } }
                item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { AnnouncementActions(selected, onAction) } }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        } else Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
            Column(Modifier.padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "搜索标题或正文", leadingIcon = Icons.Outlined.Search)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SystemPicker(listOf("全部公告", "只看未读"), if (unreadOnly) 1 else 0, { unreadOnly = it == 1 }, Modifier.weight(1f), label = "查看")
                    val categories = listOf("") + announcementCategories.keys
                    SystemPicker(categories.map { announcementCategories[it] ?: "全部分类" }, categories.indexOf(category).coerceAtLeast(0), { category = categories[it] }, Modifier.weight(1f), label = "分类")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${shown.size} 条公告", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val unread = announcements.count { it.readKey !in readKeys }
                    if (unread > 0) Text("$unread 条未读", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (feed.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (feed.error.isNotBlank()) Text(feed.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown.isEmpty() && !feed.loading) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Campaign, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (unreadOnly) "没有未读公告" else if (query.isNotBlank() || category.isNotBlank()) "没有符合条件的公告" else "暂无公告")
                    }
                }
                items(shown, key = { it.id }) { announcement ->
                    AnnouncementItem(announcement, announcement.readKey !in readKeys) { selectedId = announcement.id }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}

@Composable
fun AnnouncementItem(announcement: AnnouncementManager.Announcement, unread: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unread) { Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape)); Text("未读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                if (announcement.pinned) Text("置顶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (announcement.type == "important") Text("重要", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Text(announcementCategories[announcement.category] ?: "综合公告", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(announcement.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (announcement.summary.isNotBlank()) Text(announcement.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (announcement.startsAt > 0) Text(noticeTime(announcement.startsAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
