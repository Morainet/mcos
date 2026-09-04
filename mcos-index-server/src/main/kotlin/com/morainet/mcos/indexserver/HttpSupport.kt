package com.morainet.mcos.indexserver

import com.sun.net.httpserver.HttpExchange
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * Small request/response helpers shared by the endpoint handlers.
 * Error responses use the 09 §11.4 envelope:
 *
 * ```json
 * { "error": { "code": "NOT_FOUND", "message": "…" } }
 * ```
 *
 * @property code stable machine code (SCHEMA_VIOLATION / UNAUTHENTICATED /
 *   PERMISSION_DENIED / NOT_FOUND / ALREADY_EXISTS / RATE_LIMITED / INTERNAL).
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
) : Exception(message)

fun HttpExchange.queryParams(): Map<String, String> {
    val query = requestURI.query ?: return emptyMap()
    return query.split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) pair to "" else pair.substring(0, idx) to URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
        }
        .toMap()
}

fun HttpExchange.path(): String = requestURI.path.removePrefix("/")

fun HttpExchange.bearerToken(): String? {
    val header = requestHeaders.getFirst("Authorization") ?: return null
    if (!header.startsWith("Bearer ")) return null
    return header.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
}

fun HttpExchange.readBody(): ByteArray = requestBody.readBytes()

fun HttpExchange.send(status: Int, body: String, contentType: String = "application/json") {
    val bytes = body.toByteArray(Charsets.UTF_8)
    if (!responseHeaders.contains("Content-Type")) {
        responseHeaders.set("Content-Type", contentType)
    }
    if (!responseHeaders.contains("Cache-Control")) {
        responseHeaders.set("Cache-Control", "no-store")
    }
    sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
    try {
        if (bytes.isNotEmpty()) {
            responseBody.use { it.write(bytes) }
        }
    } catch (_: IOException) {
        // client went away; nothing to do
    }
}

fun HttpExchange.sendError(status: Int, code: String, message: String) {
    val body =
        """{"error":{"code":"${escapeJson(code)}","message":"${escapeJson(message)}"}}"""
    send(status, body)
}

private fun escapeJson(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

// ── Auth / digest primitives ────────────────────────────────────────────────

/**
 * Storage digest of a publisher token: the server never stores the plaintext
 * token, only this SHA-256 digest, and compares digests constant-time
 * (12-index-server.md §4). Token entropy (server-issued, ≥32 bytes) makes the
 * digest the right storage form here.
 */
fun tokenDigest(token: String): String = sha256Hex(token.toByteArray(Charsets.UTF_8))

fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
    a.toByteArray(Charsets.UTF_8),
    b.toByteArray(Charsets.UTF_8),
)

fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

fun base64Decode(value: String): ByteArray = Base64.getDecoder().decode(value)

/** Reads a hex sha-256 denylist file, one digest per line (comments start with `#`). */
fun loadSha256Denylist(file: java.nio.file.Path): Set<String> {
    if (!java.nio.file.Files.exists(file)) return emptySet()
    return java.nio.file.Files.readAllLines(file)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        .toSet()
}
