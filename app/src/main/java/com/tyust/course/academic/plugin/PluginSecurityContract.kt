package com.tyust.course.academic.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal object PluginSecurityContract {
    fun validate(manifest: PluginManifest) {
        if ((manifest.json.optJSONArray("dataDisclosure")?.length() ?: 0) + (manifest.json.optJSONArray("sharedOperations")?.length() ?: 0) > 0 &&
            manifest.json.optJSONArray("requires")?.let(PluginJson::objects).orEmpty().none { it.optString("name") == "privacy.status" && it.optInt("version") >= 1 }) invalid()
        val destinations = mutableSetOf<String>()
        fun origin(value: String, https: Boolean = false) {
            val url = value.toHttpUrlOrNull() ?: invalid()
            val canonical = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || '*' in url.host || value != canonical || https && !url.isHttps) invalid()
        }
        for (item in manifest.json.optJSONArray("dataDisclosure")?.let(PluginJson::objects).orEmpty()) {
            val value = item.getString("origin"); origin(value, true)
            if (!destinations.add(value)) invalid()
        }
        val operations = mutableSetOf<String>()
        for (item in manifest.json.optJSONArray("sharedOperations")?.let(PluginJson::objects).orEmpty()) {
            if (manifest.kind != "configuration" && "auth.start" !in manifest.capabilities) invalid()
            origin(item.getString("origin"))
            val path = item.getString("path")
            if (!path.startsWith('/') || path.any { it in "\\%?#" } || path.split('/').any { it in setOf(".", "..") }) invalid()
            if (!operations.add("${item.getString("origin")}|$path|${item.getString("method")}")) invalid()
            if (item.getString("risk") == "read" && item.getString("purpose") != "query" || item.getString("risk") == "read-state" && item.getString("purpose") != "mutation") invalid()
            for (name in listOf("query", "form")) {
                val schema = item.getJSONObject(name)
                PluginPlatformContract.validateServiceSchema(schema)
                if (schema.optString("type") != "object" || schema.opt("additionalProperties") != false) invalid()
            }
        }
    }
    private fun invalid(): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, "个人数据接收方或共享操作范围无效")
}
