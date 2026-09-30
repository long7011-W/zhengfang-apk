package com.tyust.course.academic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrderedGrabQueueTest {
    @Test fun retriesFinishBeforeTheNextCourseStarts() = runTest {
        val attempts = mutableListOf<String>()
        runOrderedGrabQueue(listOf("A", "B", "C"), 1) { course ->
            repeat(if (course == "A") 3 else 1) { attempts += course; delay(500) }
        }
        assertEquals(listOf("A", "A", "A", "B", "C"), attempts)
    }

    @Test fun reorderedRemainingQueueIsUsedOnEveryNewStart() = runTest {
        val calls = mutableListOf<String>()
        val remaining = mutableListOf("C", "A", "B")
        runOrderedGrabQueue(remaining.toList(), 1) { calls += it }
        remaining.remove("C")
        runOrderedGrabQueue(remaining.toList(), 1) { calls += it }
        assertEquals(listOf("C", "A", "B", "A", "B"), calls)
    }

    @Test fun parallelSlotsAreAssignedInOrderAndOwnedUntilCompletion() = runTest {
        val calls = mutableListOf<String>()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val job = launch {
            runOrderedGrabQueue(listOf("A", "B", "C"), 2) {
                calls += it
                when (it) { "A" -> first.await(); "B" -> second.await() }
            }
        }
        testScheduler.runCurrent()
        assertEquals(listOf("A", "B"), calls)
        second.complete(Unit); testScheduler.runCurrent()
        assertEquals(listOf("A", "B", "C"), calls)
        first.complete(Unit); job.join()
    }

    @Test fun attentionAndCancellationPreventStartingRemainingCourses() = runTest {
        var allowed = true
        val calls = mutableListOf<String>()
        runOrderedGrabQueue(listOf("A", "B", "C"), 1, { allowed }) { calls += it; allowed = false }
        assertEquals(listOf("A"), calls)
        val job = launch { runOrderedGrabQueue(listOf("B", "C"), 1) { calls += it; delay(1000) } }
        testScheduler.runCurrent(); job.cancel(); job.join()
        assertEquals(listOf("A", "B"), calls)
    }
}
