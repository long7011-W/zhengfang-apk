package com.tyust.course.academic.plugin

import android.content.Context
import android.util.Base64
import com.tyust.course.BuildConfig
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A dedicated release credential; never expose it or request headers to plugin JS. */
internal object PluginMapRequestAuth {
    const val HEADER = "X-Zhengfang-Map-Auth"
    @Volatile private var application: Context? = null
    fun initialize(context: Context) { application = context.applicationContext }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    internal fun signedHeader(credential: String, packageName: String, versionCode: Int, certificate: String, query: String,
                              timestamp: Long = System.currentTimeMillis() / 1000,
                              nonce: String = encode(ByteArray(24).also { SecureRandom().nextBytes(it) }), personalAccess: String? = null): String {
        require(credential.length in 32..256)
        require(personalAccess == null || PluginPersonalMapStore.valid(personalAccess))
        val personalHash = personalAccess?.let { PluginJson.sha256(it.toByteArray(Charsets.UTF_8)) }
        val canonical = (listOf("v1-search", "GET", "/api/maps/v1/search", packageName, versionCode, certificate, timestamp, nonce,
            Normalizer.normalize(query.trim(), Normalizer.Form.NFC)) + listOfNotNull(personalHash)).joinToString("\n")
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(credential.toByteArray(Charsets.UTF_8), "HmacSHA256")); doFinal(canonical.toByteArray(Charsets.UTF_8))
        }
        val value = JSONObject().put("v", 1).put("packageName", packageName).put("appVersionCode", versionCode)
            .put("certSha256", certificate).put("timestamp", timestamp).put("nonce", nonce).put("signature", encode(signature))
        if (personalHash != null) value.put("personalHash", personalHash)
        return encode(value.toString().toByteArray(Charsets.UTF_8))
    }

    internal fun accepts(request: Request): Boolean {
        val url = request.url
        return request.method == "GET" && url.scheme == "https" && url.host == "plugins.hidisiwa.xyz" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/api/maps/v1/search" &&
            url.querySize == 1 && url.queryParameterName(0) == "q"
    }

    fun currentPersonalAccess(context: Context? = application): String? = try { context?.let { PluginPersonalMapStore(it).read() } }
        catch (_: Exception) { throw PluginLocationSearch.Failure("个人地图连接无法恢复，请重新连接或切换公共服务") }

    fun authorize(builder: Request.Builder, context: Context? = application, personalAccess: (() -> String?)? = null): Request.Builder {
        // Rebuild on every redirect. Never carry this proof to any other origin,
        // path, method or port, even if a plugin supplies its own header.
        builder.removeHeader(HEADER)
        builder.removeHeader(PluginPersonalMapStore.HEADER)
        val request = builder.build()
        val url = request.url
        if (!accepts(request) || context == null) return builder
        val connection = if (personalAccess == null) currentPersonalAccess(context) else personalAccess()
        if (BuildConfig.MAP_CONFIG_CLIENT_SECRET.isEmpty()) {
            if (connection != null) throw PluginLocationSearch.Failure("此版本尚未配置个人地图搜索授权，请使用正式版本")
            return builder
        }
        val query = url.queryParameter("q") ?: return builder
        if (connection != null) builder.header(PluginPersonalMapStore.HEADER, connection)
        return builder.header(HEADER, signedHeader(BuildConfig.MAP_CONFIG_CLIENT_SECRET, context.packageName,
            BuildConfig.VERSION_CODE, PluginAmap.signingCertificate(context), query, personalAccess = connection))
    }
}
