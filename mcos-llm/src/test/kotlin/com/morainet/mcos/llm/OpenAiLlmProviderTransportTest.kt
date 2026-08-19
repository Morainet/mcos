package com.morainet.mcos.llm

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/**
 * Verifies the pluggable [LlmHttpTransport] abstraction:
 * - [OpenAiLlmProvider] maps transport responses/failures to typed errors;
 * - the JVM default [JdkLlmHttpTransport] performs a real HTTP round-trip
 *   with the expected Authorization header and JSON body.
 */
class OpenAiLlmProviderTransportTest {

    private val config = LlmConfig(apiKey = "test-key-123")

    // ---- Provider <-> transport contract --------------------------------

    @Test
    fun `chat maps transport 200 to Ok`() = runBlocking {
        val transport = StubTransport(
            responses = listOf(
                HttpTransportResponse(200, """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""")
            )
        )
        val provider = OpenAiLlmProvider(config, transport)

        val result = provider.chat(listOf(ChatMessage("user", "hi")))

        assertIs<LlmResponse.Ok>(result)
        assertEquals("hello", result.content)
    }

    @Test
    fun `chat maps non-200 to LLM_API_ERROR and 5xx is retryable`() = runBlocking {
        val transport = StubTransport(
            responses = listOf(
                HttpTransportResponse(429, """{"error":{"message":"rate limited"}}""")
            )
        )
        val provider = OpenAiLlmProvider(config, transport)

        val result = provider.chat(listOf(ChatMessage("user", "hi")))

        assertIs<LlmResponse.Err>(result)
        assertEquals("LLM_API_ERROR", result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun `chat maps LlmTransportException to typed error`() = runBlocking {
        val transport = ThrowingTransport(LlmTransportException("LLM_TIMEOUT", "LLM request timed out", true))
        val provider = OpenAiLlmProvider(config, transport)

        val result = provider.chat(listOf(ChatMessage("user", "hi")))

        assertIs<LlmResponse.Err>(result)
        assertEquals("LLM_TIMEOUT", result.code)
        assertTrue(result.retryable)
    }

    @Test
    fun `chat maps ConnectException to LLM_CONNECT_ERROR`() = runBlocking {
        val transport = ThrowingTransport(ConnectException("connection refused"))
        val provider = OpenAiLlmProvider(config, transport)

        val result = provider.chat(listOf(ChatMessage("user", "hi")))

        assertIs<LlmResponse.Err>(result)
        assertEquals("LLM_CONNECT_ERROR", result.code)
        assertTrue(result.retryable)
    }

    @Test
    fun `chat maps IOException to LLM_NETWORK_ERROR`() = runBlocking {
        val transport = ThrowingTransport(IOException("socket reset"))
        val provider = OpenAiLlmProvider(config, transport)

        val result = provider.chat(listOf(ChatMessage("user", "hi")))

        assertIs<LlmResponse.Err>(result)
        assertEquals("LLM_NETWORK_ERROR", result.code)
    }

    @Test
    fun `toolCall forwards transport errors`() = runBlocking {
        val transport = ThrowingTransport(LlmTransportException("LLM_TIMEOUT", "timed out", true))
        val provider = OpenAiLlmProvider(config, transport)

        val result = provider.toolCall(
            messages = listOf(ChatMessage("user", "hi")),
            tools = listOf(ToolDescriptor("cmd", "desc", JsonObject(emptyMap()))),
        )

        assertIs<ToolCallResponse.Err>(result)
        assertEquals("LLM_TIMEOUT", result.code)
    }

    // ---- Real HTTP round-trip through JdkLlmHttpTransport ---------------

    @Test
    fun `JdkLlmHttpTransport posts JSON with bearer auth`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedAuth: String? = null
        var receivedBody: String? = null
        server.createContext("/v1/chat/completions") { exchange ->
            receivedAuth = exchange.requestHeaders.getFirst("Authorization")
            receivedBody = exchange.requestBody.bufferedReader().readText()
            val payload = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(payload.toByteArray()) }
            exchange.close()
        }
        server.start()
        try {
            val transport = JdkLlmHttpTransport()
            val response = transport.postJson(
                url = "http://127.0.0.1:${server.address.port}/v1/chat/completions",
                apiKey = "test-key-123",
                body = """{"model":"gpt-4o-mini","messages":[]}""",
                connectTimeoutMs = 5_000,
                requestTimeoutMs = 5_000,
            )

            assertEquals(200, response.statusCode)
            assertEquals("Bearer test-key-123", receivedAuth)
            assertTrue(receivedBody.orEmpty().contains("gpt-4o-mini"))
            assertTrue(response.body.contains("choices"))
        } finally {
            server.stop(0)
        }
    }

    // ---- Test doubles ----------------------------------------------------

    private class StubTransport(
        private val responses: List<HttpTransportResponse>,
    ) : LlmHttpTransport {
        override suspend fun postJson(
            url: String,
            apiKey: String,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): HttpTransportResponse = responses.first()
    }

    private class ThrowingTransport(
        private val error: Throwable,
    ) : LlmHttpTransport {
        override suspend fun postJson(
            url: String,
            apiKey: String,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): HttpTransportResponse = throw error
    }
}
