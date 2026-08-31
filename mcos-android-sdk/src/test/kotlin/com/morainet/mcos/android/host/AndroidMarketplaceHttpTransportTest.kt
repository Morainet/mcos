package com.morainet.mcos.android.host

import com.morainet.mcos.marketplace.MarketplaceTransportException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [AndroidMarketplaceHttpTransport] against a minimal raw-socket
 * HTTP server. `com.sun.net.httpserver` is not on the Android unit-test
 * bootclasspath (the mockable android.jar shadows the JDK), so the fixture
 * hand-rolls just enough HTTP/1.1 for HttpURLConnection — HttpURLConnection is
 * otherwise pure JDK and needs no Robolectric.
 */
class AndroidMarketplaceHttpTransportTest {

    /**
     * Single-purpose HTTP/1.1 server: every request is answered by [responder]
     * after an optional [delayMs]. Request method/path/headers/body of the
     * latest request are captured for assertions.
     */
    private class TinyHttpServer {
        private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val running = ArrayList<Thread>()
        @Volatile var delayMs: Long = 0
        @Volatile var responder: (Request) -> Response = { Response(404) }

        class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
        class Response(val status: Int, val contentType: String? = null, val body: ByteArray = ByteArray(0))

        val lastRequest = AtomicReference<Request>()
        val started = CountDownLatch(1)
        val port: Int get() = serverSocket.localPort

        init {
            val acceptor = Thread {
                started.countDown()
                while (!serverSocket.isClosed) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (e: IOException) {
                        break // closed by stop()
                    }
                    val worker = Thread { serve(socket) }.apply { isDaemon = true }
                    synchronized(running) { running.add(worker) }
                    worker.start()
                }
            }
            acceptor.isDaemon = true
            acceptor.start()
        }

        fun stop() {
            serverSocket.close()
            synchronized(running) { running.forEach { it.interrupt() } }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use { s ->
                    val input = s.getInputStream()
                    val head = readUntilHeaderEnd(input)
                    val lines = head.decodeToString().split("\r\n")
                    val parts = lines.first().split(" ")
                    val headers = lines.drop(1)
                        .filter { it.contains(":") }
                        .associate {
                            val idx = it.indexOf(':')
                            it.substring(0, idx).trim().lowercase() to it.substring(idx + 1).trim()
                        }
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = if (contentLength > 0) input.readNBytes(contentLength) else ByteArray(0)
                    val request = Request(parts[0], parts.getOrNull(1).orEmpty(), headers, body)
                    lastRequest.set(request)

                    if (delayMs > 0) Thread.sleep(delayMs)
                    val response = responder(request)
                    val statusText = when (response.status) {
                        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 404 -> "Not Found"
                        else -> "Status"
                    }
                    val headOut = buildString {
                        append("HTTP/1.1 ${response.status} $statusText\r\n")
                        response.contentType?.let { append("Content-Type: $it\r\n") }
                        append("Content-Length: ${response.body.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }.encodeToByteArray()
                    s.getOutputStream().apply {
                        write(headOut)
                        write(response.body)
                        flush()
                    }
                }
            } catch (expected: IOException) {
                // Client timed out and closed the socket — expected in delay tests.
            }
        }

        private fun readUntilHeaderEnd(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            var lastFour = 0
            while (true) {
                val b = input.read()
                if (b < 0) break
                out.write(b)
                lastFour = ((lastFour shl 8) or b) and 0xFFFF_FFFF.toInt()
                if (lastFour == 0x0D0A0D0A) break
            }
            return out.toByteArray()
        }
    }

    private lateinit var server: TinyHttpServer
    private lateinit var transport: AndroidMarketplaceHttpTransport

    @Before
    fun setUp() {
        server = TinyHttpServer()
        server.started.await()
        transport = AndroidMarketplaceHttpTransport()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    // ── getJson ────────────────────────────────────────────────────────

    @Test
    fun getJsonReturnsStatusAndBody() = runBlocking {
        server.responder = { TinyHttpServer.Response(200, "application/json", """{"ok":true}""".toByteArray()) }

        val response = transport.getJson(url("/v1/plugins?page=1"), connectTimeoutMs = 2_000, requestTimeoutMs = 2_000)

        assertEquals(200, response.statusCode)
        assertEquals("""{"ok":true}""", response.body)
    }

    @Test
    fun getJsonSurfacesHttpErrorStatus() = runBlocking {
        server.responder = { TinyHttpServer.Response(404) }

        val response = transport.getJson(url("/v1/plugins?page=1"), connectTimeoutMs = 2_000, requestTimeoutMs = 2_000)

        assertEquals(404, response.statusCode)
        assertEquals("", response.body)
    }

    // ── getBytes (plugin artifact download path) ───────────────────────

    @Test
    fun getBytesRoundTripsBinaryArtifact() = runBlocking {
        val artifact = ByteArray(512) { it.toByte() }
        server.responder = { TinyHttpServer.Response(200, "application/octet-stream", artifact) }

        val bytes = transport.getBytes(url("/artifact.mcos"), connectTimeoutMs = 2_000, requestTimeoutMs = 2_000)

        assertArrayEquals(artifact, bytes)
    }

    // ── postJson (telemetry / report path) ─────────────────────────────

    @Test
    fun postJsonSendsBodyWithJsonContentType() = runBlocking {
        val sentBody = """{"packageId":"example.hello"}"""
        server.responder = { TinyHttpServer.Response(201, "application/json", """{"id":"r-1"}""".toByteArray()) }

        val response = transport.postJson(url("/v1/reports"), body = sentBody, connectTimeoutMs = 2_000, requestTimeoutMs = 2_000)

        assertEquals(201, response.statusCode)
        assertEquals("""{"id":"r-1"}""", response.body)

        val request = server.lastRequest.get()
        assertEquals("POST", request.method)
        assertEquals(sentBody, request.body.decodeToString())
        assertTrue(
            "expected JSON Content-Type, got ${request.headers["content-type"]}",
            request.headers["content-type"]?.startsWith("application/json") == true,
        )
    }

    // ── timeout mapping ────────────────────────────────────────────────

    @Test
    fun readTimeoutMapsToRetryableMarketplaceTimeout() = runBlocking {
        server.delayMs = 1_500
        server.responder = { TinyHttpServer.Response(200, "application/json", "[]".toByteArray()) }

        try {
            transport.getJson(url("/v1/plugins?page=1"), connectTimeoutMs = 2_000, requestTimeoutMs = 200)
            fail("expected MarketplaceTransportException")
        } catch (e: MarketplaceTransportException) {
            assertEquals("MARKETPLACE_TIMEOUT", e.code)
            assertTrue(e.retryable)
        }
    }

    @Test
    fun connectFailurePropagatesRawForIndexMapping() = runBlocking {
        // Grab a free ephemeral port and close the socket so nothing listens
        // there: a refused connection must propagate as a raw IOException so
        // MarketplaceIndex can map it to MARKETPLACE_UNREACHABLE — not be
        // swallowed as a timeout. (Using server.port + 1 is flaky: another
        // parallel fixture can occupy that port.)
        val deadPort = ServerSocket(0).use { it.localPort }
        val deadUrl = "http://127.0.0.1:$deadPort/v1/plugins?page=1"
        try {
            transport.getJson(deadUrl, connectTimeoutMs = 500, requestTimeoutMs = 500)
            fail("expected an IOException")
        } catch (e: MarketplaceTransportException) {
            fail("connect refusal should propagate raw, got $e")
        } catch (expected: IOException) {
            // Expected: ConnectException.
        }
    }
}
