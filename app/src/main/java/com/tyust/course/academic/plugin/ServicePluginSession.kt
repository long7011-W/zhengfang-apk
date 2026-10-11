package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.*
import org.json.JSONObject

/** Private state always belongs to this service. School credentials are used only through a grant. */
class ServicePluginSession(
    private val app: Context, val pkg: PluginPackage, private val accountScope: String,
    private val requestConfirmation: ((String, String, String) -> Boolean)? = null,
    private val readStateConfirmation: ((JSONObject) -> Boolean?)? = null,
    private val scopeStillActive: () -> Boolean = { true }
) {
    private val school = pkg.manifest.school
    private val baseUrl = "${school.getString("protocol")}://${school.getString("domain")}${school.getString("basePath")}".trimEnd('/') + "/"
    private var adapter = createAdapter("")
    private val sandboxConnection = com.tyust.course.academic.plugin.runtime.PluginSandboxConnections.acquire(app)
    private var closed = false
    private var sharedAccess: PluginAcademicSession? = null
    private var sharedGrant = ""
    val sharesAcademicSession: Boolean get() = pkg.manifest.sharesAcademicSession
    val needsLogin: Boolean get() = pkg.manifest.service!!.getJSONObject("authentication").getString("mode") == "password"
    private var independentAuthenticated = !needsLogin
    val authenticated: Boolean get() = !closed && if (!sharesAcademicSession) independentAuthenticated else runCatching {
        val access = sharedAccess ?: return@runCatching false
        access.requireGrant(sharedGrant); access.requireCredentials(); true
    }.getOrDefault(false)
    val session: AcademicSession get() = adapter.session

    init {
        require(pkg.manifest.isService)
        ServicePluginContract.validateManifest(pkg.manifest)
        if (sharesAcademicSession) runCatching { restoreAcademicAuthorization() }
    }

    private fun createAdapter(username: String, requestBase: String = baseUrl, academicKey: AcademicSessionKey? = null): PluginAcademicAdapter {
        // Even developer previews must partition private service data by the real school account.
        val owner = academicKey?.let { "shared:${it.schoolId}\u0000${it.accountKey}" } ?: accountScope
        val scope = PluginJson.sha256((owner + "\u0000" + pkg.manifest.id + "\u0000" + username).toByteArray())
        val key = AcademicSessionKey(school.getString("id"), "service:$scope")
        return PluginAcademicAdapter(app.applicationContext, pkg, AcademicSession(key, requestBase), scopeStillActive = { !closed && scopeStillActive() })
    }

    fun academicAuthorizationDescription(): String {
        ensureScope(); check(sharesAcademicSession)
        val access = academicAccess()
        val description = access.description()
        access.requireCredentials()
        sharedAccess = access; sharedGrant = ""
        return description
    }
    /** A renewed school login needs a fresh handle, not another consent dialog. */
    fun restoreAcademicAuthorization(): Boolean {
        ensureScope(); check(sharesAcademicSession)
        if (authenticated) return true
        val access = academicAccess()
        if (!access.siteAuthorized()) return false
        val grant = access.existingGrant() ?: return false
        adopt(access, grant)
        return true
    }
    internal fun hasAcademicConsent(): Boolean {
        ensureScope(); check(sharesAcademicSession)
        return try { academicAccess().hasSiteConsent() }
        catch (error: PluginException) {
            if (error.code != PluginErrorCode.SESSION_EXPIRED) throw error
            PluginAcademicSession.rememberedSiteConsent(app, pkg)
        }
    }
    suspend fun ensureAcademicAuthorization(prompt: suspend (String) -> String?) {
        ensureScope()
        val access = academicAccess()
        val grant = PluginConsentCoordinator.request(access.coordinationKey()) {
            access.requireCredentials()
            if (access.siteAuthorized()) access.authorize()
            else {
                val choice = PluginExecutionBudget.userInput { prompt(access.description()) }
                if (choice !in setOf("once", "remember")) throw PluginException(PluginErrorCode.CANCELLED, "已拒绝授权")
                access.authorizeSite(choice == "remember")
            }
        }
        adopt(access, grant.getString("grant"))
    }
    private fun academicAccess() = PluginAcademicSession(app, pkg, { !closed && scopeStillActive() }).also {
        it.confirmUnknownRequest = requestConfirmation
        it.confirmReadStateRequest = readStateConfirmation
    }
    /** Called by the host only after the user confirms the displayed authorization. */
    fun authorizeAcademicSession(remember: Boolean = false, includeReadState: Boolean = false) {
        ensureScope(); check(sharesAcademicSession)
        val access = checkNotNull(sharedAccess) { "请先确认共享教务登录的授权范围" }
        access.requireCredentials()
        adopt(access, access.authorizeSite(remember).getString("grant"))
    }
    private fun adopt(access: PluginAcademicSession, grant: String) {
        access.requireGrant(grant); access.requireCredentials()
        adapter.clearLoginState(); adapter.session.retire()
        adapter = createAdapter("", access.session.baseUrl, access.session.key)
        sharedAccess = access; sharedGrant = grant
    }

    suspend fun login(username: String, password: String): LoginResult {
        ensureScope()
        require(needsLogin && username.isNotBlank() && password.isNotEmpty()) { "请填写服务账号与密码" }
        logout()
        adapter = createAdapter(username.trim())
        return adapter.login(Credentials(username.trim(), password)).also(::acceptLogin)
    }
    suspend fun submitCaptcha(code: String): LoginResult {
        ensureScope(); return adapter.submitCaptcha(code).also(::acceptLogin)
    }
    suspend fun refreshCaptcha(): CaptchaChallenge? { ensureScope(); return adapter.refreshCaptcha().also { ensureScope() } }
    private fun acceptLogin(result: LoginResult) {
        ensureScope()
        independentAuthenticated = result.status == AcademicStatus.SUCCESS
        if (result.status == AcademicStatus.HUMAN_VERIFICATION_REQUIRED) {
            logout()
            throw AcademicException(AcademicStatus.UNSUPPORTED, "此服务要求网页认证，请使用官方网页；当前校园插件支持账号密码和验证码登录")
        }
    }
    suspend fun page(id: String, params: JSONObject = JSONObject()): JSONObject = invoke("service.page", JSONObject().put("pageId", id).put("params", params))
    fun reusableAction(id: String): Boolean = sharedAccess?.reusableAction(id) != null
    fun rememberedAction(id: String): Boolean = sharedAccess?.rememberedAction(id) == true
    fun rememberAction(id: String) {
        ensureScope(); val access = checkNotNull(sharedAccess)
        access.requireGrant(sharedGrant)
        access.rememberOperation(access.reusableAction(id) ?: throw PluginException(PluginErrorCode.PERMISSION_DENIED, "操作未获提供者审核"))
    }
    suspend fun action(id: String, params: JSONObject, confirmed: Boolean): JSONObject = invoke("service.action", JSONObject().put("actionId", id).put("params", params), confirmed)
    fun requireActive() { ensureScope(); session.requireActive(); if (!authenticated) throw AcademicException(AcademicStatus.SESSION_EXPIRED, if (sharesAcademicSession) "请先授权使用本校教务登录" else "请先登录此服务") }
    suspend fun nativeResult(result: JSONObject): JSONObject {
        requireActive()
        val callback = ServiceNativePolicy.operation(pkg.manifest, result.getString("operationId")).getString("resultActionId")
        ServiceNativePolicy.validateResult(pkg.manifest, callback, result)
        return invoke("service.action", JSONObject().put("actionId", callback).put("nativeResult", result).put("params", JSONObject()))
    }
    private suspend fun invoke(method: String, args: JSONObject, confirmed: Boolean = false): JSONObject {
        ensureScope()
        requireActive()
        try {
            return (if (sharesAcademicSession) adapter.invokeSharedService(method, args, confirmed, checkNotNull(sharedAccess), sharedGrant)
                else adapter.invoke(method, args, confirmed)).also { ensureScope() }
        } catch (error: AcademicException) {
            if (error.status == AcademicStatus.SESSION_EXPIRED) {
                if (sharesAcademicSession) { sharedGrant = ""; resetPrivateSession() } else logout()
            }
            throw error
        }
    }
    fun logout() {
        if (sharesAcademicSession) { PluginAcademicSession.revoke(app, pkg.manifest.id); sharedGrant = ""; sharedAccess = null }
        resetPrivateSession()
    }
    private fun resetPrivateSession() {
        adapter.clearLoginState(); adapter.session.retire()
        independentAuthenticated = !closed && !needsLogin
        if (!closed) adapter = createAdapter("")
    }
    fun close() { sandboxConnection.close(); closed = true; independentAuthenticated = false; sharedGrant = ""; adapter.clearLoginState(); adapter.session.retire() }
    private fun ensureScope() {
        if (closed || !scopeStillActive()) { close(); throw AcademicException(AcademicStatus.SESSION_EXPIRED, "学校、账号或插件状态已改变，请重新打开校园服务") }
    }
}
