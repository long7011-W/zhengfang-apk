package com.tyust.course.academic.plugin

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Bounded metadata only: never accepts request strings, exception messages or account identifiers. */
internal object PluginTrace {
    private val traces = LinkedHashMap<String, JSONObject>()
    private val start = mutableMapOf<String, Long>()
    private var context: Context? = null
    fun initialize(app: Context) { context = app.applicationContext }
    @Synchronized fun stage(op: PluginOperation, phase: String, durationMs: Long? = null) {
        if (!phase.matches(Regex("[a-z_]{1,40}"))) return
        val now = SystemClock.elapsedRealtime()
        val row = traces.getOrPut(op.id) {
            start[op.id] = now
            JSONObject().put("call", op.id).put("api", Build.VERSION.SDK_INT)
                .put("app", com.tyust.course.BuildConfig.VERSION_NAME)
                .put("kind", when { op.method.startsWith("auth.") -> "login"; op.method.startsWith("study.") -> "study"; else -> "plugin" })
                .put("pluginVersion", op.manifest.version.takeIf { it.matches(Regex("[0-9A-Za-z.+_-]{1,40}")) } ?: "unknown")
                .put("phases", JSONArray())
        }
        row.put("stage", phase)
        val phases = row.getJSONArray("phases")
        if (phases.length() < 64) phases.put(JSONObject().put("stage", phase).put("elapsedMs", now - (start[op.id] ?: now)).apply { durationMs?.let { put("durationMs", it.coerceAtLeast(0)) } })
        while (traces.size > 32) { val first = traces.keys.first(); traces.remove(first); start.remove(first) }
    }
    @Synchronized fun failure(op: PluginOperation, code: PluginErrorCode) {
        if (!traces.containsKey(op.id)) stage(op, "operation")
        traces[op.id]?.put("error", code.name)
        context?.let { app ->
            val report = "插件调用诊断（不含账号、地址或请求内容）\n" + JSONArray(traces.values.toList()).toString(2)
            com.tyust.course.diagnostics.AppDiagnostics.savePluginReport(app, report.take(48 * 1024))
        }
    }
}
