package com.tyust.course.academic.plugin

import android.content.*
import android.os.ParcelFileDescriptor
import com.tyust.course.academic.plugin.runtime.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Exercises the real client/adapter/host chain with a deterministic Binder service. */
internal class RespondingSandboxContext(base: Context, private val reply: (JSONObject, IPluginHost) -> JSONObject) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        conn.onServiceConnected(ComponentName(this, PluginSandboxService::class.java), object : IPluginSandbox.Stub() {
            override fun execute(input: ParcelFileDescriptor, host: IPluginHost, callback: IPluginResult) {
                val request = read(input)
                callback.complete(request.getJSONObject("context").getString("operationId"), descriptor(this@RespondingSandboxContext, reply(request, host)))
            }
            override fun cancel(operationId: String) {}
        })
        return true
    }
    override fun unbindService(conn: ServiceConnection) {}
    companion object {
        fun descriptor(context: Context, value: JSONObject): ParcelFileDescriptor {
            val file = File.createTempFile("sandbox-fixture-", ".json", context.cacheDir)
            file.writeText(value.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { file.delete() }
        }
        fun read(input: ParcelFileDescriptor): JSONObject = ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
            // Robolectric's file-backed pipes can reach EOF before the coroutine writes.
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val deadline = System.nanoTime() + 2_000_000_000L
            var result: JSONObject? = null
            while (result == null && System.nanoTime() < deadline) {
                val count = stream.read(buffer)
                if (count > 0) { bytes.write(buffer, 0, count); result = runCatching { JSONObject(bytes.toString("UTF-8")) }.getOrNull() }
                else Thread.sleep(1)
            }
            checkNotNull(result) { "Sandbox fixture did not receive complete JSON" }
        }
    }
}
