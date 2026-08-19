package com.morainet.mcos.llm

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GrammarLlmProvider]: real grammar-constrained decoding through
 * llama.cpp `grammar` and vLLM / Outlines `guided_*` request fields
 * (06 §3.2 V2).
 */
class GrammarLlmProviderTest {

    private val config = LlmConfig(apiKey = "test-key-123")

    // ---- P1-P3: grammar injection fields -----------------------------------

    @Test
    fun `P1-GBNF grammar is sent in the llama-cpp grammar field`() = runBlocking {
        val transport = RecordingTransport()
        val provider = GrammarLlmProvider(
            injection = GrammarInjection.GBNF_GRAMMAR_FIELD,
            config = config,
            transport = transport,
        )

        val result = provider.constrainedChat(
            messages = listOf(ChatMessage("user", "say hi")),
            grammar = LlmGrammar.gbnf("root ::= ws \"hi\" ws"),
        )

        assertIs<LlmResponse.Ok>(result)
        val body = transport.lastBody.orEmpty()
        assertTrue(body.contains("\"grammar\":\"root ::= ws \\\"hi\\\" ws\""))
        assertFalse(body.contains("response_format"))
    }

    @Test
    fun `P2-GBNF grammar is sent in the vLLM guided_grammar field`() = runBlocking {
        val transport = RecordingTransport()
        val provider = GrammarLlmProvider(
            injection = GrammarInjection.VLLM_GUIDED_GRAMMAR,
            config = config,
            transport = transport,
        )

        provider.constrainedChat(
            messages = listOf(ChatMessage("user", "say hi")),
            grammar = LlmGrammar.gbnf("root ::= ws \"hi\" ws"),
        )

        assertTrue(transport.lastBody.orEmpty().contains("\"guided_grammar\":\"root ::= ws \\\"hi\\\" ws\""))
    }

    @Test
    fun `P3-JSON Schema is sent in the vLLM guided_json field`() = runBlocking {
        val transport = RecordingTransport()
        val provider = GrammarLlmProvider(
            injection = GrammarInjection.VLLM_GUIDED_JSON,
            config = config,
            transport = transport,
        )

        provider.constrainedChat(
            messages = listOf(ChatMessage("user", "say hi")),
            grammar = LlmGrammar.jsonSchema("""{"type":"object"}"""),
        )

        assertTrue(transport.lastBody.orEmpty().contains("\"guided_json\":\"{\\\"type\\\":\\\"object\\\"}\""))
    }

    // ---- P4: format mismatch ------------------------------------------------

    @Test
    fun `P4-format mismatch is CAPABILITY_EXCEEDED and never hits the wire`() = runBlocking {
        val transport = RecordingTransport()
        val provider = GrammarLlmProvider(
            injection = GrammarInjection.GBNF_GRAMMAR_FIELD,
            config = config,
            transport = transport,
        )

        val result = provider.constrainedChat(
            messages = listOf(ChatMessage("user", "say hi")),
            grammar = LlmGrammar.jsonSchema("""{"type":"object"}"""),
        )

        assertIs<LlmResponse.Err>(result)
        assertEquals(LlmErrorCode.CAPABILITY_EXCEEDED, result.code)
        assertFalse(result.retryable)
        assertNull(transport.lastBody)
    }

    // ---- P5-P7: transport contract inherited from OpenAiLlmProvider ---------

    @Test
    fun `P5-chat reuses Ok parsing`() = runBlocking {
        val transport = RecordingTransport()
        val provider = GrammarLlmProvider(config = config, transport = transport)

        val result = provider.chat(listOf(ChatMessage("user", "hi")))

        assertIs<LlmResponse.Ok>(result)
        assertEquals("hello", result.content)
    }

