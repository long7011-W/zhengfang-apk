package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.diagnostics.AppDiagnostics
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginTraceTest {
    @Test fun startupReportContainsPhasesButNoAccountRequestOrExceptionText() {
        val app = RuntimeEnvironment.getApplication(); PluginTrace.initialize(app)
        val session = AcademicSessionStore().session("private-school", "private-account", "https://private.school.invalid")
        val manifest = PluginManifest(JSONObject("""{"id":"trace.test","kind":"independent","version":"1.0.0","network":[]}"""))
        val op = PluginOperation(session,manifest,"auth.start")
        try {
            PluginTrace.stage(op,"protocol_lock",12)
            PluginTrace.stage(op,"binding")
            op.failure(PluginErrorCode.RUNTIME_EXITED,"synthetic-password-and-token")
            val report=AppDiagnostics.latest(app)!!
            assertTrue(report.contains(op.id)); assertTrue(report.contains("binding")); assertTrue(report.contains("login")); assertTrue(report.contains("durationMs"))
            listOf("private-account","private-school","private.school.invalid","synthetic-password-and-token").forEach { assertFalse(report.contains(it)) }
        } finally { op.close(); session.retire() }
    }
}
