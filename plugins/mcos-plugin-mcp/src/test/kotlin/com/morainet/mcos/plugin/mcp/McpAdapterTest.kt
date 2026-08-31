package com.morainet.mcos.plugin.mcp

import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.HttpResponse
import com.morainet.mcos.sdk.SideEffectClass
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * McpClient + McpAdapter — discovery, schema synthesis, and proxy invoke over
 * a fake NetService. AD1-AD14. Per 04 §10, 02 §12.1/§12.4.
 */
class McpAdapterTest {

    private val config = McpServerConfig(id = "demo", endpoint = "https://mcp.example/rpc")

    /** A two-tool server: one mappable, one with an unmappable oneOf schema. */
    private fun twoToolListJson(): String = """
        {"tools":[
          {"name":"echo","description":"Echo text back",
           "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}},
          {"name":"weird",
           "inputSchema":{"type":"object","properties":{"x":{"oneOf":[{"type":"string"},{"type":"integer"}]}}}}
        ]}
    """.trimIndent()

    private fun routing(
        onList: () -> HttpResponse = { rpcOk("""{"tools":[]}""") },
        onCall: (RecordedRequest) -> HttpResponse = { rpcOk("""{"content":[],"isError":false}""") },
    ): FakeNetService = FakeNetService { req ->
        val method = Json.parseToJsonElement(req.body!!).jsonObject["method"]!!.jsonPrimitive.content
        when (method) {
            "tools/list" -> onList()
            "tools/call" -> onCall(req)
            else -> HttpResponse(404)
        }
    }

    // ─── Discovery ─────────────────────────────────────────────────────────

    @Test fun `AD1 discovery synthesizes namespaced command ids`() = runBlocking {
        val net = routing(onList = { rpcOk(twoToolListJson()) })
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        val ids = discovery.plugin.manifest.commands.map { it.id }
        assertEquals(listOf("mcp.demo.echo"), ids)
        assertEquals("mcos.plugin.mcp.demo", discovery.plugin.manifest.id)
    }

    @Test fun `AD2 unmappable tool is skipped, not registered`() = runBlocking {
        val net = routing(onList = { rpcOk(twoToolListJson()) })
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        assertEquals(1, discovery.plugin.manifest.commands.size)
        assertEquals(1, discovery.skipped.size)
        assertEquals("weird", discovery.skipped.first().toolName)
        assertEquals("oneOf", discovery.skipped.first().unmappedType)
    }

    @Test fun `AD3 synthesized command carries the converted inputSchema`() = runBlocking {
        val net = routing(onList = { rpcOk(twoToolListJson()) })
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        val schema = discovery.plugin.manifest.commands.first().inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        val textProp = schema["properties"]!!.jsonObject["text"]!!.jsonObject
        assertEquals("string", textProp["type"]?.jsonPrimitive?.content)
        // The converter capped the string; the raw MCP schema had no maxLength.
        assertTrue(textProp.containsKey("maxLength"))
    }

    @Test fun `AD4 every MCP command defaults to network side-effect class`() = runBlocking {
        val net = routing(onList = { rpcOk(twoToolListJson()) })
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        assertEquals(SideEffectClass.network, discovery.plugin.manifest.commands.first().sideEffectClass)
    }

    @Test fun `AD5 destructiveHint upgrades to destructive`() = runBlocking {
        val listJson = """
            {"tools":[{"name":"rm","inputSchema":{"type":"object"},
             "annotations":{"destructiveHint":true}}]}
        """.trimIndent()
        val net = routing(onList = { rpcOk(listJson) })
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        assertEquals(SideEffectClass.destructive, discovery.plugin.manifest.commands.first().sideEffectClass)
    }

    @Test fun `AD6 tool names are sanitized into command-id-safe segments`() = runBlocking {
        val listJson = """{"tools":[{"name":"read-file","inputSchema":{"type":"object"}}]}"""
        val net = routing(onList = { rpcOk(listJson) })
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        assertEquals("mcp.demo.read_file", discovery.plugin.manifest.commands.first().id)
    }

    // ─── Proxy invoke ────────────────────────────────────────────────────────

