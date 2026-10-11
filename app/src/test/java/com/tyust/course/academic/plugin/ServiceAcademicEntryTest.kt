package com.tyust.course.academic.plugin

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.ui.system.GlassOverlayHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class ServiceAcademicEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedConsentNeverShowsAnAuthorizationButtonDuringRestoreOrLoginFailure() {
        val state = mutableStateOf<ServiceAcademicRestore>(ServiceAcademicRestore.Restoring)
        var authorizations = 0; var logins = 0
        compose.setContent { MaterialTheme { GlassOverlayHost {
            ServiceAcademicEntry(state.value, false, { authorizations++ }, { logins++ })
        } } }
        compose.onNodeWithTag("service-academic-restoring").assertIsDisplayed()
        compose.onNodeWithTag("service-academic-authorize").assertDoesNotExist()
        compose.runOnIdle { state.value = ServiceAcademicRestore.NeedsLogin("请重新登录学校账号") }
        compose.onNodeWithTag("service-academic-authorize").assertDoesNotExist()
        compose.onNodeWithTag("service-academic-relogin").performClick()
        compose.runOnIdle {
            assertEquals(0, authorizations); assertEquals(1, logins)
            state.value = ServiceAcademicRestore.Ready
        }
        compose.onNodeWithTag("service-academic-authorize").assertDoesNotExist()
        compose.onNodeWithTag("service-academic-relogin").assertDoesNotExist()
    }

    @Test fun firstUseStillOffersAnExplicitAuthorizationAction() {
        var authorizations = 0
        compose.setContent { MaterialTheme { GlassOverlayHost {
            ServiceAcademicEntry(ServiceAcademicRestore.NeedsConsent, false, { authorizations++ }, {})
        } } }
        compose.onNodeWithTag("service-academic-authorize").performClick()
        compose.runOnIdle { assertEquals(1, authorizations) }
        compose.onNodeWithTag("service-academic-relogin").assertDoesNotExist()
    }
}
