package com.tyust.course.academic.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.academic.*
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BuiltinLoginRegressionDeviceTest {
    @Test fun bundledZfLogsInAndRefreshesTheSameAccountAgainstAMockSchool() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AcademicProviderRegistry.initialize(context)
        MockWebServer().use { server ->
            val submissions = AtomicInteger()
            val profile = "<html><input name='xh' value='synthetic-student'><input name='xm' value='测试学生'>退出登录</html>"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val response = MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8")
                    return when {
                        path == "/sso/zfiotlogin" -> response.setResponseCode(404)
                        path == "/xtgl/login_slogin.html" && request.method == "GET" -> response
                            .setHeader("Set-Cookie", "session=synthetic; Path=/")
                            .setBody("<html><form method='post'><input name='csrftoken' value='csrf'><input name='yhm'><input name='mm' type='password'><input name='mmsfjm' value='0'></form></html>")
                        path == "/xtgl/login_slogin.html" && request.method == "POST" -> {
                            submissions.incrementAndGet()
                            response.setBody(profile).setBodyDelay(6, TimeUnit.SECONDS)
                        }
                        path in setOf("/xtgl/index_initMenu.html", "/xtgl/index_cxYhxxIndex.html") -> response.setBody(profile)
                        else -> response.setResponseCode(404)
                    }
                }
            }
            server.start()
            val school = SchoolConfig("login-regression", "合成学校", "localhost:${server.port}", "http").apply {
                academicSystem = "zf"
                basePath = ""
            }
            val session = AcademicSessionStore().session(school.id, "dev:login-regression", server.url("/").toString())
            val provider = requireNotNull(AcademicProviderRegistry.resolve(school))
            repeat(2) {
                val adapter = AcademicProviderRegistry.adapterFor(provider, school, session)
                val result = adapter.login(Credentials("synthetic-student", "synthetic-password"))
                assertEquals(AcademicStatus.SUCCESS, result.status)
                assertEquals("synthetic-student", result.studentId)
                adapter.clearLoginState()
                assertTrue(session.cookieHeader().contains("session=synthetic"))
            }
            assertEquals("Login and refresh must each submit exactly once", 2, submissions.get())
        }
    }
}
