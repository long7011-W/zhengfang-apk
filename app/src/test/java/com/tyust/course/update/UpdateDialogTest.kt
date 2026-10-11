package com.tyust.course.update

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.ui.system.GlassOverlayHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Real manager -> signed mock response -> actual dialog semantics, no GPU claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp")
class UpdateDialogTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var manager: UpdateManager
    private val notes = "This release note must only appear for an installable candidate"
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_update_v2", 0).edit().clear().commit()
        File(app.filesDir, "app-updates").deleteRecursively()
        server = MockWebServer()
        server.start()
    }
    @After fun cleanup() {
        if (::manager.isInitialized) manager.close()
        server.close()
    }
    private fun display(delta: Int, cached: Boolean = false, test: Boolean = false, minSdk: Int = 24) {
        app.getSharedPreferences("app_update_v2", 0).edit().putBoolean("test", test).commit()
        manager = UpdateManager(app, UpdateFixtures.keys,
            { listOf(server.url("/manifest").toString()) }, { it.startsWith(server.url("/").toString()) })
        val code = manager.getCurrentVersionCode() + delta
        val payload = UpdateFixtures.payload().put("versionCode", code).put("versionName", "1.0.$code")
            .put("channel", if (test) "test" else "stable").put("releaseNotes", notes).put("minSdk", minSdk)
        val envelope = UpdateFixtures.envelope(payload.toString())
        if (cached) {
            File(app.filesDir, "app-updates/stable-manifest.json").writeText(envelope)
            server.enqueue(MockResponse().setResponseCode(503))
        } else server.enqueue(MockResponse().setBody(envelope))
        manager.checkForUpdate(manual = true)
        runBlocking { withTimeout(8000) { manager.state.first { it.check !in setOf(UpdateManager.Check.IDLE, UpdateManager.Check.CHECKING) } } }
        compose.setContent { MaterialTheme { GlassOverlayHost { UpdateDialog(manager) } } }
    }
    private fun noOldReleaseOffer() {
        compose.onNodeWithText("→", substring = true).assertDoesNotExist()
        compose.onNodeWithText(notes).assertDoesNotExist()
        compose.onNodeWithText("下载更新").assertDoesNotExist()
        compose.onNodeWithText("下载线路与浏览器下载").assertDoesNotExist()
        compose.onNodeWithText("当前版本 v${manager.getCurrentVersionName()}").assertIsDisplayed()
    }
    @Test fun olderStableShowsLatestWithoutReverseArrowOrOldNotes() {
        display(-1)
        noOldReleaseOffer()
        compose.onNodeWithText("已是最新版本").assertIsDisplayed()
        compose.onNodeWithText("重新检查").assertIsEnabled()
    }
    @Test fun sameStableDoesNotOfferReinstallOrOldNotes() {
        display(0)
        noOldReleaseOffer()
        compose.onNodeWithText("已是最新版本").assertIsDisplayed()
    }
    @Test fun newerCompatibleVersionShowsRealUpgradeAndDownload() {
        display(1)
        val info = manager.state.value.manifest!!
        compose.onNodeWithText("v${manager.getCurrentVersionName()} → v${info.versionName}").assertIsDisplayed()
        compose.onNodeWithText(notes).assertExists()
        compose.onNodeWithText("下载更新").assertIsEnabled()
    }
    @Test fun failedRefreshWithOlderCacheDoesNotPretendCheckSucceeded() {
        display(-1, cached = true)
        noOldReleaseOffer()
        compose.onNodeWithText("已是最新版本").assertDoesNotExist()
        compose.onNodeWithText("当前为缓存信息，无法确认是否已有更新版本。").assertExists()
    }
    @Test fun newerCacheRetainsWarningAlongsideDownload() {
        display(1, cached = true)
        compose.onNodeWithText("当前为缓存信息，无法确认是否已有更新版本。").assertExists()
        compose.onNodeWithText("下载更新").assertIsEnabled()
    }
    @Test fun explicitCurrentTestRedownloadHasNoUpgradeArrow() {
        display(0, test = true)
        compose.onNodeWithText("→", substring = true).assertDoesNotExist()
        compose.onNodeWithText("当前测试版本 v${manager.getCurrentVersionName()}").assertIsDisplayed()
        compose.onNodeWithText("重新下载测试包").assertIsEnabled()
    }
    @Test fun unsupportedAndroidDoesNotExposeBrowserBypass() {
        display(1, minSdk = 100)
        noOldReleaseOffer()
        compose.onNodeWithText("新版本不支持当前 Android 版本").assertIsDisplayed()
    }
    @Test fun staleBrowserActionCannotOpenOlderApk() {
        display(-1)
        manager.openBrowser(manager.state.value.manifest!!.mirrors.first())
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
