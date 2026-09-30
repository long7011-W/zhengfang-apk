package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSession
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Host-only, in-memory credential. Never serialize this object into plugin state or diagnostics. */
internal class PluginAcademicToken(
    val owner: String, val epoch: Long, private val scope: HttpUrl, private val value: String
) {
    fun header(url: HttpUrl): Pair<String, String> {
        if (PluginAuthScope.origin(url) != PluginAuthScope.origin(scope) ||
            url.encodedPath != scope.encodedPath && !url.encodedPath.startsWith(scope.encodedPath.trimEnd('/') + "/"))
            throw PluginException(PluginErrorCode.UNTRUSTED_URL, "请求超出教务令牌的授权范围")
        return "X-Token" to value
    }
    companion object {
        fun owner(pkg: PluginPackage) = "${pkg.manifest.id}:${pkg.digest}:${pkg.official}:${pkg.bundled}"
    }
}

/** Staged per authentication call; a token never becomes shareable after a failed/captcha login. */
internal class PluginAcademicTokenCapture(private val operation: PluginOperation, private val pkg: PluginPackage) {
    private val rule = PluginAcademicTokenRule(pkg.manifest.json.getJSONObject("academicSessionToken"))
    private var pending: PluginAcademicToken? = null
    init {
        PluginAcademicTokenRule.validate(pkg.manifest)
        if (!operation.method.startsWith("auth.") || operation.manifest.id != pkg.manifest.id)
            throw PluginException(PluginErrorCode.PERMISSION_DENIED, "仅教务认证适配器可以提取登录令牌")
    }
    fun capture(url: HttpUrl, method: String, purpose: String, status: Int, body: String) {
        rule.capture(operation, PluginAcademicToken.owner(pkg), url, method, purpose, status, body)?.let { pending = it }
    }
    fun publish(result: JSONObject) {
        if (result.optString("status") != "authenticated") { pending = null; return }
        pending?.let { token -> synchronized(operation.session) { operation.requireActive(); operation.session.pluginToken = token } }
        pending = null
    }
}

/** Paths are relative to the host-selected academic base, never a caller-selected account or origin. */
internal class PluginAcademicTokenRule(value: JSONObject) {
    private val response = value.optJSONObject("response") ?: invalid()
    private val request = value.optJSONObject("request") ?: invalid()
    private val path = response.optString("path")
    private val method = response.optString("method")
    private val pointer = response.optString("jsonPointer")
    private val prefix = request.optString("pathPrefix")
    init {
        if (value.keys().asSequence().toSet() != setOf("response", "request") ||
            response.keys().asSequence().toSet() != setOf("path", "method", "jsonPointer") ||
            request.keys().asSequence().toSet() != setOf("pathPrefix", "header") ||
            method !in setOf("GET", "POST") || request.optString("header") != "X-Token") invalid()
        listOf(path, prefix).forEach { text ->
            if (text.length !in 1..1024 || !text.startsWith('/') || text.startsWith("//") ||
                text.any { it in "%\\?#" || it.code !in 33..126 } || text.split('/').any { it in setOf(".", "..") }) invalid()
        }
        if (pointer.length !in 2..512 || !pointer.startsWith('/') || pointer.split('/').size > 17 ||
            Regex("~(?![01])").containsMatchIn(pointer)) invalid()
    }
    private fun endpoint(session: AcademicSession, path: String): HttpUrl =
        (session.baseUrl.trimEnd('/') + path).toHttpUrlOrNull() ?: invalid()

    fun capture(operation: PluginOperation, owner: String, url: HttpUrl, verb: String, purpose: String, status: Int, body: String): PluginAcademicToken? {
        val expected = endpoint(operation.session, path)
        if (purpose != "auth" || verb != method || PluginAuthScope.origin(url) != PluginAuthScope.origin(expected) || url.encodedPath != expected.encodedPath || status !in 200..299) return null
        operation.requireActive()
        var current: Any? = try { PluginJson.requireValid(body); JSONObject(body) } catch (_: Exception) { missing() }
        pointer.drop(1).split('/').forEach { encoded ->
            val key = encoded.replace("~1", "/").replace("~0", "~")
            current = when (val node = current) {
                is JSONObject -> node.opt(key)
                is JSONArray -> if (key.matches(Regex("0|[1-9][0-9]*"))) key.toIntOrNull()?.let(node::opt) else null
                else -> null
            }
        }
        val token = current as? String ?: missing()
        if (token.length !in 1..8192 || token.any { it.code !in 33..126 }) missing()
        return PluginAcademicToken(owner, operation.epoch, endpoint(operation.session, prefix), token)
    }
    companion object {
        fun validate(manifest: PluginManifest) {
            if (!manifest.json.has("academicSessionToken")) return
            val inherited = manifest.kind in setOf("configuration", "extension") && manifest.baseProvider in BuiltinAcademicInheritance.providers
            if (manifest.apiVersion != 3 || !manifest.isAcademic || "auth.start" !in manifest.capabilities && !inherited ||
                manifest.json.optJSONArray("requires")?.let(PluginJson::objects).orEmpty().none { it.optString("name") == "academic.session.request" && it.optInt("version") >= 2 })
                throw PluginException(PluginErrorCode.VALIDATION_FAILED, "教务令牌声明需要认证适配器和 academic.session.request 版本 2")
            PluginAcademicTokenRule(manifest.json.optJSONObject("academicSessionToken") ?: invalid())
        }
        fun inherit(parent: PluginManifest, target: JSONObject) {
            val declaration = parent.json.optJSONObject("academicSessionToken") ?: return
            target.put("academicSessionToken", JSONObject(declaration.toString()))
            val requirements = target.optJSONArray("requires")?.let(PluginJson::objects).orEmpty()
            val version = maxOf(2, requirements.filter { it.optString("name") == "academic.session.request" }.maxOfOrNull { it.optInt("version") } ?: 0)
            target.put("requires", JSONArray(requirements.filter { it.optString("name") != "academic.session.request" })
                .put(JSONObject().put("name", "academic.session.request").put("version", version)))
        }
        private fun invalid(): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, "教务令牌提取声明无效")
        private fun missing(): Nothing = throw PluginException(PluginErrorCode.PAGE_CHANGED, "登录响应缺少有效教务令牌，请检查适配声明")
    }
}
