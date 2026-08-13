package com.mcos.runtime.workflow

import com.mcos.runtime.audit.AuditLog
import com.mcos.runtime.audit.RunOutcome
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
 * Matches [01-architecture.md Workflow Engine].
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
                outcome = when (outcome) {
                    WorkflowOutcome.COMPLETED -> RunOutcome.OK
                    WorkflowOutcome.FAILED -> RunOutcome.FAILED
                    WorkflowOutcome.CANCELLED -> RunOutcome.CANCELLED
                }
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
            // P0-C3: when cancelOnFailure is set (default), the first branch to
            // fail flips this flag; sibling branches that have not started yet
            // observe it and skip without executing their side effects. Branches
            // already in flight run to completion (structured cancellation of
            // in-flight work is a P3 concern requiring cooperative checking
            // inside commands).
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val deferred = step.steps.map { s ->
                async {
                    if (step.cancelOnFailure && failed.get()) {
                        // A sibling already failed before this branch began —
                        // skip it and record a CANCELLED result so callers can
                        // tell skipped steps apart from executed ones.
                        mutableListOf(
                            WorkflowStepResult(
                                commandId = null,
                                ok = false,
                                code = McosErrorCode.CANCELLED.name,
                                message = "cancelled_by_sibling"
                            )
                        ) to false
                    } else {
                        val localCollector = mutableListOf<WorkflowStepResult>()
                        val ok = executeStep(s, localCollector)
                        if (!ok && step.cancelOnFailure) {
                            failed.set(true)
                        }
                        localCollector to ok
                    }
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
        // Safety gate: non-idempotent commands are never retried.
        // Retrying a non-idempotent operation (e.g. files.delete) that
        // failed partway through could apply the side effect twice.
        if (!step.idempotent) {
            return executeStep(step.step, collector)
        }

        var attempts = 0
        while (attempts <= step.maxRetries) {
            attempts++
            // Snapshot collector size so we can locate the error result(s)
            // produced by THIS attempt. When the retried step is composite
            // (Sequential/Parallel/If) it may append multiple results, and
            // the trailing entry is not necessarily the one that caused the
            // failure — so we must inspect the whole [sizeBefore, size) slice
            // rather than just lastOrNull().
            val sizeBefore = collector.size
            val ok = executeStep(step.step, collector)
            if (ok) return true

            // Check error code filter: if retryOnCodes is specified and none
            // of the error codes produced by this attempt are in the set,
            // do not retry.
            if (step.retryOnCodes.isNotEmpty()) {
                val newResults = collector.subList(sizeBefore, collector.size)
                val hasRetryableCode = newResults.any { res ->
                    res.code != null && res.code in step.retryOnCodes
                }
                if (!hasRetryableCode) {
                    return false
                }
            }

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
