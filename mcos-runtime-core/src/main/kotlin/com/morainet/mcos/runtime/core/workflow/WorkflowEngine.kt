package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunOutcome
import com.morainet.mcos.security.audit.RunRecord
import com.morainet.mcos.security.audit.StepRecord
import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.security.NullAuditLog
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * @param auditLog [AuditLog] for recording workflow run records. Defaults to
 *        the named [NullAuditLog] (no trail) — pass a real sink explicitly.
 */
class WorkflowEngine(
    private val executor: Executor,
    private val auditLog: AuditLog = NullAuditLog
) {

    /**
     * Execute a workflow definition.
     *
     * @param definition The root workflow step to execute.
     * @param inputs Run inputs exposed to steps as `__input.*` (05 §6.2):
     *        for manual runs the caller-supplied inputs, for event-triggered
     *        runs the matched event payload, for schedule runs empty. Top-level
     *        command args of the shape `{"$ref": "__input.a.b"}` resolve
     *        against this object (dotted paths).
     * @param stepSource Audit source label handed to the [Executor] for every
     *        command step (08 §14) — `"CLI"` for manual runs, `"EVENT"` for
     *        trigger-fired runs. The engine's own run record stays `WORKFLOW`.
     * @param authFor Per-command [AuthStamp] supplier (e.g. a pre-authorized
     *        trigger run's read/write-scoped stamp); returning null for a
     *        command makes that step go through the normal kernel path.
     * @return [WorkflowResult] with step-by-step results and overall outcome.
     */
    suspend fun execute(
        definition: WorkflowStep,
        inputs: JsonObject = JsonObject(emptyMap()),
        stepSource: String = "CLI",
        authFor: (String) -> AuthStamp? = { null }
    ): WorkflowResult {
        val runId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        val collectedSteps = mutableListOf<WorkflowStepResult>()
        var outcome = WorkflowOutcome.COMPLETED
        val ctx = RunContext(inputs, stepSource, authFor)

        try {
            val rootOk = executeStep(definition, ctx, collectedSteps)
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
        auditLog.append(
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

    /** Per-run execution context threaded through every step (05 §6.2). */
    private class RunContext(
        val inputs: JsonObject,
        val stepSource: String,
        val authFor: (String) -> AuthStamp?
    )

    private suspend fun executeStep(
        step: WorkflowStep,
        ctx: RunContext,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        return when (step) {
            is WorkflowStep.Command -> executeCommand(step, ctx, collector)
            is WorkflowStep.Sequential -> executeSequential(step, ctx, collector)
            is WorkflowStep.Parallel -> executeParallel(step, ctx, collector)
            is WorkflowStep.If -> executeIf(step, ctx, collector)
            is WorkflowStep.Loop -> executeLoop(step, ctx, collector)
            is WorkflowStep.Retry -> executeRetry(step, ctx, collector)
            is WorkflowStep.Try -> executeTry(step, ctx, collector)
        }
    }

    // ─── Primitive step types ───────────────────────────────────────────

    private suspend fun executeCommand(
        step: WorkflowStep.Command,
        ctx: RunContext,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        val start = System.currentTimeMillis()
        val resolvedArgs = resolveArgs(step.args, ctx.inputs)
        val result = if (resolvedArgs is ArgResolution.Failed) {
            CommandResult.Err(
                code = McosErrorCode.SCHEMA_VIOLATION.name,
                message = "Unresolvable input reference in args for '${step.commandId}': ${resolvedArgs.reason}",
                retryable = false
            )
        } else {
            executor.execute(
                step.commandId,
                (resolvedArgs as ArgResolution.Resolved).args,
                ctx.authFor(step.commandId),
                source = ctx.stepSource
            )
        }
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

    // ─── `__input` arg resolution (05 §6.2) ─────────────────────────────

    /** Result of resolving `{"$ref": "__input.*"}` values in command args. */
    private sealed class ArgResolution {
        data class Resolved(val args: JsonObject) : ArgResolution()
        data class Failed(val reason: String) : ArgResolution()
    }

    /**
     * Resolve top-level `{"$ref": "__input.a.b"}` arg values against the run
     * inputs (dotted-path traversal). Nested `$ref`s are not resolved this
     * round — the trigger demo only needs top-level references. An
     * unresolvable reference fails the step with `SCHEMA_VIOLATION`
     * (`input_ref_unresolvable`) rather than executing with a dangling ref.
     */
    private fun resolveArgs(args: JsonObject, inputs: JsonObject): ArgResolution {
        if (args.values.none { isInputRef(it) }) return ArgResolution.Resolved(args)
        val resolved = LinkedHashMap<String, JsonElement>(args.size)
        for ((key, value) in args) {
            if (!isInputRef(value)) {
                resolved[key] = value
                continue
            }
            val path = (value as JsonObject)["\$ref"]!!.let { (it as JsonPrimitive).content }
            val target = resolveInputPath(path, inputs)
                ?: return ArgResolution.Failed("input_ref_unresolvable: $path")
            resolved[key] = target
        }
        return ArgResolution.Resolved(JsonObject(resolved))
    }

    private fun isInputRef(value: JsonElement): Boolean =
        value is JsonObject && value.size == 1 && value.containsKey("\$ref")

    /** `__input.a.b` → [inputs]`[a][b]`; bare `__input` → the whole object. */
    private fun resolveInputPath(path: String, inputs: JsonObject): JsonElement? {
        if (!path.startsWith("__input")) return null
        val rest = path.removePrefix("__input").removePrefix(".")
        if (rest.isEmpty()) return inputs
        var current: JsonElement = inputs
        for (segment in rest.split('.')) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    private suspend fun executeSequential(
        step: WorkflowStep.Sequential,
        ctx: RunContext,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        var allOk = true
        for (s in step.steps) {
            val ok = executeStep(s, ctx, collector)
            if (!ok) {
                allOk = false
                break
            }
        }
        return allOk
    }

    private suspend fun executeParallel(
        step: WorkflowStep.Parallel,
        ctx: RunContext,
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
                        val ok = executeStep(s, ctx, localCollector)
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
        ctx: RunContext,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        val conditionHolds = evaluateCondition(step.condition, collector)
        return if (conditionHolds) {
            executeStep(step.thenStep, ctx, collector)
        } else {
            step.elseStep?.let { executeStep(it, ctx, collector) } ?: true
        }
    }

    private suspend fun executeLoop(
        step: WorkflowStep.Loop,
        ctx: RunContext,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        var iteration = 0
        while (true) {
            // Execute body first (do-while semantics for condition-based loops)
            val ok = executeStep(step.body, ctx, collector)
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
        ctx: RunContext,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        // Safety gate: non-idempotent commands are never retried.
        // Retrying a non-idempotent operation (e.g. files.delete) that
        // failed partway through could apply the side effect twice.
        if (!step.idempotent) {
            return executeStep(step.step, ctx, collector)
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
            val ok = executeStep(step.step, ctx, collector)
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
        ctx: RunContext,
        collector: MutableList<WorkflowStepResult>
    ): Boolean {
        val ok = executeStep(step.step, ctx, collector)
        if (!ok && step.compensation.isNotEmpty()) {
            // Run compensation steps sequentially
            for (compensateStep in step.compensation) {
                try {
                    executeStep(compensateStep, ctx, collector)
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
