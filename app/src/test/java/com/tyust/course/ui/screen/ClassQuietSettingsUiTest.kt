package com.tyust.course.ui.screen

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.schedule.ClassQuietManager
import com.tyust.course.ui.system.GlassOverlayHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class ClassQuietSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsEntryOpensOptInAndExplainsTheMissingSystemPermission() {
        val manager = ClassQuietManager.get(RuntimeEnvironment.getApplication()).apply { setEnabled(false) }
        compose.setContent { MaterialTheme { GlassOverlayHost {
            var show by remember { mutableStateOf(false) }
            SettingsScreen(studentName = "测试", studentId = "", schoolName = "测试学校",
                onSchoolSelect = {}, onCookieConfig = {}, onClearCache = {}, onCheckUpdate = {},
                onAbout = {}, onCredits = {}, onLogout = {}, classQuietSummary = manager.status.summary,
                onClassQuietSettings = { show = true })
            if (show) ClassQuietSettingsDialog { show = false }
        } } }
        compose.onNodeWithText("上课自动静音").performScrollTo().performClick()
        compose.onNodeWithTag("class-quiet-toggle").assertIsOff().performClick()
        compose.onNodeWithTag("class-quiet-toggle").assertIsOn()
        compose.onNodeWithText("允许勿扰控制").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(manager.enabled) }
        compose.onNodeWithTag("class-quiet-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("class-quiet-toggle").assertIsOff()
        compose.onNodeWithText("允许勿扰控制").assertDoesNotExist()
        compose.runOnIdle { assertFalse(manager.enabled) }
    }
}
