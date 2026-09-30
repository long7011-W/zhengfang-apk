package com.tyust.course.academic.plugin

import android.util.Base64
import java.net.URLEncoder

/** Exact host-held values, not heuristics such as searching for a field named password. */
internal object PluginSecretResponse {
    fun requireSafe(bytes: ByteArray, responseUrl: String, secrets: List<String>) {
        val text = bytes.toString(Charsets.ISO_8859_1)
        for (secret in secrets.filter { it.isNotEmpty() }) {
            val utf8 = secret.toByteArray()
            val variants = listOf(utf8.toString(Charsets.ISO_8859_1), Base64.encodeToString(utf8, Base64.NO_WRAP),
                URLEncoder.encode(secret, "UTF-8"))
            if (variants.any { text.contains(it) || responseUrl.contains(it) })
                throw PluginException(PluginErrorCode.PERMISSION_DENIED, "共享响应包含认证材料，已阻止交给插件")
        }
    }
}
