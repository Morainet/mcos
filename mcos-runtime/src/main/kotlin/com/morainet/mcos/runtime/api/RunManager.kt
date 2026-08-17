package com.morainet.mcos.runtime.api

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the lifecycle of in-flight runs, extracted from [McosRuntime] so the
 * facade stays a thin wiring layer.
 *
 * Previously `execute()` created a fresh `CoroutineScope(Dispatchers.Default)`
 * per call — an *orphan* scope with no parent, never cancelled, leaking one
 * scope (and its dispatcher resources) per run. This owned scope fixes that:
 *  - Every run launched by [launch] is a child of [scope], so [shutdown]
 *    cleanly cancels them all.
 *  - The [SupervisorJob] means one run's failure does not cancel sibling
 *    runs (structured concurrency with failure isolation).
 *  - [activeRuns] entries are removed from each run's `finally` block.
 */
internal class RunManager {

    private val activeRuns = ConcurrentHashMap<String, Job>()

    /** Owned coroutine scope for all run executions (P0-C2). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Launch a run as a child of the owned [scope] and track it for
     * cancellation. The [runId] is deregistered in the job's `finally`, so a
     * completed (or failed, or cancelled) run stops being cancellable.
     */
    fun launch(runId: String, block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch {
            try {
                block()
            } finally {
                activeRuns.remove(runId)
            }
        }
        activeRuns[runId] = job
        return job
    }

    /** Cancel a running execution by its runId. */
    fun cancel(runId: String) {
        activeRuns[runId]?.cancel()
    }

    /**
     * Cancel every in-flight run and release the owned coroutine scope
     * (P0-C2). Idempotent; after shutdown, new [launch] calls start on a
     * cancelled scope and complete immediately as cancelled.
     */
    fun shutdown() {
        // Cancel every active run; the SupervisorJob's children are cancelled
        // in bulk by cancelling the scope's job as well.
        activeRuns.values.forEach { it.cancel() }
        activeRuns.clear()
        scope.cancel()
    }
}
