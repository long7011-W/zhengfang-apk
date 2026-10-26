package com.tyust.course.academic

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Executes the handoff in Android's real WebView with entirely local synthetic pages. */
@RunWith(AndroidJUnit4::class)
class LntuWebLoginAssistDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var browser: WebView? = null
    private val login = AcademicSchoolProfiles.LNTU_LOGIN_URL

    private fun load(url: String = login, action: String = login.substringBefore('?'), body: String? = null) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            browser?.destroy()
            browser = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                layout(0, 0, 1080, 1600)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                loadDataWithBaseURL(url, body ?: """
                    <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                    <div id="pwdLoginDiv"><form id="loginFromId" method="post" action="$action">
                    <input id="username" name="username"><input id="password" type="password" name="userPassword">
                    <input id="saltPassword" name="password" type="hidden"><input id="pwdEncryptSalt" type="hidden" value="synthetic-salt">
                    <a href="#" id="login_submit">登录</a></form></div>
                    <script>
                    window.submissions = 0; window.captchaChecks = 0;
                    window.checkNeedCaptcha = function() { window.captchaChecks++; };
                    document.querySelector('#login_submit').addEventListener('click', function(e) {
                      e.preventDefault(); window.submissions++;
                      var password = document.querySelector('#password');
                      document.querySelector('#saltPassword').value = 'school-encrypted:' + password.value;
                      password.disabled = true;
                    });
                    </script>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
        }
        assertTrue("Synthetic browser page did not load", loaded.await(20, TimeUnit.SECONDS))
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { browser!!.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("WebView script did not complete", done.await(15, TimeUnit.SECONDS))
        return result
    }

    private fun state(): JSONObject = JSONObject(evaluate("""
        (function() { return {user: document.querySelector('#username').value,
          encrypted: document.querySelector('#saltPassword').value,
          submissions: window.submissions, captchaChecks: window.captchaChecks}; })();
    """.trimIndent()))

    @Test fun handoffRunsSchoolEncryptionAndCaptchaChecksExactlyOnce() {
        load()
        val password = "synthetic-'\"\\-@\u2028"
        assertEquals("\"submitted\"", evaluate(LntuWebLoginAssist.submitScript("fixture-student", password)))
        val first = state()
        assertEquals("fixture-student", first.getString("user"))
        assertEquals("school-encrypted:$password", first.getString("encrypted"))
        assertEquals(1, first.getInt("captchaChecks"))
        assertEquals(1, first.getInt("submissions"))
        evaluate(LntuWebLoginAssist.submitScript("other-student", "other-password"))
        assertEquals(first.toString(), state().toString())
    }

    @Test fun changedFormDestinationAndForeignPagesReceiveNoCredentials() {
        for (action in listOf("https://other.example.test/login", LntuWebLoginAssist.ORIGIN + "/other",
            "https://authserver.lntu.edu.cn.evil.test/authserver/login", "http://authserver.lntu.edu.cn/authserver/login",
            login.substringBefore('?') + "?service=https%3A%2F%2Fother.example.test%2F")) {
            load(action = action)
            assertEquals("\"untrusted\"", evaluate(LntuWebLoginAssist.submitScript("fixture-student", "synthetic-password")))
            assertEquals("", state().getString("user"))
            assertEquals(0, state().getInt("submissions"))
        }
        load(url = "https://other.example.test/login")
        assertEquals("\"untrusted\"", evaluate(LntuWebLoginAssist.submitScript("fixture-student", "synthetic-password")))
        assertEquals("", state().getString("user"))
    }

    @Test fun webVpnMayExposeTheVerifiedUpstreamCasFormAction() {
        load(action = "https://authserver.lntu.edu.cn/authserver/login?" + login.substringAfter('?'))
        assertEquals("\"submitted\"", evaluate(LntuWebLoginAssist.submitScript("fixture-student", "synthetic-password")))
        assertEquals("fixture-student", state().getString("user"))
        assertEquals("school-encrypted:synthetic-password", state().getString("encrypted"))
        assertEquals(1, state().getInt("captchaChecks"))
        assertEquals(1, state().getInt("submissions"))
    }

    @Test fun schoolCaptchaCheckRunsBeforeSubmittingEvenWithoutFocus() {
        load()
        evaluate("""
            window.schoolCaptchaChecked = false;
            window.checkedBeforeSubmit = false;
            var check = window.checkNeedCaptcha;
            window.checkNeedCaptcha = function() { check(); window.schoolCaptchaChecked = true; };
            document.querySelector('#login_submit').addEventListener('click', function() {
              window.checkedBeforeSubmit = window.schoolCaptchaChecked;
            });
        """.trimIndent())
        assertEquals("\"submitted\"", evaluate(LntuWebLoginAssist.submitScript("fixture-student", "synthetic-password")))
        assertEquals("true", evaluate("window.checkedBeforeSubmit"))
        assertEquals(1, state().getInt("captchaChecks"))
    }

    @Test fun anUnavailableFormCanBecomeReadyWithoutConsumingTheAttempt() {
        load()
        evaluate("window.pendingForm = document.querySelector('#loginFromId'); window.pendingForm.remove()")
        assertEquals("\"form-unavailable\"", evaluate(LntuWebLoginAssist.submitScript("fixture-student", "synthetic-password")))
        evaluate("document.querySelector('#pwdLoginDiv').appendChild(window.pendingForm)")
        assertEquals("\"submitted\"", evaluate(LntuWebLoginAssist.submitScript("fixture-student", "synthetic-password")))
        assertEquals(1, state().getInt("submissions"))
    }

    @Test fun aUserEditedFormIsPreserved() {
        load()
        evaluate("document.querySelector('#username').value = 'user-edited-account'")
        assertEquals("\"manual\"", evaluate(LntuWebLoginAssist.submitScript("fixture-student", "synthetic-password")))
        assertEquals("user-edited-account", state().getString("user"))
        assertEquals(0, state().getInt("submissions"))
    }

    @Test fun automaticReturnRequiresTheSignedInAcademicIdentity() {
        val url = LntuWebLoginAssist.COMPLETION_URL + ";jsessionid=FIXTURE.node1"
        load(url = url, body = "<a href='/eams/homeExt.action'>合成学生(fixture-student)</a>")
        assertEquals("true", evaluate(LntuWebLoginAssist.signedInScript("fixture-student")))
        assertEquals("false", evaluate(LntuWebLoginAssist.signedInScript("other-student")))
        load(url = url, body = "<input type='password'><a href='/eams/homeExt.action'>请登录</a>")
        assertEquals("false", evaluate(LntuWebLoginAssist.signedInScript("fixture-student")))
    }

    @After fun destroy() { instrumentation.runOnMainSync { browser?.destroy(); browser = null } }
}
