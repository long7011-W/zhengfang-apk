package com.tyust.course.academic.plugin

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/** Concurrent consumers share both acceptance and rejection of one visible question. */
internal object PluginConsentCoordinator {
    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    suspend fun request(key: String, block: suspend () -> JSONObject): JSONObject {
        var leader = false
        val result = synchronized(pending) { pending.getOrPut(key) { leader = true; CompletableDeferred() } }
        if (leader) {
            try { result.complete(block()) }
            catch (error: Throwable) { result.completeExceptionally(error) }
            finally { synchronized(pending) { if (pending[key] === result) pending.remove(key) } }
        }
        return JSONObject(result.await().toString())
    }
}
