package com.tyust.course.academic.plugin

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginConnectFallbackSafetyTest {
    @Test
    fun `trusted tls connects after unreachable first address`() {
        TlsFixture(trusted = true).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("secure"))
            call(fixture.client, fixture.request, fixture.listener).execute().use {
                assertEquals("secure", it.body!!.string())
                assertTrue(it.handshake != null)
            }
            assertEquals(2, fixture.connections.get())
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun `untrusted tls certificate stops before trying another address`() {
        TlsFixture(trusted = false, addresses = listOf(ipv4, spare)).use { fixture ->
            assertThrows(SSLHandshakeException::class.java) {
                call(fixture.client, fixture.request, fixture.listener).execute()
            }
            assertEquals(1, fixture.connections.get())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun `trusted certificate for different hostname stops before fallback`() {
        TlsFixture(trusted = true, hostname = "different.test", addresses = listOf(ipv4, spare)).use { fixture ->
            assertThrows(SSLPeerUnverifiedException::class.java) {
                call(fixture.client, fixture.request, fixture.listener).execute()
            }
            assertEquals(1, fixture.connections.get())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun `tls handshake disconnect before http request may use next address`() {
        val failing = MockWebServer()
        TlsFixture(trusted = true).use { fixture ->
            try {
                failing.useHttps(fixture.serverCertificates.sslSocketFactory(), false)
                failing.start(spare, fixture.server.port)
                failing.enqueue(MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE))
                fixture.server.enqueue(MockResponse().setBody("secure"))
                call(fixture.client, fixture.request, fixture.listener).execute().use {
                    assertEquals("secure", it.body!!.string())
                }
                assertEquals(2, fixture.connections.get())
                val failedHandshake = failing.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("", failedHandshake.requestLine)
                assertEquals(0L, failedHandshake.bodySize)
                assertEquals(0, failedHandshake.headers.size)
                assertEquals(1, fixture.server.requestCount)
            } finally {
                failing.shutdown()
            }
        }
    }

    @Test
    fun `dns failure does not retry or open a socket`() {
        val lookups = AtomicInteger()
        val sockets = FailingSockets { ConnectException("Unreachable") }
        val client = client(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups.incrementAndGet()
                throw UnknownHostException("No DNS record")
            }
        }).newBuilder().socketFactory(sockets).build()
        try {
            assertThrows(UnknownHostException::class.java) { call(client).execute() }
            assertEquals(1, lookups.get())
            assertEquals(0, sockets.attempts.size)
        } finally {
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `single unreachable address is attempted once`() {
        assertFailedRoutes(listOf(ipv4), 1) { ConnectException("Unreachable") }
    }

    @Test
    fun `duplicate dns records are not attempted twice`() {
        assertFailedRoutes(listOf(ipv4, ipv4, spare, spare), 2) { ConnectException("Unreachable") }
    }

    @Test
    fun `connection attempts are bounded when many routes fail`() {
        val addresses = (1..8).map { InetAddress.getByAddress(byteArrayOf(127, 0, 0, it.toByte())) }
        assertFailedRoutes(addresses, PluginConnectFallback.MAX_ATTEMPTS) { ConnectException("Unreachable") }
    }

    @Test
    fun `connect timeout before request send may use another address`() {
        assertFailedRoutes(listOf(ipv4, spare), 2) { SocketTimeoutException("Connect timed out") }
    }

    @Test
    fun `thread interruption must not trigger another address`() {
        assertFailedRoutes(listOf(ipv4, spare), 1) { InterruptedIOException("Interrupted") }
    }

    @Test
    fun `protocol failure must not trigger another address`() {
        assertFailedRoutes(listOf(ipv4, spare), 1) { ProtocolException("Invalid protocol") }
    }

    @Test
    fun `certificate failure must not trigger another address`() {
        assertFailedRoutes(listOf(ipv4, spare), 1) {
            SSLHandshakeException("Untrusted certificate").apply { initCause(CertificateException("Untrusted")) }
        }
    }

    @Test
    fun `hostname or certificate pin failure must not trigger another address`() {
        assertFailedRoutes(listOf(ipv4, spare), 1) { SSLPeerUnverifiedException("Wrong hostname") }
    }

    @Test
    fun `http proxy connection failure cannot fall back to direct origin`() {
        val sockets = FailingSockets { ConnectException("Proxy unreachable") }
        val client = client(dns(listOf(ipv4, spare))).newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(ipv4, 3128)))
            .socketFactory(sockets)
            .build()
        try {
            assertThrows(ConnectException::class.java) { call(client).execute() }
            assertEquals(listOf(InetSocketAddress(ipv4, 3128)), sockets.attempts)
        } finally {
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `healthy connections are reused by consecutive plugin calls`() {
        Fixture(listOf(ipv4)).use { fixture ->
            val connections = mutableListOf<Connection>()
            val listener = object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    connections += connection
                }
            }
            repeat(2) {
                fixture.server.enqueue(MockResponse().setBody("ok"))
                call(fixture.client, fixture.request, listener).execute().use { response ->
                    assertEquals("ok", response.body!!.string())
                }
            }
            assertEquals(2, fixture.server.requestCount)
            assertEquals(0, fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.sequenceNumber)
            assertEquals(1, fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.sequenceNumber)
            assertSame(connections[0], connections[1])
        }
    }

    @Test
    fun `dns snapshot is used even when resolver would change its order`() {
        Fixture(listOf(spare, ipv4)).use { fixture ->
            val lookups = AtomicInteger()
            val client = fixture.client.newBuilder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return if (lookups.incrementAndGet() == 1) listOf(spare, ipv4) else listOf(spare)
                }
            }).build()
            fixture.server.enqueue(MockResponse().setBody("ok"))
            call(client, fixture.request).execute().use { assertEquals("ok", it.body!!.string()) }
            assertEquals(1, lookups.get())
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun `post reaches server once after pre-send connection fallback`() {
        Fixture(listOf(spare, ipv4)).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("ok"))
            val writes = AtomicInteger()
            val request = fixture.request.newBuilder().post(object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = 7L
                override fun writeTo(sink: BufferedSink) {
                    writes.incrementAndGet()
                    sink.writeUtf8("payload")
                }
            }).build()
            call(fixture.client, request).execute().use { assertEquals(200, it.code) }
            assertEquals(1, writes.get())
            assertEquals(1, fixture.server.requestCount)
            assertEquals("payload", fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        }
    }

    @Test
    fun `post disconnected after upload is never replayed`() {
        Fixture(listOf(ipv4, ipv4)).use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            fixture.server.enqueue(MockResponse().setBody("would be a replay"))
            val request = fixture.request.newBuilder().post("payload".toRequestBody()).build()
            assertThrows(IOException::class.java) { call(fixture.client, request).execute() }
            assertEquals(1, fixture.server.requestCount)
            assertEquals("payload", fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        }
    }

    @Test
    fun `post read timeout after upload is never replayed`() {
        Fixture(listOf(ipv4, ipv4)).use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            fixture.server.enqueue(MockResponse().setBody("would be a replay"))
            val client = fixture.client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
            val request = fixture.request.newBuilder().post("payload".toRequestBody()).build()
            assertThrows(SocketTimeoutException::class.java) { call(client, request).execute() }
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun `post 503 retry-after zero is returned without replay`() {
        assertStatusNotReplayed(503)
    }

    @Test
    fun `bodyless 503 cannot replay a possible get mutation`() {
        Fixture(listOf(ipv4)).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            fixture.server.enqueue(MockResponse().setBody("would be a replay"))
            assertThrows(ProtocolException::class.java) {
                call(fixture.client, fixture.request).execute()
            }
            assertEquals(1, fixture.server.requestCount)
            assertEquals("GET", fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }

    @Test
    fun `post 408 retry-after zero is returned without replay`() {
        assertStatusNotReplayed(408)
    }

    @Test
    fun `put 503 retry-after zero is returned without replay`() {
        assertStatusNotReplayed(503, "PUT")
    }

    @Test
    fun `post redirect is returned without following or replaying`() {
        Fixture(listOf(ipv4)).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/other"))
            fixture.server.enqueue(MockResponse().setBody("would be a replay"))
            val request = fixture.request.newBuilder().post("payload".toRequestBody()).build()
            call(fixture.client, request).execute().use { assertEquals(307, it.code) }
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun `completed call cannot execute again and clone has independent state`() {
        Fixture(listOf(ipv4)).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("ok"))
            fixture.server.enqueue(MockResponse().setBody("clone"))
            val call = call(fixture.client, fixture.request)
            call.execute().use { assertEquals("ok", it.body!!.string()) }
            assertTrue(call.isExecuted())
            assertThrows(IllegalStateException::class.java) { call.execute() }
            call.cancel()
            val clone = call.clone()
            assertFalse(clone.isExecuted())
            assertFalse(clone.isCanceled())
            assertNotSame(call.timeout(), clone.timeout())
            clone.execute().use { assertEquals("clone", it.body!!.string()) }
            assertEquals(2, fixture.server.requestCount)
        }
    }

    private fun assertStatusNotReplayed(status: Int, method: String = "POST") {
        Fixture(listOf(ipv4)).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
            fixture.server.enqueue(MockResponse().setBody("would be a replay"))
            val request = fixture.request.newBuilder().method(method, "payload".toRequestBody()).build()
            call(fixture.client, request).execute().use { assertEquals(status, it.code) }
            assertEquals(1, fixture.server.requestCount)
            assertEquals(method, fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }

    private fun assertFailedRoutes(addresses: List<InetAddress>, attempts: Int, failure: () -> IOException) {
        val sockets = FailingSockets(failure)
        val client = client(dns(addresses)).newBuilder().socketFactory(sockets).build()
        try {
            assertThrows(IOException::class.java) { call(client).execute() }
            assertEquals(attempts, sockets.attempts.size)
            assertEquals(attempts, sockets.attempts.distinct().size)
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private class Fixture(addresses: List<InetAddress>) : AutoCloseable {
        val server = MockWebServer()
        val client = client(dns(addresses))
        val request: Request

        init {
            server.start(ipv4, 0)
            request = Request.Builder().url("http://plugin-safety.test:${server.port}/").build()
        }

        override fun close() {
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private class TlsFixture(
        trusted: Boolean,
        hostname: String = "plugin-safety.test",
        addresses: List<InetAddress> = listOf(spare, ipv4),
    ) : AutoCloseable {
        private val certificate = HeldCertificate.Builder()
            .commonName(hostname).addSubjectAlternativeName(hostname).build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer()
        val connections = AtomicInteger()
        val listener = object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                connections.incrementAndGet()
            }
        }
        val client: OkHttpClient
        val request: Request

        init {
            val clientCertificates = HandshakeCertificates.Builder().apply {
                if (trusted) addTrustedCertificate(certificate.certificate)
                else addPlatformTrustedCertificates()
            }.build()
            client = client(dns(addresses)).newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start(ipv4, 0)
            request = Request.Builder().url("https://plugin-safety.test:${server.port}/").build()
        }

        override fun close() {
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private class FailingSockets(private val failure: () -> IOException) : SocketFactory() {
        val attempts = mutableListOf<SocketAddress>()

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                attempts += endpoint
                throw failure()
            }
        }

        override fun createSocket(host: String, port: Int): Socket = unsupported()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            unsupported()
        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            unsupported()
        private fun unsupported(): Nothing = error("OkHttp must create an unconnected socket")
    }

    companion object {
        private val ipv4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val spare = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))

        private fun dns(addresses: List<InetAddress>) = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = addresses
        }

        private fun client(dns: Dns) = OkHttpClient.Builder()
            .dns(dns).proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        private fun call(
            client: OkHttpClient,
            request: Request = Request.Builder().url("http://plugin-safety.test/").build(),
            listener: EventListener = EventListener.NONE,
        ) = PluginConnectFallbackCall(client, listener, request, PluginConnectFallback.MAX_ATTEMPTS)
    }
}
