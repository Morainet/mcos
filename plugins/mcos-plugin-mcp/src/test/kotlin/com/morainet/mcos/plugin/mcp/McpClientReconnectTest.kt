package com.morainet.mcos.plugin.mcp

import com.morainet.mcos.sdk.HttpResponse
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * McpClient reconnect/backoff (10-roadmap.md §6.2). Only connection-level
 * faults — the request never reached the server — are retried; a server that
 * responded (any status, including 5xx) is not, so a non-idempotent
 * `tools/call` is never re-run against a server that may already have executed
 * it. Backoff base is 1ms here to keep the suite fast.
 */
class McpClientReconnectTest {

    @Test fun `RC1 a connection fault is retried and then succeeds`() = runBlocking {
        var calls = 0
        val net = FakeNetService {
            calls++
            if (calls < 3) throw IOException("connection refused")
            rpcOk("""{"tools":[]}""")
        }
        val client = McpClient(net, "https://mcp.example/rpc", maxConnectRetries = 2, backoffBaseMs = 1)
        val tools = client.listTools()
        assertTrue(tools.isEmpty())
        assertEquals(3, calls) // 1 initial + 2 retries, succeeding on the last
    }

    @Test fun `RC2 a connection fault past the retry budget surfaces retryable UNAVAILABLE`() = runBlocking {
        var calls = 0
        val net = FakeNetService {
            calls++
            throw IOException("connection refused")
        }
        val client = McpClient(net, "https://mcp.example/rpc", maxConnectRetries = 2, backoffBaseMs = 1)
        val e = assertFailsWith<McpException> { client.listTools() }
        assertEquals("UNAVAILABLE", e.code)
        assertTrue(e.retryable)
        assertEquals(3, calls) // exhausted: 1 + 2 retries
    }

    @Test fun `RC3 a 5xx response is not auto-retried`() = runBlocking {
        var calls = 0
        val net = FakeNetService {
            calls++
            HttpResponse(status = 503)
        }
        val client = McpClient(net, "https://mcp.example/rpc", maxConnectRetries = 2, backoffBaseMs = 1)
        val e = assertFailsWith<McpException> { client.listTools() }
        assertEquals("UNAVAILABLE", e.code)
        assertEquals(1, calls) // the server responded — no reconnect
    }

    @Test fun `RC4 retries are disabled when maxConnectRetries is zero`() = runBlocking {
        var calls = 0
        val net = FakeNetService {
            calls++
            throw IOException("connection refused")
        }
        val client = McpClient(net, "https://mcp.example/rpc", maxConnectRetries = 0, backoffBaseMs = 1)
        assertFailsWith<McpException> { client.listTools() }
        assertEquals(1, calls)
    }
}
