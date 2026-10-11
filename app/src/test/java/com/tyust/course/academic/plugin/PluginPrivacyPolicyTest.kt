package com.tyust.course.academic.plugin

import android.app.Application
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginPrivacyPolicyTest {
    private fun pkg(id: String = "test.privacy", digest: String = "release-one", publisher: String? = "key-one") = PluginPackage(
        PluginManifest(JSONObject().put("id", id).put("name", "Synthetic privacy fixture").put("version", "1.0.0").put("apiVersion", 3).put("kind", "native")
            .put("permissions", JSONArray(listOf("academic.read", "network"))).put("network", JSONArray())
            .put("dataDisclosure", JSONArray().put(JSONObject().put("origin", "https://analysis.example").put("categories", JSONArray(listOf("academic"))).put("purpose", "Synthetic analysis")))), "", digest, true, publisher = publisher)
    @Test fun consentSurvivesSamePublisherUpgradeButNotOwnerAccountOrScopeChanges() {
        val original = pkg()
        fun identity(p: PluginPackage = original, account: String = "synthetic-account") = PluginConsentPolicy.identity(p, account, "school", null)
        assertEquals(identity(), identity(original.copy(digest = "new-release")))
        assertNotEquals(identity(), identity(original.copy(publisher = "other-key")))
        assertNotEquals(identity(), identity(account = "other-account"))
        assertNotEquals(identity(pkg(publisher = null)), identity(pkg(digest = "other-dev", publisher = null)))
        val expanded = original.copy(manifest = PluginManifest(JSONObject(original.manifest.json.toString()).put("permissions", JSONArray(listOf("academic.read","network","files")))))
        assertNotEquals(identity(), identity(expanded))
    }
    @Test fun disclosureRequiresExplicitConsentAndLabelSurvivesRestartRevocationAndPermissionRemoval() {
        val app = RuntimeEnvironment.getApplication(); val p = pkg()
        val guard = PluginDataGuard(app, p); val destination = "https://analysis.example/result".toHttpUrl()
        assertFalse(guard.allowed(destination)); guard.mark(); guard.authorize(destination)
        assertTrue(PluginDataGuard(app, p).allowed(destination))
        assertFalse(guard.allowed("https://analysis.example.evil.test/result".toHttpUrl()))
        guard.revoke()
        val withoutPermission = p.copy(manifest=PluginManifest(JSONObject(p.manifest.json.toString()).put("permissions", JSONArray())))
        assertTrue(PluginDataGuard(app, withoutPermission).sensitive())
        assertThrows(PluginException::class.java) { PluginDataGuard(app, withoutPermission).requireNetwork(destination) }
    }
    @Test fun revocationCancelsInflightCallsAndInvalidatesAnAlreadyOpenDisclosurePrompt() {
        val app = RuntimeEnvironment.getApplication(); val p = pkg()
        val guard = PluginDataGuard(app, p)
        val session = com.tyust.course.academic.AcademicSessionStore().session("synthetic", "account", "https://school.example")
        val operation = PluginOperation(session, p.manifest, "host.effect")
        guard.track(operation); guard.revoke()
        assertThrows(PluginException::class.java) { operation.requireActive() }
        assertThrows(PluginException::class.java) { guard.authorize("https://analysis.example".toHttpUrl()) }
        guard.untrack(operation); session.retire()
    }
    @Test fun crossPluginDataCannotLaunderThePersistentLabel() {
        val app = RuntimeEnvironment.getApplication()
        val receiver = pkg("test.receiver").copy(manifest=PluginManifest(JSONObject(pkg("test.receiver").manifest.json.toString()).put("permissions", JSONArray())))
        val source = PluginDataGuard(app, pkg()); val target = PluginDataGuard(app, receiver)
        assertFalse(target.sensitive()); target.inherit(source)
        assertTrue(PluginDataGuard(app, receiver).sensitive())
        assertThrows(PluginException::class.java) { target.requireNetwork("https://analysis.example".toHttpUrl()) }
    }
    @Test
    @Config(sdk = [24, 32])
    fun actualSecretsCannotLeakInTextBinaryEncodingOrUrl() {
        for (body in listOf("cookie=synthetic-secret", "c3ludGhldGljLXNlY3JldA=="))
            assertThrows(PluginException::class.java) { PluginSecretResponse.requireSafe(body.toByteArray(), "https://school.example", listOf("synthetic-secret")) }
        assertThrows(PluginException::class.java) { PluginSecretResponse.requireSafe("ok".toByteArray(), "https://school.example/?t=synthetic-secret", listOf("synthetic-secret")) }
        PluginSecretResponse.requireSafe("synthetic-grade".toByteArray(), "https://school.example", listOf("synthetic-secret"))
    }
    @Test fun siteOriginsAcceptIpv6AndRejectCredentialsOrOtherPorts() {
        val url = "https://[::1]/api/read".toHttpUrl()
        val origins = setOf(PluginAuthScope.origin(url))
        PluginSiteConsent.requireRequest(origins, url, "GET", "query")
        for (bad in listOf("https://[::1]:8443/api/read", "https://user:secret@[::1]/api/read"))
            assertThrows(PluginException::class.java) { PluginSiteConsent.requireRequest(origins, bad.toHttpUrl(), "GET", "query") }
    }

    @Test fun reviewProofBindsTheExactPackageAndPermissionsAndManifestCannotSelfCertify() {
        val p = pkg(digest = "a".repeat(64))
        val scope = JSONObject()
        for (name in listOf("academicSharing", "network", "permissions", "dataDisclosure", "sharedOperations")) scope.put(name, p.manifest.json.opt(name) ?: JSONObject.NULL)
        val proof = JSONObject().put("version", 1).put("packageSha256", p.digest).put("sourceSha256", "b".repeat(64))
            .put("scopeSha256", PluginJson.sha256(PluginJson.canonical(scope).toByteArray()))
            .put("reviewedAt", "2026-09-30T00:00:00Z").put("contractVersion", "3.2.5").put("ruleVersion", "synthetic")
        assertTrue(PluginReviewProof.matches(p, proof))
        assertFalse(PluginReviewProof.matches(p.copy(digest = "changed"), proof))
        val expanded = p.copy(manifest = PluginManifest(JSONObject(p.manifest.json.toString()).put("permissions", JSONArray(listOf("academic.read", "network", "files")))))
        assertFalse(PluginReviewProof.matches(expanded, proof))
        assertFalse(PluginReviewProof.reviewed(p.copy(manifest = PluginManifest(JSONObject(p.manifest.json.toString()).put("securityReview", proof)))))
    }

    @Test fun callerCannotDeclareAnUnreviewedLowRiskOperation() {
        val parameter = JSONObject().put("type","object").put("properties",JSONObject()).put("additionalProperties",false)
        val declaration=JSONObject().put("id","seen").put("title","Read state").put("origin","https://school.example").put("path","/seen").put("method","POST").put("purpose","mutation").put("risk","read-state").put("query",parameter).put("form",parameter)
        val source = pkg().copy(manifest=PluginManifest(JSONObject(pkg().manifest.json.toString()).put("sharedOperations",JSONArray().put(declaration))))
        assertNotNull(PluginSharedOperation.match(source,"https://school.example/seen".toHttpUrl(),"POST","mutation",JSONObject()))
        assertNull(PluginSharedOperation.match(source.copy(official=false),"https://school.example/seen".toHttpUrl(),"POST","mutation",JSONObject()))
        assertThrows(PluginException::class.java) { PluginSharedOperation.match(source,"https://school.example/seen".toHttpUrl(),"POST","query",JSONObject()) }
        assertThrows(PluginException::class.java) { PluginSharedOperation.match(source,"https://school.example/seen?leak=secret".toHttpUrl(),"POST","mutation",JSONObject()) }
    }
}
