package com.tyust.course.ui.route

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyust.course.academic.gradeSemesters
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.GregorianCalendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32], application = Application::class)
class GradeCalendarDateTest {
    @get:Rule val compose = createComposeRule()
    private var now = GregorianCalendar(2026, 6, 31)
    private var terms = emptyList<String>()
    private var clockReads = 0
    private val visible = mutableStateOf(true)
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Before fun grantTheAppsDeclaredReceiverPermission() {
        // Android grants this manifest-declared signature permission on install; Robolectric does not.
        val app = RuntimeEnvironment.getApplication()
        val permission = app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertNotNull(app.packageManager.getPermissionInfo(permission, 0))
        shadowOf(app).grantPermissions(permission)
    }

    private fun show() {
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) {
                    val date = rememberGradeCalendarDate { clockReads++; now }
                    val available = remember(date) { gradeSemesters(null, emptyList(), true, date.calendar()).map { it.id } }
                    SideEffect { terms = available }
                }
            }
        }
    }

    private fun broadcast(action: String) {
        compose.runOnUiThread {
            RuntimeEnvironment.getApplication().sendBroadcast(Intent(action))
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    @Test fun openPageAddsOnlyArrivedTermsAtAugustAndFebruaryBoundaries() {
        show()
        compose.runOnIdle { assertEquals("2025-2026-2", terms.first()) }
        now = GregorianCalendar(2026, 7, 1)
        broadcast(Intent.ACTION_DATE_CHANGED)
        compose.runOnIdle {
            assertEquals("2026-2027-1", terms.first())
            assertFalse("2026-2027-2" in terms)
        }
        now = GregorianCalendar(2027, 1, 1)
        broadcast(Intent.ACTION_DATE_CHANGED)
        compose.runOnIdle { assertEquals("2026-2027-2", terms.first()) }
    }

    @Test fun resumeCatchesMissedDatesAndDisposalReleasesObservers() {
        show()
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        now = GregorianCalendar(2026, 7, 1)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals("2026-2027-1", terms.first()); visible.value = false }
        compose.waitForIdle()
        val readsAfterClose = clockReads
        broadcast(Intent.ACTION_DATE_CHANGED)
        compose.runOnUiThread {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertEquals(readsAfterClose, clockReads) }
    }

    @Test fun clockAndTimezoneChangesRecomputeFromLocalDate() {
        show()
        now = GregorianCalendar(2026, 7, 1)
        broadcast(Intent.ACTION_TIME_CHANGED)
        compose.runOnIdle { assertEquals("2026-2027-1", terms.first()) }
        now = GregorianCalendar(2026, 6, 31)
        broadcast(Intent.ACTION_TIMEZONE_CHANGED)
        compose.runOnIdle { assertEquals("2025-2026-2", terms.first()) }
    }
}
