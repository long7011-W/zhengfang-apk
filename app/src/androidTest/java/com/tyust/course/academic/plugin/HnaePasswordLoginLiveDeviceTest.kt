package com.tyust.course.academic.plugin

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.tyust.course.BuildConfig
import com.tyust.course.LoginActivity
import com.tyust.course.academic.*
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in production login flow. No credentials, cookies, tickets or personal details enter the report. */
@RunWith(AndroidJUnit4::class)
class HnaePasswordLoginLiveDeviceTest {
    @Test fun schoolAndBuiltinPasswordLoginsSaveReusableSessions() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.filesDir, "authorized-hnae-login.json")
        assumeTrue("No authorized school credentials supplied", input.isFile)
        val credentials = try { JSONObject(input.readText()) } finally { input.delete() }
        val device = UiDevice.getInstance(instrumentation)
        AcademicProviderRegistry.initialize(context)
        val manager = UserManager.getInstance().apply { init(context) }
        val pkg = runBlocking { AcademicProviderRegistry.packages().install(File(context.filesDir, "plugin-live/hnae.eduplugin").readBytes(), true) }
        AcademicProviderRegistry.reload()
        val school = AcademicProviderRegistry.bindSchool(pkg)
        val reports = JSONArray()
        val output = File(context.getExternalFilesDir(null), "hnae-login-validation.json")
        for (provider in listOf(pkg.manifest.id, "builtin.kingo")) {
            AcademicProviderRegistry.choose(school, provider)
            manager.currentSchool = school
            val report = JSONObject().put("choice", provider).put("readOnly", true)
            reports.put(report)
            val intent = Intent(context, LoginActivity::class.java).putExtra("force_relogin", true)
                .putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            ActivityScenario.launchActivityForResult<LoginActivity>(intent).use { scenario ->
                SystemClock.sleep(1_000)
                lateinit var gateway: AcademicPasswordLoginGateway
                scenario.onActivity { activity ->
                    LoginActivity::class.java.declaredMethods.single { it.name == "setSelectedLoginSchool" }
                        .apply { isAccessible = true }.invoke(activity, school)
                    LoginActivity::class.java.declaredMethods.single { it.name == "handlePasswordLogin" }
                        .apply { isAccessible = true }.invoke(activity, credentials.getString("username"), credentials.getString("password"))
                    gateway = LoginActivity::class.java.getDeclaredField("activePasswordLoginGateway")
                        .apply { isAccessible = true }.get(activity) as AcademicPasswordLoginGateway
                }
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (SystemClock.elapsedRealtime() < deadline && gateway.completedPlugin == null) {
                    assertNull("Unexpected browser fallback", gateway.webLogin)
                    SystemClock.sleep(250)
                }
                val adapter = gateway.completedPlugin
                assertNotNull("Password login did not complete", adapter)
                report.put("provider", adapter!!.pinned.manifest.id).put("passwordLogin", true)
                val savedDeadline = SystemClock.elapsedRealtime() + 15_000
                while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < savedDeadline) {
                    device.findObject(By.text("确认绑定"))?.click()
                    SystemClock.sleep(200)
                }
                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
                assertTrue(manager.isLoggedIn && manager.hasSavedCookie())
                assertEquals(credentials.getString("username"), manager.studentId)
                report.put("saved", true)
                runBlocking {
                    val reused = AcademicGatewayFactory.create(school, manager.currentAccountStorageKey) as PluginAcademicAdapter
                    assertEquals(AcademicStatus.SUCCESS, reused.validateSession().status)
                    val catalog = reused.catalog()
                    val first = reused.schedule(catalog.currentTerm)
                    val second = reused.schedule(catalog.currentTerm)
                    assertTrue(first.isNotEmpty())
                    assertEquals(first, second)
                    report.put("sessionReused", true).put("schedule", first.size).put("refresh", second.size)
                }
            }
            output.writeText(reports.toString(2))
        }
    }
}
