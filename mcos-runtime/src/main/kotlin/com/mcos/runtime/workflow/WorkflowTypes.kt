package com.mcos.runtime.workflow

import com.mcos.sdk.CommandResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Workflow step types for defining multi-step command execution graphs.
 *
 * Supports: sequential, parallel, conditional, loop, retry, try/compensation.
 *
 * Matches [01-architecture.md §Workflow Engine].
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

    /** Execute steps in parallel; any failure aborts remaining steps */
    data class Parallel(val steps: List<WorkflowStep>) : WorkflowStep()

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

    /** Retry [step] up to [maxRetries] times with [backoffMs] delay between attempts */
    data class Retry(
        val step: WorkflowStep,
        val maxRetries: Int = 3,
        val backoffMs: Long = 1000
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
