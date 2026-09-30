package com.tyust.course.login

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.LoginActivity
import com.tyust.course.academic.AcademicGatewayFactory
import com.tyust.course.academic.AcademicStatus
import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DedicatedSsoLoginDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun initializedPluginsPreserveBothDedicatedSsoGateways() {
        AcademicProviderRegistry.initialize(context)
        assertTrue(PasswordLoginGatewayFactory.create(tyust()) is TyustSsoLoginManager)
        assertTrue(PasswordLoginGatewayFactory.create(
            SchoolConfig("zjut", "浙江工业大学", "www.gdjw.zjut.edu.cn", "http")) is ZjutSsoLoginManager)
    }

    @Test fun loginActivityImportsNativeCookieBeforePluginValidation() {
        AcademicProviderRegistry.initialize(context)
        val originalSchool = UserManager.getInstance().getSchoolById("tyust")
        MockWebServer().use { server ->
            val sawCookie = AtomicBoolean()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val authenticated = request.getHeader("Cookie").orEmpty().contains("JSESSIONID=synthetic")
                    if (authenticated) sawCookie.set(true)
                    return MockResponse().setHeader("Content-Type", "text/html;charset=UTF-8").setBody(
                        if (authenticated) "<html><input name='xh' value='synthetic-student'><input name='xm' value='测试学生'>退出登录</html>"
                        else "<html>用户登录</html>"
                    )
                }
            }
            server.start()
            val school = SchoolConfig("tyust", "Synthetic SSO", "localhost:${server.port}", "http").apply {
                academicSystem = "zf"; basePath = ""
            }
            val intent = Intent(context, LoginActivity::class.java).putExtra("force_relogin", true)
                .putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            try {
                ActivityScenario.launch<LoginActivity>(intent).use { scenario ->
                    scenario.onActivity { activity ->
                        invoke(activity, "setSelectedLoginSchool", school)
                        invoke(activity, "setPendingPasswordLogin", true)
                        field(activity, "pendingPasswordUsername", "synthetic-student")
                        field(activity, "pendingPasswordSchool", school)
                        field(activity, "activePasswordLoginGateway", TyustSsoLoginManager())
                        invoke(activity, "performLoginValidation", school, "JSESSIONID=synthetic")
                    }
                    val deadline = SystemClock.elapsedRealtime() + 30_000
                    var validated = false
                    while (!validated && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { validated = invoke(it, "getBindingStudentId") == "synthetic-student" }
                        if (!validated) SystemClock.sleep(100)
                    }
                    assertTrue("Native SSO cookie never reached the teaching provider", sawCookie.get())
                    assertTrue("LoginActivity did not validate the native SSO identity", validated)
                }
            } finally {
                if (originalSchool != null) UserManager.getInstance().updateSchoolConfig(originalSchool)
            }
        }
    }

    /** Opt-in only: the runner supplies a private, short-lived credential file without shell arguments. */
    @Test fun authorizedTyustAccountLogsInAndRefreshesThroughTheProductionFactory() = runBlocking {
        val credentialFile = File(context.filesDir, "authorized-tyust-sso.json")
        assumeTrue("No authorized account supplied", credentialFile.isFile)
        val credentials = try { JSONObject(credentialFile.readText()) } finally { credentialFile.delete() }
        val school = tyust()
        val key = "sso-live-${UUID.randomUUID()}"
        AcademicProviderRegistry.initialize(context)
        try {
            repeat(2) {
                val gateway = PasswordLoginGatewayFactory.create(school)
                assertTrue(gateway is TyustSsoLoginManager)
                val result = LoginCallback()
                try {
                    gateway.login(school, credentials.getString("username"), credentials.getString("password"), result)
                    assertTrue("SSO callback timed out", result.latch.await(90, TimeUnit.SECONDS))
                    assertNull(result.failure)
                    assertTrue(result.cookie.isNotBlank())
                    AcademicGatewayFactory.importCookie(school, key, result.cookie, username = credentials.getString("username"))
                    val identity = AcademicGatewayFactory.create(school, key).validateSession()
                    assertEquals(AcademicStatus.SUCCESS, identity.status)
                    assertTrue("Authenticated identity mismatch", identity.studentId == credentials.getString("username"))
                } finally { gateway.clearSensitiveState() }
            }
        } finally { AcademicGatewayFactory.invalidate(school, key) }
    }

    private fun tyust() = SchoolConfig("tyust", "太原科技大学", "newjwc.tyust.edu.cn", "https")
    private fun invoke(activity: LoginActivity, name: String, vararg values: Any): Any? =
        LoginActivity::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }
            .invoke(activity, *values)
    private fun field(activity: LoginActivity, name: String, value: Any) {
        LoginActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private class LoginCallback : PasswordLoginCallback {
        val latch = CountDownLatch(1)
        var cookie = ""
        var failure: String? = null
        override fun onSuccess(cookie: String) { this.cookie = cookie; latch.countDown() }
        override fun onError(message: String) { failure = message; latch.countDown() }
        override fun onInvalidCredentials() = onError("Credentials rejected")
        override fun onCaptchaRequired(imageBytes: ByteArray) = onError("Human verification required")
        override fun onCaptchaInvalid() = onError("Human verification rejected")
    }
}
