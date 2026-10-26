package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSchoolProfiles
import com.tyust.course.academic.AcademicSessionStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PluginRequestPacingTest {
    private val base = "https://webvpn.lntu.edu.cn${AcademicSchoolProfiles.LNTU_BASE_PATH}"

    @Test fun onlyTheVerifiedSchoolContextUsesTheInterval() {
        assertEquals(1_250L, PluginRequestPacing.intervalMillis("$base/homeExt.action?term=1".toHttpUrl()))
        for (url in listOf("http://webvpn.lntu.edu.cn${AcademicSchoolProfiles.LNTU_BASE_PATH}/homeExt.action",
            base.replace(".edu.cn/", ".edu.cn:8443/") + "/homeExt.action",
            base.replace("webvpn.lntu.edu.cn", "other.example.test") + "/homeExt.action",
            "$base-extra/homeExt.action", "https://webvpn.lntu.edu.cn/login")) {
            assertEquals(url, 0L, PluginRequestPacing.intervalMillis(url.toHttpUrl()))
        }
    }

    @Test fun cancellationDuringTheWaitPreventsTheNextRequest() {
        val session = AcademicSessionStore().session("synthetic", "synthetic", base)
        val manifest = PluginManifest(JSONObject().put("id", "synthetic.pacing").put("version", "1.0.0"))
        val first = PluginOperation(session, manifest, "study.terms")
        val entered = CountDownLatch(1)
        val next = PluginOperation(session, manifest, "study.schedule", scopeStillActive = { entered.countDown(); true })
        val executor = Executors.newSingleThreadExecutor()
        try {
            PluginRequestPacing.await(first, "$base/homeExt.action".toHttpUrl())
            val waiting = executor.submit<PluginErrorCode?> {
                try { PluginRequestPacing.await(next, "$base/courseTableForStd.action".toHttpUrl()); null }
                catch (error: PluginException) { error.code }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            next.close()
            assertEquals(PluginErrorCode.CANCELLED, waiting.get(3, TimeUnit.SECONDS))
            assertFalse(next.mutationSent)
        } finally {
            first.close(); next.close(); session.retire(); executor.shutdownNow()
        }
    }
}
