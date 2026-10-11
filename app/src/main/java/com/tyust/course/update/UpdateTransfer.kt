package com.tyust.course.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}

class UpdateTransfer(
    private val client: OkHttpClient = client(),
    private val allowUrl: (String) -> Boolean = UpdateManifestVerifier::validUrl
) {
    @Volatile private var active: Call? = null
    fun cancel() { active?.cancel() }
    companion object {
        fun client() = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
                val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
            } }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun validateRange(value: String?, offset: Long, size: Long): Long {
            val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(value.orEmpty())
                ?: updateError("DOWNLOAD_RANGE", "下载线路返回了错误的续传范围")
            val (start, end, total) = match.destructured
            val first = start.toLong(); val last = end.toLong()
            if (first != offset || total.toLong() != size || last < first || last >= size)
                updateError("DOWNLOAD_RANGE", "下载线路返回了错误的续传范围")
            return last - first + 1
        }
    }

    suspend fun download(manifest: UpdateManifest, mirror: UpdateMirror, file: File, etag: String?,
                         budgetMillis: Long, progress: (Long, String?) -> Unit) {
        if (file.length() > manifest.size) file.delete()
        if (file.exists() && file.length() == manifest.size) return
        var offset = file.length(); var url = mirror.url
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        for (redirect in 0..5) {
            currentCoroutineContext().ensureActive()
            if (!allowUrl(url)) updateError("DOWNLOAD_URL", "下载跳转地址不受支持")
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            if (offset > 0) {
                request.header("Range", "bytes=$offset-")
                if (!etag.isNullOrBlank() && !etag.startsWith("W/")) request.header("If-Range", etag)
            }
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) updateError("DOWNLOAD_BUDGET", "下载已暂停，请继续下载")
            val call = client.newCall(request.build()).also { it.timeout().timeout(remaining, TimeUnit.MILLISECONDS); active = it }
            call.awaitResponse().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    url = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                        ?: updateError("DOWNLOAD_REDIRECT", "下载线路返回了无效跳转")
                    return@use
                }
                if (response.code == 416) { file.delete(); updateError("DOWNLOAD_RANGE", "断点已失效，请更换线路重新下载") }
                if (response.code !in setOf(200, 206)) updateError("DOWNLOAD_HTTP", "下载线路暂时不可用（HTTP ${response.code}）")
                val body = response.body ?: updateError("DOWNLOAD_EMPTY", "下载线路没有返回文件")
                if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true))
                    updateError("DOWNLOAD_HTML", "下载线路返回了网页，请更换线路")
                if (!response.header("Content-Encoding").isNullOrBlank() && response.header("Content-Encoding") != "identity")
                    updateError("DOWNLOAD_ENCODING", "下载线路返回了不支持的文件编码")
                val expected = if (response.code == 206) validateRange(response.header("Content-Range"), offset, manifest.size)
                    else { offset = 0; manifest.size }
                val newEtag = response.header("ETag")
                if (response.code == 206 && !etag.isNullOrBlank() && !etag.startsWith("W/") && newEtag != null && newEtag != etag) {
                    file.delete(); updateError("DOWNLOAD_CHANGED", "下载文件已变化，请重新下载")
                }
                if (body.contentLength() >= 0 && body.contentLength() != expected)
                    updateError("DOWNLOAD_SIZE", "下载文件长度与清单不一致")
                var received = 0L
                RandomAccessFile(file, "rw").use { output ->
                    output.setLength(offset); output.seek(offset)
                    progress(offset, newEtag)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            if (received + n > expected) updateError("DOWNLOAD_SIZE", "下载文件超出声明大小")
                            output.write(buffer, 0, n); received += n; progress(offset + received, newEtag)
                        }
                    }
                    output.fd.sync()
                }
                if (received != expected || file.length() != manifest.size) updateError("DOWNLOAD_TRUNCATED", "下载中断，已保留断点")
                return
            }
        }
        updateError("DOWNLOAD_REDIRECT", "下载跳转次数过多")
    }
}
