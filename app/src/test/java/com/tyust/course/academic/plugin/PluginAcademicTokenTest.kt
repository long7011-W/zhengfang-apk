package com.tyust.course.academic.plugin

import android.app.Application
import android.net.Uri
import com.tyust.course.academic.*
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.utils.RecoveryFailure
import com.tyust.course.utils.SessionRecoveryResult
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import okio.ByteString.Companion.toByteString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginAcademicTokenTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var school: SchoolConfig
    private lateinit var source: PluginPackage
    private lateinit var session: AcademicSession
    private val installed = mutableListOf<String>()
    private val secret = "synthetic-response-token"
    private val interaction = object : NativePluginInteraction {
        override suspend fun confirm(title: String, message: String) = true
        override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = null
        override suspend fun pick(types: Array<String>): Uri? = null
        override suspend fun notificationPermission() = false
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) {}
        override fun back() {}
    }
    private fun url(path: String) = "http://127.0.0.1:${server.port}$path".toHttpUrl()
    private fun rule() = JSONObject("""{"response":{"path":"/api/login","method":"POST","jsonPointer":"/data/token"},"request":{"pathPrefix":"/api","header":"X-Token"}}""")
    private fun schoolManifest(value: SchoolConfig = school) = JSONObject().put("id", value.id).put("name", value.name)
        .put("domain", value.domain).put("protocol", value.protocol).put("basePath", value.basePath).put("academicSystem", value.academicSystem)
    private fun network() = JSONArray().put(JSONObject().put("origin", url("/").toString().trimEnd('/')).put("pathPrefix", "/")
        .put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(listOf("auth", "query", "mutation"))))
    private fun install(manifest: JSONObject, signer: KeyPair? = null): PluginPackage {
        // Host integration fixtures have no JS execution; protocol execution is tested in QuickJS.
        val js = ByteArray(0)
        manifest.put("entry", "index.js").put("files", JSONObject().put("index.js", PluginJson.sha256(js)))
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for ((name, data) in listOf("manifest.json" to manifest.toString().toByteArray(), "index.js" to js)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
            if (signer != null) {
                val signature = Signature.getInstance("SHA256withECDSA").run {
                    initSign(signer.private); update(PluginJson.canonical(manifest).toByteArray()); sign()
                }
                zip.putNextEntry(ZipEntry("signature.json"))
                zip.write(JSONObject().put("keyId", "synthetic-consent").put("signature", signature.toByteString().base64()).toString().toByteArray())
                zip.closeEntry()
            }
        } }.toByteArray()
        return runBlocking { AcademicProviderRegistry.packages().install(bytes, allowDevelopment = true) }.also { installed += it.manifest.id; AcademicProviderRegistry.reload() }
    }
    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = RuntimeEnvironment.getApplication(); UserManager.getInstance().init(app)
        AcademicProviderRegistry.initialize(app); PluginPages.initialize(app)
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        school = SchoolConfig("token-school", "Synthetic school", "127.0.0.1:${server.port}", "http").apply { basePath = "/jw"; academicSystem = "zf" }
        source = install(JSONObject().put("id", "test.token-source").put("name", "Synthetic adapter").put("version", "1.0.0")
            .put("kind", "independent").put("apiVersion", 3).put("capabilities", JSONArray(PluginManifest.AUTH))
            .put("school", schoolManifest()).put("network", network()).put("academicSessionToken", rule())
            .put("requires", JSONArray().put(JSONObject().put("name", "academic.session.request").put("version", 2))))
        AcademicProviderRegistry.choose(school, source.manifest.id)
        val user = UserManager.getInstance(); user.init(app); user.currentSchool = school; user.studentId = "synthetic-student"; user.saveCookieLogin("fixture=login")
        session = AcademicGatewayFactory.sharedSession(school, user.currentAccountStorageKey)!!
        assertEquals(url("/jw").toString(), session.baseUrl.trimEnd('/'))
        session.cookies.clear()
    }
    @After fun cleanup() {
        if (::session.isInitialized) session.retire()
        if (::school.isInitialized) AcademicProviderRegistry.choose(school, null)
        installed.distinct().forEach { AcademicProviderRegistry.packages().deactivate(it) }; AcademicProviderRegistry.reload()
        app.getSharedPreferences("plugin-local-catalog", 0).edit().remove("key").commit()
        server.shutdown(); Dispatchers.resetMain()
    }
    private fun capture(body: String = """{"data":{"token":"$secret"}}""", status: Int = 200, result: String = "authenticated"): PluginAcademicTokenCapture {
        val op = PluginOperation(session, source.manifest, "auth.start")
        val capture = PluginAcademicTokenCapture(op, source)
        val host = PluginHost(op, app.cacheDir, captureToken = capture::capture)
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        host.call("http", JSONObject().put("url", url("/jw/api/login").toString()).put("method", "POST").put("purpose", "auth"))
        server.takeRequest(1, TimeUnit.SECONDS)
        assertNull(session.pluginToken)
        capture.publish(JSONObject().put("status", result))
        assertFalse(host.report().toString().contains(secret)); op.close()
        return capture
    }
    private fun caller(id: String) = install(JSONObject().put("id", id).put("name", id).put("version", "1.0.0").put("apiVersion", 3).put("kind", "native")
        .put("capabilities", JSONArray()).put("permissions", JSONArray(listOf("academic.session", "network"))).put("network", network())
        .put("requires", JSONArray().put(JSONObject().put("name", "academic.session.request").put("version", 2)))
        .put("matches", JSONArray().put(JSONObject().put("host", "127.0.0.1").put("port", server.port).put("pathPrefix", "/jw")))
        .put("contributes", JSONObject().put("pages", JSONArray()).put("entries", JSONArray())))
    private fun access(pkg: PluginPackage) = PluginAcademicSession(app, pkg, { true })
    private fun request(path: String = "/jw/api/me") = JSONObject().put("url", url(path).toString()).put("purpose", "query")
    private fun host(pkg: PluginPackage, access: PluginAcademicSession, grant: String): PluginHost = PluginHost(
        PluginOperation(session, pkg.manifest, "host.effect", scopeStillActive = { access.requireGrant(grant); true }), app.cacheDir,
        access.cookies(grant), sharedToken = { access.tokenHeader(grant, it) }, sharedRequest = { url, method, purpose, form -> access.requireRequest(grant, url, method, purpose, form) })
    private fun effect(name: String, input: JSONObject, version: Int = 1) = JSONObject().put("id", "test-effect").put("capability", name).put("version", version).put("input", input)

    @Test fun responseTokenIsSharedByTwoNativePluginsWithoutReturningTheSecret() = runBlocking {
        capture()
        for (id in listOf("test.author-one", "test.author-two")) {
            val pkg = caller(id)
            val local = AcademicSession(AcademicSessionKey("plugin:$id", "default"), "https://invalid.example/")
            val native = NativeCapabilityHost(app, pkg, local, interaction) { true }
            try {
                val grant = native.execute(effect("academic.session.authorize", JSONObject()), NativeFlow(true)) as JSONObject
                assertFalse(grant.toString().contains(secret))
                server.enqueue(MockResponse().setBody("{\"name\":\"fixture\"}"))
                val response = native.execute(effect("academic.session.request", JSONObject().put("grant", grant.getString("grant")).put("request", request()), 2), NativeFlow(true)) as JSONObject
                assertFalse(response.toString().contains(secret))
                val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(secret, sent.getHeader("X-Token")); assertNull(sent.getHeader("Cookie")); assertNull(sent.getHeader("Authorization"))
            } finally { native.close(); local.retire() }
        }
    }

    private fun serviceManifest(id: String, shared: Boolean = true): JSONObject = JSONObject()
        .put("id", id).put("name", "Synthetic campus service").put("version", "1.0.0").put("apiVersion", 3).put("kind", "service")
        .put("school", schoolManifest()).put("network", network()).put("capabilities", JSONArray(listOf("service.page", "service.action")))
        .put("requires", JSONArray().put(JSONObject().put("name", "academic.session.request").put("version", 3)))
        .put("service", JSONObject("""{"schoolIds":["token-school"],"authentication":{"mode":"none"},"pages":[{"id":"main","title":"Main"}],"entries":[{"id":"main","title":"Main","pageId":"main","icon":"school","order":0}],"actions":[{"id":"save","title":"Save","kind":"mutation","confirmation":"Submit synthetic data"}]}""").put("academicSession", shared))

    private fun serviceContext(http: () -> JSONObject = { request() }, beforeRequest: () -> Unit = {}): RespondingSandboxContext =
        RespondingSandboxContext(app) { call, bridge ->
            beforeRequest()
            val payload = http()
            if (call.getString("operation") == "service.action") payload.put("purpose", "mutation").put("method", "POST")
            val response = RespondingSandboxContext.read(bridge.call(call.getJSONObject("context").getString("operationId"), "http",
                RespondingSandboxContext.descriptor(app, payload)))
            if (!response.getBoolean("ok")) response
            else PluginJson.success(if (call.getString("operation") == "service.page")
                JSONObject().put("pageId", "main").put("title", "Fixture").put("blocks", JSONArray())
            else JSONObject().put("actionId", "save").put("confirmed", true))
        }

    private fun reviewedProvider() {
        val signer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        AcademicProviderRegistry.configureLocalCatalog(url("/catalog.json").toString(), JSONObject().put("keyId", "synthetic-consent")
            .put("spki", signer.public.encoded.toByteString().base64()).toString())
        val empty = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")
        val targets = JSONObject("""{"type":"object","properties":{"id":{"type":"string","enum":["one","two"]}},"required":["id"],"additionalProperties":false}""")
        fun operation(id: String, path: String, risk: String) = JSONObject().put("id", id).put("title", "Synthetic $id")
            .put("origin", url("/").toString().trimEnd('/')).put("path", path)
            .put("method", if (risk == "read") "GET" else "POST").put("purpose", if (risk == "read") "query" else "mutation")
            .put("risk", risk).put("query", empty).put("form", if (risk == "read-state") targets else empty)
        val manifest = JSONObject(source.manifest.json.toString()).put("id", "test.reviewed-source")
            .put("sharedOperations", JSONArray().put(operation("seen", "/jw/api/seen", "read-state"))
                .put(operation("read", "/jw/api/me", "read")).put(operation("submit", "/jw/api/submit", "high")))
        manifest.getJSONArray("requires").put(JSONObject().put("name", "privacy.status").put("version", 1))
        source = install(manifest, signer)
        assertTrue(source.official); assertNotNull(source.publisher)
        AcademicProviderRegistry.choose(school, source.manifest.id)
        session = AcademicGatewayFactory.sharedSession(school, UserManager.getInstance().currentAccountStorageKey)!!
        capture()
    }
    private fun seen(id: String = "one") = request("/jw/api/seen").put("method", "POST").put("purpose", "mutation")
        .put("form", JSONObject().put("id", id))
    private fun refreshContext(requests: () -> List<JSONObject> = { listOf(seen(), seen("two"), request()) }) = RespondingSandboxContext(app) { call, bridge ->
        var result = JSONObject()
        for (payload in requests()) {
            val response = RespondingSandboxContext.read(bridge.call(call.getJSONObject("context").getString("operationId"), "http",
                RespondingSandboxContext.descriptor(app, payload)))
            if (!response.getBoolean("ok")) return@RespondingSandboxContext response
            result = response.getJSONObject("data")
        }
        PluginJson.success(if (call.getString("operation") == "service.page") JSONObject().put("pageId", "main")
            .put("title", JSONObject(result.getString("body")).optString("text", "Fixture")).put("blocks", JSONArray())
        else JSONObject().put("actionId", call.getJSONObject("args").getString("actionId")).put("confirmed", true))
    }
    private suspend fun refresh(runtime: ServicePluginSession) {
        repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
        server.enqueue(MockResponse().setBody("""{"text":"synthetic-message"}"""))
        assertEquals("synthetic-message", runtime.page("main").getString("title"))
        repeat(3) { assertEquals(secret, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token")) }
    }

    @Test fun oneGroupedConsentCoversRepeatedReadStateRefreshCloseAndSameAccountRelogin() = runBlocking {
        reviewedProvider(); val pkg = install(serviceManifest("test.refresh-remember"))
        var prompts = 0
        fun runtime() = ServicePluginSession(refreshContext(), pkg, "account", requestConfirmation = { _, _, _ -> prompts++; false },
            readStateConfirmation = { prompts++; null })
        val first = runtime()
        assertTrue(first.academicAuthorizationDescription().contains("学校站点"))
        first.authorizeAcademicSession(remember = true, includeReadState = true)
        repeat(3) { refresh(first) }; first.close()
        val reopened = runtime(); assertTrue(reopened.authenticated); refresh(reopened)
        session.invalidate(); UserManager.getInstance().saveCookieLogin("fixture=renewed")
        session = AcademicGatewayFactory.sharedSession(school, UserManager.getInstance().currentAccountStorageKey)!!; capture()
        assertFalse(reopened.authenticated)
        assertTrue(reopened.restoreAcademicAuthorization()); refresh(reopened); reopened.close()
        assertEquals(0, prompts)
        val final = runtime(); assertTrue(final.authenticated)
        final.logout(); final.close()
        val revoked = runtime(); assertFalse(revoked.authenticated); revoked.close()
    }

    @Test fun coldStartRestoresRememberedConsentAndCredentialsWithoutAnAuthorizationClick() = runBlocking {
        val user = UserManager.getInstance(); user.addCustomSchool(school)
        capture(); val pkg = install(serviceManifest("test.cold-consent"))
        val first = ServicePluginSession(serviceContext(), pkg, "account")
        var prompts = 0
        first.ensureAcademicAuthorization { prompts++; "remember" }
        val oldAccess = access(pkg); val oldGrant = oldAccess.existingGrant()!!
        first.close(); AcademicGatewayFactory.invalidate(school, user.currentAccountStorageKey)
        UserManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        UserManager.getInstance().init(app)
        AcademicProviderRegistry.initialize(app)
        // Token-based logins may have persisted consent before any new credential session exists.
        val account = UserManager.getInstance().currentAccountStorageKey
        AcademicGatewayFactory.invalidate(school, account)
        assertNull(AcademicGatewayFactory.sharedSession(school, account))
        val reopened = ServicePluginSession(serviceContext(), pkg, "account")
        assertTrue(reopened.hasAcademicConsent()); assertFalse(reopened.authenticated)
        var renewals = 0
        assertEquals(ServiceAcademicRestore.Ready, restoreServiceAcademicLogin(reopened) { expected ->
            renewals++
            AcademicGatewayFactory.importCookie(school, account, "fixture=restored")
            session = AcademicGatewayFactory.sharedSession(school, account)!!
            capture(); SessionRecoveryResult.Recovered(expected)
        })
        assertEquals(1, prompts); assertEquals(1, renewals)
        assertTrue(reopened.authenticated)
        assertNotEquals(oldGrant, access(pkg).existingGrant())
        assertThrows(PluginException::class.java) { oldAccess.requireGrant(oldGrant) }
        reopened.close()
    }

    @Test fun readyConsentRestorationPreservesTheCurrentServiceSession() = runBlocking {
        capture(); val pkg = install(serviceManifest("test.ready-consent"))
        val runtime = ServicePluginSession(serviceContext(), pkg, "account")
        runtime.ensureAcademicAuthorization { "remember" }
        val before = runtime.session
        assertEquals(ServiceAcademicRestore.Ready, restoreServiceAcademicLogin(runtime) { error("No login needed") })
        assertSame(before, runtime.session); runtime.close()
    }

    @Test fun expiredLoginKeepsConsentAndDoesNotAskForConsentAgain() = runBlocking {
        capture(); val pkg = install(serviceManifest("test.expired-consent"))
        val runtime = ServicePluginSession(serviceContext(), pkg, "account")
        runtime.ensureAcademicAuthorization { "remember" }
        val user = UserManager.getInstance(); user.sessionState.expire(user.sessionState.token)
        assertTrue(runtime.hasAcademicConsent()); assertFalse(runtime.authenticated)
        var attempts = 0
        val result = restoreServiceAcademicLogin(runtime) {
            attempts++; SessionRecoveryResult.NeedsLogin(RecoveryFailure.NoPassword)
        }
        assertTrue(result is ServiceAcademicRestore.NeedsLogin); assertEquals(1, attempts)
        assertTrue(runtime.hasAcademicConsent()); assertFalse(runtime.authenticated)
        runtime.close()
    }

    @Test fun noConsentNeverStartsALoginOrAutomaticallyApprovesIt() = runBlocking {
        val pkg = install(serviceManifest("test.no-consent"))
        val runtime = ServicePluginSession(serviceContext(), pkg, "account")
        assertEquals(ServiceAcademicRestore.NeedsConsent, restoreServiceAcademicLogin(runtime) { error("No consent") })
        assertFalse(runtime.authenticated); runtime.close()
    }

    @Test fun revocationWhileRestoringCannotCreateANewGrant() = runBlocking {
        capture(); val pkg = install(serviceManifest("test.revoke-restore"))
        val runtime = ServicePluginSession(serviceContext(), pkg, "account")
        runtime.ensureAcademicAuthorization { "remember" }; session.pluginToken = null
        assertEquals(ServiceAcademicRestore.NeedsConsent, restoreServiceAcademicLogin(runtime) { expected ->
            PluginAcademicSession.revoke(app, pkg.manifest.id)
            SessionRecoveryResult.Recovered(expected)
        })
        assertFalse(runtime.authenticated); assertNull(access(pkg).existingGrant()); runtime.close()
    }

    @Test fun accountChangeWhileRestoringRejectsTheOldPage() = runBlocking {
        capture(); val pkg = install(serviceManifest("test.account-restore"))
        val user = UserManager.getInstance(); val original = user.currentAccountStorageKey
        val runtime = ServicePluginSession(serviceContext(), pkg, "account", scopeStillActive = { user.currentAccountStorageKey == original })
        runtime.ensureAcademicAuthorization { "remember" }; session.pluginToken = null
        assertThrows(AcademicException::class.java) { runBlocking {
            restoreServiceAcademicLogin(runtime) {
                user.studentId = "different-account"; user.saveCookieLogin("fixture=other")
                SessionRecoveryResult.Recovered(user.sessionState.token)
            }
        } }
        assertFalse(runtime.authenticated); runtime.close()
    }

    @Test fun anExistingLoginConsentNeedsOneUpgradePromptThenNoRequestPrompts() = runBlocking {
        reviewedProvider(); val pkg = install(serviceManifest("test.refresh-upgrade")); var prompts = 0
        access(pkg).authorize(remember = true)
        val runtime = ServicePluginSession(refreshContext(), pkg, "account", readStateConfirmation = { error("No endpoint question") })
        assertFalse(runtime.authenticated)
        runtime.ensureAcademicAuthorization { prompts++; "remember" }
        repeat(3) { refresh(runtime) }
        assertEquals(1, prompts); runtime.close()
    }

    @Test fun onceOnlyGroupedConsentNeverBecomesPermanentAfterRelogin() = runBlocking {
        reviewedProvider(); val pkg = install(serviceManifest("test.refresh-once"))
        val runtime = ServicePluginSession(refreshContext(), pkg, "account")
        runtime.academicAuthorizationDescription(); runtime.authorizeAcademicSession(includeReadState = true)
        repeat(2) { refresh(runtime) }; runtime.close()
        session.invalidate(); UserManager.getInstance().saveCookieLogin("fixture=renewed")
        session = AcademicGatewayFactory.sharedSession(school, UserManager.getInstance().currentAccountStorageKey)!!; capture()
        val reopened = ServicePluginSession(refreshContext(), pkg, "account")
        assertFalse(reopened.authenticated); assertFalse(reopened.restoreAcademicAuthorization()); reopened.close()
    }

    @Test fun deniedSiteConsentAndRevocationWhilePromptingNeverSendARequest() = runBlocking {
        reviewedProvider(); val pkg = install(serviceManifest("test.refresh-denied")); var prompts = 0
        val runtime = ServicePluginSession(refreshContext(), pkg, "account")
        val before = server.requestCount
        assertThrows(PluginException::class.java) { runBlocking { runtime.ensureAcademicAuthorization { prompts++; null } } }
        assertEquals(1, prompts); assertEquals(before, server.requestCount); runtime.close()
        val revoked = ServicePluginSession(refreshContext(), pkg, "account")
        assertThrows(PluginException::class.java) { runBlocking { revoked.ensureAcademicAuthorization {
            PluginAcademicSession.revoke(app, pkg.manifest.id); "remember"
        } } }
        assertEquals(before, server.requestCount); revoked.close()
        assertNull(access(pkg).existingGrant())
    }

    @Test fun siteConsentAcceptsLegacyBodiesButDeniesForeignSitesTokenEndpointsAndWriteReplay() = runBlocking {
        reviewedProvider(); val pkg = install(serviceManifest("test.refresh-boundary")); var next = seen(); var prompts = 0
        val runtime = ServicePluginSession(refreshContext { listOf(next) }, pkg, "account", requestConfirmation = { _, _, _ -> prompts++; false })
        runtime.academicAuthorizationDescription(); runtime.authorizeAcademicSession(remember = true)
        val before = server.requestCount
        for (allowed in listOf(seen("new-target"), seen().apply { remove("form"); put("body", "{\"id\":\"one\"}") }, request("/jw/api/unknown"))) {
            next = allowed; server.enqueue(MockResponse().setBody("{\"text\":\"ok\"}"))
            assertEquals("ok", runtime.page("main").getString("title"))
        }
        for (bad in listOf(request().put("url", "https://foreign.example/jw/api/seen"), request("/jw/api/login"), request("/jw/api/%6cogin"), request("/jw/api/%256cogin"))) {
            next = bad
            assertThrows(AcademicException::class.java) { runBlocking { runtime.page("main") } }
        }
        assertEquals(before + 3, server.requestCount); assertEquals(0, prompts)
        next = seen(); server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/jw/api/submit"))
        assertEquals(AcademicStatus.RESULT_UNKNOWN, assertThrows(AcademicException::class.java) { runBlocking { runtime.page("main") } }.status)
        assertEquals(before + 4, server.requestCount); runtime.close()
    }

    @Test fun simultaneousNativeLoginRequestsShareOnePromptAndReadStateEffectsReuseIt() = runBlocking {
        reviewedProvider(); val pkg = caller("test.native-refresh"); var prompts = 0
        val ui = object : NativePluginInteraction by interaction {
            override suspend fun consent(title: String, message: String) = choose("$title\n$message", NativePluginInteraction.CONSENT_CHOICES)
            override suspend fun choose(title: String, choices: List<Pair<String, String>>): String? {
                prompts++; assertTrue(title.contains("学校站点")); delay(20); return "remember"
            }
            override suspend fun confirm(title: String, message: String): Boolean { prompts++; return false }
        }
        val local = AcademicSession(AcademicSessionKey("native-consent", "synthetic"), url("/").toString())
        val native = NativeCapabilityHost(app, pkg, local, ui) { true }
        try {
            val grants = coroutineScope { List(2) { async { native.execute(effect("academic.session.authorize", JSONObject()), NativeFlow(true)) as JSONObject } }.awaitAll() }
            assertEquals(grants[0].getString("grant"), grants[1].getString("grant")); assertEquals(1, prompts)
            repeat(20) {
                server.enqueue(MockResponse().setBody("{}"))
                native.execute(effect("academic.session.request", JSONObject().put("grant", grants[0].getString("grant")).put("request", seen()), 2), NativeFlow(false))
                server.takeRequest(2, TimeUnit.SECONDS)
            }
            assertEquals(1, prompts)
        } finally { native.close(); local.retire() }
    }

    @Test fun servicePagesAndActionsShareAfterConsentAndReopenWithoutAnotherLogin() = runBlocking {
        capture()
        session.cookies.saveFromResponse(url("/jw/api/me"), listOf(okhttp3.Cookie.Builder().name("session").value("school-cookie").hostOnlyDomain("127.0.0.1").path("/jw").build()))
        for (id in listOf("test.service-a", "test.service-b")) {
            val pkg = install(serviceManifest(id))
            val runtime = ServicePluginSession(serviceContext(), pkg, "fixture-account", requestConfirmation = { _, _, _ -> true })
            assertFalse(runtime.authenticated)
            assertThrows(AcademicException::class.java) { runBlocking { runtime.page("main") } }
            assertFalse(runtime.academicAuthorizationDescription().contains(secret))
            assertFalse(runtime.authenticated)
            runtime.authorizeAcademicSession()
            assertTrue(runtime.authenticated); assertNotSame(session, runtime.session)
            assertEquals(session.baseUrl, runtime.session.baseUrl)
            server.enqueue(MockResponse().setBody("{}"))
            assertFalse(runtime.page("main").toString().contains(secret))
            server.takeRequest(2, TimeUnit.SECONDS)!!.also { assertEquals(secret, it.getHeader("X-Token")); assertEquals("session=school-cookie", it.getHeader("Cookie")) }
            assertThrows(PluginException::class.java) { runBlocking { runtime.action("save", JSONObject(), false) } }
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(runtime.action("save", JSONObject(), true).getBoolean("confirmed"))
            assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            val privateSession = runtime.session
            runtime.close()
            assertTrue(privateSession.retired); assertFalse(session.retired); assertNotNull(session.pluginToken)
            val reopened = ServicePluginSession(serviceContext(), pkg, "fixture-account", requestConfirmation = { _, _, _ -> true })
            assertTrue(reopened.authenticated); assertNotSame(privateSession, reopened.session)
            server.enqueue(MockResponse().setBody("{}")); reopened.page("main")
            assertEquals(secret, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token"))
            reopened.logout(); assertFalse(reopened.authenticated); assertNotNull(session.pluginToken)
            reopened.close()
            val revoked = ServicePluginSession(serviceContext(), pkg, "fixture-account", requestConfirmation = { _, _, _ -> true })
            assertFalse(revoked.authenticated); revoked.close()
        }
    }

    @Test fun serviceRevocationDuringSandboxExecutionBlocksTheRequest() = runBlocking {
        capture(); val pkg = install(serviceManifest("test.service-revoke"))
        val runtime = ServicePluginSession(serviceContext(beforeRequest = { PluginAcademicSession.revoke(app, pkg.manifest.id) }), pkg, "account", requestConfirmation = { _, _, _ -> true })
        runtime.academicAuthorizationDescription(); runtime.authorizeAcademicSession()
        assertThrows(AcademicException::class.java) { runBlocking { runtime.page("main") } }
        assertEquals(1, server.requestCount); assertFalse(runtime.authenticated); assertNotNull(session.pluginToken)
        runtime.close()
    }

    @Test fun serviceExpiryClearsTheSchoolTokenInsteadOfOnlyItsPrivateSession() = runBlocking {
        capture(); val pkg = install(serviceManifest("test.service-expiry"))
        val runtime = ServicePluginSession(serviceContext(), pkg, "account", requestConfirmation = { _, _, _ -> true })
        runtime.academicAuthorizationDescription(); runtime.authorizeAcademicSession()
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(AcademicException::class.java) { runBlocking { runtime.page("main") } }
        assertNull(session.pluginToken); assertFalse(session.retired); assertFalse(runtime.authenticated)
        val reopened = ServicePluginSession(serviceContext(), pkg, "account", requestConfirmation = { _, _, _ -> true })
        assertFalse(reopened.authenticated); reopened.close()
        runtime.close()
    }

    @Test fun sharedServiceChecksRedirectsAndRejectsExplicitTokenOverrides() = runBlocking {
        capture(); val pkg = install(serviceManifest("test.service-scope"))
        var next = request("/jw/api-evil/me")
        val runtime = ServicePluginSession(serviceContext({ next }), pkg, "account", requestConfirmation = { _, _, _ -> true })
        runtime.academicAuthorizationDescription(); runtime.authorizeAcademicSession()
        assertThrows(AcademicException::class.java) { runBlocking { runtime.page("main") } }
        next = request().put("headers", JSONObject().put("x-token", "override"))
        assertThrows(AcademicException::class.java) { runBlocking { runtime.page("main") } }
        assertEquals(1, server.requestCount)
        next = request(); server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/outside"))
        assertThrows(AcademicException::class.java) { runBlocking { runtime.page("main") } }
        assertEquals(2, server.requestCount); runtime.close()
    }

    @Test fun serviceConsentCannotSurviveSchoolSessionChangeOrProviderReplacement() {
        capture(); val pkg = install(serviceManifest("test.service-stale"))
        val runtime = ServicePluginSession(serviceContext(), pkg, "account", requestConfirmation = { _, _, _ -> true })
        runtime.academicAuthorizationDescription()
        session.invalidate()
        assertThrows(PluginException::class.java) { runtime.authorizeAcademicSession() }
        assertFalse(runtime.authenticated); runtime.close()
        capture()
        val replaced = ServicePluginSession(serviceContext(), pkg, "account", requestConfirmation = { _, _, _ -> true })
        replaced.academicAuthorizationDescription()
        AcademicProviderRegistry.choose(school, "builtin.zf")
        assertThrows(PluginException::class.java) { replaced.authorizeAcademicSession() }
        assertFalse(replaced.authenticated); replaced.close()
    }

    @Test fun serviceReportedExpiryClearsTokenAndAccountSwitchPreventsAnyRequest() = runBlocking {
        capture(); val pkg = install(serviceManifest("test.service-account"))
        val context = RespondingSandboxContext(app) { _, _ -> PluginJson.error(PluginErrorCode.SESSION_EXPIRED, "Synthetic login expired") }
        val expired = ServicePluginSession(context, pkg, "account", requestConfirmation = { _, _, _ -> true })
        expired.academicAuthorizationDescription(); expired.authorizeAcademicSession()
        assertThrows(AcademicException::class.java) { runBlocking { expired.page("main") } }
        assertNull(session.pluginToken); expired.close()
        capture()
        val switched = ServicePluginSession(serviceContext(), pkg, "account", requestConfirmation = { _, _, _ -> true })
        assertTrue(switched.authenticated)
        UserManager.getInstance().studentId = "another-synthetic-account"
        assertThrows(AcademicException::class.java) { runBlocking { switched.page("main") } }
        assertEquals(2, server.requestCount); switched.close()
    }

    @Test fun legacyServicesStayIsolatedAndCookieSchoolsDoNotRequireResponseTokens() = runBlocking {
        val cookieSource = JSONObject(source.manifest.json.toString()).put("id", "test.cookie-source").apply { remove("academicSessionToken") }
        source = install(cookieSource); AcademicProviderRegistry.choose(school, source.manifest.id)
        session.cookies.saveFromResponse(url("/jw/api/me"), listOf(okhttp3.Cookie.Builder().name("session").value("school-cookie").hostOnlyDomain("127.0.0.1").path("/jw").build()))
        for (shared in listOf(false, true)) {
            val pkg = install(serviceManifest("test.cookie-$shared", shared))
            val runtime = ServicePluginSession(serviceContext(), pkg, "account", requestConfirmation = { _, _, _ -> true })
            if (shared) { runtime.academicAuthorizationDescription(); runtime.authorizeAcademicSession() }
            server.enqueue(MockResponse().setBody("{}")); runtime.page("main")
            val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(sent.getHeader("X-Token")); assertEquals(if (shared) "session=school-cookie" else null, sent.getHeader("Cookie"))
            runtime.close(); assertFalse(session.retired)
        }
    }

    @Test fun serviceOptInRejectsOldHostsPasswordModeAndAcademicTokenProduction() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("apiVersion", 2) },
            { it.getJSONObject("service").getJSONObject("authentication").put("mode", "password") },
            { it.getJSONArray("requires").getJSONObject(0).put("version", 2) },
            { it.put("academicSessionToken", rule()) }
        )) assertThrows(PluginException::class.java) { install(serviceManifest("test.invalid-service").also(change)) }
        val pkg = install(serviceManifest("test.old-host-service"))
        assertThrows(PluginException::class.java) { PluginPlatformContract.requireCompatible(pkg.manifest, 91, mapOf("academic.session.request" to 2)) }
    }

    @Test fun sharedServicePreviewPartitionsPrivateStorageByActualAcademicAccount() {
        capture(); val pkg = install(serviceManifest("test.service-preview"))
        val first = ServicePluginSession(serviceContext(), pkg, "preview:constant", requestConfirmation = { _, _, _ -> true })
        first.academicAuthorizationDescription(); first.authorizeAcademicSession()
        val firstKey = first.session.key
        first.close(); session.retire()
        val user = UserManager.getInstance(); user.studentId = "second-synthetic-student"; user.saveCookieLogin("fixture=second")
        session = AcademicGatewayFactory.sharedSession(school, user.currentAccountStorageKey)!!; session.cookies.clear(); capture()
        val second = ServicePluginSession(serviceContext(), pkg, "preview:constant", requestConfirmation = { _, _, _ -> true })
        assertFalse(second.authenticated)
        second.academicAuthorizationDescription(); second.authorizeAcademicSession()
        assertNotEquals(firstKey, second.session.key)
        second.close()
    }
    @Test fun captchaFailureMalformedTokenAndStaleCaptureNeverPublish() {
        capture(result = "captcha"); assertNull(session.pluginToken)
        capture(status = 401); assertNull(session.pluginToken)
        for (token in listOf(JSONObject.NULL, 123, "", "bad token", "bad\r\n", "x".repeat(8193))) {
            val error = assertThrows(PluginException::class.java) { capture(JSONObject().put("data", JSONObject().put("token", token)).toString()) }
            assertEquals(PluginErrorCode.PAGE_CHANGED, error.code); assertNull(session.pluginToken)
        }
        val op = PluginOperation(session, source.manifest, "auth.start"); val capture = PluginAcademicTokenCapture(op, source)
        capture.capture(url("/jw/api/login"), "POST", "auth", 200, """{"data":{"token":"$secret"}}""")
        session.invalidate()
        assertThrows(PluginException::class.java) { capture.publish(JSONObject().put("status", "authenticated")) }; assertNull(session.pluginToken)
    }
    @Test fun wrongResponseOriginPathMethodOrPurposeDoesNotCaptureAndPointerEscapesWork() {
        val op = PluginOperation(session, source.manifest, "auth.start")
        val rule = PluginAcademicTokenRule(rule())
        val body = """{"data":{"token":"$secret"}}"""
        for (url in listOf(url("/jw/api/login-extra"), "https://other.test/jw/api/login".toHttpUrl()))
            assertNull(rule.capture(op, "owner", url, "POST", "auth", 200, body))
        assertNull(rule.capture(op, "owner", url("/jw/api/login"), "GET", "auth", 200, body))
        assertNull(rule.capture(op, "owner", url("/jw/api/login"), "POST", "query", 200, body))
        val spec = rule(); spec.getJSONObject("response").put("jsonPointer", "/a~1b/0/~0token")
        assertEquals(secret, PluginAcademicTokenRule(spec).capture(op, "owner", url("/jw/api/login"), "POST", "auth", 200,
            """{"a/b":[{"~token":"$secret"}]}""")!!.header(url("/jw/api/me")).second)
    }
    @Test fun scopeRedirectHeaderOverridesAndRevokedGrantsCannotSendToken() {
        capture(); val pkg = caller("test.scope"); val access = access(pkg); val grant = access.authorize().getString("grant"); val host = host(pkg, access, grant)
        for (req in listOf(request("/jw/api-evil/me"), request("/jw/other"), request().put("headers", JSONObject().put("x-token", "override")),
            request().put("headers", JSONObject().put("Authorization", "Bearer override")), request().put("cookieHeader", JSONObject().put("cookie", "token").put("header", "X-Token"))))
            assertThrows(PluginException::class.java) { host.call("http", req) }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/jw/outside"))
        assertEquals(PluginErrorCode.UNTRUSTED_URL, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        server.takeRequest(1, TimeUnit.SECONDS); assertEquals(2, server.requestCount)
        PluginAcademicSession.revoke(app, pkg.manifest.id)
        assertThrows(PluginException::class.java) { host.call("http", request()) }; assertEquals(2, server.requestCount)
    }
    @Test fun expiredHttpResponseClearsTokenAndMissingTokenDoesNotFallBackToCookies() {
        capture(); val pkg = caller("test.expired"); val access = access(pkg); val grant = access.authorize().getString("grant"); val host = host(pkg, access, grant)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(PluginErrorCode.SESSION_EXPIRED, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        assertNull(session.pluginToken)
        assertEquals(PluginErrorCode.SESSION_EXPIRED, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        assertEquals(2, server.requestCount)
    }
    @Test fun providerAccountEpochAndOwnerChangesPreventReuse() {
        capture(); val pkg = caller("test.isolation"); val access = access(pkg); val grant = access.authorize().getString("grant")
        val other = AcademicSession(AcademicSessionKey(school.id, "another-account"), school.fullBasePath); assertNull(other.pluginToken)
        session.pluginToken = PluginAcademicToken("other-provider", session.epoch, url("/jw/api"), secret)
        assertEquals(PluginErrorCode.SESSION_EXPIRED, assertThrows(PluginException::class.java) { access.tokenHeader(grant, url("/jw/api/me")) }.code)
        AcademicProviderRegistry.choose(school, "builtin.zf")
        assertEquals(PluginErrorCode.STALE_CONTEXT, assertThrows(PluginException::class.java) { access.tokenHeader(grant, url("/jw/api/me")) }.code)
        session.invalidate(); assertNull(session.pluginToken)
    }
    @Test fun declarationsRejectOldCapabilitiesNonAcademicWritersAndUnsafePaths() {
        for (path in listOf("//other.test", "/../api", "/a/./api", "/api%2f", "/api?token=x", "/api#x", "/a\\b")) {
            val spec = rule(); spec.getJSONObject("request").put("pathPrefix", path)
            assertThrows(PluginException::class.java) { PluginAcademicTokenRule(spec) }
        }
        val old = JSONObject(source.manifest.json.toString()).put("requires", JSONArray())
        assertThrows(PluginException::class.java) { PluginAcademicTokenRule.validate(PluginManifest(old)) }
        assertThrows(PluginException::class.java) { PluginAcademicTokenCapture(PluginOperation(session, source.manifest, "host.effect"), source) }
    }

    @Test fun allSevenAcademicTypesCanShareResponseTokens() {
        for (type in listOf("zf", "zf_old", "qz", "qz_old", "jinzhi", "chengfang", "legacy_zf")) {
            AcademicGatewayFactory.invalidate(school, UserManager.getInstance().currentAccountStorageKey)
            school.academicSystem = type
            source = install(JSONObject(source.manifest.json.toString()).put("id", "test.source-${type.replace('_', '-')}").put("school", schoolManifest()))
            AcademicProviderRegistry.choose(school, source.manifest.id)
            val user = UserManager.getInstance(); user.currentSchool = school; user.saveCookieLogin("fixture=login")
            session = AcademicGatewayFactory.sharedSession(school, user.currentAccountStorageKey)!!; session.cookies.clear()
            capture()
            val pkg = caller("test.reader-${type.replace('_', '-')}"); val access = access(pkg); val grant = access.authorize().getString("grant")
            server.enqueue(MockResponse().setBody(type))
            assertEquals(type, host(pkg, access, grant).call("http", request()).getJSONObject("data").getString("body"))
            assertEquals(secret, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token"))
        }
    }

    @Test fun allSevenBuiltinInheritancePathsRetainTheSchoolTokenDeclaration() {
        for ((baseId, id) in BuiltinAcademicInheritance.providers) {
            val type = baseId.removePrefix("builtin.")
            val school = SchoolConfig("inherited", "Synthetic", "jw.example.test", "https").apply { basePath = "/new"; academicSystem = type }
            val json = JSONObject().put("id", "test.inherited").put("name", "Inherited").put("version", "1.0.0").put("kind", "configuration")
                .put("apiVersion", 3).put("extends", baseId).put("school", schoolManifest(school)).put("capabilities", JSONArray()).put("network", JSONArray())
                .put("requires", JSONArray().put(JSONObject().put("name", "academic.session.request").put("version", 2))).put("academicSessionToken", rule())
            if (type == "jinzhi") json.put("builtinConfig", JSONObject().put("casBaseUrl", "https://cas.example.test/auth")
                .put("periods", JSONArray().put(JSONObject().put("number", 1).put("start", "08:00").put("end", "08:45"))))
            if (type == "chengfang") json.put("builtinConfig", JSONObject().put("loginUrl", "https://auth.example.test/login?service=https%3A%2F%2Fjw.example.test%2Fnew%2FssoLogin"))
            val parent = PluginPackage(PluginManifest(json), "", "parent", false)
            PluginAcademicTokenRule.validate(parent.manifest)
            val inherited = BuiltinAcademicInheritance.inherit(parent, AcademicProviderRegistry.knownPackage(id)!!, school)
            assertEquals(rule().toString(), inherited.manifest.json.getJSONObject("academicSessionToken").toString())
            assertTrue(PluginJson.objects(inherited.manifest.json.getJSONArray("requires")).any { it.getString("name") == "academic.session.request" && it.getInt("version") >= 2 })
        }
    }

    @Test fun versionOneAndOrdinaryNetworkDoNotAcquireTokenAndVersionTwoRequiresDeclaration() = runBlocking {
        capture()
        val pkg = caller("test.compatibility")
        val local = AcademicSession(AcademicSessionKey("plugin:${pkg.manifest.id}", "default"), "https://invalid.example/")
        val native = NativeCapabilityHost(app, pkg, local, interaction) { true }
        try {
            val grant = (native.execute(effect("academic.session.authorize", JSONObject()), NativeFlow(true)) as JSONObject).getString("grant")
            server.enqueue(MockResponse().setBody("old client"))
            native.execute(effect("academic.session.request", JSONObject().put("grant", grant).put("request", request()), 1), NativeFlow(true))
            assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token"))
            // A shared-data reader cannot use ordinary networking to launder data.
            try { native.execute(effect("network.request", request(), 1), NativeFlow(true)); fail("Undeclared disclosure must be blocked") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.PERMISSION_DENIED, e.code) }
            pkg.manifest.json.put("requires", JSONArray())
            try {
                native.execute(effect("academic.session.request", JSONObject().put("grant", grant).put("request", request()), 2), NativeFlow(true))
                fail("Version 2 needs an explicit capability requirement")
            } catch (e: PluginException) { assertEquals(PluginErrorCode.UNSUPPORTED, e.code) }
            assertEquals(2, server.requestCount)
        } finally { native.close(); local.retire() }
    }
}
