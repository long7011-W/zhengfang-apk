package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.AcademicGatewayFactory
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A grant belongs to a caller release and the current school login, never its author. */
internal class PluginAcademicSession(
    private val app: Context, private val caller: PluginPackage, private val active: () -> Boolean,
    private val callerCurrent: () -> Boolean = {
        AcademicProviderRegistry.isCurrentPackage(caller.manifest.id, caller.digest) &&
            UserManager.getInstance().currentSchool?.let { AcademicProviderRegistry.isEnabled(caller.manifest.id, it) } == true
    }
) {
    private val user = UserManager.getInstance()
    private val token = user.sessionState.token
    private val school = user.currentSchool?.let { SchoolConfig.fromJson(it.toJson()) }
    private val account = user.currentAccountStorageKey
    val session = school?.let { AcademicGatewayFactory.sharedSession(it, account) }
        ?: throw PluginException(PluginErrorCode.SESSION_EXPIRED, "请先登录本校教务账号")
    private val epoch = session.epoch
    private val base = session.baseUrl.toHttpUrlOrNull()
        ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "学校地址无效")
    private val provider = school?.let(AcademicProviderRegistry::resolve)
    private val tokenProvider = school?.let(AcademicProviderRegistry::authenticationPackage)
    private val sourceDigest = provider?.manifest?.id?.let { AcademicProviderRegistry.knownPackage(it)?.digest }
    private val prefs = app.getSharedPreferences("native-plugin-permissions", Context.MODE_PRIVATE)
    private val key = prefix(caller.manifest.id) + PluginJson.sha256(listOf(
        caller.digest, caller.official.toString(), account, school?.toJson().toString(), provider?.digest,
        sourceDigest, token.generation.toString(), session.instanceId, epoch.toString()
    ).joinToString("\u0000").toByteArray())

    fun requireCurrent() = requireCurrent(requireLogin = true)

    private fun requireCurrent(requireLogin: Boolean) {
        if (caller.manifest.sharesAcademicSession) ServicePluginContract.validateManifest(caller.manifest)
        else if ("academic.session" !in caller.manifest.permissions || "network" !in caller.manifest.permissions)
            denied("插件需要声明教务登录共享和网络权限")
        if (prefs.getLong(revocationKey, 0L) != capturedRevocation || !active() || !callerCurrent() || school == null ||
            requireLogin && (!user.isLoggedIn || user.sessionState.state.value.expired) ||
            account.isBlank() || account != user.currentAccountStorageKey || !user.sessionState.isCurrent(token) ||
            school.toJson().toString() != user.currentSchool?.toJson()?.toString() || session.retired || session.epoch != epoch ||
            AcademicGatewayFactory.sharedSession(school, account) !== session ||
            !AcademicProviderRegistry.matches(caller, school) ||
            (AcademicProviderRegistry.resolve(school)?.digest != provider?.digest) ||
            (AcademicProviderRegistry.authenticationPackage(school)?.digest != tokenProvider?.digest) ||
            provider != null && !AcademicProviderRegistry.isCurrentPackage(provider.manifest.id, sourceDigest.orEmpty()))
            throw PluginException(PluginErrorCode.STALE_CONTEXT, "教务账号、学校或插件已改变，请重新授权")
    }

    private val consentKey = prefix(caller.manifest.id) + "consent:" + PluginConsentPolicy.identity(
        caller, account, school?.toJson().toString(), tokenProvider ?: provider)
    private val siteOrigins = PluginSiteConsent.origins(caller, tokenProvider ?: provider, base)
    private val siteKey = PluginSiteConsent.key(
        caller, account, school!!.id + "\u0000" + PluginAuthScope.origin(base), tokenProvider ?: provider)
    private val sessionSiteKey = key + ":site"
    fun coordinationKey() = key + ":" + capturedRevocation
    fun siteAuthorized(): Boolean {
        requireCurrent()
        return savedSiteConsent()
    }
    /** Consent can survive expired credentials; this boolean never authorizes an actual request. */
    fun hasSiteConsent(): Boolean {
        requireCurrent(requireLogin = false)
        return savedSiteConsent()
    }
    private fun savedSiteConsent(): Boolean {
        val saved = prefs.getStringSet(siteKey, emptySet()).orEmpty() + prefs.getStringSet(sessionSiteKey, emptySet()).orEmpty()
        return siteOrigins.isNotEmpty() && saved.containsAll(siteOrigins)
    }
    fun authorizeSite(remember: Boolean): JSONObject = synchronized(user.sessionState) { synchronized(grantLock) {
        requireCurrent()
        if (siteOrigins.isEmpty()) denied("插件未声明当前学校站点")
        val result = authorize()
        check(prefs.edit().putStringSet(if (remember) siteKey else sessionSiteKey, siteOrigins).commit())
        result
    } }
    private val revocationKey = prefix(caller.manifest.id) + "revocation"
    private val capturedRevocation = prefs.getLong(revocationKey, 0L)
    fun authorized(): Boolean { requireCurrent(); return existingGrant() != null }
    fun remembered(): Boolean { requireCurrent(); return prefs.getBoolean(consentKey, false) }


    /** Restoring a grant must never recreate one after a concurrent revocation. */
    fun existingGrant(): String? = synchronized(user.sessionState) { synchronized(grantLock) {
        requireCurrent()
        prefs.getString(key, null)?.takeIf(String::isNotBlank) ?: if (remembered() || siteAuthorized()) authorize().getString("grant") else null
    } }

    fun requireCredentials() {
        requireCurrent()
        if (tokenProvider?.manifest?.json?.has("academicSessionToken") == true) synchronized(session) {
            if (session.pluginToken?.let { it.epoch == epoch && it.owner == PluginAcademicToken.owner(tokenProvider) } != true)
                throw PluginException(PluginErrorCode.SESSION_EXPIRED, "教务令牌已失效，请重新登录本校账号")
        }
    }

    fun expireCredentials(grant: String, expected: PluginAcademicToken?) {
        requireGrant(grant)
        synchronized(session) { if (session.pluginToken === expected) session.pluginToken = null }
    }

    fun description(): String {
        requireCurrent()
        if (siteOrigins.isEmpty()) denied("插件未声明当前学校站点")
        val label = user.username.ifBlank { user.studentId.orEmpty() }.let { if (it.length > 4) it.take(2) + "••••" + it.takeLast(2) else it }
        val local = !caller.bundled && !PluginReviewProof.reviewed(caller) || (tokenProvider ?: provider)?.let { !it.bundled && !PluginReviewProof.reviewed(it) } == true
        return "允许 ${caller.manifest.name} 使用 ${school!!.name} 的账号 $label？\n\n" +
            "学校站点：${siteOrigins.joinToString("\n")}\n\n" +
            "允许查询、读取正文及更新已读等站内请求，刷新时不再逐条询问。站点授权无法判断每个接口的业务含义，请仅信任可靠来源。\n" +
            (if (local) "此次信任按包保存，未认定为官方审核；调用插件或登录提供者换包后需重新确认。\n" else "") +
            "仅本次在当前登录会话内有效；记住后关闭页面、重启 App 或同账号重登会自动恢复，无需再点授权；可在插件详情撤销。选退课、评教等业务入口仍需确认，向其他网站提供个人数据另行授权。"
    }

    /** Called only after the host confirmation completes; recheck every captured identity. */
    fun authorize(remember: Boolean = false, includeReadState: Boolean = false): JSONObject = synchronized(user.sessionState) { synchronized(grantLock) {
        requireCurrent()
        val grant = prefs.getString(key, null) ?: UUID.randomUUID().toString().let { value ->
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(prefix(caller.manifest.id)) && it != key && !it.contains(":consent:") && it != revocationKey }.forEach {
                edit.remove(it); running.remove(it)?.forEach(PluginOperation::close)
            }
            check(edit.putString(key, value).commit()); value
        }
        val edit = prefs.edit()
        if (remember) edit.putBoolean(consentKey, true)
        // Only the host UI that displayed description() may include these scopes. Restoring
        // an existing grant must never silently add newly available operation consent.
        if (includeReadState) readStateOperations().forEach { rule ->
            edit.putBoolean(if (remember) operationKey(rule) else sessionOperationKey(rule), true)
        }
        check(edit.commit())
        JSONObject().put("grant", grant).put("schoolName", school!!.name).put("baseUrl", base.toString())
    } }

    fun requireGrant(grant: String) {
        requireCurrent()
        if (grant.isBlank() || prefs.getString(key, null) != grant) denied("教务登录授权已撤销或不属于当前插件和账号")
    }

    fun requireRequest(grant: String, url: HttpUrl, method: String, purpose: String, form: JSONObject?) {
        requireGrant(grant)
        if (siteAuthorized()) {
            requireSiteDestination(url, method, purpose)
            return
        }
        val prefix = base.encodedPath.trimEnd('/')
        if (purpose !in setOf("query", "mutation") || PluginAuthScope.origin(url) != PluginAuthScope.origin(base) ||
            url.encodedPath != prefix && !url.encodedPath.startsWith("$prefix/"))
            throw PluginException(PluginErrorCode.UNTRUSTED_URL, "共享登录仅可用于当前学校教务地址范围")
        PluginNetworkPolicy(caller.manifest.network).requireAllowed(url, method, purpose, form)
        PluginSharedOperation.match(tokenProvider ?: provider, url, method, purpose, form)
        val authority = tokenProvider ?: provider
        if (authority != null) {
            PluginNetworkPolicy(authority.manifest.network).requireAllowed(url, method, purpose, form)
            val authPath = authority.manifest.json.optJSONObject("academicSessionToken")?.optJSONObject("response")?.optString("path")
            if (!authPath.isNullOrBlank() && url.encodedPath == base.encodedPath.trimEnd('/') + authPath)
                throw PluginException(PluginErrorCode.PERMISSION_DENIED, "共享插件不能读取认证令牌提取端点")
        }
    }

    fun requireSiteDestination(url: HttpUrl, method: String, purpose: String) {
        requireCurrent()
        PluginSiteConsent.requireRequest(siteOrigins, url, method, purpose)
        requireNonAuthenticationEndpoint(url)
    }

    private fun requireNonAuthenticationEndpoint(url: HttpUrl) {
        val authority = tokenProvider ?: provider ?: return
        val path = authority.manifest.json.optJSONObject("academicSessionToken")?.optJSONObject("response")?.optString("path")
        if (!path.isNullOrBlank() && PluginAuthScope.origin(url) == PluginAuthScope.origin(base) &&
            "/" + url.pathSegments.joinToString("/") == base.encodedPath.trimEnd('/') + path)
            denied("共享插件不能读取认证令牌提取端点")
    }

    var confirmUnknownRequest: ((String, String, String) -> Boolean)? = null
    /** true = remember this reviewed operation, false = once, null = deny. */
    var confirmReadStateRequest: ((JSONObject) -> Boolean?)? = null
    /** Returns true only for an approved, provider-reviewed read-state request. */
    fun requireReviewedReadOrConfirmation(request: JSONObject): Boolean {
        requireCurrent()
        if (siteAuthorized()) return request.getString("purpose") == "mutation"
        val rule = operation(request)
        if (rule?.optString("risk") == "read" && request.getString("purpose") == "query") return false
        if (rule?.optString("risk") == "read-state" && operationAuthorized(rule)) return true
        if (rule?.optString("risk") == "read-state" && confirmReadStateRequest != null) {
            val remember = confirmReadStateRequest?.invoke(JSONObject(rule.toString())) ?: denied("已取消状态更新")
            requireCurrent()
            if (remember) rememberOperation(rule)
            return true
        }
        if (confirmUnknownRequest?.invoke(request.getString("url"), request.optString("method", "GET"), request.getString("purpose")) != true)
            denied("未经提供者审核的端点需要逐次明确确认")
        requireCurrent()
        return rule?.optString("risk") == "read-state"
    }
    fun operation(request: JSONObject): JSONObject? {
        requireCurrent()
        val url = request.getString("url").toHttpUrlOrNull()
            ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "地址无效")
        // Free-form bodies cannot receive endpoint-scoped reusable authorization.
        if (request.has("body")) return null
        return PluginSharedOperation.match(tokenProvider ?: provider, url, request.optString("method", "GET"),
            request.getString("purpose"), request.optJSONObject("form"))
    }
    fun reusableAction(id: String): JSONObject? {
        return readStateOperations().singleOrNull { it.optString("id") == id }
    }
    private fun readStateOperations(): List<JSONObject> {
        requireCurrent()
        val authority = tokenProvider ?: provider ?: return emptyList()
        if (!authority.bundled && (!authority.official || authority.publisher == null)) return emptyList()
        // This is only the list displayed in the consent dialog. Each real request also
        // checks required parameters in both network policies and the operation schema.
        fun declaresEndpoint(rules: List<JSONObject>, url: HttpUrl, rule: JSONObject) = runCatching {
            PluginNetworkPolicy(rules.map { JSONObject(it.toString()).apply { remove("requiredQuery"); remove("requiredForm") } })
                .requireAllowed(url, rule.getString("method"), rule.getString("purpose"), null)
        }.isSuccess
        val prefix = base.encodedPath.trimEnd('/')
        return authority.manifest.json.optJSONArray("sharedOperations")?.let(PluginJson::objects).orEmpty()
            .filter { rule ->
                val url = (rule.optString("origin") + rule.optString("path")).toHttpUrlOrNull()
                rule.optString("risk") == "read-state" && url != null &&
                    PluginAuthScope.origin(url) == PluginAuthScope.origin(base) &&
                    (url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")) &&
                    declaresEndpoint(caller.manifest.network, url, rule) && declaresEndpoint(authority.manifest.network, url, rule)
            }
    }
    fun rememberedAction(id: String): Boolean = reusableAction(id)?.let(::operationAuthorized) == true
    fun requireRememberedAction(id: String, url: HttpUrl, method: String, purpose: String, form: JSONObject?) {
        val rule = PluginSharedOperation.match(tokenProvider ?: provider, url, method, purpose, form)
            ?: denied("记住的操作不能请求未经审核的端点")
        if (purpose == "query" && rule.optString("risk") == "read") return
        if (rule.optString("id") != id || rule.optString("risk") != "read-state" || !operationAuthorized(rule))
            denied("请求超出记住的操作范围")
    }
    private fun operationKey(rule: JSONObject) = consentKey + ":operation:" + PluginJson.sha256(PluginJson.canonical(rule).toByteArray())
    private fun sessionOperationKey(rule: JSONObject) = key + ":operation:" + PluginJson.sha256(PluginJson.canonical(rule).toByteArray())
    fun operationRemembered(rule: JSONObject): Boolean { requireCurrent(); return prefs.getBoolean(operationKey(rule), false) }
    fun operationAuthorized(rule: JSONObject): Boolean { requireCurrent(); return operationRemembered(rule) || prefs.getBoolean(sessionOperationKey(rule), false) }
    fun rememberOperation(rule: JSONObject) = synchronized(grantLock) {
        requireCurrent()
        if (rule.optString("risk") != "read-state") throw PluginException(PluginErrorCode.PERMISSION_DENIED, "此操作不能免确认")
        check(prefs.edit().putBoolean(operationKey(rule), true).commit())
    }

    fun cookies(grant: String): CookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> { requireGrant(grant); return session.cookies.loadForRequest(url) }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { requireGrant(grant); session.cookies.saveFromResponse(url, cookies) }
    }

    fun tokenHeader(grant: String, url: HttpUrl): Pair<String, String>? {
        requireGrant(grant)
        if (tokenProvider?.manifest?.json?.has("academicSessionToken") != true) return null
        return synchronized(session) {
            if (session.retired || session.epoch != epoch) throw PluginException(PluginErrorCode.SESSION_EXPIRED, "教务会话已失效")
            val token = session.pluginToken?.takeIf { it.epoch == epoch && it.owner == PluginAcademicToken.owner(tokenProvider) }
                ?: throw PluginException(PluginErrorCode.SESSION_EXPIRED, "教务令牌已失效，请重新登录本校账号")
            token.header(url)
        }
    }

    fun track(grant: String, operation: PluginOperation) = synchronized(grantLock) {
        requireGrant(grant); running.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(operation)
        listOfNotNull(provider?.manifest?.id, tokenProvider?.manifest?.id).forEach { id ->
            byProvider.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(operation)
        }; Unit
    }
    fun untrack(operation: PluginOperation) = synchronized(grantLock) { running[key]?.remove(operation); byProvider.values.forEach { it.remove(operation) }; Unit }

    companion object {
        private val grantLock = Any()
        private val byProvider = ConcurrentHashMap<String, MutableSet<PluginOperation>>()
        fun cancelProvider(id: String) = synchronized(grantLock) { byProvider.remove(id)?.forEach(PluginOperation::close); Unit }
        private val running = ConcurrentHashMap<String, MutableSet<PluginOperation>>()
        private fun prefix(id: String) = "$id:academic-session:"
        /** Read durable consent before a cold start has reconstructed the credential session. */
        internal fun rememberedSiteConsent(app: Context, caller: PluginPackage): Boolean {
            val user = UserManager.getInstance()
            val school = user.currentSchool ?: return false
            if (!AcademicProviderRegistry.isCurrentPackage(caller.manifest.id, caller.digest) ||
                !AcademicProviderRegistry.isEnabled(caller.manifest.id, school) || !AcademicProviderRegistry.matches(caller, school))
                throw PluginException(PluginErrorCode.STALE_CONTEXT, "插件或学校已改变，请重新打开页面")
            val base = school.fullBasePath.toHttpUrlOrNull() ?: return false
            val provider = AcademicProviderRegistry.authenticationPackage(school) ?: AcademicProviderRegistry.resolve(school)
            val origins = PluginSiteConsent.origins(caller, provider, base)
            val key = PluginSiteConsent.key(caller, user.currentAccountStorageKey,
                school.id + "\u0000" + PluginAuthScope.origin(base), provider)
            return origins.isNotEmpty() && app.getSharedPreferences("native-plugin-permissions", Context.MODE_PRIVATE)
                .getStringSet(key, emptySet()).orEmpty().containsAll(origins)
        }
        fun revoke(app: Context, id: String) = synchronized(grantLock) {
            val prefs = app.getSharedPreferences("native-plugin-permissions", Context.MODE_PRIVATE)
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(prefix(id)) }.forEach { key ->
                edit.remove(key); running.remove(key)?.forEach(PluginOperation::close)
            }
            edit.putLong(prefix(id) + "revocation", prefs.getLong(prefix(id) + "revocation", 0L) + 1L)
            check(edit.commit())
        }
        private fun denied(message: String): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, message)
    }
}
