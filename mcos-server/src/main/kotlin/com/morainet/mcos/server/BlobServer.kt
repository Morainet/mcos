package com.morainet.mcos.server

import com.morainet.mcos.security.EnterprisePolicy
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * HTTP surface of `mcos-server` implementing the `SyncBlobTransport` REST
 * contract (see [MemorySyncClient.kt]) plus mandatory Bearer-token auth:
 *
 * | Method | Path                  | Success | Errors                                    |
 * |--------|-----------------------|---------|-------------------------------------------|
 * | PUT    | `/blobs/{id}`         | 204     | 400 bad id, 401 no/bad token, 405, 413    |
 * | GET    | `/blobs/{id}`         | 200     | 400 bad id, 401, 404 not found, 405       |
 * | DELETE | `/blobs/{id}`         | 204     | 400 bad id, 401, 405 (idempotent)         |
 * | GET    | `/healthz`            | 200     | 405 (no auth required)                    |
 *
 * The server treats every blob as opaque bytes: it never parses, inspects or
 * transforms payloads — decryption happens only on the device.
 *
 * The **enterprise-policy management channel** (08-security §13.3, the
 * "delivered via mcos-server" arm) is the one surface the server DOES
 * understand: the single well-known document behind `/enterprise/policy` must
 * be valid `EnterprisePolicy` JSON, so a typo is refused with 400 at upload
 * time instead of silently pushing clients into their fail-closed restricted
 * mode on the next fetch.
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
        http.createContext("/enterprise/policy", ::handlePolicy)
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

    /**
     * `PUT|GET|DELETE /enterprise/policy` — the single well-known enterprise
     * policy document ([08-security.md 13.3] "delivered via mcos-server").
     * Same mandatory Bearer auth as the blob API. `PUT` parses the body as
     * [EnterprisePolicy] and refuses malformed / unsupported-version documents
     * with 400, so operators learn about a bad policy at upload time.
     */
    private fun handlePolicy(exchange: HttpExchange) {
        try {
            if (!authorized(exchange)) return respond(exchange, 401, null)
            when (exchange.requestMethod) {
                "PUT" -> {
                    val bytes = exchange.requestBody.readBytes()
                    if (bytes.size > MAX_POLICY_BYTES) {
                        return respond(
                            exchange,
                            413,
                            "enterprise policy exceeds $MAX_POLICY_BYTES bytes"
                                .toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    val validationError = policyValidationError(bytes)
                    if (validationError != null) {
                        return respond(exchange, 400, validationError.toByteArray(StandardCharsets.UTF_8))
                    }
                    store.put(POLICY_ID, bytes)
                    respond(exchange, 204, null)
                }
                "GET" -> {
                    val body = store.get(POLICY_ID)
                    if (body == null) respond(exchange, 404, null)
                    else {
                        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                        respond(exchange, 200, body)
                    }
                }
                "DELETE" -> {
                    store.delete(POLICY_ID)
                    respond(exchange, 204, null)
                }
                else -> respond(exchange, 405, null)
            }
        } catch (e: IOException) {
            respond(exchange, 500, null)
        } finally {
            exchange.close()
        }
    }

    /**
     * Parse [bytes] as an enterprise policy document. Returns a human-readable
     * reason when the document is invalid, `null` when it is acceptable.
     */
    private fun policyValidationError(bytes: ByteArray): String? = try {
        EnterprisePolicy.parse(bytes.toString(StandardCharsets.UTF_8))
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
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
        } catch (e: IOException) {
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

    companion object {
        /** The blob-id slot holding the single enterprise policy document. */
        private const val POLICY_ID = "enterprise-policy"

        /** A policy document is a small JSON config — cap it far below the blob cap. */
        const val MAX_POLICY_BYTES: Long = 1L * 1024 * 1024
    }
}
