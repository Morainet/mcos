package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.executor.Command

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
    /** Commands parsed from LLM output, ready for [com.morainet.mcos.runtime.core.executor.Executor.executeSequence]. */
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
    val providerId: String? = null,

    /**
     * The planning mode used to produce this plan (06 §3.2). Populated on
     * success; `null` when no provider was successfully used.
     */
    val planMode: PlanMode? = null,

    /**
     * Utterance class assigned by the [UtteranceClassifier] in
     * [PlanMode.LATENCY_TIERED] routing (06 §13.1). `null` outside that mode.
     */
    val utteranceClass: UtteranceClass? = null,

    /**
     * End-to-end planning latency in milliseconds, measured from when
     * `plan()` started until the result was produced (06 §15.0 telemetry).
     * `null` when not measured.
     */
    val latencyMs: Long? = null,

    /**
     * Diagnostic routing label for [PlanMode.LATENCY_TIERED] (06 §13.1):
     * `direct-parser`, `recipe:<id>`, `llm:<providerId>`, or a failure path.
     */
    val route: String? = null,
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

    /**
     * Grammar-constrained decoding (CONSTRAINED mode, 06 §3.2 V2): the model
     * output is constrained by the injected IR JSON Schema (llama.cpp GBNF,
     * Outlines, Gemini structured output, OpenAI response_format, …).
     */
    CONSTRAINED,

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
 * Where an [LlmProvider] executes its inference (06 §13.0).
 *
 * The planner's fallback chain respects a privacy gate between tiers: an
 * ON_DEVICE provider must not escalate to a CLOUD provider unless the user
 * explicitly enabled "Allow cloud planner" (06 §13.2, privacy-first default).
 */
enum class ProviderTier {
    /** Runs on-device (MLC-LLM, GGUF, NNAPI): offline, private, weaker. */
    ON_DEVICE,

    /** Runs on a cloud service: networked, provider data policy applies. */
    CLOUD,
}

/**
 * Standard error codes used by the planner / fallback chain (06 §13.2).
 */
object LlmErrorCode {
    /**
     * Provider cannot handle the request with its current capabilities
     * (06 §13.2 `Refuse(CAPABILITY)`). On-device providers return this to
     * signal a legitimate escalation request to cloud.
     */
    const val CAPABILITY_EXCEEDED = "CAPABILITY_EXCEEDED"

    /**
     * Planner wanted to escalate an on-device failure to a cloud provider
     * but "Allow cloud planner" is disabled (06 §13.2 privacy gate).
     */
    const val CLOUD_FALLBACK_DISABLED = "CLOUD_FALLBACK_DISABLED"
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
     * Where this provider runs (06 §13.0). Defaults to [ProviderTier.CLOUD];
     * on-device implementations (MLC-LLM, GGUF, NNAPI) must override.
     */
    val tier: ProviderTier get() = ProviderTier.CLOUD

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
     * Structured tool-calling request used in NATIVE_TOOL_CALL planning mode
     * (06 §3.0). Only providers advertising [Capability.TOOL_CALL] are routed
     * here; the default implementation reports that tool calling is
     * unsupported, so chat-only providers are unaffected.
     *
     * @param messages Ordered list of chat messages (system -> user -> ...).
     * @param tools Available commands projected as [ToolDescriptor]s.
     * @return [ToolCallResponse.Ok] with the model's proposed tool calls, or
     *         [ToolCallResponse.Err] on failure.
     */
    suspend fun toolCall(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
    ): ToolCallResponse = ToolCallResponse.Err(
        "LLM_TOOL_CALL_UNSUPPORTED",
        "Provider $id does not advertise TOOL_CALL",
        false
    )

    /**
     * Grammar formats this provider can decode against (06 §3.2 V2).
     *
     * The planner injects the highest-fidelity format the provider advertises:
     * token-level [GrammarFormat.GBNF] grammars first, [GrammarFormat.JSON_SCHEMA]
     * as the portable fallback. Defaults to [GrammarFormat.JSON_SCHEMA]
     * (OpenAI `response_format`, vLLM `guided_json`, Outlines `json_schema`);
     * providers with real grammars (llama.cpp / GGUF, vLLM `guided_grammar`)
     * override with [GrammarFormat.GBNF].
     */
    val grammarFormats: Set<GrammarFormat> get() = setOf(GrammarFormat.JSON_SCHEMA)

    /**
     * Grammar-constrained planning request used in CONSTRAINED mode (06 §3.2 V2).
     *
     * Only providers advertising [Capability.CONSTRAINED] are routed here. The
     * [grammar] is the MCOS IR grammar in one of the provider's advertised
     * [grammarFormats] (llama.cpp GBNF, Outlines, Gemini structured output,
     * OpenAI response_format, …); the model's reply MUST be a single JSON
     * object conforming to that grammar.
     *
     * The default implementation reports that constrained decoding is
     * unsupported (non-retryable), so providers without grammar support are
     * unaffected and fall back to [PlanMode.FREEFORM_JSON].
     */
    suspend fun constrainedChat(
        messages: List<ChatMessage>,
        grammar: LlmGrammar,
    ): LlmResponse = LlmResponse.Err(
        LlmErrorCode.CAPABILITY_EXCEEDED,
        "Provider $id does not advertise CONSTRAINED (grammar-constrained decoding)",
        false
    )

    /**
     * Health probe used by [LlmProviderRegistry] to exclude unhealthy
     * providers from routing. Defaults to healthy; override to perform a
     * lightweight connectivity check (e.g. a minimal chat request).
     */
    suspend fun probe(): LlmProbeResult = LlmProbeResult.Ok
}
