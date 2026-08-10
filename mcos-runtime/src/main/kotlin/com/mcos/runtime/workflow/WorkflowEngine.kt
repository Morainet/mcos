package com.mcos.runtime.workflow

import com.mcos.runtime.audit.AuditLog
import com.mcos.runtime.audit.RunRecord
import com.mcos.runtime.audit.StepRecord
import com.mcos.runtime.error.McosErrorCode
import com.mcos.runtime.executor.Executor
import com.mcos.sdk.CommandResult
import kotlinx.coroutines.*
import java.util.UUID

/**
 * WorkflowEngine - orchestrates multi-step command execution with control flow.
 *
 * Built on top of [Executor]. Supports sequential, parallel, conditional,
 * loop, retry, and try/compensation steps.
 *
 * Matches [01-architecture.md §Workflow Engine].
 *
 * @param executor The [Executor] to invoke individual commands.
 * @param auditLog Optional [AuditLog] for recording workflow run records.
 */
class WorkflowEngine(
    private val executor: Executor,
    private val auditLog: AuditLog? = null
) {

    /**
     * Execute a workflow definition.
     *
     * @param definition The root workflow step to execute.
     * @return [WorkflowResult] with step-by-step results and overall outcome.
     */
    suspend fun execute(definition: WorkflowStep): WorkflowResult {
        val runId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        val collectedSteps = mutableListOf<WorkflowStepResult>()
        var outcome = WorkflowOutcome.COMPLETED

        try {
            val rootOk = executeStep(definition, collectedSteps)
            if (!rootOk) {
                outcome = WorkflowOutcome.FAILED
            }
        } catch (e: CancellationException) {
            outcome = WorkflowOutcome.CANCELLED
            throw e
        } catch (e: Exception) {
            collectedSteps.add(
                WorkflowStepResult(
                    commandId = null,
                    ok = false,
                    code = McosErrorCode.WORKFLOW_INVALID.name,
                    message = "Workflow error: ${e.message?.take(200) ?: e.javaClass.simpleName}",
                    durationMs = 0
                )
            )
            outcome = WorkflowOutcome.FAILED
        }

        val totalDurationMs = System.currentTimeMillis() - startTime

        // Record audit
        auditLog?.append(
            RunRecord(
                runId = runId,
                timestamp = startTime,
                source = "WORKFLOW",
                steps = collectedSteps.map { step ->
                    StepRecord(
                        commandId = step.commandId ?: "workflow",
                        pluginId = "workflow",
                        ok = step.ok,
                        code = step.code,
                        message = step.message?.take(200),
                        durationMs = step.durationMs
                    )
                },
                totalDurationMs = totalDurationMs,
                outcome = outcome.name.lowercase()
            )
        )

        return WorkflowResult(
            runId = runId,
            steps = collectedSteps.toList(),
            outcome = outcome,
            totalDurationMs = totalDurationMs
        )
    }

    // ─── Step dispatch ──────────────────────────────────────────────────

    private suspend fun executeStep(
        step: WorkflowStep,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        return when (step) {
            is WorkflowStep.Command -> executeCommand(step, collector)
            is WorkflowStep.Sequential -> executeSequential(step, collector)
            is WorkflowStep.Parallel -> executeParallel(step, collector)
            is WorkflowStep.If -> executeIf(step, collector)
            is WorkflowStep.Loop -> executeLoop(step, collector)
            is WorkflowStep.Retry -> executeRetry(step, collector)
            is WorkflowStep.Try -> executeTry(step, collector)
        }
    }

    // ─── Primitive step types ───────────────────────────────────────────

    private suspend fun executeCommand(
        step: WorkflowStep.Command,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        val start = System.currentTimeMillis()
        val result = executor.execute(step.commandId, step.args)
        val durationMs = System.currentTimeMillis() - start

        val stepResult = when (result) {
            is CommandResult.Ok -> WorkflowStepResult(
                commandId = step.commandId,
                ok = true,
                durationMs = durationMs
            )
            is CommandResult.Err -> WorkflowStepResult(
                commandId = step.commandId,
                ok = false,
                code = result.code,
                message = result.message,
                durationMs = durationMs
            )
        }
        collector.add(stepResult)
        return stepResult.ok
    }

    private suspend fun executeSequential(
        step: WorkflowStep.Sequential,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        var allOk = true
        for (s in step.steps) {
            val ok = executeStep(s, collector)
            if (!ok) {
                allOk = false
                break
            }
        }
        return allOk
    }

    private suspend fun executeParallel(
        step: WorkflowStep.Parallel,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        return coroutineScope {
            val deferred = step.steps.map { s ->
                async {
                    val localCollector = mutableListOf<WorkflowStepResult>()
                    val ok = executeStep(s, localCollector)
                    localCollector to ok
                }
            }
            var allOk = true
            for (d in deferred) {
                val (localResults, ok) = d.await()
                collector.addAll(localResults)
                if (!ok) allOk = false
            }
            allOk
        }
    }

    // ─── Control flow step types ────────────────────────────────────────

    private suspend fun executeIf(
        step: WorkflowStep.If,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        val conditionHolds = evaluateCondition(step.condition, collector)
        return if (conditionHolds) {
            executeStep(step.thenStep, collector)
        } else {
            step.elseStep?.let { executeStep(it, collector) } ?: true
        }
    }

    private suspend fun executeLoop(
        step: WorkflowStep.Loop,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        var iteration = 0
        while (true) {
            // Execute body first (do-while semantics for condition-based loops)
            val ok = executeStep(step.body, collector)
            if (!ok) return false
            iteration++

            // Check max iterations after body execution
            if (iteration >= step.maxIterations) {
                if (step.condition != null && evaluateCondition(step.condition, collector)) {
                    collector.add(
                        WorkflowStepResult(
                            commandId = null,
                            ok = false,
                            code = McosErrorCode.MAX_ITERATIONS_EXCEEDED.name,
                            message = "Loop exceeded max iterations (${step.maxIterations})"
                        )
                    )
                    return false
                }
                break
            }

            // Check condition to decide whether to continue
            val conditionHolds = step.condition?.let { evaluateCondition(it, collector) } ?: true
            if (!conditionHolds) break
        }
        return true
    }

    private suspend fun executeRetry(
        step: WorkflowStep.Retry,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        var attempts = 0
        while (attempts <= step.maxRetries) {
            attempts++
            val ok = executeStep(step.step, collector)
            if (ok) return true
            // Remove failed attempt from collector for retry tracking?
            // Keep all attempts visible in the result for diagnostics.
            if (attempts <= step.maxRetries) {
                delay(step.backoffMs)
            }
        }
        return false
    }

    private suspend fun executeTry(
        step: WorkflowStep.Try,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        val ok = executeStep(step.step, collector)
        if (!ok && step.compensation.isNotEmpty()) {
            // Run compensation steps sequentially
            for (compensateStep in step.compensation) {
                try {
                    executeStep(compensateStep, collector)
                } catch (_: Exception) {
                    collector.add(
                        WorkflowStepResult(
                            commandId = null,
                            ok = false,
                            code = McosErrorCode.COMPENSATION_FAILED.name,
                            message = "Compensation step failed"
                        )
                    )
                }
            }
        }
        return ok
    }

    // ─── Condition evaluation ──────────────────────────────────────────

    private fun evaluateCondition(
        condition: WorkflowCondition,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        return when (condition) {
            is WorkflowCondition.Always -> condition.value
            is WorkflowCondition.BasedOnPrevious -> {
                val last = collector.lastOrNull()
                when (condition.predicate) {
                    WorkflowPredicate.LAST_STEP_SUCCEEDED -> last?.ok == true
                    WorkflowPredicate.LAST_STEP_FAILED -> last != null && !last.ok
                    WorkflowPredicate.HAS_ARTIFACTS -> last != null && last.ok
                }
            }
        }
    }
}
