package com.tyust.course.academic.plugin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Only a verified authentication provider may describe a reusable low-risk operation. */
internal object PluginSharedOperation {
    fun match(provider: PluginPackage?, url: HttpUrl, method: String, purpose: String, form: JSONObject?): JSONObject? {
        if (provider == null || !provider.bundled && (!provider.official || provider.publisher == null)) return null
        val candidates = provider.manifest.json.optJSONArray("sharedOperations")?.let(PluginJson::objects).orEmpty()
            .filter { it.optString("origin").toHttpUrlOrNull()?.let(PluginAuthScope::origin) == PluginAuthScope.origin(url) && it.optString("path") == url.encodedPath && it.optString("method") == method }
        if (candidates.isEmpty()) return null
        val rule = candidates.singleOrNull() ?: denied()
        if (rule.getString("purpose") != purpose) denied()
        val query = JSONObject()
        url.queryParameterNames.forEach { key ->
            val values = url.queryParameterValues(key)
            if (values.size != 1) denied()
            query.put(key, values.single())
        }
        PluginSchema(JSONObject()).validate(query, rule.getJSONObject("query"))
        PluginSchema(JSONObject()).validate(form ?: JSONObject(), rule.getJSONObject("form"))
        return rule
    }
    private fun denied(): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, "请求与提供者审核的操作范围不符")
}
