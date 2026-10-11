package com.tyust.course.ui.theme

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NavigationMotionStateTest {
    @Test fun reducedMotionCompletesWithoutWaitingForAnExternalRedraw() = runTest {
        val state = NavigationMotionState(0, this)
        state.select(0, true); runCurrent()
        assertTrue(state.transitionFinished)
        assertEquals(1f, state.moduleProgress(0), 0f)
        state.select(3, true); runCurrent()
        assertEquals(setOf(3), state.pages.keys)
        assertEquals(1f, state.transform(3).alpha, 0f)
        assertEquals(1f, state.moduleProgress(3), 0f)
        state.dispose()
    }
    @Test fun rapidReversalsKeepAtMostTwoPagesAndFinishAtOpaqueTarget() = runTest {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16); return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        val state = NavigationMotionState(0, CoroutineScope(coroutineContext + clock))
        state.select(0, true); runCurrent()
        for (page in listOf(1, 2, 0, 4, 1, 0, 3)) {
            state.select(page, false); runCurrent()
            assertFalse(state.transitionFinished)
            assertTrue(state.pages.size <= 2)
        }
        advanceUntilIdle()
        assertTrue(state.transitionFinished)
        assertEquals(setOf(3), state.pages.keys)
        assertEquals(1f, state.transform(3).alpha, 0.0001f)
        assertEquals(1f, state.moduleProgress(3), 0.0001f)
        state.dispose()
    }
}
