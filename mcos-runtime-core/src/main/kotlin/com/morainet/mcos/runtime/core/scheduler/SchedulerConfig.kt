package com.morainet.mcos.runtime.core.scheduler

/**
 * Scheduler tuning knobs — 03-runtime.md §8.2 (config mirror at "RuntimeConfig.scheduler",
 * sourced from 08-security.md §10.1) plus the §8.4 fairness/backpressure constants.
 *
 * The three concurrency caps are normative defaults; every field is constructor-tunable
 * so a host can size the scheduler for its device class. Live retuning after [start]
 * is NOT supported (semaphores are fixed at construction) — the hot-reload scenario of
 * 03 §"hot-reload" (`maxParallel` applies to runs enqueued after the change) is host
 * work and deliberately out of scope.
 *
 * @param maxConcurrentInvokes Max parallel run bodies globally (§8.2: default 4).
 *        Enforced by the shared semaphore acquired before dispatch (§8.4).
 * @param maxConcurrentPerPlugin Max parallel command invocations per plugin (§8.2: default 2).
 *        Enforced by [InvocationLimiter] at Executor Stage 8 pre-dispatch.
 * @param maxConcurrentDestructive Max parallel `destructive`-class invocations globally
 *        (§8.2: default 1 — serial).
 * @param laneCapacity Bounded capacity of each of the four lane channels (§8.4: 64).
 * @param backpressureThreshold Queue depth above which a lane is considered under
 *        sustained backpressure (§8.4: "sustained depth > 32").
 * @param drainGraceMs How long [RunScheduler.shutdown] lets queued + in-flight work
 *        finish before cancelling the remainder (03 §"shutdown": default 5 s).
 * @param initialRetryMs First `retryAfterMs` returned when a lane rejects a submission
 *        (§8.4: 500 ms), doubling per repeated rejection of the same run.
 * @param maxRetryMs Ceiling for the exponential rejection backoff (§8.4: 30 s).
 */
data class SchedulerConfig(
    val maxConcurrentInvokes: Int = 4,
    val maxConcurrentPerPlugin: Int = 2,
    val maxConcurrentDestructive: Int = 1,
    val laneCapacity: Int = 64,
    val backpressureThreshold: Int = 32,
    val drainGraceMs: Long = 5_000,
    val initialRetryMs: Long = 500,
    val maxRetryMs: Long = 30_000,
)
