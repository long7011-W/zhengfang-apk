package com.tyust.course.ui

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.tyust.course.BuildConfig
import com.tyust.course.MainActivity
import com.tyust.course.announcement.AnnouncementManager
import com.tyust.course.demo.DemoData
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.AppThemeMode
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** Real Compose screens in the separate preview App, with a synthetic public feed. */
@RunWith(AndroidJUnit4::class)
class AnnouncementCenterDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private class Fixture(var document: String, var offline: Boolean = false)
    private fun notice(id: String, title: String, popup: Boolean = false, revision: Int = 1) = JSONObject()
        .put("id", id).put("title", title).put("content", "## 使用方法\n\n1. 打开所需功能。\n2. 返回公告中心查看其它说明。\n\n| 功能 | 入口 |\n| --- | --- |\n| 成绩 | 下方按钮 |")
        .put("summary", "可从同一条公告进入多个 App 功能。").put("contentType", "markdown")
        .put("type", if (popup) "important" else "info").put("display", if (popup) "popup" else "center")
        .put("revision", revision).put("category", if (id == "guide") "guide" else "maintenance").put("pinned", id == "guide")
        .put("actions", JSONArray().put(JSONObject().put("label", "跳到成绩").put("kind", "screen").put("target", "grades"))
            .put(JSONObject().put("label", "进入插件中心").put("kind", "screen").put("target", "plugins")))
    private fun feed(vararg items: JSONObject) = JSONObject().put("schemaVersion", 1).put("announcements", JSONArray(items.toList())).toString()
    private fun withFeed(document: String, block: (DemoUiDriver, Fixture) -> Unit) {
        assumeTrue("Only the separate preview App is allowed", BuildConfig.UI_PREVIEW)
        val fixture = Fixture(document)
        val previousLoader = AnnouncementManager.documentLoader
        val prefs = context.getSharedPreferences("announcement_prefs", 0)
        val previousRead = prefs.getStringSet("read_announcement_ids", emptySet())!!.toSet()
        val hadSeen = prefs.contains("portal_feed_seen")
        val previousSeen = prefs.getBoolean("portal_feed_seen", false)
        val file = File(context.filesDir, "app-announcements-v1.json")
        val previousCache = file.takeIf { it.exists() }?.readBytes()
        val oldTheme = AppearanceSettingsManager.themeMode
        try {
            prefs.edit().remove("read_announcement_ids").commit()
            AnnouncementManager.documentLoader = { _, _, _ -> if (fixture.offline) throw IOException("synthetic offline") else fixture.document }
            runBlocking { AnnouncementManager.refresh(force = true) }
            instrumentation.runOnMainSync {
                UserManager.getInstance().init(context)
                UserManager.getInstance().startDemoSession(DemoData.school())
            }
            DemoUiDriver().use { block(it, fixture) }
        } finally {
            AnnouncementManager.documentLoader = previousLoader
            prefs.edit().putStringSet("read_announcement_ids", previousRead).apply {
                if (hadSeen) putBoolean("portal_feed_seen", previousSeen) else remove("portal_feed_seen")
            }.commit()
            if (previousCache == null) file.delete() else file.writeBytes(previousCache)
            instrumentation.runOnMainSync { AppearanceSettingsManager.updateThemeMode(oldTheme) }
        }
    }
    private fun openCenter(ui: DemoUiDriver) {
        ui.navigate("设置")
        ui.scrollTo("公告中心")
        val activity = requireNotNull(ui.main)
        if (ui.boundsOf("公告中心").bottom > activity.window.decorView.height * 4 / 5) {
            val view = activity.window.decorView
            ui.shell("input -d ${activity.display!!.displayId} swipe ${view.width / 2} ${view.height * 3 / 4} ${view.width / 2} ${view.height / 2} 300")
            SystemClock.sleep(400)
        }
        ui.click("公告中心"); ui.waitText("刷新公告")
    }
    @Test fun centerReadsFiltersRecreatesAndOpensTheSelectedAppFunction() = withFeed(feed(notice("guide", "公告功能指南"), notice("maintenance", "服务维护说明"))) { ui, _ ->
        SystemClock.sleep(1200)
        assertFalse("Normal notices must not open a popup", ui.hasText("我知道了"))
        openCenter(ui); ui.waitText("2 条未读"); ui.screenshot("announcements-center")
        ui.click("公告功能指南"); ui.waitText("公告详情"); ui.waitText("使用方法")
        assertTrue(AnnouncementManager.isRead(context, AnnouncementManager.state.value.items.first { it.id == "guide" }))
        ui.screenshot("announcements-detail")
        val activity = requireNotNull(ui.main)
        ui.onMain { activity.recreate() }
        ui.await("Announcement detail did not survive recreation") { ui.main !== activity && ui.hasText("公告详情") }
        ui.scrollTo("跳到成绩"); ui.click("跳到成绩"); ui.waitSelected("成绩")
        openCenter(ui); ui.waitText("1 条未读")
        val search = requireNotNull(device.findObject(By.clazz("android.widget.EditText")))
        search.text = "维护"
        ui.waitText("1 条公告"); assertFalse(ui.hasText("公告功能指南"))
        search.text = ""; ui.waitText("2 条公告")
        ui.onMain { AppearanceSettingsManager.updateThemeMode(AppThemeMode.Dark) }
        SystemClock.sleep(600); ui.screenshot("announcements-dark")
        ui.click("全部已读")
        ui.await("Read-all did not clear the unread state") { AnnouncementManager.unreadCount(context) == 0 }
        ui.click("公告功能指南"); ui.scrollTo("进入插件中心"); ui.click("进入插件中心")
        ui.await("The plugin-center action did not open its App screen") { ui.foreground is com.tyust.course.academic.plugin.PluginCenterActivity }
    }
    @Test fun importantPopupPersistsReadRevisionAndWithdrawalDoesNotReturnFromOfflineCache() = withFeed(feed(notice("important", "重要：后台功能说明", true))) { ui, fixture ->
        ui.waitText("重要：后台功能说明"); ui.screenshot("announcements-important")
        ui.click("我知道了")
        assertEquals(0, AnnouncementManager.unreadCount(context))
        device.pressHome()
        ui.await("Main activity did not leave the foreground") { var stopped = false; ui.onMain { stopped = ui.main?.lifecycle?.currentState == Lifecycle.State.CREATED }; stopped }
        ui.onMain { requireNotNull(ui.main).startActivity(Intent(ui.main, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        SystemClock.sleep(1800); assertFalse(ui.hasText("我知道了"))
        fixture.document = feed(notice("important", "已更新的重要提醒", true, 2))
        runBlocking { AnnouncementManager.refresh(force = true) }
        ui.waitText("已更新的重要提醒"); ui.click("我知道了")
        fixture.offline = true
        runBlocking { AnnouncementManager.refresh(force = true) }
        assertEquals(1, AnnouncementManager.state.value.items.size)
        assertTrue(AnnouncementManager.state.value.cached)
        openCenter(ui); ui.waitText("已更新的重要提醒"); ui.screenshot("announcements-offline")
        fixture.offline = false; fixture.document = feed()
        runBlocking { AnnouncementManager.refresh(force = true) }
        ui.waitText("暂无公告")
        fixture.offline = true; runBlocking { AnnouncementManager.refresh(force = true) }
        assertTrue(AnnouncementManager.state.value.items.isEmpty())
        ui.waitText("暂无公告"); ui.screenshot("announcements-withdrawn")
    }
}
