package com.tyust.course.academic.plugin

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** No private material or decrypted SDK key is included in toString or persisted. */
internal class PluginAmapConfiguration(val apiKey: String?, val expiresAt: Long)

internal object PluginMapConfigCrypto {
    private const val PATH = "/api/maps/v1/client-config"
    private const val ALGORITHM = "P256-HKDF-SHA256-A256GCM"
    private val info = "ZhengfangMapConfig/v1".toByteArray(Charsets.UTF_8)
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    private fun decode(value: String): ByteArray {
        require(value.isNotEmpty() && value.length <= 8192 && value.matches(Regex("[A-Za-z0-9_-]+")))
        return Base64.decode(value, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }
    private fun hmac(key: ByteArray, value: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    fun create(credential: String, packageName: String, versionCode: Int, certificate: String,
               timestamp: Long = System.currentTimeMillis() / 1000,
               nonce: String = encode(ByteArray(24).also { SecureRandom().nextBytes(it) }),
               pair: KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }): Exchange {
        require(credential.length in 32..256)
        val publicKey = encode(pair.public.encoded)
        val canonical = listOf(1, "POST", PATH, packageName, versionCode, certificate, timestamp, nonce, publicKey).joinToString("\n")
        val signature = encode(hmac(credential.toByteArray(Charsets.UTF_8), canonical.toByteArray(Charsets.UTF_8)))
        val body = JSONObject().put("v", 1).put("packageName", packageName).put("appVersionCode", versionCode)
            .put("certSha256", certificate).put("timestamp", timestamp).put("nonce", nonce)
            .put("clientPublicKey", publicKey).put("signature", signature)
        return Exchange(body, canonical, pair, credential, packageName, nonce)
    }

    class Exchange internal constructor(val body: JSONObject, private val canonical: String, private val pair: KeyPair,
                                        private val credential: String, private val packageName: String, private val nonce: String) {
        fun open(response: JSONObject, now: Long = System.currentTimeMillis() / 1000): PluginAmapConfiguration {
            try {
                require(response.getInt("v") == 1 && response.getString("algorithm") == ALGORITHM && response.getString("nonce") == nonce)
                val expiresAt = response.getLong("expiresAt")
                require(expiresAt in now..(now + 390))
                val serverPublicKey = response.getString("serverPublicKey")
                require(serverPublicKey.length <= 180)
                val iv = response.getString("iv")
                val ciphertext = response.getString("ciphertext")
                val proof = decode(response.getString("proof"))
                val expected = hmac(credential.toByteArray(Charsets.UTF_8), listOf("v1-response", canonical, expiresAt, serverPublicKey, iv, ciphertext).joinToString("\n").toByteArray(Charsets.UTF_8))
                require(proof.size == 32 && MessageDigest.isEqual(expected, proof))
                val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(serverPublicKey)))
                val shared = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(publicKey, true); generateSecret() }
                val extracted = hmac(digest(canonical), shared)
                val key = hmac(extracted, info + byteArrayOf(1))
                shared.fill(0); extracted.fill(0)
                val ivBytes = decode(iv)
                require(ivBytes.size == 12)
                val plain = try {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ivBytes))
                        updateAAD(listOf(canonical, expiresAt, serverPublicKey).joinToString("\n").toByteArray(Charsets.UTF_8))
                        doFinal(decode(ciphertext))
                    }
                } finally { key.fill(0) }
                val payload = try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
                require(payload.getInt("v") == 1 && payload.getString("packageName") == packageName)
                val validUntil = payload.getLong("expiresAt")
                require(validUntil in now..(now + 3690))
                val apiKey = if (payload.isNull("apiKey")) null else payload.getString("apiKey").also { require(it.matches(Regex("[a-fA-F0-9]{32}"))) }
                return PluginAmapConfiguration(apiKey, validUntil)
            } catch (_: Exception) {
                // Provider/authentication data must never be echoed in diagnostics.
                throw IOException("地图配置校验失败，请重试")
            }
        }
    }
}
