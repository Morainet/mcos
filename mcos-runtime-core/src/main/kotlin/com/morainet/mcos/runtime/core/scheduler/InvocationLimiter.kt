package com.morainet.mcos.runtime.core.scheduler

import com.morainet.mcos.sdk.SideEffectClass
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore

/**
 * InvocationLimiter — the §8.2 per-invocation concurrency caps, enforced at the
 * Executor's Stage-8 pre-dispatch (below run granularity: a workflow's parallel
 * siblings funnel through the Executor, so the caps hold for workflow runs too).
 *
 *  - Max parallel invocations **per plugin**: [SchedulerConfig.maxConcurrentPerPlugin] (default 2).
 *  - Max parallel `destructive` invocations **globally**: [SchedulerConfig.maxConcurrentDestructive]
 *    (default 1 — destructive effects are serialized device-wide).
 *
 * Acquisition order is fixed (destructive slot → plugin slot) so concurrent
 * callers cannot deadlock against each other. Permits are held for the handler
 * dispatch only — authorization, egress and rate limiting have already run by
 * the time a slot is acquired, so a policy-rejected command never holds a slot.
 *
 * A `null` limiter (the Executor default) disables the caps — matching the
 * optional-by-default posture of the other host-tunable subsystems.
 */
class InvocationLimiter(config: SchedulerConfig = SchedulerConfig()) {

    private val perPluginPermits = config.maxConcurrentPerPlugin.coerceAtLeast(1)
    private val destructive = Semaphore(config.maxConcurrentDestructive.coerceAtLeast(1))
    private val perPlugin = ConcurrentHashMap<String, Semaphore>()

    /**
     * Run [block] under the invocation caps for [pluginId] / [sideEffectClass].
     * Waits (suspend) until both applicable slots are free; releases them on
     * any completion path, including cancellation.
     */
    suspend fun <T> withPermits(
        pluginId: String,
        sideEffectClass: SideEffectClass,
        block: suspend () -> T,
    ): T {
        if (sideEffectClass != SideEffectClass.destructive) {
            return withPluginSlot(pluginId, block)
        }
        destructive.acquire()
        try {
            return withPluginSlot(pluginId, block)
        } finally {
            destructive.release()
        }
    }

    private suspend fun <T> withPluginSlot(pluginId: String, block: suspend () -> T): T {
        val sem = perPlugin.computeIfAbsent(pluginId) { Semaphore(perPluginPermits) }
        sem.acquire()
        try {
            return block()
        } finally {
            sem.release()
        }
    }
}
