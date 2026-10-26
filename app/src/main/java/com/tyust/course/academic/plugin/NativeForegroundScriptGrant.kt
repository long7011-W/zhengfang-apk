package com.tyust.course.academic.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** An in-memory grant for one explicitly started queue. Never serialized or exposed to plugin code. */
internal class NativeForegroundScriptGrant(declaration: JSONObject, input: JSONObject, private val active: () -> Boolean) {
    private val plan = JSONObject(declaration.toString())
    private val selection = JSONObject(input.toString())
    val scriptId: String = plan.getString("id")
    private val routes = PluginJson.objects(plan.getJSONArray("starts"))
    private val fields = routes.first().getJSONObject("scope").getJSONObject("parameters")
    private val items = PluginJson.objects(selection.optJSONArray(routes.first().getJSONObject("scope").getString("inputList")) ?: JSONArray())
    private val owned = linkedMapOf<String, Map<String, String>>()
    private val started = mutableSetOf<Map<String, String>>()
    private val consumed = mutableSetOf<String>()

    init {
        if (items.size !in 1..20) invalid("请选择 1–20 份作业或任务")
        val bindings = items.map { item -> fields.keys().asSequence().associateWith { key ->
            (item.opt(fields.getString(key)) as? String)?.takeIf { it.isNotBlank() && it.length <= 256 }
                ?: invalid("队列项目缺少有效的范围标识")
        } }
        if (bindings.distinct().size != bindings.size) invalid("队列项目重复")
    }

    fun supports(name: String) = name in setOf("userscript.start", "userscript.control", "userscript.configure", "userscript.action", "userscript.interact")
    private fun checkActive() { if (!active()) denied("持续任务的账号、版本或授权已失效") }
    private fun enabled(rule: JSONObject) = !rule.has("when") || selection.opt(rule.getString("when")) == true
    private fun binding(value: String): Map<String, String>? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (routes.none { route -> url.host == route.getString("host") &&
                (url.encodedPath == route.getString("pathPrefix") || url.encodedPath.startsWith(route.getString("pathPrefix").trimEnd('/') + "/")) }) return null
        val actual = mutableMapOf<String, String>()
        for (key in fields.keys()) {
            if (url.queryParameterNames.filter { it.equals(key, true) } != listOf(key)) return null
            actual[key] = url.queryParameterValues(key).singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
        }
        return actual.takeIf { items.any { item -> fields.keys().asSequence().all { key -> item.opt(fields.getString(key)) == actual[key] } } }
    }

    @Synchronized fun requireStart(id: String, url: String, current: JSONObject?) {
        checkActive()
        if (id != scriptId) denied("脚本不在本次队列范围内")
        if (current != null && current.optString("handle").isNotBlank() && current.optString("status") !in TERMINAL)
            denied("请先停止当前脚本，再启动作业队列")
        val selected = binding(url) ?: denied("启动地址超出本次选择的任务范围")
        if (!started.add(selected)) duplicate()
    }

    @Synchronized fun remember(handle: String, url: String) {
        // Register before another suspension, so cancellation can stop a just-created script.
        owned[handle] = binding(url) ?: denied("启动地址不属于本次队列")
    }
    @Synchronized fun handles(): List<String> = owned.keys.toList()
    @Synchronized fun allowsDocument(handle: String, href: String): Boolean = active() && owned[handle]?.let { binding(href) == it } == true
    @Synchronized fun requireControl(input: JSONObject, current: JSONObject) {
        checkActive()
        if (input.optString("action") != "stop" || input.has("values") ||
            current.optString("handle") != input.optString("handle") || !owned.containsKey(input.optString("handle")))
            denied("持续队列只能停止自己启动的脚本")
    }
    @Synchronized fun requireConfiguration(input: JSONObject, current: JSONObject? = null) {
        checkActive()
        if (current != null && current.optString("handle").isNotBlank() &&
            current.optString("status") !in TERMINAL && !owned.containsKey(current.optString("handle")))
            denied("请先停止当前脚本，再应用本轮作业设置")
        if (input.optString("scriptId") != scriptId || input.has("credential") ||
            PluginJson.canonical(input.optJSONObject("values") ?: JSONObject()) != PluginJson.canonical(plan.getJSONObject("settings")))
            denied("持续队列不能修改声明以外的脚本设置或凭据")
    }
    @Synchronized fun requireInteraction(input: JSONObject, current: JSONObject, command: Boolean) {
        checkActive()
        val handle = input.optString("handle")
        val scope = owned[handle] ?: denied("脚本不属于本次队列")
        if (current.optString("handle") != handle || binding(current.optString("lastUrl")) != scope || current.optString("status") in TERMINAL)
            denied("当前页面已离开本次作业范围")
        if (input.has("credential")) denied("持续队列不能自动输入凭据")
        val action = input.optString("actionId")
        val key: String
        if (command) {
            if (current.optJSONObject("interaction")?.optString("id").orEmpty().isNotBlank() ||
                PluginJson.objects(plan.getJSONArray("actions")).none { it.getString("id") == action && enabled(it) } ||
                PluginJson.objects(current.optJSONArray("actions") ?: JSONArray()).none { it.optString("id") == action })
                denied("操作未包含在启动队列时的授权中")
            key = "$handle:action:$action"
        } else {
            val prompt = current.optJSONObject("interaction") ?: denied("提示已关闭")
            if (prompt.optString("id") != input.optString("interactionId") || prompt.optString("kind") != "notice" ||
                PluginJson.objects(prompt.optJSONArray("actions") ?: JSONArray()).none { it.optString("id") == action }) denied("提示已变化或需要手动输入")
            if (PluginJson.objects(plan.getJSONArray("prompts")).none { rule ->
                    rule.getString("actionId") == action && enabled(rule) &&
                        (if (rule.getString("ruleId") == "native") prompt.optString("id").startsWith("native:") && rule.optString("messageIncludes").isNotBlank()
                         else prompt.optString("ruleId") == rule.getString("ruleId")) &&
                        (!rule.has("messageIncludes") || prompt.optString("message").contains(rule.getString("messageIncludes")))
                }) denied("此提示不在本次自动处理范围内")
            key = "$handle:prompt:${prompt.getString("id")}:$action"
        }
        if (!consumed.add(key)) duplicate()
    }
    fun description(labels: Map<String, String>): String {
        val names = items.joinToString("、") { it.optString("name", "所选项目") }.take(600)
        val actions = PluginJson.objects(plan.getJSONArray("actions")).filter(::enabled).joinToString("、") { labels[it.getString("id")] ?: "已声明操作" }
        return "本次仅处理 ${items.size} 项：$names。自动操作范围：$actions。切换账号或停止队列后授权失效。"
    }
    companion object {
        private val TERMINAL = setOf("stopped", "interrupted", "failed", "result_unknown", "idle", "completed")
        private fun invalid(message: String): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, message)
        private fun denied(message: String): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, message)
        private fun duplicate(): Nothing = throw PluginException(PluginErrorCode.RESULT_UNKNOWN, "此队列操作已发送，请先核对结果")
    }
}
