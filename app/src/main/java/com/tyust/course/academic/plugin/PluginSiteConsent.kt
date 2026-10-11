package com.tyust.course.academic.plugin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A user grants a site, not a claim that each HTTP request is harmless. */
internal object PluginSiteConsent {
    fun key(caller: PluginPackage, account: String, school: String, provider: PluginPackage?) =
        caller.manifest.id + ":academic-session:consent:site-v1:" + owner(caller, account, school, provider)

    fun owner(caller: PluginPackage, account: String, school: String, provider: PluginPackage?): String =
        PluginJson.sha256(PluginJson.canonical(JSONObject().put("caller", caller.manifest.id)
            .put("publisher", PluginReviewProof.publisher(caller)).put("account", account).put("school", school)
            .put("provider", provider?.manifest?.id.orEmpty()).put("providerPublisher", provider?.let(PluginReviewProof::publisher).orEmpty())).toByteArray())

    fun origins(caller: PluginPackage, provider: PluginPackage?, base: HttpUrl): Set<String> {
        val offered = setOf(PluginAuthScope.origin(base)) + PluginJson.strings(
            provider?.manifest?.json?.optJSONObject("academicSharing")?.optJSONArray("origins") ?: JSONArray()).mapNotNull { it.toHttpUrlOrNull()?.let(PluginAuthScope::origin) }
        return caller.manifest.network.mapNotNull { it.optString("origin").toHttpUrlOrNull()?.let(PluginAuthScope::origin) }
            .filter { it in offered }.toSortedSet()
    }

    fun requireRequest(origins: Set<String>, url: HttpUrl, method: String, purpose: String): JSONObject {
        if (Regex("(?i)%25").containsMatchIn(url.encodedPath) || PluginAuthScope.origin(url) !in origins || method !in setOf("GET", "POST") || purpose !in setOf("query", "mutation"))
            throw PluginException(PluginErrorCode.UNTRUSTED_URL, "请求超出已授权的学校站点")
        val rule = JSONObject().put("origin", url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")).put("pathPrefix", "/")
            .put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(listOf("query", "mutation")))
        // Reuse URL canonicalization checks without importing endpoint/schema restrictions.
        return PluginNetworkPolicy(listOf(rule)).requireAllowed(url, method, purpose, null)
    }
}
