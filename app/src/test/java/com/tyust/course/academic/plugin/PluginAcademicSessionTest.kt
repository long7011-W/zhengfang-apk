package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicGatewayFactory
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginAcademicSessionTest {
    private lateinit var app: Application
    private lateinit var user: UserManager
    private lateinit var school: SchoolConfig
    private lateinit var server: MockWebServer
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication(); user = UserManager.getInstance(); user.init(app)
        AcademicProviderRegistry.initialize(app)
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        school = SchoolConfig("synthetic-sharing", "Synthetic school", "127.0.0.1:${server.port}", "http").apply { academicSystem = "zf"; basePath = "/jw" }
        user.currentSchool = school; user.studentId = "synthetic-student"; user.saveCookieLogin("SYNTHETIC=existing-login")
    }
    @After fun cleanup() { server.shutdown() }
    private fun pkg(id: String = "test.author.one", author: String = "Author One") = PluginPackage(PluginManifest(JSONObject()
        .put("id", id).put("author", author).put("name", "Synthetic tool").put("kind", "native").put("version", "1.0.0").put("apiVersion", 3)
        .put("permissions", JSONArray(listOf("academic.session", "network"))).put("capabilities", JSONArray())
        .put("contributes", JSONObject().put("pages", JSONArray()).put("entries", JSONArray()))
        .put("matches", JSONArray().put(JSONObject().put("host", "127.0.0.1").put("port", server.port).put("pathPrefix", "/jw")))
        .put("network", JSONArray().put(JSONObject().put("origin", "http://127.0.0.1:${server.port}").put("pathPrefix", "/")
            .put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(listOf("query", "mutation", "auth")))))), "", id.hashCode().toString(), true)
    private fun access(caller: PluginPackage = pkg(), active: () -> Boolean = { true }) = PluginAcademicSession(app, caller, active, callerCurrent = { true })
    private fun request(path: String = "/jw/read", purpose: String = "query") = JSONObject().put("url", "http://127.0.0.1:${server.port}$path").put("purpose", purpose)
        .put("method", if (purpose == "mutation") "POST" else "GET")
    private fun host(access: PluginAcademicSession, caller: PluginPackage, grant: String, confirmed: Boolean = false): Pair<PluginOperation, PluginHost> {
        val op = PluginOperation(access.session, caller.manifest, "host.effect", confirmed = confirmed, scopeStillActive = { access.requireGrant(grant); true })
        return op to PluginHost(op, app.cacheDir, access.cookies(grant)) { url, method, purpose, form -> access.requireRequest(grant, url, method, purpose, form) }
    }

    private fun siteHost(a: PluginAcademicSession, caller: PluginPackage, grant: String): PluginHost {
        val op = PluginOperation(a.session, caller.manifest, "host.effect", scopeStillActive = { a.requireGrant(grant); true })
        return PluginHost(op, app.cacheDir, a.cookies(grant), sharedSite = { a.siteAuthorized() },
            sharedApproval = a::requireReviewedReadOrConfirmation, sharedRequest = { url, method, purpose, form -> a.requireRequest(grant, url, method, purpose, form) })
    }
    @Test fun localSiteConsentAllowsTwentyRefreshesRawBodiesAndSameSiteRedirectsWithoutPrompts() {
        val caller = pkg().copy(official = false)
        val a = access(caller); val grant = a.authorizeSite(true).getString("grant")
        var prompts = 0; a.confirmUnknownRequest = { _, _, _ -> prompts++; false }
        val host = siteHost(a, caller, grant)
        repeat(20) {
            server.enqueue(MockResponse().setBody("updated")); server.enqueue(MockResponse().setBody("notice text"))
            assertEquals("updated", host.call("http", request("/new/seen", "mutation").put("body", "{\"id\":1}")).getJSONObject("data").getString("body"))
            assertEquals("notice text", host.call("http", request("/new/text")).getJSONObject("data").getString("body"))
        }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/new/final")); server.enqueue(MockResponse().setBody("redirected"))
        assertEquals("redirected", host.call("http", request("/new/text")).getJSONObject("data").getString("body"))
        assertEquals(0, prompts); assertEquals(42, server.requestCount)
    }
    @Test fun siteConsentReopensAndRebindsButLocalPackageChangesAndRevocationDoNotInherit() {
        val caller = pkg().copy(official = false)
        val a = access(caller); val grant = a.authorizeSite(true).getString("grant")
        assertTrue(access(caller).siteAuthorized())
        user.saveCookieLogin("SYNTHETIC=renewed-login")
        val reopened = access(caller); assertTrue(reopened.siteAuthorized())
        assertNotEquals(grant, reopened.existingGrant())
        assertThrows(PluginException::class.java) { a.requireGrant(grant) }
        assertFalse(access(caller.copy(digest = "new-package")).siteAuthorized())
        PluginAcademicSession.revoke(app, caller.manifest.id)
        assertThrows(PluginException::class.java) { reopened.authorizeSite(true) }
        assertFalse(access(caller).siteAuthorized())
    }
    @Test fun onceConsentCoversTheCurrentLoginSessionButNeverANewLogin() {
        val caller = pkg().copy(official = false)
        val a = access(caller); a.authorizeSite(false)
        repeat(20) { assertTrue(access(caller).siteAuthorized()) }
        user.saveCookieLogin("SYNTHETIC=new-login")
        assertFalse(access(caller).siteAuthorized())
    }
    @Test fun siteScopeRejectsForeignOriginsEncodedPathsAndNeverPromotesLegacyConsent() {
        val caller = pkg(); val a = access(caller)
        a.authorize(remember = true); assertFalse(access(caller).siteAuthorized())
        val grant = a.authorizeSite(true).getString("grant"); val host = siteHost(a, caller, grant)
        for (request in listOf(request().put("url", "https://other-school.test/jw/read"), request("/jw/%2foutside")))
            assertThrows(PluginException::class.java) { host.call("http", request) }
        assertEquals(0, server.requestCount)
    }
    @Test fun siteTransportReusesConnectionsButNeverAcrossAccounts() {
        val caller = pkg(); val a = access(caller); val grant = a.authorizeSite(true).getString("grant")
        repeat(2) { server.enqueue(MockResponse().setBody("ok")); siteHost(a, caller, grant).call("http", request()) }
        assertEquals(0, server.takeRequest().sequenceNumber); assertEquals(1, server.takeRequest().sequenceNumber)
        user.studentId = "other-student"; user.saveCookieLogin("SYNTHETIC=other-login")
        val b = access(caller); assertFalse(b.siteAuthorized()); val next = b.authorizeSite(true).getString("grant")
        server.enqueue(MockResponse().setBody("ok")); siteHost(b, caller, next).call("http", request())
        assertEquals(0, server.takeRequest().sequenceNumber)
    }

    @Test fun differentAuthorsReuseOneLoginWithSeparateRevocableGrants() {
        val first = pkg(); val second = pkg("test.author.two", "Unrelated Author")
        val a = access(first); val b = access(second)
        val grantA = a.authorize().getString("grant"); val resultB = b.authorize(); val grantB = resultB.getString("grant")
        assertNotEquals(grantA, grantB); assertSame(a.session, b.session)
        assertFalse(resultB.toString().contains("existing-login"))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, assertThrows(PluginException::class.java) { b.requireGrant(grantA) }.code)
        for ((caller, access, grant) in listOf(Triple(first, a, grantA), Triple(second, b, grantB))) {
            server.enqueue(MockResponse().setBody("synthetic response").addHeader("Set-Cookie", "rotated=fixture; Path=/jw"))
            val (op, host) = host(access, caller, grant)
            val response = host.call("http", request()).getJSONObject("data")
            assertEquals("synthetic response", response.getString("body")); assertFalse(response.getJSONObject("headers").has("Set-Cookie"))
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie")!!.contains("SYNTHETIC=existing-login")); op.close()
        }
        PluginAcademicSession.revoke(app, first.manifest.id)
        assertThrows(PluginException::class.java) { a.requireGrant(grantA) }
        b.requireGrant(grantB)
        assertTrue(b.session.cookieHeader().contains("rotated=fixture"))
    }

    @Test fun missingGrantOtherSchoolAndOutOfScopeRedirectNeverReceiveTheLogin() {
        val caller = pkg(); val access = access(caller)
        assertThrows(PluginException::class.java) { access.requireGrant("ungranted") }
        val grant = access.authorize().getString("grant")
        val (op, host) = host(access, caller, grant)
        for (path in listOf("/outside", "/jw-other", "/jw/%2foutside")) {
            assertEquals(PluginErrorCode.UNTRUSTED_URL, assertThrows(PluginException::class.java) { host.call("http", request(path)) }.code)
        }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/other-system"))
        assertEquals(PluginErrorCode.UNTRUSTED_URL, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        assertEquals(1, server.requestCount); op.close()
        val other = caller.copy(manifest = PluginManifest(JSONObject(caller.manifest.json.toString()).put("matches", JSONArray()
            .put(JSONObject().put("host", "other-school.test").put("pathPrefix", "/")))))
        assertThrows(PluginException::class.java) { access(other).authorize() }
    }

    @Test fun accountPluginAndSchoolProviderChangesInvalidateCapturedAuthorization() {
        val caller = pkg(); val a = access(caller); val grant = a.authorize().getString("grant")
        val updated = access(caller.copy(digest = "updated"))
        assertThrows(PluginException::class.java) { updated.requireGrant(grant) }
        AcademicProviderRegistry.choose(school, "builtin.qz")
        assertThrows(PluginException::class.java) { a.requireGrant(grant) }
        AcademicProviderRegistry.choose(school, null)
        user.sessionState.replace("another-synthetic-account")
        assertThrows(PluginException::class.java) { a.authorize() }
    }

    @Test fun sessionExpiryAndActivityCancellationCannotReuseOrCommitAGrant() {
        var live = true
        val a = access(active = { live }); val grant = a.authorize().getString("grant")
        live = false; assertThrows(PluginException::class.java) { a.authorize() }
        live = true
        user.sessionState.expire(user.sessionState.token)
        assertThrows(PluginException::class.java) { a.requireGrant(grant) }
        AcademicGatewayFactory.invalidate(school, user.currentAccountStorageKey)
        assertThrows(PluginException::class.java) { a.requireGrant(grant) }
    }

    @Test fun mutationsStillNeedConfirmationAndRevokingACallPreservesUnknownResult() {
        val caller = pkg(); val a = access(caller); val grant = a.authorize().getString("grant")
        val (unconfirmed, host) = host(a, caller, grant)
        assertThrows(PluginException::class.java) { host.call("http", request(purpose = "mutation")) }
        assertEquals(0, server.requestCount); unconfirmed.close()
        val (confirmed, _) = host(a, caller, grant, confirmed = true)
        a.track(grant, confirmed); confirmed.markMutation()
        PluginAcademicSession.revoke(app, caller.manifest.id)
        val error = assertThrows(PluginException::class.java) { confirmed.requireActive() }
        assertEquals(PluginErrorCode.RESULT_UNKNOWN, confirmed.failure(error.code, error.message.orEmpty()).code)
        a.untrack(confirmed)
    }

    @Test fun callingAnUnknownEndpointQueryDoesNotBypassConfirmation() {
        val a = access(); a.authorize()
        assertThrows(PluginException::class.java) { a.requireReviewedReadOrConfirmation(request()) }
        var confirmations = 0
        a.confirmUnknownRequest = { _, _, _ -> confirmations++; true }
        a.requireReviewedReadOrConfirmation(request())
        a.requireReviewedReadOrConfirmation(request())
        assertEquals(2, confirmations)
        a.confirmUnknownRequest = { _, _, _ -> false }
        assertThrows(PluginException::class.java) { a.requireReviewedReadOrConfirmation(request()) }
        assertEquals(0, server.requestCount)
    }

    @Test fun rememberedConsentRebindsAfterReloginButRevocationWinsAgainstAnOldPrompt() {
        val caller = pkg(); val old = access(caller)
        val previous = old.authorize(remember = true).getString("grant")
        user.saveCookieLogin("SYNTHETIC=new-login")
        val replacement = access(caller)
        val next = replacement.existingGrant()
        assertNotNull(next); assertNotEquals(previous, next)
        assertThrows(PluginException::class.java) { old.requireGrant(previous) }
        PluginAcademicSession.revoke(app, caller.manifest.id)
        assertThrows(PluginException::class.java) { replacement.authorize(remember = true) }
        assertNull(access(caller).existingGrant())
    }

    @Test fun cookieTokenBindingStaysInTheHostAndRequiresNetworkPermission() {
        user.saveCookieLogin("token=synthetic-token")
        val caller = pkg(); val a = access(caller); val grant = a.authorize().getString("grant")
        server.enqueue(MockResponse().setBody("done"))
        val (op, host) = host(a, caller, grant)
        host.call("http", request().put("cookieHeader", JSONObject().put("cookie", "token").put("header", "X-Token")))
        assertEquals("synthetic-token", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token")); op.close()
        val undeclared = caller.copy(manifest = PluginManifest(JSONObject(caller.manifest.json.toString()).put("permissions", JSONArray(listOf("academic.session")))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, assertThrows(PluginException::class.java) { access(undeclared).authorize() }.code)
    }
}
