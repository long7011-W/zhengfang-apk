package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject

/** Stable consent identity is independent of a login instance, never of its owner. */
internal object PluginConsentPolicy {
    fun publisher(pkg: PluginPackage): String = when {
        pkg.official && pkg.publisher != null -> "signed:${pkg.publisher}"
        pkg.bundled -> "bundled:${pkg.manifest.id}"
        else -> "package:${pkg.digest}"
    }
    // Sort set-like manifest declarations; object keys are canonicalized by PluginJson.
    private fun normalized(value: Any?): Any = when (value) {
        is JSONObject -> JSONObject().apply { value.keys().asSequence().sorted().forEach { put(it, normalized(value.get(it))) } }
        is JSONArray -> JSONArray((0 until value.length()).map { normalized(value.get(it)) }.sortedBy { PluginJson.canonical(it) })
        else -> value ?: JSONObject.NULL
    }
    fun identity(pkg: PluginPackage, account: String, school: String, provider: PluginPackage?): String {
        val scope = JSONObject().put("caller", pkg.manifest.id).put("publisher", publisher(pkg))
            .put("account", account).put("school", school)
            .put("provider", provider?.manifest?.id.orEmpty()).put("providerPublisher", provider?.let(::publisher).orEmpty())
        for (name in listOf("network", "permissions", "dataDisclosure", "sharedOperations", "service", "services", "servers", "matches"))
            scope.put(name, normalized(pkg.manifest.json.opt(name)))
        scope.put("providerOperations", normalized(provider?.manifest?.json?.opt("sharedOperations")))
        return PluginJson.sha256(PluginJson.canonical(scope).toByteArray())
    }
}