    @Test
    fun `P6-HTTP 5xx maps to retryable LLM_API_ERROR`() = runBlocking {
        val transport = object : LlmHttpTransport {
            override suspend fun postJson(
                url: String,
                apiKey: String,
                body: String,
                connectTimeoutMs: Long,
                requestTimeoutMs: Long,
            ) = HttpTransportResponse(500, """{"error":{"message":"boom"}}""")
        }
        val provider = GrammarLlmProvider(config = config, transport = transport)

        val result = provider.constrainedChat(
            messages = listOf(ChatMessage("user", "hi")),
            grammar = LlmGrammar.gbnf("root ::= ws \"hi\" ws"),
        )

        assertIs<LlmResponse.Err>(result)
        assertEquals("LLM_API_ERROR", result.code)
        assertTrue(result.retryable)
    }

    @Test
    fun `P7-transport exceptions are mapped to typed errors`() = runBlocking {
        val transport = object : LlmHttpTransport {
            override suspend fun postJson(
                url: String,
                apiKey: String,
                body: String,
                connectTimeoutMs: Long,
                requestTimeoutMs: Long,
            ): HttpTransportResponse = throw LlmTransportException("LLM_TIMEOUT", "timed out", true)
        }
        val provider = GrammarLlmProvider(config = config, transport = transport)

        val result = provider.constrainedChat(
            messages = listOf(ChatMessage("user", "hi")),
            grammar = LlmGrammar.gbnf("root ::= ws \"hi\" ws"),
        )

        assertIs<LlmResponse.Err>(result)
        assertEquals("LLM_TIMEOUT", result.code)
        assertTrue(result.retryable)
    }

    @Test
    fun `P7b-IOException maps to LLM_NETWORK_ERROR`() = runBlocking {
        val transport = object : LlmHttpTransport {
            override suspend fun postJson(
                url: String,
                apiKey: String,
                body: String,
                connectTimeoutMs: Long,
                requestTimeoutMs: Long,
            ): HttpTransportResponse = throw IOException("socket reset")
        }
        val provider = GrammarLlmProvider(config = config, transport = transport)

        val result = provider.constrainedChat(
            messages = listOf(ChatMessage("user", "hi")),
            grammar = LlmGrammar.gbnf("root ::= ws \"hi\" ws"),
        )

        assertIs<LlmResponse.Err>(result)
        assertEquals("LLM_NETWORK_ERROR", result.code)
        assertTrue(result.retryable)
    }

    // ---- P8/P9: advertised surface ------------------------------------------

    @Test
    fun `P8-grammarFormats mirrors the injection mode`() {
        val gbnf = GrammarLlmProvider(injection = GrammarInjection.GBNF_GRAMMAR_FIELD, config = config)
        assertEquals(setOf(GrammarFormat.GBNF), gbnf.grammarFormats)

        val vllmGrammar = GrammarLlmProvider(injection = GrammarInjection.VLLM_GUIDED_GRAMMAR, config = config)
        assertEquals(setOf(GrammarFormat.GBNF), vllmGrammar.grammarFormats)

        val vllmJson = GrammarLlmProvider(injection = GrammarInjection.VLLM_GUIDED_JSON, config = config)
        assertEquals(setOf(GrammarFormat.JSON_SCHEMA), vllmJson.grammarFormats)
    }

    @Test
    fun `P9-tier defaults to on-device and is configurable`() {
        val local = GrammarLlmProvider(config = config)
        assertEquals(ProviderTier.ON_DEVICE, local.tier)

        val hosted = GrammarLlmProvider(
            injection = GrammarInjection.VLLM_GUIDED_JSON,
            tier = ProviderTier.CLOUD,
            config = config,
        )
        assertEquals(ProviderTier.CLOUD, hosted.tier)
        assertTrue(hosted.capabilities.contains(Capability.CONSTRAINED))
    }

    // ---- Test doubles ----------------------------------------------------

    private class RecordingTransport(
        var lastBody: String? = null,
    ) : LlmHttpTransport {
        override suspend fun postJson(
            url: String,
            apiKey: String,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): HttpTransportResponse {
            lastBody = body
            return HttpTransportResponse(200, """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""")
        }
    }
}
