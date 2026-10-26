package com.tyust.course.academic.plugin.runtime

import com.dokar.quickjs.QuickJs
import com.tyust.course.academic.plugin.PluginErrorCode
import com.tyust.course.academic.plugin.PluginException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * QuickJS 1.0.15 keeps an unsynchronized process-wide JNI instance count and class cache.
 * Its per-engine locks do not protect those references from another engine's init/close.
 * Keep creation, evaluation, async JS continuations and disposal on one process-wide thread.
 * Suspended host I/O releases the thread, so independent engines can still make progress.
 */
internal object PluginQuickJs {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PluginQuickJs").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // The engine must not escape this block. Blocking host calls belong on Dispatchers.IO;
    // every return into QuickJS, including cancellation cleanup, stays on this dispatcher.
    suspend fun <T> withEngine(block: suspend (QuickJs) -> T): T = withContext(dispatcher) {
        val engine = try { QuickJs.create(dispatcher) }
        catch (error: LinkageError) {
            throw PluginException(PluginErrorCode.RUNTIME_EXITED, "插件引擎无法初始化", error)
        }
        try { block(engine) }
        finally { engine.close() }
    }
}