    @Test fun `AD7 proxy invoke round-trips tool content into an Ok result`() = runBlocking {
        val net = routing(
            onList = { rpcOk(twoToolListJson()) },
            onCall = { rpcOk("""{"content":[{"type":"text","text":"hi"}],"isError":false}""") },
        )
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        val handler = discovery.plugin.handlers()["mcp.demo.echo"]!!
        val result = handler.invoke(
            execContext("mcp.demo.echo", buildJsonObject { put("text", "hi") }, net),
        )
        assertTrue(result is CommandResult.Ok)
        val text = (result as CommandResult.Ok).value.jsonObject["content"]!!
            .let { Json.encodeToString(JsonElement.serializer(), it) }
        assertTrue(text.contains("hi"))
    }

    @Test fun `AD8 proxy forwards the original tool name and args to tools call`() = runBlocking {
        val net = routing(onList = { rpcOk("""{"tools":[{"name":"read-file","inputSchema":{"type":"object"}}]}""") })
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        discovery.plugin.handlers()["mcp.demo.read_file"]!!.invoke(
            execContext("mcp.demo.read_file", buildJsonObject { put("path", "/tmp/x") }, net),
        )
        val callBody = Json.parseToJsonElement(net.requests.last().body!!).jsonObject
        val params = callBody["params"]!!.jsonObject
        assertEquals("read-file", params["name"]?.jsonPrimitive?.content)
        assertEquals("/tmp/x", params["arguments"]!!.jsonObject["path"]?.jsonPrimitive?.content)
    }

    @Test fun `AD9 isError result maps to a CommandResult Err`() = runBlocking {
        val net = routing(
            onList = { rpcOk("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") },
            onCall = { rpcOk("""{"content":[{"type":"text","text":"boom"}],"isError":true}""") },
        )
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        val result = discovery.plugin.handlers()["mcp.demo.echo"]!!.invoke(
            execContext("mcp.demo.echo", JsonObject(emptyMap()), net),
        )
        assertTrue(result is CommandResult.Err)
        assertEquals("PLUGIN_ERROR", (result as CommandResult.Err).code)
    }

    @Test fun `AD10 HTTP 401 on a tool call maps to PERMISSION_DENIED`() = runBlocking {
        val net = routing(
            onList = { rpcOk("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") },
            onCall = { HttpResponse(401) },
        )
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        val result = discovery.plugin.handlers()["mcp.demo.echo"]!!.invoke(
            execContext("mcp.demo.echo", JsonObject(emptyMap()), net),
        )
        assertEquals("PERMISSION_DENIED", (result as CommandResult.Err).code)
    }

    @Test fun `AD11 a connection fault maps to a retryable UNAVAILABLE`() = runBlocking {
        val net = routing(
            onList = { rpcOk("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") },
            onCall = { throw IOException("connection refused") },
        )
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        val result = discovery.plugin.handlers()["mcp.demo.echo"]!!.invoke(
            execContext("mcp.demo.echo", JsonObject(emptyMap()), net),
        ) as CommandResult.Err
        assertEquals("UNAVAILABLE", result.code)
        assertTrue(result.retryable)
    }

    @Test fun `AD12 JSON-RPC invalid-params maps to SCHEMA_VIOLATION`() = runBlocking {
        val net = routing(
            onList = { rpcOk("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") },
            onCall = {
                val err = """{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"bad args"}}"""
                HttpResponse(200, body = err.encodeToByteArray())
            },
        )
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        val result = discovery.plugin.handlers()["mcp.demo.echo"]!!.invoke(
            execContext("mcp.demo.echo", JsonObject(emptyMap()), net),
        ) as CommandResult.Err
        assertEquals("SCHEMA_VIOLATION", result.code)
    }

    // ─── Client / transport ──────────────────────────────────────────────────

    @Test fun `AD13 the token is sent as a bearer header`() = runBlocking {
        val net = routing(onList = { rpcOk("""{"tools":[]}""") })
        McpAdapter.discover(net, config.copy(token = "s3cr3t"))
        assertEquals("Bearer s3cr3t", net.requests.first().headers["Authorization"])
    }

