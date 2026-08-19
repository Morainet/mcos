package com.morainet.mcos.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How a grammar backend expects the constrained grammar in the request body
 * (06 §3.2 V2 real-grammar backends).
 *
 * - [GBNF_GRAMMAR_FIELD]: llama.cpp `llama-server` OpenAI compatibility --
 *   `{"grammar": "<gbnf>"}`.
 * - [VLLM_GUIDED_GRAMMAR]: vLLM / Outlines -- `{"guided_grammar": "<gbnf>"}`.
 * - [VLLM_GUIDED_JSON]: vLLM / Outlines JSON-Schema-guided decoding --
 *   `{"guided_json": "<json-schema>"}`.
 */
enum class GrammarInjection {
    GBNF_GRAMMAR_FIELD,
    VLLM_GUIDED_GRAMMAR,
    VLLM_GUIDED_JSON,
}

/**
 * OpenAI-compatible [LlmProvider] with real token-level grammar-constrained
 * decoding (06 §3.2 V2).
 *
 * Unlike [OpenAiLlmProvider] (which approximates CONSTRAINED by appending the
 * JSON Schema to the system prompt), this provider injects the grammar into a
 * dedicated request-body field so the backend's decoder guarantees the output
 * shape at sampling time:
 *
 * - llama.cpp `llama-server` via [GrammarInjection.GBNF_GRAMMAR_FIELD] --
 *   pass [LlmGrammar]s built by [GbnfGrammar.buildIrGrammar].
 * - vLLM / Outlines via [GrammarInjection.VLLM_GUIDED_GRAMMAR] (GBNF) or
 *   [GrammarInjection.VLLM_GUIDED_JSON] (JSON Schema).
 *
 * [tier] defaults to [ProviderTier.ON_DEVICE] (local GGUF / llama-server);
 * override to [ProviderTier.CLOUD] when pointing at a hosted vLLM endpoint.
 *
 * @param injection Which grammar field the backend speaks.
 * @param tier Where the backend runs (06 §13.0).
 * @param config Connection and model configuration.
 * @param transport HTTP transport used for requests.
 */
class GrammarLlmProvider(
    private val injection: GrammarInjection = GrammarInjection.GBNF_GRAMMAR_FIELD,
    override val tier: ProviderTier = ProviderTier.ON_DEVICE,
    config: LlmConfig,
    transport: LlmHttpTransport = JdkLlmHttpTransport(),
) : OpenAiLlmProvider(config, transport) {

    override val id: String get() = "grammar"

    override val capabilities: Set<Capability> =
        setOf(Capability.CHAT, Capability.CONSTRAINED)

    override val grammarFormats: Set<GrammarFormat>
        get() = when (injection) {
            GrammarInjection.GBNF_GRAMMAR_FIELD,
            GrammarInjection.VLLM_GUIDED_GRAMMAR -> setOf(GrammarFormat.GBNF)
            GrammarInjection.VLLM_GUIDED_JSON -> setOf(GrammarFormat.JSON_SCHEMA)
        }

    override suspend fun constrainedChat(
        messages: List<ChatMessage>,
        grammar: LlmGrammar,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val field = when (injection) {
            GrammarInjection.GBNF_GRAMMAR_FIELD -> {
                if (grammar.format != GrammarFormat.GBNF) return@withContext formatMismatch(grammar)
                "grammar"
            }
            GrammarInjection.VLLM_GUIDED_GRAMMAR -> {
                if (grammar.format != GrammarFormat.GBNF) return@withContext formatMismatch(grammar)
                "guided_grammar"
            }
            GrammarInjection.VLLM_GUIDED_JSON -> {
                if (grammar.format != GrammarFormat.JSON_SCHEMA) return@withContext formatMismatch(grammar)
                "guided_json"
            }
        }
        try {
            val requestBody = buildJsonObject {
                put("model", JsonPrimitive(config.model))
                put("max_tokens", JsonPrimitive(config.maxTokens))
                put("temperature", JsonPrimitive(config.temperature))
                put(field, JsonPrimitive(grammar.content))
                put("messages", buildJsonArray {
                    messages.forEach { msg ->
                        add(buildJsonObject {
                            put("role", JsonPrimitive(msg.role))
                            put("content", JsonPrimitive(msg.content))
                        })
                    }
                })
            }.toString()
            val httpResponse = sendRequest(requestBody)
            if (httpResponse.statusCode == 200) {
                parseOkResponse(httpResponse.body)
            } else {
                parseErrorResponse(httpResponse.statusCode, httpResponse.body)
            }
        } catch (e: LlmTransportException) {
            LlmResponse.Err(e.code, e.message, e.retryable)
        } catch (e: java.net.ConnectException) {
            LlmResponse.Err("LLM_CONNECT_ERROR", "Cannot reach LLM endpoint: ${e.message}", true)
        } catch (e: java.io.IOException) {
            LlmResponse.Err("LLM_NETWORK_ERROR", e.message ?: "Network error", true)
        } catch (e: Exception) {
            LlmResponse.Err("LLM_UNEXPECTED_ERROR", e.message ?: "Unexpected error", false)
        }
    }

    private fun formatMismatch(grammar: LlmGrammar): LlmResponse =
        LlmResponse.Err(
            LlmErrorCode.CAPABILITY_EXCEEDED,
            "Provider $id ($injection) cannot decode grammar format ${grammar.format}",
            false
        )
}
