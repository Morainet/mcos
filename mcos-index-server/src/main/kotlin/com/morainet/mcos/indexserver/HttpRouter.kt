package com.morainet.mcos.indexserver

import com.sun.net.httpserver.HttpExchange
import java.nio.charset.Charset

/**
 * Minimal path-param router over `com.sun.net.httpserver` (the same
 * zero-dependency posture as `mcos-server`).
 *
 * Route patterns look like `/v1/plugins/{packageId}/versions/{version}`.
 */
class HttpRouter {
    private data class Route(
        val method: String,
        val regex: Regex,
        val paramNames: List<String>,
        val handler: Handler,
    )

    fun interface Handler {
        fun handle(exchange: HttpExchange, params: Map<String, String>)
    }

    private val routes = mutableListOf<Route>()

    fun get(pattern: String, handler: Handler) = add("GET", pattern, handler)

    fun post(pattern: String, handler: Handler) = add("POST", pattern, handler)

    fun delete(pattern: String, handler: Handler) = add("DELETE", pattern, handler)

    fun add(method: String, pattern: String, handler: Handler) {
        val paramNames = mutableListOf<String>()
        val regex = Regex(
            "^" +
                pattern.split('/').joinToString("/") { segment ->
                    if (segment.startsWith("{") && segment.endsWith("}")) {
                        paramNames += segment.substring(1, segment.length - 1)
                        "([^/]+)"
                    } else {
                        Regex.escape(segment)
                    }
                } +
                "$",
        )
        routes += Route(method, regex, paramNames, handler)
    }

    /** Dispatches one exchange; returns true when a route matched. */
    fun dispatch(exchange: HttpExchange): Boolean {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        for (route in routes) {
            if (route.method != method) continue
            val match = route.regex.matchEntire(path) ?: continue
            val params = route.paramNames.zip(match.groupValues.drop(1)).toMap()
            route.handler.handle(exchange, params)
            return true
        }
        return false
    }
}

fun HttpExchange.sendBytes(status: Int, bytes: ByteArray, contentType: String) {
    if (!responseHeaders.contains("Content-Type")) {
        responseHeaders.set("Content-Type", contentType)
    }
    responseHeaders.set("Cache-Control", "no-store")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

/** A parsed `multipart/form-data` part. */
data class FormPart(
    val name: String,
    val fileName: String? = null,
    val value: ByteArray,
) {
    fun text(): String = value.toString(Charsets.UTF_8)
}

/**
 * Minimal `multipart/form-data` parser (used by the publisher submit endpoint;
 * 12-index-server.md §5.2). Reads the whole body into memory — fine for the
 * MVP's small artifacts; a CDN upload path is a V1+ concern.
 */
object Multipart {
    fun parse(body: ByteArray, contentType: String): Map<String, FormPart> {
        val boundary = extractBoundary(contentType)
            ?: throw ApiException(400, "SCHEMA_VIOLATION", "multipart/form-data requires a boundary")
        val marker = ("--$boundary").toByteArray(Charsets.ISO_8859_1)
        val parts = mutableMapOf<String, FormPart>()
        // Scan part by part. Every marker starts a new part's headers — the
        // marker we found as "end" of part N is also the "start" of part N+1
        // (each delimiter line closes one part and opens the next), so we
        // resume scanning AT that marker rather than after it.
        var searchFrom = 0
        while (true) {
            val partStart = indexOf(body, marker, searchFrom)
            if (partStart < 0) break
            val afterMarker = partStart + marker.size
            // Epilogue `--boundary--`: no more parts.
            if (startsWith(body, afterMarker, "--")) break
            // Content begins after the boundary line's CRLF.
            var contentStart = afterMarker
            if (isCrlf(body, contentStart)) contentStart += 2
            val partEnd = indexOf(body, marker, contentStart)
            if (partEnd < 0) break

            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), contentStart)
            if (headerEnd < 0 || headerEnd > partEnd) {
                // Malformed part (no header terminator): skip to the next marker.
                searchFrom = partEnd
                continue
            }
            val headers = String(body.copyOfRange(contentStart, headerEnd), Charsets.ISO_8859_1)
            val contentBase = headerEnd + 4
            var contentEnd = partEnd
            // Trim ONE trailing CRLF written before the next boundary.
            if (isCrlf(body, contentEnd - 2)) contentEnd -= 2
            if (contentEnd < contentBase) contentEnd = contentBase

            val tokens = headers.split(';').map { it.trim() }
            val name = tokens.firstOrNull { it.startsWith("name=") }
                ?.removePrefix("name=")?.trim('"')
            if (name != null) {
                val fileName = tokens.firstOrNull { it.startsWith("filename=") }
                    ?.removePrefix("filename=")?.trim('"')
                parts[name] = FormPart(name, fileName, body.copyOfRange(contentBase, contentEnd))
            }
            searchFrom = partEnd
        }
        return parts
    }

    private fun startsWith(haystack: ByteArray, offset: Int, needle: String): Boolean {
        val bytes = needle.toByteArray(Charsets.ISO_8859_1)
        if (offset < 0 || offset + bytes.size > haystack.size) return false
        for (i in bytes.indices) if (haystack[offset + i] != bytes[i]) return false
        return true
    }

    private fun isCrlf(bytes: ByteArray, at: Int): Boolean =
        at >= 0 && at + 1 < bytes.size && bytes[at].toInt() == '\r'.code && bytes[at + 1].toInt() == '\n'.code

    private fun extractBoundary(contentType: String): String? {
        return contentType.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("boundary=") }
            ?.removePrefix("boundary=")?.trim('"')
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
