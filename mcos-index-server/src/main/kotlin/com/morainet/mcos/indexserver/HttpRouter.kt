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
        val boundaryBytes = ("--$boundary").toByteArray(Charsets.ISO_8859_1)
        val parts = mutableMapOf<String, FormPart>()
        var cursor = 0
        val needle = boundaryBytes
        while (true) {
            val next = indexOf(body, needle, cursor)
            if (next < 0) break
            var start = next + needle.size
            if (start + 1 < body.size && body[start].toInt() == '\r'.code && body[start + 1].toInt() == '\n'.code) {
                start += 2
            }
            val end = indexOf(body, needle, start)
            if (end < 0) break

            // Header block ends at CRLF CRLF.
            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), start)
            if (headerEnd < 0 || headerEnd > end) {
                cursor = end + 1
                continue
            }
            val headers = String(body.copyOfRange(start, headerEnd), Charsets.ISO_8859_1)
            val contentStart = headerEnd + 4
            var contentEnd = end
            // Trim the trailing CRLF before the boundary.
            if (contentEnd >= 2 &&
                body[contentEnd - 2].toInt() == '\r'.code &&
                body[contentEnd - 1].toInt() == '\n'.code
            ) {
                contentEnd -= 2
            }
            val name = headers.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("name=") }
                ?.removePrefix("name=")?.trim('"')
                ?.let { it }
            if (name != null) {
                val fileName = headers.split(';').map { it.trim() }
                    .firstOrNull { it.startsWith("filename=") }
                    ?.removePrefix("filename=")?.trim('"')
                parts[name] = FormPart(name, fileName, body.copyOfRange(contentStart, contentEnd))
            }
            cursor = end + 1
        }
        return parts
    }

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
