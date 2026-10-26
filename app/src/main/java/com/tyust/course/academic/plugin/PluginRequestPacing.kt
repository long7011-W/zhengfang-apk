package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSchoolProfiles
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl

/** School transport limits are shared by all calls in the current account session. */
internal object PluginRequestPacing {
    internal fun intervalMillis(url: HttpUrl): Long = if (url.isHttps && url.host == "webvpn.lntu.edu.cn" &&
        url.port == 443 && (url.encodedPath == AcademicSchoolProfiles.LNTU_BASE_PATH ||
            url.encodedPath.startsWith(AcademicSchoolProfiles.LNTU_BASE_PATH + "/"))) 1_250 else 0

    fun await(operation: PluginOperation, url: HttpUrl) {
        val interval = intervalMillis(url)
        if (interval == 0L) return
        operation.requireActive()
        // Host bridge calls already run synchronously on a Binder worker. Reuse the account's
        // monotonic request clock, then recheck cancellation before a request can be sent.
        runBlocking { operation.session.paceRequest(interval) }
        operation.requireActive()
    }
}
