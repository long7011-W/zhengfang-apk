package com.tyust.course.academic.plugin

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginSiteRuntimeTest {
    @Test fun concurrentRejectionIsDeliveredToEveryWaiterWithoutRepeatingTheQuestion() = runBlocking {
        var prompts = 0
        val outcomes = coroutineScope { List(4) {
            async { runCatching { PluginConsentCoordinator.request("synthetic-dialog") {
                prompts++; delay(30); throw PluginException(PluginErrorCode.CANCELLED, "declined")
            } } }
        }.awaitAll() }
        assertEquals(1, prompts)
        assertTrue(outcomes.all { (it.exceptionOrNull() as? PluginException)?.code == PluginErrorCode.CANCELLED })
    }
    @Test fun userDeliberationDoesNotConsumeExecutionTime() = runBlocking {
        assertEquals("completed", PluginExecutionBudget.run(200) {
            PluginExecutionBudget.userInput { delay(350) }
            delay(10); "completed"
        })
    }
    @Test fun computationDeadlineAndParentCancellationStillApply() = runBlocking {
        val error = runCatching { PluginExecutionBudget.run(25) { delay(500) } }.exceptionOrNull()
        assertEquals(PluginErrorCode.TIMEOUT, (error as PluginException).code)
        val outer = runCatching { withTimeout(25) { PluginExecutionBudget.run(500) {
            PluginExecutionBudget.userInput { delay(500) }
        } } }.exceptionOrNull()
        assertTrue(outer is TimeoutCancellationException)
    }
}
