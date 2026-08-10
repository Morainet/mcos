package com.mcos.runtime.llm

import com.mcos.runtime.executor.Command

/**
 * Chat message sent to an [LlmProvider].
 */
data class ChatMessage(
    val role: String,   // "system", "user", "assistant"
    val content: String
)

/**
 * Response from an [LlmProvider.chat] call.
 */
sealed class LlmResponse {
    /** Successful LLM call with the raw text content. */
    data class Ok(val content: String) : LlmResponse()

    /** Failed LLM call with error details. */
    data class Err(
        val code: String,
        val message: String,
        val retryable: Boolean = true
    ) : LlmResponse()
}

/**
 * Result of an [LlmPlanner.plan] call: parsed commands ready for execution,
 * plus diagnostic information.
 */
data class LlmPlan(
    /** Commands parsed from LLM output, ready for [com.mcos.runtime.executor.Executor.executeSequence]. */
    val commands: List<Command>,

    /** Raw DSL text returned by the LLM, for debugging. */
    val rawDsl: String,

    /** Human-readable summary of what happened (for logging/UX). */
    val thoughts: String? = null,

    /** If non-null, planning failed with this error. */
    val error: LlmResponse.Err? = null
) {
    /** True if the plan contains executable commands without errors. */
    val isSuccess: Boolean get() = error == null && commands.isNotEmpty()
}

/**
 * Abstraction for a chat-completion LLM backend.
 *
 * Implementations should handle:
 * - HTTP transport
 * - API authentication
 * - Response parsing
 *
 * Built-in implementation: [OpenAiLlmProvider].
 */
interface LlmProvider {
    /**
     * Send a list of chat messages and receive the LLM's reply.
     *
     * @param messages Ordered list of messages (system -> user -> assistant -> ...).
     * @return [LlmResponse.Ok] with the model's text, or [LlmResponse.Err] on failure.
     */
    suspend fun chat(messages: List<ChatMessage>): LlmResponse
}
