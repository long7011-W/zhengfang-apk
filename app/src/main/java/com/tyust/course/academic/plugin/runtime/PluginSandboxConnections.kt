package com.tyust.course.academic.plugin.runtime

import android.content.*
import android.os.*
import com.tyust.course.academic.plugin.*
import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable

/** The Binder connection is reusable. Engines and host capabilities never are. */
internal object PluginSandboxConnections {
    private val entries = mutableMapOf<Context, Entry>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    internal class Entry(val context: Context) {
        val ready = CompletableDeferred<IPluginSandbox>()
        val listeners = mutableMapOf<Any, (String) -> Unit>()
        var bound = false
        var dead = false
        var idle: Runnable? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = synchronized(PluginSandboxConnections) {
                if (!dead) ready.complete(IPluginSandbox.Stub.asInterface(service))
                Unit
            }
            override fun onServiceDisconnected(name: ComponentName) = fail("插件进程已退出，请重试")
            override fun onNullBinding(name: ComponentName) = fail("插件进程启动失败：服务未提供连接")
            override fun onBindingDied(name: ComponentName) = fail("插件服务连接已失效，请重试")
        }
        fun fail(message: String) = synchronized(PluginSandboxConnections) {
            if (!dead) {
                ready.completeExceptionally(PluginException(PluginErrorCode.RUNTIME_EXITED, message))
                listeners.values.toList().forEach { it(message) }
                dispose(this)
            }
        }
    }
    class Lease internal constructor(private val entry: Entry, private val token: Any, private val idleMillis: Long) : Closeable {
        val ready get() = entry.ready
        fun requireReady() = synchronized(PluginSandboxConnections) {
            if (entry.dead) throw PluginException(PluginErrorCode.RUNTIME_EXITED, "插件服务连接已失效，请重试")
        }
        fun invalidate(message: String) = entry.fail(message)
        override fun close() = synchronized(PluginSandboxConnections) {
            if (entry.listeners.remove(token) == null) return@synchronized
            if (entry.listeners.isEmpty() && !entry.dead) {
                if (idleMillis == 0L || !entry.ready.isCompleted) dispose(entry)
                else Runnable { synchronized(PluginSandboxConnections) { if (entry.listeners.isEmpty()) dispose(entry) } }
                    .also { entry.idle = it; handler.postDelayed(it, idleMillis.coerceAtMost(30_000)) }
            }
        }
    }
    @Synchronized fun acquire(context: Context, idleMillis: Long = 30_000, disconnected: (String) -> Unit = {}): Lease {
        val app = context.applicationContext
        val entry = entries.getOrPut(app) { Entry(app) }
        entry.idle?.let(handler::removeCallbacks); entry.idle = null
        val token = Any(); entry.listeners[token] = disconnected
        if (!entry.bound && !entry.dead) {
            try {
                entry.bound = app.bindService(Intent(app, PluginSandboxService::class.java), entry.connection, Context.BIND_AUTO_CREATE)
                if (!entry.bound) entry.fail("插件进程启动失败：无法绑定服务")
                // A synchronous null/dead test callback may precede bindService's return.
                if (entry.dead && entry.bound) { runCatching { app.unbindService(entry.connection) }; entry.bound = false }
            } catch (e: Exception) { entry.fail("系统未允许启动插件服务") }
        }
        return Lease(entry, token, idleMillis)
    }
    @Synchronized private fun dispose(entry: Entry) {
        entry.dead = true
        entry.idle?.let(handler::removeCallbacks); entry.idle = null
        if (entries[entry.context] === entry) entries.remove(entry.context)
        if (entry.bound) { runCatching { entry.context.unbindService(entry.connection) }; entry.bound = false }
    }
}
