package com.tyust.course.academic

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A worker owns a course through all its retries. Dequeue happens in display order. */
internal suspend fun <T> runOrderedGrabQueue(
    items: List<T>, workers: Int, canContinue: () -> Boolean = { true },
    execute: suspend (T) -> Unit
) = coroutineScope {
    require(workers in 1..2)
    val pending = ArrayDeque(items)
    val lock = Any()
    fun next(): T? = synchronized(lock) { if (canContinue()) pending.removeFirstOrNull() else null }
    repeat(minOf(workers, items.size)) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) execute(next() ?: break)
        }
    }
}
