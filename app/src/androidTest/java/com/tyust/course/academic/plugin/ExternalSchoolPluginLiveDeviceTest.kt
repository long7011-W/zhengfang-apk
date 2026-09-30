package com.tyust.course.academic.plugin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.academic.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in account and package, provided privately by the local runner. Read-only school operations. */
@RunWith(AndroidJUnit4::class)
class ExternalSchoolPluginLiveDeviceTest {
    @Test fun authorizedSchoolPluginLogsInRefreshesAndReadsInheritedQueries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val credentialFile = File(context.filesDir, "authorized-school-plugin.json")
        val packageFile = File(context.filesDir, "authorized-school-plugin.eduplugin")
        assumeTrue("No authorized plugin account supplied", credentialFile.isFile && packageFile.isFile)
        val credentials = try { JSONObject(credentialFile.readText()) } finally { credentialFile.delete() }
        AcademicProviderRegistry.initialize(context)
        val pkg = AcademicProviderRegistry.packages().install(packageFile.readBytes(), allowDevelopment = true)
        AcademicProviderRegistry.reload()
        val school = AcademicProviderRegistry.school(pkg)
        val session = AcademicSessionStore().session(school.id, "dev:authorized-${UUID.randomUUID()}", school.fullBasePath)
        val report = JSONObject().put("pluginId", pkg.manifest.id).put("packageSha256", PluginJson.sha256(packageFile.readBytes()))
        val errors = mutableListOf<String>()
        val adapter = AcademicProviderRegistry.adapterFor(pkg, school, session)
        suspend fun check(name: String, action: suspend () -> Int) {
            try { report.put(name, JSONObject().put("status", "passed").put("count", action())) }
            catch (error: Exception) {
                report.put(name, JSONObject().put("status", "failed").put("message", error.message.orEmpty()))
                errors += "$name: ${error.message}"
            }
        }
        try {
            repeat(2) { index ->
                val result = adapter.login(Credentials(credentials.getString("username"), credentials.getString("password")))
                assertEquals("Plugin login did not authenticate", AcademicStatus.SUCCESS, result.status)
                assertTrue("Plugin identity mismatch", result.studentId == credentials.getString("username"))
                assertEquals(AcademicStatus.SUCCESS, adapter.validateSession().status)
                report.put(if (index == 0) "login" else "refresh", "passed")
            }
            val terms = adapter.catalog()
            report.put("terms", JSONObject().put("status", "passed").put("count", terms.terms.size))
            check("schedule") { adapter.schedule(terms.currentTerm).size }
            check("grades") { adapter.grades(terms.currentTerm).grades.size }
            check("exams") { adapter.exams(terms.currentTerm).size }
            check("selectionScopes") {
                val courses = adapter.loadCourseContext()
                if (courses.scopes.isEmpty()) report.put("courseList", "no-open-selection-round")
                else check("courseList") { adapter.listCourses(courses, CourseQuery()).size }
                courses.scopes.size
            }
            assertTrue(errors.joinToString("; "), errors.isEmpty())
        } finally {
            File(context.getExternalFilesDir(null), "authorized-school-plugin-result.json").writeText(report.toString(2))
            adapter.clearLoginState(); PluginHost.clearTemporaryState(session); session.retire()
        }
    }
}
