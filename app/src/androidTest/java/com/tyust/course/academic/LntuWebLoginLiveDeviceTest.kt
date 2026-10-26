package com.tyust.course.academic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.tyust.course.BuildConfig
import com.tyust.course.LoginActivity
import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in live check; the runner supplies a private file, and the school captcha remains interactive. */
@RunWith(AndroidJUnit4::class)
class LntuWebLoginLiveDeviceTest {
    @Test fun authorizedAccountReturnsThroughTheProductionLoginGateway() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.filesDir, "authorized-lntu-login.json")
        assumeTrue("No authorized LNTU credentials supplied", input.isFile)
        val credentials = try { JSONObject(input.readText()) } finally { input.delete() }
        val device = UiDevice.getInstance(instrumentation)
        val report = JSONObject().put("status", "running").put("captchaDetectedByAccessibility", false)
        val reportFile = File(context.getExternalFilesDir(null), "lntu-login-validation.json")
        fun stage(value: String) {
            report.put("status", value)
            reportFile.writeText(report.toString())
            instrumentation.sendStatus(2, Bundle().apply { putString("lntuStage", value) })
        }
        AcademicProviderRegistry.initialize(context)
        val userManager = UserManager.getInstance().apply { init(context) }
        val profile = AcademicSchoolProfiles.profiles.single { it.id == "lntu" }
        // Use an actual picker entry; an unknown ID would make LoginScreen select its first school
        // and correctly cancel the in-flight login as soon as Compose observes the selection.
        val school = userManager.supportedSchools.firstOrNull {
            it.fullBasePath.trimEnd('/') == LntuWebLoginAssist.COMPLETION_URL.substringBeforeLast('/')
        } ?: profile.school().also { userManager.addCustomSchool(it) }
        userManager.currentSchool = school
        val intent = Intent(context, LoginActivity::class.java).putExtra("force_relogin", true)
            .putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
        ActivityScenario.launchActivityForResult<LoginActivity>(intent).use { scenario ->
            lateinit var gateway: AcademicPasswordLoginGateway
            scenario.onActivity { activity ->
                LoginActivity::class.java.declaredMethods.single { it.name == "setSelectedLoginSchool" }
                    .apply { isAccessible = true }.invoke(activity, school)
                LoginActivity::class.java.declaredMethods.single { it.name == "handlePasswordLogin" }
                    .apply { isAccessible = true }.invoke(activity, credentials.getString("username"), credentials.getString("password"))
                gateway = LoginActivity::class.java.getDeclaredField("activePasswordLoginGateway")
                    .apply { isAccessible = true }.get(activity) as AcademicPasswordLoginGateway
            }
            stage("awaiting-school-verification")
            val timeoutSeconds = InstrumentationRegistry.getArguments().getString("lntuTimeoutSeconds")
                ?.toLongOrNull()?.coerceIn(30, 600) ?: 180
            val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1_000
            while (SystemClock.elapsedRealtime() < deadline && gateway.completedPlugin == null) {
                if (!report.getBoolean("captchaDetectedByAccessibility") && device.hasObject(By.textContains("安全验证"))) {
                    report.put("captchaDetectedByAccessibility", true); stage("captcha-visible")
                }
                if (device.hasObject(By.textContains("用户名或者密码有误"))) {
                    stage("school-rejected-credentials")
                    fail("The school rejected the supplied credentials after verification")
                }
                SystemClock.sleep(500)
            }
            val adapter = gateway.completedPlugin
            if (adapter == null) stage("verification-not-completed")
            assertNotNull("School verification did not finish within the live check", adapter)
            val identity = runBlocking { adapter!!.validateSession() }
            assertEquals(AcademicStatus.SUCCESS, identity.status)
            assertTrue("Authenticated account mismatch", identity.studentId == credentials.getString("username"))
            report.put("identityMatched", true).put("sessionValidated", true)
            stage("session-validated")
            val saveDeadline = SystemClock.elapsedRealtime() + 15_000
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < saveDeadline) {
                device.findObject(By.text("确认绑定"))?.click()
                SystemClock.sleep(200)
            }
            assertEquals("LoginActivity did not complete after school authentication", Lifecycle.State.DESTROYED, scenario.state)
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertTrue("The authenticated session was not saved", userManager.isLoggedIn && userManager.hasSavedCookie())
            assertTrue("The saved identity does not match", userManager.studentId == credentials.getString("username"))
            report.put("loginSaved", true).put("activityReturned", true)
            stage("passed")
        }
    }
}
