package com.morainet.mcos.runtime.core.scheduler

import com.morainet.mcos.runtime.core.error.McosErrorCode
import java.util.Collections
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RunScheduler — the Stage-7 admission + dispatch layer (03-runtime.md §8).
 *
 * Replaces the previous `RunManager` (unbounded `scope.launch` on every execute —
 * no admission control, no concurrency cap, no backpressure) while keeping its
 * structured-concurrency guarantees (P0-C2):
 *  - Every run body is a child of the owned [scope], so [shutdown] cleanly
 *    cancels them; the [SupervisorJob] means one run's failure does not cancel
 *    its siblings.
 *
 * ## Lanes (§8.1)
 * Four bounded channels ([SchedulerConfig.laneCapacity], default 64): `interactive`
 * (user-facing CLI/chat), `workflow` (multi-step jobs), `background` (event/schedule
 * triggered), `expedited` (cancellation run-requests ONLY — anything else enqueued
 * there is rejected with `INTERNAL`, §8.4).
 *
 * ## Concurrency (§8.2 / §8.4)
 * Each lane has a dedicated worker pool (sized by [SchedulerConfig.maxConcurrentInvokes])
 * so a saturated `background` lane cannot starve `interactive` — there is no strict
 * priority across lanes. The global cap is a shared [Semaphore] acquired before the
 * body dispatch and released on completion. Waiting for a permit never counts against
 * the command timeout (the Executor's `withTimeout` starts inside the body).
 *
 * ## Backpressure (§8.4)
 * A full lane rejects the submission with `RATE_LIMITED` + `retryAfterMs`
 * (500 ms first rejection, doubling per repeated rejection of the same submission
 * fingerprint, capped at 30 s). Sustained lane depth over
 * [SchedulerConfig.backpressureThreshold] fires [onBackpressure] once per episode
 * (sampling is event-driven — on enqueue and worker pickup — not on a timer).
 *
 * ## Cancellation (§8.3)
 * [cancel] of a queued item drops it before its body ever runs and returns `true`
 * so the caller publishes the terminal `RunCancelled` event itself (observers must
 * see a terminal event — EventBus rule). [cancel] of a running item cancels its job;
 * the body's own cancellation path produces the terminal event. As-built, `cancel()`
 * is O(1) cooperative cancellation that never contends with the semaphore — strictly
 * better than routing it through `expedited` admission; the expedited lane is still
 * reserved and guarded per §8.4.
 *
 * ## Shutdown (03 §"shutdown")
 * [shutdown] closes admission (`UNAVAILABLE` for further enqueues), then gives queued
 * and in-flight work [SchedulerConfig.drainGraceMs] to finish before cancelling the
 * remainder. Returns the runIds whose bodies never ran so the caller can publish
 * terminal events for them.
 *
 * @param config Tuning knobs; see [SchedulerConfig].
 * @param onBackpressure Invoked when a lane enters a sustained-backpressure episode
 *        (lane, current depth). Wired by the host to the `scheduler.backpressure`
 *        system event + audit record (§8.4 "Observability").
 * @param dispatcher Dispatcher for workers and run bodies (injectable for tests).
 * @param timeSource Millisecond clock for semaphore wait metrics (injectable for tests).
 */
class RunScheduler(
    private val config: SchedulerConfig = SchedulerConfig(),
    private val onBackpressure: suspend (lane: SchedulerLane, depth: Int) -> Unit = { _, _ -> },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeSource: () -> Long = System::currentTimeMillis,
) {

    /** One admitted item on a lane channel. */
    private class QueuedItem(
        val runId: String,
        val lane: SchedulerLane,
        val body: suspend () -> Unit,
    ) {
        /** Guards the dropped-vs-started decision (queued-cancel race, §8.3). */
        val lock = Any()

        /** Set when the item is cancelled before its body starts. */
        @Volatile
        var dropped: Boolean = false

        /** Set immediately before the body runs (under [lock]). */
        @Volatile
        var started: Boolean = false

        /** Completed once the item is fully finished (skipped, cancelled or run). */
        val gate: CompletableJob = Job()
    }

    /** Per-lane channel + depth/inFlight counters. */
    private class LaneState(val lane: SchedulerLane, capacity: Int) {
        val channel: Channel<QueuedItem> = Channel(capacity)
        val depth = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
    }

    private class BackoffTracker(private val initialMs: Long, private val maxMs: Long) {
        private val mutex = Any()

        /** Access-order LRU: rejection counts per submission fingerprint, bounded. */
        private val rejections = LinkedHashMap<String, Int>(16, 0.75f, true)

        /** Record one more rejection of [key] and return its §8.4 backoff hint. */
        fun nextRetryMs(key: String): Long = synchronized(mutex) {
            if (rejections.size > MAX_TRACKED_KEYS) {
                val entryIter = rejections.entries.iterator()
                repeat(rejections.size - MAX_TRACKED_KEYS) {
                    entryIter.next()
                    entryIter.remove()
                }
            }
            val count = (rejections[key] ?: 0) + 1
            rejections[key] = count
            var ms = initialMs
            repeat(count - 1) { ms = (ms * 2).coerceAtMost(maxMs) }
            ms
        }

        /** A successful admission clears the fingerprint's escalation. */
        fun reset(key: String) = synchronized(mutex) { rejections.remove(key) }

        companion object {
            private const val MAX_TRACKED_KEYS = 256
        }
    }

    private val lanes: Map<SchedulerLane, LaneState> = SchedulerLane.entries.associateWith {
        LaneState(it, config.laneCapacity)
    }

    private val globalSemaphore = Semaphore(config.maxConcurrentInvokes.coerceAtLeast(1))
    private val backoff = BackoffTracker(config.initialRetryMs, config.maxRetryMs)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val queuedRuns = ConcurrentHashMap<String, QueuedItem>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val workers = Collections.synchronizedList(mutableListOf<Job>())

    private val started = AtomicBoolean(false)
    private val shutdownFlag = AtomicBoolean(false)

    // Lanes currently in a sustained-backpressure episode (§8.4) — the episode fires
    // once on entry and re-arms after depth recovers to <= threshold.
    private val pressuredLanes = Collections.synchronizedSet(mutableSetOf<SchedulerLane>())

    // Semaphore wait metrics (§8.4 "Observability").
    private val semaphoreAcquisitions = AtomicLong(0)
    private val semaphoreMaxWaitMs = AtomicLong(0)
    @Volatile
    private var semaphoreLastWaitMs: Long = 0

    /**
     * Spin up the per-lane worker coroutines (03 §"startup" step 5: "Scheduler —
     * spin up queue channels + worker coroutines"). Idempotent. Items may be
     * enqueued before [start] — they wait on the lane channel until workers run.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val workersPerLane = config.maxConcurrentInvokes.coerceAtLeast(1)
        for (state in lanes.values) {
            repeat(workersPerLane) {
                workers.add(
                    scope.launch {
                        for (item in state.channel) {
                            processItem(state, item)
                        }
                    }
                )
            }
        }
    }

    /**
     * Admission (Stage 7). Enqueues [body] onto [lane] unless the lane is full
     * (`RATE_LIMITED` + exponential `retryAfterMs`), the expedited guard trips
     * (`INTERNAL` — only [WorkKind.CANCELLATION] may use [SchedulerLane.EXPEDITED]),
     * or the scheduler is shut down (`UNAVAILABLE`).
     *
     * @param fingerprint Stable identity of the submission for the §8.4 repeated-
     *        rejection backoff (e.g. `source + payload`); distinct submissions
     *        get fresh 500 ms hints.
     */
    fun enqueue(
        lane: SchedulerLane,
        kind: WorkKind,
        runId: String,
        fingerprint: String,
        body: suspend () -> Unit,
    ): SubmitResult {
        if (shutdownFlag.get()) {
            return SubmitResult.Rejected(
                code = McosErrorCode.UNAVAILABLE.name,
                retryAfterMs = null,
                message = "Scheduler is shutting down; run '$runId' was not enqueued",
            )
        }
        if (lane == SchedulerLane.EXPEDITED && kind != WorkKind.CANCELLATION) {
            return SubmitResult.Rejected(
                code = McosErrorCode.INTERNAL.name,
                retryAfterMs = null,
                message = "Only cancellation run-requests may use the expedited lane (03 §8.4); " +
                    "got $kind for run '$runId'",
            )
        }
        val state = lanes.getValue(lane)
        val item = QueuedItem(runId, lane, body)
        // Register before the send: with an inline-dispatching receiver the
        // worker could observe the item before this thread returns otherwise
        // (leaking a completed item's entry in [queuedRuns]).
        queuedRuns[runId] = item
        val outcome = state.channel.trySend(item)
        if (outcome.isFailure) {
            queuedRuns.remove(runId)
            val retryAfterMs = backoff.nextRetryMs(fingerprint)
            return SubmitResult.Rejected(
                code = McosErrorCode.RATE_LIMITED.name,
                retryAfterMs = retryAfterMs,
                message = "Scheduler lane '$lane' is full (capacity ${config.laneCapacity}); " +
                    "retry after ${retryAfterMs}ms",
            )
        }
        backoff.reset(fingerprint)
        maybeUpdateBackpressure(state, state.depth.incrementAndGet())
        return SubmitResult.Admitted(runId)
    }

    /**
     * Cancel a run (§8.3). Two phases:
     *  - queued (or picked but not started): the item is dropped — its body will
     *    never run — and `true` is returned so the caller publishes the terminal
     *    `RunCancelled` event itself.
     *  - running: the body's job is cancelled and `false` returned — the body's
     *    own cancellation path produces the terminal event.
     *
     * Cancelling an unknown or already-finished run is a no-op returning `false`.
     */
    fun cancel(runId: String): Boolean {
        val item = queuedRuns[runId] ?: run {
            runningJobs[runId]?.cancel()
            return false
        }
        val droppedWhileQueued = synchronized(item.lock) {
            if (item.started) {
                false
            } else {
                item.dropped = true
                true
            }
        }
        if (!droppedWhileQueued) {
            runningJobs[runId]?.cancel()
            return false
        }
        // The body will never run — deregister now so shutdown() does not
        // report an already-cancelled run as "never started" again (the
        // caller already published the terminal event for it).
        queuedRuns.remove(runId)
        item.gate.cancel()
        return true
    }

    /** Point-in-time observability snapshot (§8.4). */
    fun metrics(): SchedulerMetrics = SchedulerMetrics(
        laneDepth = lanes.mapValues { it.value.depth.get() },
        laneInFlight = lanes.mapValues { it.value.inFlight.get() },
        semaphoreAcquisitions = semaphoreAcquisitions.get(),
        semaphoreMaxWaitMs = semaphoreMaxWaitMs.get(),
        semaphoreLastWaitMs = semaphoreLastWaitMs,
    )

    /**
     * Shut down: reject further enqueues, give queued + in-flight work
     * [SchedulerConfig.drainGraceMs] to finish, then cancel the remainder.
     * Idempotent.
     *
     * @return runIds whose bodies never ran (dropped at shutdown) — the caller
     *         should publish terminal `RunCancelled` events for them.
     */
    fun shutdown(): List<String> {
        if (!shutdownFlag.compareAndSet(false, true)) return emptyList()
        lanes.values.forEach { it.channel.close() }
        val pending = (workers.toList() + runningJobs.values.toList())
        val drained = runBlocking {
            withTimeoutOrNull(config.drainGraceMs) {
                pending.joinAll()
                true
            } ?: false
        }
        if (!drained) {
            scope.cancel()
        }
        // Whatever never started is dropped — surface it so no observer hangs.
        return queuedRuns.values.filter { !it.started }.map { it.runId }.also {
            it.forEach { runId -> cancel(runId) }
        }
    }

    // ─── Internals ──────────────────────────────────────────────────────

    /**
     * Worker body for one received item: decrement depth, then run the item's
     * body as a supervisor child under the global semaphore. The worker slot
     * (and therefore the lane's pool) is held for the item's full duration.
     */
    private suspend fun processItem(state: LaneState, item: QueuedItem) {
        maybeUpdateBackpressure(state, state.depth.decrementAndGet())
        if (item.dropped) {
            // Cancelled while queued: the body never runs, so the finally in
            // [runItem] never will either — clean the registration here.
            queuedRuns.remove(item.runId)
            item.gate.complete()
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { runItem(state, item) }
        // Register before start so cancel() of a just-started body always finds
        // its job (the queued/running handoff race, §8.3).
        runningJobs[item.runId] = job
        try {
            job.start()
            job.join()
        } finally {
            runningJobs.remove(item.runId)
        }
    }

    private suspend fun runItem(state: LaneState, item: QueuedItem) {
        // Queued-cancel race: whoever takes item.lock first wins. If cancel()
        // marked the item dropped, the body never runs and cancel() returned
        // true (the caller publishes the terminal event).
        val shouldRun = synchronized(item.lock) {
            if (item.dropped) {
                false
            } else {
                item.started = true
                true
            }
        }
        if (!shouldRun) {
            // Lost the queued-cancel race: same cleanup duty as above.
            queuedRuns.remove(item.runId)
            item.gate.complete()
            return
        }
        val waitStart = timeSource()
        globalSemaphore.acquire()
        val waitMs = timeSource() - waitStart
        semaphoreAcquisitions.incrementAndGet()
        semaphoreLastWaitMs = waitMs
        semaphoreMaxWaitMs.accumulateAndGet(waitMs) { cur, new -> maxOf(cur, new) }
        state.inFlight.incrementAndGet()
        try {
            item.body()
        } finally {
            state.inFlight.decrementAndGet()
            globalSemaphore.release()
            queuedRuns.remove(item.runId)
            item.gate.complete()
        }
    }

    /**
     * Sustained-backpressure episode tracking (§8.4): fire [onBackpressure] when a
     * lane crosses the threshold, re-arm once depth recovers to <= threshold.
     * Sampling is event-driven (enqueue + worker pickup), not a timer — the
     * "sustained" qualifier is approximated by episode edges, not duration.
     */
    private fun maybeUpdateBackpressure(state: LaneState, depth: Int) {
        val entered = if (depth > config.backpressureThreshold) {
            pressuredLanes.add(state.lane)
        } else {
            pressuredLanes.remove(state.lane)
            false
        }
        if (!entered) return
        // Fire-and-forget outside any lock; the callback publishes to the event
        // bus. Not tracked in `workers` — a stuck callback must not block
        // shutdown's grace drain, and scope.cancel() reaps it at the end.
        scope.launch { onBackpressure(state.lane, depth) }
    }
}
