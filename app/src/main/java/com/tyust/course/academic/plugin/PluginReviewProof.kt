package com.tyust.course.academic.plugin

import org.json.JSONObject

/** Metadata comes only from the store's reverified signed catalog, never the manifest. */
internal object PluginReviewProof {
    fun reviewed(pkg: PluginPackage): Boolean {
        if (!pkg.official || pkg.publisher == null) return false
        val metadata = runCatching { AcademicProviderRegistry.packages().metadata(pkg.manifest.id) }.getOrNull() ?: return false
        val release = metadata.optJSONArray("releases")?.let(PluginJson::objects)?.firstOrNull { it.optString("sha256") == pkg.digest }
            ?: metadata.takeIf { it.optString("sha256") == pkg.digest } ?: return false
        return matches(pkg, release.optJSONObject("securityReview") ?: return false)
    }
    fun matches(pkg: PluginPackage, proof: JSONObject): Boolean {
        val scope = JSONObject()
        for (name in listOf("academicSharing", "network", "permissions", "dataDisclosure", "sharedOperations")) scope.put(name, pkg.manifest.json.opt(name) ?: JSONObject.NULL)
        return proof.optInt("version") == 1 && proof.optString("packageSha256") == pkg.digest &&
            proof.optString("sourceSha256").matches(Regex("[a-f0-9]{64}")) && proof.optString("reviewedAt").isNotBlank() &&
            proof.optString("contractVersion").isNotBlank() && proof.optString("ruleVersion").isNotBlank() &&
            proof.optString("scopeSha256") == PluginJson.sha256(PluginJson.canonical(scope).toByteArray())
    }
    fun publisher(pkg: PluginPackage) = if (pkg.bundled || reviewed(pkg)) PluginConsentPolicy.publisher(pkg) else "package:${pkg.digest}"
}
