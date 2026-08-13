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
    val error: LlmResponse.Err? = null,

    /**
     * The provider that produced this plan (or, on failure, the last provider
     * attempted). Populated when the planner has a fallback chain (§17 V1).
     */
    val providerId: String? = null
) {
    /** True if the plan contains executable commands without errors. */
    val isSuccess: Boolean get() = error == null && commands.isNotEmpty()
}

/**
 * Capabilities an [LlmProvider] may advertise (06 §3.0).
 *
 * The planner picks the highest-fidelity mode a provider's capabilities allow;
 * the registry and fallback chain use these to route planning requests.
 */
enum class Capability {
    /** Plain chat-completion (used for FREEFORM_JSON planning). */
    CHAT,

    /** Provider-curated plan endpoint (e.g. Gemini). */
    PLAN,

    /** Native tool-calling (NATIVE_TOOL_CALL mode). */
    TOOL_CALL,

    /** Embedding endpoint (catalog retrieval, semantic index). */
    EMBED,
}

/**
 * Result of an [LlmProvider.probe] health check.
 */
sealed class LlmProbeResult {
    /** Provider is healthy and ready to serve. */
    data object Ok : LlmProbeResult()

    /** Provider failed the health check; it should be excluded from routing. */
    data class Err(val code: String, val message: String) : LlmProbeResult()
}

/**
 * Abstraction for an LLM backend.
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
     * Stable identifier used for routing and diagnostics, e.g. "openai",
     * "gemini", "on-device". Defaults to the simple class name.
     */
    val id: String get() = this::class.simpleName ?: "llm-provider"

    /**
     * Capabilities this provider advertises. Defaults to [Capability.CHAT]
     * (plain chat-completion). Providers that support native tool-calling
     * or embeddings should override this (06 §3.0).
     */
    val capabilities: Set<Capability> get() = setOf(Capability.CHAT)

    /**
     * Send a list of chat messages and receive the LLM's reply.
     *
     * @param messages Ordered list of messages (system -> user -> assistant -> ...).
     * @return [LlmResponse.Ok] with the model's text, or [LlmResponse.Err] on failure.
     */
    suspend fun chat(messages: List<ChatMessage>): LlmResponse

    /**
     * Health probe used by [LlmProviderRegistry] to exclude unhealthy
     * providers from routing. Defaults to healthy; override to perform a
     * lightweight connectivity check (e.g. a minimal chat request).
     */
    suspend fun probe(): LlmProbeResult = LlmProbeResult.Ok
}
