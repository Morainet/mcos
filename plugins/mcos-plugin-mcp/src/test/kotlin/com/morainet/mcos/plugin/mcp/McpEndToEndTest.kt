package com.morainet.mcos.plugin.mcp

import com.morainet.mcos.sdk.CommandResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end: the adapter → client → JDK HTTP transport → a live in-process
 * reference MCP server. Proves the JSON-RPC-over-HTTP path works against a real
 * socket, not just a fake NetService. E1-E5.
 */
class McpEndToEndTest {

    private var server: ReferenceMcpServer? = null

    @AfterTest fun tearDown() {
        server?.stop()
    }

    private val toolsJson = """
        {"tools":[
          {"name":"echo","description":"Echo","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}},
          {"name":"weird","inputSchema":{"type":"object","properties":{"x":{"anyOf":[{"type":"string"}]}}}}
        ]}
    """.trimIndent()

    private fun start(token: String? = null): ReferenceMcpServer =
        ReferenceMcpServer(toolsJson, requiredToken = token).also { it.start(); server = it }

    @Test fun `E1 discovery over real HTTP registers the mappable tool`() = runBlocking {
        val srv = start()
        val config = McpServerConfig(id = "demo", endpoint = srv.endpoint)
        val discovery = McpAdapter.discover(JdkNetService(), config)
        assertEquals(listOf("mcp.demo.echo"), discovery.plugin.manifest.commands.map { it.id })
    }

    @Test fun `E2 the unmappable tool is skipped over real HTTP`() = runBlocking {
        val srv = start()
        val discovery = McpAdapter.discover(JdkNetService(), McpServerConfig("demo", srv.endpoint))
        assertEquals(1, discovery.skipped.size)
        assertEquals("anyOf", discovery.skipped.first().unmappedType)
    }

    @Test fun `E3 proxy invoke round-trips through the live server`() = runBlocking {
        val srv = start()
        val net = JdkNetService()
        val discovery = McpAdapter.discover(net, McpServerConfig("demo", srv.endpoint))
        val result = discovery.plugin.handlers()["mcp.demo.echo"]!!.invoke(
            execContext("mcp.demo.echo", buildJsonObject { put("text", "hello") }, net),
        )
        assertTrue(result is CommandResult.Ok)
        val content = (result as CommandResult.Ok).value.jsonObject["content"].toString()
        assertTrue(content.contains("called echo"), "server echoed the tool name: $content")
        // The server logged the exact tool name + arguments it received.
        val logged = srv.callLog.single()
        assertEquals("echo", logged["name"]?.jsonPrimitive?.content)
        assertEquals("hello", (logged["arguments"] as JsonObject)["text"]?.jsonPrimitive?.content)
    }

    @Test fun `E4 a valid bearer token authenticates end to end`() = runBlocking {
        val srv = start(token = "s3cr3t")
        val net = JdkNetService()
        val discovery = McpAdapter.discover(net, McpServerConfig("demo", srv.endpoint, token = "s3cr3t"))
        assertEquals(1, discovery.plugin.manifest.commands.size)
    }

    @Test fun `E5 a missing token surfaces as PERMISSION_DENIED`() = runBlocking {
        val srv = start(token = "s3cr3t")
        val net = JdkNetService()
        // Discovery itself hits tools/list unauthenticated → the client raises
        // an McpException the adapter lets propagate; assert the mapped code.
        val err = try {
            McpAdapter.discover(net, McpServerConfig("demo", srv.endpoint))
            null
        } catch (e: McpException) {
            e
        }
        assertEquals("PERMISSION_DENIED", err?.code)
    }
}
