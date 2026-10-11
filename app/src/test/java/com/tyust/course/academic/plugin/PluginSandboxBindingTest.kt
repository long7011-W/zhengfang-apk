package com.tyust.course.academic.plugin

import android.app.Application
import android.content.*
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import org.robolectric.RuntimeEnvironment
import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.academic.plugin.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginSandboxBindingTest {
    private val app = RuntimeEnvironment.getApplication()
    private fun operation() = PluginOperation(
        AcademicSessionStore().session("school", "account", "https://school.test"),
        PluginManifest(JSONObject("""{"id":"binding.test","kind":"independent","version":"1.0.0","network":[]}""")),
        "study.terms", development = true
    )
    private class BindingContext(base: Context, val bind: (ServiceConnection) -> Boolean) : ContextWrapper(base) {
        lateinit var connection: ServiceConnection
        var unbound = 0
        var bound = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            bound++
            return bind(conn)
        }
        override fun unbindService(conn: ServiceConnection) { assertSame(connection, conn); unbound++ }
    }
    private suspend fun invoke(context: Context, op: PluginOperation, timeout: Long = 100) =
        PluginSandboxClient(context, timeout, 0).execute("", JSONObject(), op, PluginHost(op, app.cacheDir))

    private fun replyingContext(response: JSONObject, firstFailure: String? = null): BindingContext {
        var attempts = 0
        return BindingContext(app) { connection ->
        if (attempts++ == 0 && firstFailure != null) {
            if (firstFailure == "null") connection.onNullBinding(ComponentName(app, PluginSandboxService::class.java))
            return@BindingContext firstFailure != "false"
        }
        connection.onServiceConnected(ComponentName(app, PluginSandboxService::class.java), object : IPluginSandbox.Stub() {
            override fun execute(input: ParcelFileDescriptor, host: IPluginHost, callback: IPluginResult) {
                // Robolectric backs pipes with files: EOF can precede the async writer.
                // Wait for the complete JSON request without changing production transport.
                val request = ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    val deadline = System.nanoTime() + 2_000_000_000L
                    var parsed: JSONObject? = null
                    while (parsed == null && System.nanoTime() < deadline) {
                        val count = stream.read(buffer)
                        if (count > 0) {
                            bytes.write(buffer, 0, count)
                            parsed = runCatching { JSONObject(bytes.toString("UTF-8")) }.getOrNull()
                        } else Thread.sleep(1)
                    }
                    checkNotNull(parsed) { "Sandbox fixture did not receive the request" }
                }
                val file = java.io.File.createTempFile("sandbox-response-", ".json", app.cacheDir)
                try {
                    file.writeText(response.toString())
                    callback.complete(request.getJSONObject("context").getString("operationId"),
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                } finally { file.delete() }
            }
            override fun cancel(operationId: String) {}
        })
        true
        }
    }

    @Test fun transientStartupFailureRecoversBeforeDeliveringLogin() = runBlocking {
        for (failure in listOf("false", "null")) {
            val context = replyingContext(PluginJson.success(JSONObject()), failure)
            val login = PluginOperation(AcademicSessionStore().session("synthetic", "account", "https://school.test"), operation().manifest, "auth.start", development = true)
            try { assertTrue(invoke(context, login, 1000).getBoolean("ok")); assertEquals(2, context.bound) }
            finally { login.close() }
        }
    }
    @Test fun attemptedDeliveryIsNeverReplayedEvenWhenBinderDies() = runBlocking {
        var delivered = 0
        val context = BindingContext(app) { connection ->
            connection.onServiceConnected(ComponentName(app, PluginSandboxService::class.java), object : IPluginSandbox.Stub() {
                override fun execute(input: ParcelFileDescriptor, host: IPluginHost, callback: IPluginResult) { delivered++; input.close(); throw android.os.DeadObjectException() }
                override fun cancel(operationId: String) {}
            }); true
        }
        val op = operation()
        try { assertEquals(PluginErrorCode.RUNTIME_EXITED, (runCatching { invoke(context, op) }.exceptionOrNull() as PluginException).code) }
        finally { op.close() }
        assertEquals(1, delivered); assertEquals(1, context.bound)
    }
    @Test fun successfulSandboxReturnLeavesCallerOperationActiveUntilCallerClosesIt() = runBlocking {
        val context = replyingContext(PluginJson.success(JSONObject().put("items", org.json.JSONArray())))
        val op = operation()
        try {
            assertTrue(invoke(context, op, 1000).getBoolean("ok"))
            op.requireActive()
            assertEquals(1, context.unbound)
        } finally { op.close() }
        assertEquals(PluginErrorCode.CANCELLED, assertThrows(PluginException::class.java) { op.requireActive() }.code)
    }

    @Test fun minimalNativePageAndButtonResultSurviveTheSandboxReturn() = runBlocking {
        val pkg = PluginPackage(PluginManifest(JSONObject("""{"id":"native.smoke","name":"Smoke","version":"1.0.0","apiVersion":3,"kind":"native","capabilities":["ui.init","ui.reduce"],"permissions":[],"network":[],"contributes":{"pages":[{"id":"main","title":"Smoke"}],"entries":[]}}""")), "", "fixture", false)
        val session = AcademicSessionStore().session("plugin:native.smoke", "default", "https://invalid.example/")
        for ((method, count) in listOf("ui.init" to 0, "ui.reduce" to 1)) {
            val response = JSONObject("""{"state":{"n":$count},"view":{"id":"counter","type":"text","text":"Count: $count"},"effects":[]}""")
            val context = replyingContext(PluginJson.success(response))
            val result = NativePluginRunner.invoke(context, pkg, session, method, JSONObject().put("pageId", "main"), JSONObject(), active = { true })
            assertEquals(count, result.getJSONObject("state").getInt("n"))
            assertEquals(0, context.unbound)
        }
        session.retire()
    }

    @Test fun pageLeasesReuseOneConnectionAndReleaseAfterThirtyIdleSeconds() = runBlocking {
        val context = replyingContext(PluginJson.success(JSONObject()))
        val a = PluginSandboxConnections.acquire(context)
        val b = PluginSandboxConnections.acquire(context)
        assertSame(a.ready.await(), b.ready.await()); assertEquals(1, context.bound)
        a.close(); assertEquals(0, context.unbound); b.close()
        val clock = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        clock.idleFor(java.time.Duration.ofSeconds(29)); assertEquals(0, context.unbound)
        clock.idleFor(java.time.Duration.ofSeconds(2)); assertEquals(1, context.unbound)
    }
    @Test fun startupTimeoutRetiresAPinnedConnectionSoRetryCanBindAgain() = runTest {
        val context = BindingContext(app) { true }
        val page = PluginSandboxConnections.acquire(context)
        val failure = runCatching { invoke(context, operation(), 25) }.exceptionOrNull() as PluginException
        assertEquals(PluginErrorCode.RUNTIME_EXITED, failure.code); assertEquals(1, context.unbound)
        val retry = PluginSandboxConnections.acquire(context, 0)
        assertEquals(2, context.bound)
        page.close(); retry.close(); assertEquals(2, context.unbound)
    }

    @Test fun deadConnectionNotifiesAllConsumersAndNextRequestBindsFresh() = runBlocking {
        val context = replyingContext(PluginJson.success(JSONObject())); var disconnected = 0
        val a = PluginSandboxConnections.acquire(context) { disconnected++ }
        val b = PluginSandboxConnections.acquire(context) { disconnected++ }
        val old = a.ready.await()
        context.connection.onServiceDisconnected(ComponentName(app, PluginSandboxService::class.java))
        assertEquals(2, disconnected); assertEquals(1, context.unbound)
        val c = PluginSandboxConnections.acquire(context, 0)
        assertNotSame(old, c.ready.await()); assertEquals(2, context.bound)
        a.close(); b.close(); c.close(); assertEquals(2, context.unbound)
    }

    @Test fun privateServiceKeepsSeparateProcessWithoutIsolatedUidForOemCompatibility() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, PluginSandboxService::class.java), 0)
        assertEquals(app.packageName + ":academic_plugin", info.processName)
        assertFalse(info.exported)
        // #30/#50: the isolated UID launch path can be killed before service publication.
        assertEquals(0, info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS)
    }
    @Test fun failedBindDoesNotExecuteOrUnbindANonexistentConnection() = runTest {
        val context = BindingContext(app) { false }
        val error = runCatching { invoke(context, operation()) }.exceptionOrNull() as PluginException
        assertEquals(PluginErrorCode.RUNTIME_EXITED, error.code)
        assertTrue(error.message!!.contains("启动失败"))
        assertEquals(0, context.unbound)
    }
    @Test fun noCallbackIsAStartupFailureAndLateConnectionCannotExecute() = runTest {
        val context = BindingContext(app) { true }
        val error = runCatching { invoke(context, operation()) }.exceptionOrNull() as PluginException
        assertEquals(PluginErrorCode.RUNTIME_EXITED, error.code)
        assertTrue(error.message!!.contains("启动超时"))
        assertEquals(1, context.unbound)
        var calls = 0
        context.connection.onServiceConnected(ComponentName(app, PluginSandboxService::class.java), object : IPluginSandbox.Stub() {
            override fun execute(input: ParcelFileDescriptor, host: IPluginHost, callback: IPluginResult) { calls++; input.close() }
            override fun cancel(operationId: String) { calls++ }
        })
        assertEquals(0, calls)
    }
    @Test fun nullAndDeadBindingsFailImmediatelyAndAlwaysUnbind() = runTest {
        for (nullBinding in listOf(true, false)) {
            val context = BindingContext(app) {
                val name = ComponentName(app, PluginSandboxService::class.java)
                if (nullBinding) it.onNullBinding(name) else it.onBindingDied(name)
                true
            }
            val error = runCatching { invoke(context, operation()) }.exceptionOrNull() as PluginException
            assertEquals(PluginErrorCode.RUNTIME_EXITED, error.code)
            assertEquals(2, context.unbound)
            assertEquals(0, testScheduler.currentTime)
        }
    }
    @Test fun cancellationWhileConnectingClosesTheOperation() = runTest {
        val context = BindingContext(app) { true }
        val op = operation()
        val task = launch { try { invoke(context, op) } finally { op.close() } }
        runCurrent()
        task.cancelAndJoin()
        assertEquals(1, context.unbound)
        assertEquals(PluginErrorCode.CANCELLED, (runCatching { op.requireActive() }.exceptionOrNull() as PluginException).code)
    }
    @Test fun callerDeadlineRemainsCancellationInsteadOfAPluginTimeout() = runTest {
        val context = BindingContext(app) { true }
        val error = runCatching { withTimeout(20) { invoke(context, operation(), 100) } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertEquals(1, context.unbound)
    }
}
