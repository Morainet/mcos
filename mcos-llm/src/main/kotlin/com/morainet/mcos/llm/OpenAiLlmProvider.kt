package com.morainet.mcos.llm

import java.io.IOException
import java.net.ConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI-compatible [LlmProvider] implementation.
 *
 * Compatible with any provider that speaks the OpenAI Chat Completions API,
 * including self-hosted vLLM/LiteLLM endpoints.
 *
 * HTTP transport is pluggable ([LlmHttpTransport]): the JVM default uses the
 * JDK 11+ `HttpClient`; Android injects an `HttpURLConnection`-based transport
 * because the `java.net.http` module is not available on Android.
 *
 * @param config Connection and model configuration.
 * @param transport HTTP transport used for requests.
 * @param id Registry/diagnostics id (06 §17). Defaults to "openai"; multiple
 *   OpenAI-compatible vendors (DeepSeek, Qwen, Moonshot, …) coexist in one
 *   [LlmProviderRegistry] only when each is registered under a distinct id.
 */
open class OpenAiLlmProvider(
    protected val config: LlmConfig,
    private val transport: LlmHttpTransport = JdkLlmHttpTransport(),
    override val id: String = "openai",
) : LlmProvider, StreamingLlmProvider {

    override val capabilities: Set<Capability> =
        setOf(Capability.CHAT, Capability.TOOL_CALL, Capability.CONSTRAINED)

    /**
     * Probe uses a small token budget accepted by the configured chat model.
     */
    override suspend fun probe(): LlmProbeResult =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = buildJsonObject {
                    put("model", JsonPrimitive(config.model))
                    put("max_tokens", JsonPrimitive(32))
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("ping"))
                        })
                    })
                }.toString()
                val httpResponse = sendRequest(requestBody)
                if (httpResponse.statusCode == 200) {
                    LlmProbeResult.Ok
                } else {
                    LlmProbeResult.Err(
                        "LLM_API_ERROR",
                        "Probe failed with HTTP ${httpResponse.statusCode}"
                    )
                }
            } catch (e: Exception) {
                LlmProbeResult.Err("LLM_PROBE_ERROR", e.message ?: "Probe failed")
            }
        }

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = buildRequestBody(messages)
                val httpResponse = sendRequest(requestBody)

                if (httpResponse.statusCode == 200) {
                    parseOkResponse(httpResponse.body)
                } else {
                    parseErrorResponse(httpResponse.statusCode, httpResponse.body)
                }
            } catch (e: LlmTransportException) {
                LlmResponse.Err(e.code, e.message, e.retryable)
            } catch (e: ConnectException) {
                LlmResponse.Err(LlmErrorCode.CONNECT_ERROR, "Cannot reach LLM endpoint: ${e.message}", true)
            } catch (e: IOException) {
                LlmResponse.Err(LlmErrorCode.NETWORK_ERROR, e.message ?: "Network error", true)
            } catch (e: Exception) {
                LlmResponse.Err("LLM_UNEXPECTED_ERROR", e.message ?: "Unexpected error", false)
            }
        }

    override suspend fun constrainedChat(
        messages: List<ChatMessage>,
        grammar: LlmGrammar,
    ): LlmResponse =
        withContext(Dispatchers.IO) {
            try {
                // OpenAI-side approximation of grammar-constrained decoding:
                // only JSON Schema can be injected (response_format + prompt);
                // real token-level grammars (llama.cpp GBNF, Outlines) must be
                // served by a backend that advertises GrammarFormat.GBNF.
                if (grammar.format != GrammarFormat.JSON_SCHEMA) {
                    return@withContext LlmResponse.Err(
                        LlmErrorCode.CAPABILITY_EXCEEDED,
                        "Provider $id can only decode JSON_SCHEMA grammars, got ${grammar.format}",
                        false
                    )
                }
                // Append the IR JSON Schema to the system message so the model
                // sees the exact grammar, and pin response_format to a JSON
                // object (06 §3.2 V2).
                val constrainedMessages = messages.map { msg ->
                    if (msg.role == "system") {
                        msg.copy(content = msg.content + "\n\n## IR JSON Schema (grammar)\n${grammar.content}")
                    } else msg
                }
                val requestBody = buildRequestBody(constrainedMessages, responseFormatJsonObject = true)
                val httpResponse = sendRequest(requestBody)

                if (httpResponse.statusCode == 200) {
                    parseOkResponse(httpResponse.body)
                } else {
                    parseErrorResponse(httpResponse.statusCode, httpResponse.body)
                }
            } catch (e: LlmTransportException) {
                LlmResponse.Err(e.code, e.message, e.retryable)
            } catch (e: ConnectException) {
                LlmResponse.Err(LlmErrorCode.CONNECT_ERROR, "Cannot reach LLM endpoint: ${e.message}", true)
            } catch (e: IOException) {
                LlmResponse.Err(LlmErrorCode.NETWORK_ERROR, e.message ?: "Network error", true)
            } catch (e: Exception) {
                LlmResponse.Err("LLM_UNEXPECTED_ERROR", e.message ?: "Unexpected error", false)
            }
        }

    override suspend fun toolCall(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
    ): ToolCallResponse =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = buildToolCallRequestBody(messages, tools)
                val httpResponse = sendRequest(requestBody)

                if (httpResponse.statusCode == 200) {
                    parseToolCallResponse(httpResponse.body)
                } else {
                    parseToolCallError(httpResponse.statusCode, httpResponse.body)
                }
            } catch (e: LlmTransportException) {
                ToolCallResponse.Err(e.code, e.message, e.retryable)
            } catch (e: ConnectException) {
                ToolCallResponse.Err(LlmErrorCode.CONNECT_ERROR, "Cannot reach LLM endpoint: ${e.message}", true)
            } catch (e: IOException) {
                ToolCallResponse.Err(LlmErrorCode.NETWORK_ERROR, e.message ?: "Network error", true)
            } catch (e: Exception) {
                ToolCallResponse.Err("LLM_UNEXPECTED_ERROR", e.message ?: "Unexpected error", false)
            }
        }

    override suspend fun streamChat(
        messages: List<ChatMessage>,
        onTextDelta: suspend (String) -> Unit,
    ): LlmResponse {
        if (transport !is StreamingLlmHttpTransport) return chat(messages)
        return when (val response = stream(messages, emptyList(), onTextDelta)) {
            is ToolCallResponse.Ok -> response.content?.takeIf { it.isNotBlank() }
                ?.let { LlmResponse.Ok(it) }
                ?: LlmResponse.Err(LlmErrorCode.EMPTY_RESPONSE, "The model returned no answer.", false)
            is ToolCallResponse.Err -> LlmResponse.Err(response.code, response.message, response.retryable)
        }
    }

    override suspend fun streamToolCall(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        onTextDelta: suspend (String) -> Unit,
    ): ToolCallResponse {
        if (transport !is StreamingLlmHttpTransport) return toolCall(messages, tools)
        return stream(messages, tools, onTextDelta)
    }

    private suspend fun stream(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        onTextDelta: suspend (String) -> Unit,
    ): ToolCallResponse = try {
        val eventTransport = transport as StreamingLlmHttpTransport
        val payload = if (tools.isEmpty()) buildRequestBody(messages) else buildToolCallRequestBody(messages, tools)
        val json = Json.parseToJsonElement(payload).jsonObject
        val body = JsonObject(json + ("stream" to JsonPrimitive(true))).toString()
        val pending = OpenAiStream()
        var nonStreaming: HttpTransportResponse? = null
        eventTransport.postEvents(
            config.endpoint, config.apiKey, body, config.connectTimeoutMs, config.requestTimeoutMs,
        ).collect { frame ->
            when (frame) {
                is LlmStreamFrame.Response -> nonStreaming = frame.value
                is LlmStreamFrame.Data -> {
                    val data = frame.value
                    if (data == "[DONE]") {
                        pending.markDone()
                    } else {
                        pending.countFrame(data.length)
                        val event = Json.parseToJsonElement(data).jsonObject
                        if (event["error"] is JsonObject) {
                            val error = parseProviderError(500, data)
                            throw LlmTransportException(error.code, error.message, error.retryable)
                        }
                        val delta = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let(pending::receive)
                        if (delta != null) onTextDelta(delta)
                    }
                }
            }
        }
        val response = nonStreaming
        when {
            response == null -> pending.result()
            response.statusCode == 200 -> parseToolCallResponse(response.body)
            else -> parseToolCallError(response.statusCode, response.body)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmTransportException) {
        ToolCallResponse.Err(e.code, e.message, e.retryable)
    } catch (e: IOException) {
        ToolCallResponse.Err(LlmErrorCode.NETWORK_ERROR, e.message ?: "Network error", true)
    } catch (e: IllegalArgumentException) {
        ToolCallResponse.Err("LLM_PARSE_ERROR", "The model returned an invalid stream.", false)
    }

    // ---- Request building ------------------------------------------------

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        responseFormatJsonObject: Boolean = false,
    ): String =
        buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("max_tokens", JsonPrimitive(config.maxTokens))
            put("temperature", JsonPrimitive(config.temperature))
            if (responseFormatJsonObject) {
                put("response_format", buildJsonObject {
                    put("type", JsonPrimitive("json_object"))
                })
            }
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(msg.role))
                        put("content", JsonPrimitive(msg.content))
                    })
                }
            })
        }.toString()

    private fun buildToolCallRequestBody(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
    ): String =
        buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("max_tokens", JsonPrimitive(config.maxTokens))
            put("temperature", JsonPrimitive(config.temperature))
            put("tool_choice", JsonPrimitive("auto"))
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(msg.role))
                        put("content", JsonPrimitive(msg.content))
                    })
                }
            })
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject {
                            put("name", JsonPrimitive(tool.command))
                            put("description", JsonPrimitive(tool.description))
                            put("parameters", tool.inputSchema)
                        })
                    })
                }
            })
        }.toString()

    protected suspend fun sendRequest(body: String): HttpTransportResponse =
        transport.postJson(
            url = config.endpoint,
            apiKey = config.apiKey,
            body = body,
            connectTimeoutMs = config.connectTimeoutMs,
            requestTimeoutMs = config.requestTimeoutMs,
        )

    // ---- Response parsing ------------------------------------------------

    protected fun parseOkResponse(responseBody: String): LlmResponse {
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val choices = json["choices"]?.jsonArray
            val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            if (content != null) {
                LlmResponse.Ok(content)
            } else {
                LlmResponse.Err(
                    LlmErrorCode.EMPTY_RESPONSE,
                    "The model returned no answer.",
                    false
                )
            }
        } catch (e: Exception) {
            LlmResponse.Err(
                "LLM_PARSE_ERROR",
                "Failed to parse LLM response: ${e.message?.take(200)}",
                false
            )
        }
    }

    private fun parseToolCallResponse(responseBody: String): ToolCallResponse {
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message")?.jsonObject
            val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.content ?: "stop"
            val toolCalls = message?.get("tool_calls")?.jsonArray?.mapNotNull { tc ->
                val obj = tc.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
                val name = fn["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val args = try {
                    Json.parseToJsonElement(fn["arguments"]?.jsonPrimitive?.content ?: "{}").jsonObject
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                ToolCall(id, name, args)
            } ?: emptyList()

            val usage = json["usage"]?.jsonObject?.let {
                TokenUsage(
                    prompt = it["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                    completion = it["completion_tokens"]?.jsonPrimitive?.int ?: 0,
                    total = it["total_tokens"]?.jsonPrimitive?.int ?: 0
                )
            }

            val content = message?.get("content")?.jsonPrimitive?.contentOrNull
            ToolCallResponse.Ok(toolCalls = toolCalls, finishReason = finishReason, usage = usage, content = content)
        } catch (e: Exception) {
            ToolCallResponse.Err(
                "LLM_PARSE_ERROR",
                "Failed to parse tool-call response: ${e.message?.take(200)}",
                false
            )
        }
    }

    private fun parseToolCallError(statusCode: Int, responseBody: String): ToolCallResponse {
        val error = parseProviderError(statusCode, responseBody)
        return ToolCallResponse.Err(error.code, error.message, error.retryable)
    }

    protected fun parseErrorResponse(statusCode: Int, responseBody: String): LlmResponse =
        parseProviderError(statusCode, responseBody)

    private fun parseProviderError(statusCode: Int, responseBody: String): LlmResponse.Err {
        val errorMessage = try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        val emptyResponse = errorMessage?.startsWith("模型无返回结果") == true ||
            errorMessage?.contains("No response, please try again", ignoreCase = true) == true
        if (emptyResponse && statusCode !in 401..403 && statusCode != 429) {
            return LlmResponse.Err(LlmErrorCode.EMPTY_RESPONSE, "The model returned no answer.", true)
        }
        return LlmResponse.Err(
            "LLM_API_ERROR",
            errorMessage ?: "HTTP $statusCode",
            statusCode >= 500 // server errors are retryable
        )
    }
}
