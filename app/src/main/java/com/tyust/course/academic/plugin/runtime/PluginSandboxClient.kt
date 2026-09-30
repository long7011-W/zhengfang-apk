package com.tyust.course.academic.plugin.runtime

import android.content.*
import android.os.*
import android.util.Log
import com.tyust.course.academic.plugin.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class PluginSandboxClient(context: Context, private val startupTimeoutMillis: Long = 10_000) {
    private val application = context.applicationContext

    suspend fun execute(source: String, args: JSONObject, operation: PluginOperation, host: PluginHost): JSONObject = coroutineScope {
        operation.requireActive()
        val scope = this
        val connected = CompletableDeferred<IPluginSandbox>()
        val result = CompletableDeferred<String>()
        val accepting = AtomicBoolean(true)
        val received = AtomicBoolean(false)
        val incomingDescriptors = java.util.concurrent.ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()
        val startedAt = SystemClock.elapsedRealtime()
        var phaseStartedAt = startedAt
        var phase = "binding"
        fun diagnostic(outcome: String) {
            val now = SystemClock.elapsedRealtime()
            Log.i("PluginSandbox", "phase=$phase outcome=$outcome phaseMs=${now - phaseStartedAt} totalMs=${now - startedAt}")
        }
        fun disconnected(message: String) {
            if (!accepting.get()) return
            val error = operation.failure(PluginErrorCode.RUNTIME_EXITED, message)
            connected.completeExceptionally(error)
            result.completeExceptionally(error)
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (accepting.get()) connected.complete(IPluginSandbox.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = disconnected("插件进程已退出，请重试")
            override fun onNullBinding(name: ComponentName) = disconnected("插件进程启动失败：服务未提供连接")
            override fun onBindingDied(name: ComponentName) = disconnected("插件服务连接已失效，请重试")
        }
        var bound = false
        var sandbox: IPluginSandbox? = null
        try {
            withTimeout(PluginLimits.WALL_MILLIS) {
                bound = application.bindService(Intent(application, PluginSandboxService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) throw operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程启动失败：无法绑定服务")
                sandbox = withTimeout(startupTimeoutMillis) { connected.await() }
                diagnostic("connected")
                operation.requireActive()
                val bridge = object : IPluginHost.Stub() {
                    override fun call(id: String, method: String, payload: ParcelFileDescriptor): ParcelFileDescriptor {
                        val response = try {
                            payload.use {
                                if (!accepting.get() || id != operation.id) throw PluginException(PluginErrorCode.CANCELLED, "操作标识已失效")
                                operation.requireActive()
                                host.call(method, PluginJson.parse(PluginWire.read(it)))
                            }
                        } catch (e: Exception) {
                            val error = if (e is PluginException) e else operation.failure(PluginErrorCode.VALIDATION_FAILED, "宿主调用失败")
                            PluginJson.error(error.code, error.message.orEmpty())
                        }
                        return PluginWire.send(scope, response.toString())
                    }
                }
                val callback = object : IPluginResult.Stub() {
                    override fun complete(id: String, descriptor: ParcelFileDescriptor) {
                        if (!accepting.get() || !scope.isActive || id != operation.id || result.isCompleted ||
                            !received.compareAndSet(false, true)) { descriptor.close(); return }
                        incomingDescriptors.add(descriptor)
                        // Enter use before cancellation can strand an incoming descriptor.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try { descriptor.use {
                                try { result.complete(withContext(Dispatchers.IO) { PluginWire.read(it) }) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { result.completeExceptionally(e) }
                            } } finally { incomingDescriptors.remove(descriptor) }
                        }
                    }
                }
                val request = JSONObject().put("source", source).put("operation", operation.method)
                    .put("args", args).put("context", operation.context)
                phase = "executing"
                phaseStartedAt = SystemClock.elapsedRealtime()
                PluginWire.send(scope, request.toString()).use { sandbox!!.execute(it, bridge, callback) }
                val response = PluginJson.parse(result.await())
                operation.requireActive()
                if (!response.optBoolean("ok")) {
                    val failure = response.optJSONObject("error")
                    var code = runCatching { PluginErrorCode.valueOf(failure?.optString("code").orEmpty()) }.getOrDefault(PluginErrorCode.PAGE_CHANGED)
                    val detail = failure?.optString("message") ?: "插件返回错误"
                    // __zfInvoke may catch QuickJS's interrupt and serialize it as a generic
                    // page error before the Kotlin engine can surface its typed exception.
                    if (code == PluginErrorCode.PAGE_CHANGED &&
                        detail.trim() in setOf("interrupted", "InternalError: interrupted")) code = PluginErrorCode.TIMEOUT
                    val message = if (code == PluginErrorCode.TIMEOUT) {
                        if (operation.method.startsWith("auth.")) "登录处理超时，请重试或使用网页登录"
                        else "教务处理超时，请重试"
                    } else detail
                    throw operation.failure(code, message)
                }
                diagnostic("completed")
                response
            }
        } catch (e: TimeoutCancellationException) {
            // An outer deadline must remain caller cancellation.
            currentCoroutineContext().ensureActive()
            diagnostic("timeout")
            throw if (phase == "binding") operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程启动超时，请重试")
                else operation.failure(PluginErrorCode.TIMEOUT, "插件执行超时，请重试")
        } catch (e: CancellationException) {
            diagnostic("cancelled")
            throw e
        } catch (e: RemoteException) {
            diagnostic("disconnected")
            throw operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程已退出")
        } catch (e: SecurityException) {
            diagnostic("denied")
            throw operation.failure(PluginErrorCode.RUNTIME_EXITED, "系统未允许启动插件服务")
        } catch (e: java.io.IOException) {
            diagnostic("response_interrupted")
            throw operation.failure(PluginErrorCode.RUNTIME_EXITED, "插件进程响应中断，请重试")
        } catch (e: PluginException) {
            diagnostic(e.code.name)
            throw e
        } finally {
            accepting.set(false)
            incomingDescriptors.forEach { runCatching { it.close() } }
            incomingDescriptors.clear()
            // The caller owns the operation through response validation/publication.
            // This client only retires its Binder connection and transport resources.
            runCatching { sandbox?.cancel(operation.id) }
            if (bound) runCatching { application.unbindService(connection) }
            connected.cancel()
            result.cancel()
        }
    }
}
