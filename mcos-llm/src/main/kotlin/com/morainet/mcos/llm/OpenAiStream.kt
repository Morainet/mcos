package com.morainet.mcos.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Collects one Chat Completions choice. Tool arguments stay private until the stream is complete. */
internal class OpenAiStream {
    private val text = StringBuilder()
    private val tools = sortedMapOf<Int, ToolFragments>()
    private var finished = false
    private var finishReason: String? = null
    private var charactersReceived = 0

    fun receive(choice: JsonObject): String? {
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
        val delta = choice["delta"] as? JsonObject ?: return null
        (delta["tool_calls"] as? JsonArray)?.forEach { call ->
            val entry = call.jsonObject
            val index = entry.getValue("index").jsonPrimitive.int
            require(index >= 0) { "A streamed tool call has an invalid index" }
            val tool = tools.getOrPut(index) { ToolFragments() }
            entry["id"]?.jsonPrimitive?.contentOrNull?.let(tool.id::append)
            entry["function"]?.jsonObject?.let { fn ->
                fn["name"]?.jsonPrimitive?.contentOrNull?.let(tool.name::append)
                fn["arguments"]?.jsonPrimitive?.contentOrNull?.let(tool.arguments::append)
            }
        }
        return delta["content"]?.jsonPrimitive?.contentOrNull?.also(text::append)?.takeIf { it.isNotEmpty() }
    }

    fun countFrame(size: Int) {
        charactersReceived += size
        if (charactersReceived > MAX_STREAM_CHARS) {
            throw LlmTransportException("LLM_PARSE_ERROR", "The model response was too large.", false)
        }
    }

    fun markDone() {
        finished = true
    }

    fun result(): ToolCallResponse.Ok {
        if (!finished && finishReason == null) {
            throw LlmTransportException(LlmErrorCode.STREAM_INTERRUPTED, "The model response ended unexpectedly.", true)
        }
        if (tools.isNotEmpty() && finishReason != "tool_calls" && finishReason != "stop") {
            throw LlmTransportException("LLM_PARSE_ERROR", "The model did not finish its tool request.", false)
        }
        val calls = tools.values.map { tool ->
            require(tool.id.isNotBlank() && tool.name.isNotBlank()) { "Incomplete streamed tool call" }
            val args = Json.parseToJsonElement(tool.arguments.toString()).jsonObject
            ToolCall(tool.id.toString(), tool.name.toString(), args)
        }
        return ToolCallResponse.Ok(calls, finishReason ?: "stop", content = text.toString())
    }

    private class ToolFragments {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private companion object {
        const val MAX_STREAM_CHARS = 2 * 1024 * 1024
    }
}
