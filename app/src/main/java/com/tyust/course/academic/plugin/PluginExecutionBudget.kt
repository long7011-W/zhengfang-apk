package com.tyust.course.academic.plugin

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** User deliberation has its own deadline and does not consume the execution budget. */
internal class PluginExecutionBudget : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginExecutionBudget> {
        suspend fun <T> run(millis: Long, block: suspend () -> T): T = coroutineScope {
            val budget = PluginExecutionBudget()
            val work = async(budget) { block() }
            val alarm = async { budget.waitForExpiry(millis) }
            try { select { work.onAwait { it }; alarm.onAwait { throw PluginException(PluginErrorCode.TIMEOUT, "插件执行超时，请重试") } } }
            finally { work.cancel(); alarm.cancel() }
        }
        suspend fun <T> userInput(block: suspend () -> T): T {
            val budget = currentCoroutineContext()[Key]
            budget?.change(1)
            return try { withTimeout(120_000) { block() } } finally { budget?.change(-1) }
        }
    }
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var pauses = 0
    private var last = System.nanoTime()
    private var elapsed = 0L
    @Synchronized private fun tick(): Pair<Long, Boolean> {
        val now = System.nanoTime()
        if (pauses == 0) elapsed += now - last
        last = now
        return elapsed / 1_000_000 to (pauses > 0)
    }
    @Synchronized private fun change(delta: Int) { tick(); pauses += delta; changed.trySend(Unit) }
    private suspend fun waitForExpiry(millis: Long) {
        while (true) {
            val (elapsed, paused) = tick()
            if (!paused && elapsed >= millis) return
            if (paused) changed.receive() else withTimeoutOrNull((millis - elapsed).coerceAtLeast(1)) { changed.receive() }
        }
    }
}
