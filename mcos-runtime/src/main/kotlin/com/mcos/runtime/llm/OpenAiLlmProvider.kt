package com.mcos.runtime.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI-compatible [LlmProvider] implementation.
 *
 * Uses `java.net.http.HttpClient` (built into JDK 11+) -- no external
 * HTTP library required.
 *
 * Compatible with any provider that speaks the OpenAI Chat Completions API,
 * including self-hosted vLLM/LiteLLM endpoints.
 *
 * @param config Connection and model configuration.
 */
class OpenAiLlmProvider(
    private val config: LlmConfig
) : LlmProvider {

    override val id: String get() = "openai"

    override val capabilities: Set<Capability> =
        setOf(Capability.CHAT, Capability.TOOL_CALL)

    /**
     * Probe uses a minimal 1-token chat request to check connectivity
     * without significant latency or cost.
     */
    override suspend fun probe(): LlmProbeResult =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = buildJsonObject {
                    put("model", JsonPrimitive(config.model))
                    put("max_tokens", JsonPrimitive(1))
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("ping"))
                        })
                    })
                }.toString()
                val httpResponse = sendRequest(requestBody)
                if (httpResponse.statusCode() == 200) {
                    LlmProbeResult.Ok
                } else {
                    LlmProbeResult.Err(
                        "LLM_API_ERROR",
                        "Probe failed with HTTP ${httpResponse.statusCode()}"
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

                if (httpResponse.statusCode() == 200) {
                    parseOkResponse(httpResponse.body())
                } else {
                    parseErrorResponse(httpResponse.statusCode(), httpResponse.body())
                }
            } catch (e: java.net.ConnectException) {
                LlmResponse.Err("LLM_CONNECT_ERROR", "Cannot reach LLM endpoint: ${e.message}", true)
            } catch (e: java.net.http.HttpTimeoutException) {
                LlmResponse.Err("LLM_TIMEOUT", "LLM request timed out", true)
            } catch (e: java.io.IOException) {
                LlmResponse.Err("LLM_NETWORK_ERROR", e.message ?: "Network error", true)
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

                if (httpResponse.statusCode() == 200) {
                    parseToolCallResponse(httpResponse.body())
                } else {
                    parseToolCallError(httpResponse.statusCode(), httpResponse.body())
                }
            } catch (e: java.net.ConnectException) {
                ToolCallResponse.Err("LLM_CONNECT_ERROR", "Cannot reach LLM endpoint: ${e.message}", true)
            } catch (e: java.net.http.HttpTimeoutException) {
                ToolCallResponse.Err("LLM_TIMEOUT", "LLM request timed out", true)
            } catch (e: java.io.IOException) {
                ToolCallResponse.Err("LLM_NETWORK_ERROR", e.message ?: "Network error", true)
            } catch (e: Exception) {
                ToolCallResponse.Err("LLM_UNEXPECTED_ERROR", e.message ?: "Unexpected error", false)
            }
        }

    // ---- Request building ------------------------------------------------

    private fun buildRequestBody(messages: List<ChatMessage>): String =
        buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("max_tokens", JsonPrimitive(config.maxTokens))
            put("temperature", JsonPrimitive(config.temperature))
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

    private suspend fun sendRequest(body: String): java.net.http.HttpResponse<String> {
        val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(config.connectTimeoutMs))
            .build()

        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(config.endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .timeout(java.time.Duration.ofMillis(config.requestTimeoutMs))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build()

        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
    }

    // ---- Response parsing ------------------------------------------------

    private fun parseOkResponse(responseBody: String): LlmResponse {
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val choices = json["choices"]?.jsonArray
            val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.content

            if (content != null) {
                LlmResponse.Ok(content)
            } else {
                LlmResponse.Err(
                    "LLM_PARSE_ERROR",
                    "Response missing 'choices[0].message.content'",
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

            ToolCallResponse.Ok(toolCalls = toolCalls, finishReason = finishReason, usage = usage)
        } catch (e: Exception) {
            ToolCallResponse.Err(
                "LLM_PARSE_ERROR",
                "Failed to parse tool-call response: ${e.message?.take(200)}",
                false
            )
        }
    }

    private fun parseToolCallError(statusCode: Int, responseBody: String): ToolCallResponse {
        val errorMessage = try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        return ToolCallResponse.Err(
            "LLM_API_ERROR",
            errorMessage ?: "HTTP $statusCode",
            statusCode >= 500 // server errors are retryable
        )
    }

    private fun parseErrorResponse(statusCode: Int, responseBody: String): LlmResponse {
        val errorMessage = try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        return LlmResponse.Err(
            "LLM_API_ERROR",
            errorMessage ?: "HTTP $statusCode",
            statusCode >= 500 // server errors are retryable
        )
    }
}