    @Test fun `AD14 an empty tool list yields a plugin with no commands`() = runBlocking {
        val net = routing(onList = { rpcOk("""{"tools":[]}""") })
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config)
        assertTrue(discovery.plugin.manifest.commands.isEmpty())
        assertNull(discovery.plugin.handlers()["mcp.demo.anything"])
    }

    // ─── Per-server secrets (P3, 04 §11.1 / 08 §9.2) ───────────────────────────

    @Test fun `AD15 secretKey yields a secret-template bearer header on tool calls`() = runBlocking {
        val net = routing(onList = { rpcOk("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") })
        val cfg = config.copy(secretKey = "mcp.secret.demo")
        // The handler carries the template, not the secret; the executor's
        // Stage-4 net decorator resolves it per call. Here the fake net records
        // the literal template, proving it reaches the resolution boundary.
        val discovery = McpAdapter.discover(McpClient(net, cfg.endpoint), cfg)
        discovery.plugin.handlers()["mcp.demo.echo"]!!.invoke(
            execContext("mcp.demo.echo", JsonObject(emptyMap()), net),
        )
        assertEquals("Bearer {{secret.mcp.secret.demo}}", net.requests.last().headers["Authorization"])
    }

    @Test fun `AD16 discovery resolves secretKey via the lookup for tools list auth`() = runBlocking {
        val net = routing(onList = { rpcOk("""{"tools":[]}""") })
        val cfg = config.copy(secretKey = "k1")
        McpAdapter.discover(net, cfg, secretLookup = { key -> if (key == "k1") "resolved-token" else null })
        // tools/list ran outside the executor, so the concrete token is used.
        assertEquals("Bearer resolved-token", net.requests.first().headers["Authorization"])
    }

    @Test fun `AD17 secretKey takes precedence over an inline token`() = runBlocking {
        val net = routing(onList = { rpcOk("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") })
        val cfg = config.copy(token = "inline", secretKey = "k1")
        val discovery = McpAdapter.discover(McpClient(net, cfg.endpoint), cfg)
        discovery.plugin.handlers()["mcp.demo.echo"]!!.invoke(
            execContext("mcp.demo.echo", JsonObject(emptyMap()), net),
        )
        assertEquals("Bearer {{secret.k1}}", net.requests.last().headers["Authorization"])
    }

    // ─── Circuit breaker (04 §10 connection management) ────────────────────────

    @Test fun `AD18 an open circuit fast-fails without a network request`() = runBlocking {
        val net = routing(onList = { rpcOk("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") })
        val breaker = McpCircuitBreaker(failureThreshold = 1, cooldownMs = 60_000)
        val discovery = McpAdapter.discover(McpClient(net, config.endpoint), config, breaker)
        breaker.recordFailure() // trips the circuit (threshold 1)
        val before = net.requests.size
        val result = discovery.plugin.handlers()["mcp.demo.echo"]!!.invoke(
            execContext("mcp.demo.echo", JsonObject(emptyMap()), net),
        ) as CommandResult.Err
        assertEquals("UNAVAILABLE", result.code)
        assertTrue(result.retryable)
        assertEquals(before, net.requests.size) // the network was never touched
    }

    @Test fun `AD19 a retryable failure through the handler trips the shared breaker`() = runBlocking {
        val net = routing(
            onList = { rpcOk("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") },
            onCall = { throw IOException("connection refused") },
        )
        val breaker = McpCircuitBreaker(failureThreshold = 1, cooldownMs = 60_000)
        val handler = McpAdapter.discover(McpClient(net, config.endpoint), config, breaker)
            .plugin.handlers()["mcp.demo.echo"]!!
        // First call reaches the server (and its retries) then fails → trips.
        handler.invoke(execContext("mcp.demo.echo", JsonObject(emptyMap()), net))
        val after = net.requests.size
        // Second call is short-circuited: no further network traffic.
        val result = handler.invoke(
            execContext("mcp.demo.echo", JsonObject(emptyMap()), net),
        ) as CommandResult.Err
        assertEquals("UNAVAILABLE", result.code)
        assertEquals(after, net.requests.size)
    }
}
