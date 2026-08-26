package com.morainet.mcos.plugin.mcp

import com.morainet.mcos.sdk.NetService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/** A tool advertised by an MCP server (`tools/list`). */
data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
    /** Optional MCP annotations (`readOnlyHint` / `destructiveHint`, …). */
    val annotations: JsonObject?,
)

/**
 * A transport / protocol failure talking to an MCP server. [code] is a
 * runtime error code the adapter maps onto a `CommandResult`; connection and
 * 5xx faults are [retryable] so the fallback/quarantine logic can react.
 */
class McpException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
) : RuntimeException(message)

/**
 * Minimal JSON-RPC 2.0 client for a single MCP server over HTTP, routed
 * through the host [NetService] so the runtime's egress policy, proxy and
 * enterprise domain rules apply uniformly ([04-plugin-sdk.md §10],
 * [02-command-protocol.md §12.1]).
 *
 * This is the P2 *bridge spike* transport: request/response JSON-RPC over a
 * single POST endpoint (no SSE stream, no session resumption). The spike
 * scope guardrails are in [10-roadmap.md §5.7].
 */
class McpClient(
    private val net: NetService,
    private val endpoint: String,
    private val headers: Map<String, String> = emptyMap(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val idGen = AtomicLong(0)

    /** Enumerate the server's tools. */
    suspend fun listTools(): List<McpTool> {
        val result = rpc("tools/list", JsonObject(emptyMap()))
        val tools = result["tools"]?.jsonArray ?: return emptyList()
        return tools.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpTool(
                name = name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                inputSchema = obj["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                annotations = obj["annotations"] as? JsonObject,
            )
        }
    }

    /**
     * Invoke a tool. Returns the JSON-RPC `result` object (the caller reads
     * `content` / `isError`). Protocol-level faults throw [McpException].
     */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        return rpc("tools/call", params)
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = idGen.incrementAndGet()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = try {
            net.request(
                method = "POST",
                url = endpoint,
                body = json.encodeToString(JsonElement.serializer(), payload),
                headers = headers + mapOf("Content-Type" to "application/json"),
            )
        } catch (e: Exception) {
            // A thrown request is a connection-level fault — the device could
            // not reach the server. Retryable so the adapter can re-arm later.
            throw McpException("UNAVAILABLE", "MCP server unreachable: ${e.message}", retryable = true)
        }

        when (response.status) {
            in 200..299 -> Unit
            401, 403 -> throw McpException(
                "PERMISSION_DENIED", "MCP server rejected credentials (HTTP ${response.status})",
            )
            in 500..599 -> throw McpException(
                "UNAVAILABLE", "MCP server error (HTTP ${response.status})", retryable = true,
            )
            else -> throw McpException("PLUGIN_ERROR", "MCP HTTP ${response.status}")
        }

        val body = response.body
            ?: throw McpException("PLUGIN_ERROR", "empty MCP response body")
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw McpException("PLUGIN_ERROR", "malformed MCP JSON-RPC response")
        }

        (root["error"] as? JsonObject)?.let { err ->
            val code = err["code"]?.jsonPrimitive?.intOrNull
            val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error"
            // JSON-RPC "invalid params" (-32602) is the server rejecting our
            // arguments — a schema violation on our side, not a server fault.
            val mapped = if (code == -32602) "SCHEMA_VIOLATION" else "PLUGIN_ERROR"
            throw McpException(mapped, "MCP error ${code ?: ""}: $msg")
        }

        return root["result"] as? JsonObject
            ?: throw McpException("PLUGIN_ERROR", "MCP response missing result")
    }
}
