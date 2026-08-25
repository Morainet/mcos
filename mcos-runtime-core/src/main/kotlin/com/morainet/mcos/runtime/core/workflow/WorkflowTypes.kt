package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.sdk.CommandResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Workflow step types for defining multi-step command execution graphs.
 *
 * Supports: sequential, parallel, conditional, loop, retry, try/compensation.
 *
 * Matches [01-architecture.md Workflow Engine].
 */

/** A single step in a workflow graph */
sealed class WorkflowStep {
    /** Execute a single command by ID */
    data class Command(
        val commandId: String,
        val args: JsonObject = JsonObject(emptyMap())
    ) : WorkflowStep()

    /** Execute steps sequentially; stops on first failure */
    data class Sequential(val steps: List<WorkflowStep>) : WorkflowStep()

    /**
     * Execute steps in parallel.
     *
     * @param cancelOnFailure When true (default), the first failing branch
     *        cancels any sibling branches that have not yet completed: they
     *        are skipped and recorded with code `CANCELLED_BY_SIBLING`. When
     *        false, all branches run to completion regardless of sibling
     *        outcomes (useful for independent, side-effect-free fan-out where
     *        partial results matter).
     */
    data class Parallel(val steps: List<WorkflowStep>, val cancelOnFailure: Boolean = true) : WorkflowStep()

    /** Conditional branch: evaluates [condition] based on workflow state */
    data class If(
        val condition: WorkflowCondition,
        val thenStep: WorkflowStep,
        val elseStep: WorkflowStep? = null
    ) : WorkflowStep()

    /** Loop: executes [body] while [condition] holds, up to [maxIterations] */
    data class Loop(
        val body: WorkflowStep,
        val condition: WorkflowCondition? = null,
        val maxIterations: Int = 100
    ) : WorkflowStep()

    /**
     * Retry [step] up to [maxRetries] times with [backoffMs] delay between attempts.
     *
     * **Safety**: only commands marked [idempotent] are retried. A non-idempotent
     * command (e.g. `files.delete` that fails partway) is NOT retried because
     * retrying may apply the side effect twice. The [retryOnCodes] filter further
     * restricts retries to specific error codes (empty = retry on any error,
     * but only if [idempotent] is true).
     *
     * Matches spec [05-workflow.md] RetryPolicy.
     */
    data class Retry(
        val step: WorkflowStep,
        val maxRetries: Int = 3,
        val backoffMs: Long = 1000,
        /** If false, the step is executed once with no retries. Defaults to true for safety at the call site. */
        val idempotent: Boolean = true,
        /** If non-empty, only retry when the error code is in this set. */
        val retryOnCodes: Set<String> = emptySet()
    ) : WorkflowStep()

    /** Execute [step]; on failure, run [compensation] steps sequentially */
    data class Try(
        val step: WorkflowStep,
        val compensation: List<WorkflowStep> = emptyList()
    ) : WorkflowStep()
}

/**
 * Condition for branching and looping decisions.
 * Evaluated against the current workflow state.
 */
sealed class WorkflowCondition {
    /** Evaluate using a predicate on the last step result */
    data class BasedOnPrevious(val predicate: WorkflowPredicate) : WorkflowCondition()

    /** Always evaluates to [value] */
    data class Always(val value: Boolean) : WorkflowCondition()
}

/** Predicate for [WorkflowCondition.BasedOnPrevious] */
enum class WorkflowPredicate {
    /** True if the last executed step succeeded */
    LAST_STEP_SUCCEEDED,

    /** True if the last executed step failed */
    LAST_STEP_FAILED,

    /** True if the last successful step produced artifacts */
    HAS_ARTIFACTS
}

/** Result of a single step within a workflow run */
@Serializable
data class WorkflowStepResult(
    val commandId: String?,
    val ok: Boolean,
    val code: String? = null,
    val message: String? = null,
    val durationMs: Long = 0
)

/** Overall outcome of a workflow execution */
enum class WorkflowOutcome {
    COMPLETED,
    FAILED,
    CANCELLED
}

/** Result of a full workflow execution */
data class WorkflowResult(
    val runId: String,
    val steps: List<WorkflowStepResult>,
    val outcome: WorkflowOutcome,
    val totalDurationMs: Long = 0
)

// ─── Triggers (05-workflow.md §9) ────────────────────────────────────────────

/**
 * How a workflow starts. `null` trigger (or a workflow registered without one)
 * means **manual-only** — the workflow runs exclusively via an explicit
 * `execute(WorkflowRef)` call.
 *
 * Matches spec [05-workflow.md §9]. `Trigger.Event` is the only trigger the
 * [EventTriggerManager] arms today; `Trigger.Schedule` parses (schema-faithful)
 * but arming it is rejected until the V1 scheduler work.
 */
sealed class Trigger {
    /** Started by an explicit `execute(WorkflowRef, inputs)` call (§9.1). */
    data class Manual(
        val source: String? = null,
        val inputs: List<String> = emptyList()
    ) : Trigger()

    /**
     * Started by a matching event on the EventBus (§9.2).
     *
     * @param filter `{"type": <event type>, "where": {...}}` — `type` is used
     *        as the subscription's `typePrefix` (03 §11.4 prefix semantics),
     *        `where` supports literal values and `{"$memory": path}` references
     *        (07 §13.1).
     * @param resolveMemory when `$memory` references are resolved: `ARM` once
     *        at subscription time (default — faster, won't see memory changes
     *        until re-arm), `FIRE` per incoming event before matching.
     */
    data class Event(
        val filter: JsonObject,
        val resolveMemory: MemoryResolution = MemoryResolution.ARM
    ) : Trigger()

    /**
     * Started on a cron schedule (§9.3). **Parsed but not armed** — scheduling
     * requires the Android `AlarmManager`/`WorkManager` integration planned
     * for V1; `EventTriggerManager.arm` rejects it explicitly.
     */
    data class Schedule(
        val cron: String,
        val tz: String,
        val misfirePolicy: String = "skip"
    ) : Trigger()
}

/** When [Trigger.Event] `$memory` references are resolved (05 §9.2, 07 §13.1). */
enum class MemoryResolution {
    /** Resolve once when the trigger is armed (default). */
    ARM,

    /** Resolve per incoming event, before evaluating `where`. */
    FIRE
}

/**
 * A named workflow definition: the step tree plus its (optional) trigger.
 * The workflow id is the [WorkflowStore] key — it is deliberately not
 * duplicated here.
 */
data class WorkflowSpec(
    val trigger: Trigger?,
    val step: WorkflowStep
)
