package com.mcos.runtime.llm

import com.mcos.runtime.api.ExecuteRequest
import com.mcos.runtime.api.McosRuntime
import com.mcos.runtime.api.RuntimeEvent
import com.mcos.runtime.api.Source
import com.mcos.runtime.api.Payload
import com.mcos.sdk.CommandResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * End-to-end orchestrator that combines [LlmPlanner] with [McosRuntime]
 * to implement the full "chat to execution" pipeline.
 *
 * ## Pipeline
 *
 * ```
 * NL input → LlmPlanner.plan() → parsed Commands → [PromptInjectionDetector] → McosRuntime.execute() → ChatResult
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val orchestrator = ChatOrchestrator(planner, runtime, injectionDetector = PromptInjectionDetector())
 * val result = orchestrator.chat("take a photo and share it")
 * if (result.isSuccess) {
 *     println("Ran ${result.plan.commands.size} command(s)")
 * }
 * ```
 *
 * @param planner The [LlmPlanner] for NL → DSL translation.
 * @param runtime The [McosRuntime] for executing the generated commands.
 * @param injectionDetector Optional [PromptInjectionDetector] that runs between
 *        planning and execution. If omitted, no injection checks are performed.
 * @param eventTimeoutMs Hard ceiling on how long [chat] will wait for the
 *        runtime to emit a terminal event (RunSucceeded/RunFailed/RunCancelled)
 *        before giving up. Protects against indefinite hangs when a terminal
 *        event is dropped by the SharedFlow buffer (P0-C4). Default 30s.
 */
class ChatOrchestrator(
    private val planner: LlmPlanner,
    private val runtime: McosRuntime,
    private val injectionDetector: PromptInjectionDetector? = null,
    private val eventTimeoutMs: Long = 30_000L,
) {
    /**
     * Process a natural language request end-to-end.
     *
     * If an [injectionDetector] is configured, the plan is checked for prompt injection
     * patterns before execution. Detected injections return an unsuccessful [ChatResult]
     * without executing any commands.
     *
     * @param naturalLanguage The user's utterance, e.g. "打开客厅的灯".
     * @param mode Optional [PlanMode] override passed to the planner; `null`
     *        keeps the planner's default (automatic) behavior. Use
     *        [PlanMode.LATENCY_TIERED] for latency-tiered routing
     *        (06 §13.1/§15.1, parser/recipe fast paths before any LLM call).
     * @return [ChatResult] with the plan, execution results, and diagnostic info.
     */
    suspend fun chat(naturalLanguage: String, mode: PlanMode? = null): ChatResult {
        // Step 1: Plan (NL → Commands)
        val plan = planner.plan(naturalLanguage, mode)

        if (!plan.isSuccess) {
            return ChatResult(
                plan = plan,
                results = emptyList(),
                events = emptyList(),
                success = false,
                summary = "Planning failed: ${plan.thoughts ?: plan.error?.message ?: "unknown reason"}",
            )
        }

        // Step 1.5: Security — prompt injection detection (P1)
        val detector = injectionDetector
        if (detector != null) {
            val detection = detector.detect(
                utterance = naturalLanguage,
                commands = plan.commands,
            )
            if (detection is InjectionDetection.Suspected) {
                return ChatResult(
                    plan = plan,
                    results = emptyList(),
                    events = emptyList(),
                    success = false,
                    summary = "Security: prompt injection detected (${detection.reason}) — ${detection.evidence}",
                )
            }
        }

        // Step 2: Execute (Commands → Results)
        val request = ExecuteRequest(
            source = Source.CHAT,
            payload = Payload.DslText(plan.rawDsl),
        )
        val handle = runtime.execute(request)

        val events = mutableListOf<RuntimeEvent>()

        // Collect events until a terminal event arrives, or until [eventTimeoutMs]
        // elapses — whichever comes first (P0-C4). SharedFlow never completes on
        // its own, so without the timeout a dropped terminal event (buffer
        // overflow on the runtime's SharedFlow) would leave `collect` hanging
        // forever and [chat] would never return.
        val timedOut = withTimeoutOrNull(eventTimeoutMs) {
            coroutineScope {
                val collectorJob = launch {
                    runtime.observe(handle.runId).collect { event ->
                        events.add(event)
                        when (event) {
                            is RuntimeEvent.RunSucceeded,
                            is RuntimeEvent.RunFailed,
                            is RuntimeEvent.RunCancelled -> cancel()
                            else -> { /* progress — keep collecting */ }
                        }
                    }
                }
                collectorJob.join()
            }
        } == null

        // On timeout, surface whatever events arrived as a failure rather than
        // hanging the caller indefinitely.
        if (timedOut) {
            return ChatResult(
                plan = plan,
                results = emptyList(),
                events = events,
                success = false,
                summary = "Execution timed out waiting for terminal event after ${eventTimeoutMs}ms",
            )
        }

        // Step 3: Build summary
        val succeeded = events.any { it is RuntimeEvent.RunSucceeded }
        val failedEvent = events.filterIsInstance<RuntimeEvent.RunFailed>().firstOrNull()
        val cancelled = events.any { it is RuntimeEvent.RunCancelled }

        // P3-F5: Derive per-command results from the collected step events.
        // StepSucceeded / StepFailed carry the per-step outcome; we map them
        // to CommandResult.Ok / Err, ordered by stepIndex. (The full Ok.value
        // payload is not carried by the event, so Ok results have an empty
        // object — the caller can still inspect the events list for richer
        // detail.)
        val results = extractResults(events)

        val summary = when {
            succeeded -> "Executed ${plan.commands.size} command(s) successfully"
            cancelled -> "Execution was cancelled"
            failedEvent != null -> "Execution failed: ${failedEvent.error}"
            else -> "Execution completed with unknown status"
        }

        return ChatResult(
            plan = plan,
            results = results,
            events = events,
            success = succeeded,
            summary = summary,
        )
    }

    /**
     * Map per-step events to [CommandResult]s, ordered by stepIndex.
     * Only [RuntimeEvent.StepSucceeded] and [RuntimeEvent.StepFailed] produce
     * entries; progress/log/artifact events are ignored.
     */
    private fun extractResults(events: List<RuntimeEvent>): List<CommandResult> =
        events.mapNotNull { event ->
            when (event) {
                is RuntimeEvent.StepSucceeded -> CommandResult.Ok(value = JsonObject(emptyMap()))
                is RuntimeEvent.StepFailed -> CommandResult.Err(
                    code = "STEP_FAILED",
                    message = event.error,
                )
                else -> null
            }
        }
}

/**
 * Result of a [ChatOrchestrator.chat] call.
 */
data class ChatResult(
    /** The LLM's planning output. */
    val plan: LlmPlan,

    /**
     * Per-command execution results from [McosRuntime.execute].
     * May be empty if planning failed or execution hasn't produced results yet.
     */
    val results: List<CommandResult>,

    /**
     * All [RuntimeEvent]s emitted during execution.
     * Includes step-level progress, artifacts, and terminal events.
     */
    val events: List<RuntimeEvent>,

    /** Whether the entire chat+execute pipeline completed successfully. */
    val success: Boolean,

    /** Human-readable summary of what happened. */
    val summary: String,
) {
    /**
     * Get the raw DSL text generated by the LLM.
     */
    val dsl: String get() = plan.rawDsl
}
