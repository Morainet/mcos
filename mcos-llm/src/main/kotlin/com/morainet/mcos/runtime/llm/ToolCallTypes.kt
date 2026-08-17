package com.morainet.mcos.runtime.llm

import kotlinx.serialization.json.JsonObject

/**
 * Tool-calling types for NATIVE_TOOL_CALL planning (06 §3.0).
 *
 * The planner picks the highest-fidelity mode a provider's capabilities allow:
 * `TOOL_CALL` -> [PlanMode.NATIVE_TOOL_CALL], otherwise [PlanMode.FREEFORM_JSON]
 * (06 §3.2).
 */

/**
 * A tool call proposed by the model in NATIVE_TOOL_CALL mode.
 *
 * @param id Provider-assigned call id (used to correlate results in a
 *        multi-round tool loop; not needed for one-shot planning).
 * @param command Fully-qualified command id, e.g. "camera.capture".
 * @param args Command arguments as a JSON object, matching the command's
 *        input schema.
 */
data class ToolCall(
    val id: String,
    val command: String,
    val args: JsonObject,
)

/**
 * The Planner's view of a [com.morainet.mcos.sdk.CommandDescriptor] (06 §3.0).
 *
 * Produced by projecting registry commands onto these four fields; the full
 * descriptor stays in the [com.morainet.mcos.runtime.registry.CommandRegistry].
 *
 * @param command Fully-qualified command id, e.g. "iot.ac.set".
 * @param description Short description for the model.
 * @param inputSchema JSON Schema for the input arguments.
 * @param examples Few-shot examples, optional.
 */
data class ToolDescriptor(
    val command: String,
    val description: String,
    val inputSchema: JsonObject,
    val examples: List<JsonObject> = emptyList(),
)

/**
 * Response from an [LlmProvider.toolCall] call.
 */
sealed class ToolCallResponse {
    /**
     * Successful call with the model's tool calls.
     *
     * @param toolCalls Proposed tool calls; may be empty if the model chose
     *        to answer with plain text instead.
     * @param finishReason Provider finish reason ("stop" | "tool_calls" | ...).
     * @param usage Token usage when the provider reports it.
     */
    data class Ok(
        val toolCalls: List<ToolCall>,
        val finishReason: String = "tool_calls",
        val usage: TokenUsage? = null,
    ) : ToolCallResponse()

    /** Failed call with error details. */
    data class Err(
        val code: String,
        val message: String,
        val retryable: Boolean = true,
    ) : ToolCallResponse()
}

/**
 * Token usage metadata reported by a provider (06 §3.0).
 */
data class TokenUsage(
    val prompt: Int,
    val completion: Int,
    val total: Int,
)

/**
 * How the planner asks a provider for a plan (06 §3.2).
 *
 * - [NATIVE_TOOL_CALL]: provider's structured tool-calling endpoint
 *   ([LlmProvider.toolCall]); highest fidelity, requires [Capability.TOOL_CALL].
 * - [FREEFORM_JSON]: plain chat + DSL text output; works with any
 *   [Capability.CHAT] provider.
 */
enum class PlanMode {
    /** Native tool-calling: the model emits structured tool calls (06 §3.2). */
    NATIVE_TOOL_CALL,

    /** Freeform output: model replies with MCOS DSL text (universal fallback). */
    FREEFORM_JSON,

    /**
     * Grammar-constrained decoding (06 §3.2 V2): model output is constrained
     * by the injected IR JSON Schema; the reply is a single valid IR JSON
     * object (`invoke` / `sequence` / `clarify` / `refuse`).
     */
    CONSTRAINED,

    /**
     * Latency-tiered routing (06 §13.1 routing strategy, §15.1 budget): the
     * planner first classifies the utterance ([UtteranceClassifier]) and walks
     * the cheapest path that can serve it -- exact CLI/DSL -> parser-only,
     * known recipe -> local [RecipeMatcher], then ON_DEVICE providers before
     * CLOUD providers (complex intents may invert this when cloud planning is
     * opted in). Zero-latency paths skip the LLM entirely.
     */
    LATENCY_TIERED,
}
