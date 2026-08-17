package com.morainet.mcos.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * HTTP surface of `mcos-server` implementing the `SyncBlobTransport` REST
 * contract (see [MemorySyncClient.kt]) plus mandatory Bearer-token auth:
 *
 * | Method | Path             | Success | Errors                                    |
 * |--------|------------------|---------|-------------------------------------------|
 * | PUT    | `/blobs/{id}`    | 204     | 400 bad id, 401 no/bad token, 405, 413    |
 * | GET    | `/blobs/{id}`    | 200     | 400 bad id, 401, 404 not found, 405       |
 * | DELETE | `/blobs/{id}`    | 204     | 400 bad id, 401, 405 (idempotent)         |
 * | GET    | `/healthz`       | 200     | 405 (no auth required)                    |
 *
 * The server treats every blob as opaque bytes: it never parses, inspects or
 * transforms payloads — decryption happens only on the device.
 */
class BlobServer(
    private val store: BlobStore,
    private val token: String,
    port: Int = 0,
    private val executorThreads: Int = 4,
) : AutoCloseable {

    private val http: HttpServer

    /** Bound port (may differ from requested when 0 was passed). */
    val port: Int

    /** Base URL usable by a `SyncBlobTransport` on the same host. */
    val url: String
        get() = "http://127.0.0.1:$port"

    init {
        require(token.isNotBlank()) { "mcos-server requires a non-empty API token" }
        http = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        http.executor = Executors.newFixedThreadPool(executorThreads)
        http.createContext("/blobs/", ::handleBlob)
        http.createContext("/healthz", ::handleHealth)
        http.start()
        this.port = http.address.port
    }

    private fun handleHealth(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") return methodNotAllowed(exchange)
            respond(exchange, 200, "ok".toByteArray())
        } finally {
            exchange.close()
        }
    }

    private fun handleBlob(exchange: HttpExchange) {
        try {
            val blobId = exchange.requestURI.path.removePrefix("/blobs/")
            if (blobId.isEmpty()) return respond(exchange, 404, null)
            if (!BlobStore.isValidBlobId(blobId)) return respond(exchange, 400, null)
            if (!authorized(exchange)) return respond(exchange, 401, null)

            when (exchange.requestMethod) {
                "PUT" -> {
                    val bytes = exchange.requestBody.readBytes()
                    store.put(blobId, bytes)
                    respond(exchange, 204, null)
                }
                "GET" -> {
                    val body = store.get(blobId)
                    if (body == null) respond(exchange, 404, null)
                    else respond(exchange, 200, body)
                }
                "DELETE" -> {
                    store.delete(blobId)
                    respond(exchange, 204, null)
                }
                else -> respond(exchange, 405, null)
            }
        } catch (e: BlobTooLargeException) {
            respond(exchange, 413, e.message?.toByteArray(StandardCharsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            respond(exchange, 400, null)
        } catch (e: java.io.IOException) {
            respond(exchange, 500, null)
        } finally {
            exchange.close()
        }
    }

    /** Constant-time Bearer-token check: `Authorization: Bearer <token>`. */
    private fun authorized(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Bearer ")) return false
        val presented = header.removePrefix("Bearer ")
        val expected = token.toByteArray(StandardCharsets.UTF_8)
        val actual = presented.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun methodNotAllowed(exchange: HttpExchange) {
        exchange.responseHeaders.add("Allow", "GET, PUT, DELETE")
        respond(exchange, 405, null)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray?) {
        if (status == 401) exchange.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"mcos\"")
        if (body == null) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    override fun close() {
        http.stop(0)
    }
}
