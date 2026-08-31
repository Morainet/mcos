package com.morainet.mcos.plugin.mcp

import com.morainet.mcos.sdk.HttpRequest
import com.morainet.mcos.sdk.HttpResponse
import com.morainet.mcos.sdk.NetService
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse
import java.time.Duration

/**
 * A throwaway in-process MCP server speaking the minimal JSON-RPC subset the
 * spike uses (`tools/list`, `tools/call`) over a single POST endpoint. Used by
 * the A3 E2E to exercise the real HTTP path — the plugin, the client and the
 * transport — end to end. Not production code: no SSE, no sessions.
 */
class ReferenceMcpServer(
    /** Raw `tools/list` result payload (the `{"tools":[...]}` object). */
    private val toolsListJson: String,
    /** When non-null, requests must carry `Authorization: Bearer <token>`. */
    private val requiredToken: String? = null,
) {
    private lateinit var server: HttpServer
    val callLog = mutableListOf<JsonObject>()

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}/rpc"

    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rpc") { handle(it) }
        server.executor = null
        server.start()
    }

    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (requiredToken != null) {
                val auth = exchange.requestHeaders.getFirst("Authorization")
                if (auth != "Bearer $requiredToken") {
                    respond(exchange, 401, """{"error":"unauthorized"}""")
                    return
                }
            }
            val body = exchange.requestBody.readBytes().decodeToString()
            val root = Json.parseToJsonElement(body).jsonObject
            val id = root["id"]?.jsonPrimitive?.contentOrNull ?: "1"
            val method = root["method"]?.jsonPrimitive?.contentOrNull
            val params = root["params"] as? JsonObject

            when (method) {
                "tools/list" -> respond(exchange, 200, jsonRpc(id, toolsListJson))
                "tools/call" -> {
                    params?.let { callLog += it }
                    val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull ?: "?"
                    val args = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
                    val text = "called $toolName with ${escape(args.toString())}"
                    respond(
                        exchange, 200,
                        jsonRpc(id, """{"content":[{"type":"text","text":"$text"}],"isError":false}"""),
                    )
                }
                else -> respond(
                    exchange, 200,
                    """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"method not found"}}""",
                )
            }
        } catch (e: Exception) {
            respond(exchange, 500, """{"error":"${escape(e.message ?: "internal")}"}""")
        }
    }

    private fun jsonRpc(id: String, resultJson: String): String =
        """{"jsonrpc":"2.0","id":$id,"result":$resultJson}"""

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * A real HTTP [NetService] backed by the JDK client — the transport the E2E
 * runs the adapter through (Android injects an HttpURLConnection variant, same
 * pattern as the LLM/marketplace transports).
 */
class JdkNetService : NetService {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override suspend fun request(req: HttpRequest): HttpResponse {
        val builder = JdkHttpRequest.newBuilder(URI.create(req.url)).timeout(Duration.ofSeconds(5))
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        when (req.method.uppercase()) {
            "POST" -> builder.POST(JdkHttpRequest.BodyPublishers.ofByteArray(req.body ?: ByteArray(0)))
            else -> builder.GET()
        }
        val resp = http.send(builder.build(), JdkHttpResponse.BodyHandlers.ofByteArray())
        return HttpResponse(status = resp.statusCode(), body = resp.body())
    }
}
