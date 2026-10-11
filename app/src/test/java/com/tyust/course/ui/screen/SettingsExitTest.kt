package com.tyust.course.ui.screen

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.system.GlassOverlayHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class SettingsExitTest {
    @get:Rule val compose=createComposeRule()
    private fun home(preview:Boolean) {
        var exits=0
        compose.setContent { MaterialTheme { GlassOverlayHost {
            SettingsScreen(studentName="模拟",studentId="synthetic",schoolName="模拟学校",
                onSchoolSelect={},onCookieConfig={},onClearCache={},onCheckUpdate={},onAbout={},onCredits={},
                isDemoMode=preview,onLogout={ exits++ })
        } } }
        compose.onNodeWithText(if(preview)"退出预览" else "退出登录").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,exits) }
        compose.onNodeWithText("退出当前账号").assertDoesNotExist()
    }
    @Test fun previewExitIsDirectlyReachableOnSettingsHome()=home(true)
    @Test fun accountExitIsDirectlyReachableOnSettingsHome()=home(false)
    @Test fun exitingAndReenteringDemoDoesNotDeleteSavedRealAccountOrCache() {
        val context=RuntimeEnvironment.getApplication()
        val user=UserManager.getInstance().apply { init(context);clearLoginState() }
        val school=SchoolConfig("exit-fixture","模拟学校","school.example.test","https")
        user.addCustomSchool(school);user.currentSchool=school;user.studentId="synthetic"
        user.saveCookieLogin("SESSION=synthetic-only")
        val prefs=context.getSharedPreferences("course_selector_prefs",Context.MODE_PRIVATE)
        val snapshot=HashMap(prefs.all)
        val cache=context.getSharedPreferences("exit-fixture-cache",Context.MODE_PRIVATE)
        cache.edit().putString("schedule","synthetic course").commit()
        repeat(2) {
            user.startDemoSession(school)
            assertTrue(user.isDemoMode)
            val token=user.sessionState.token
            user.clearLoginState()
            assertFalse(user.isLoggedIn);assertFalse(user.isDemoMode)
            assertFalse(user.sessionState.isCurrent(token))
            assertEquals(snapshot,prefs.all)
            assertEquals("synthetic course",cache.getString("schedule",null))
        }
    }
}
