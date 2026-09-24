package com.morainet.mcos.conformance.kernel

import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.runtime.core.scheduler.DeviceMutexMap
import com.morainet.mcos.runtime.core.scheduler.RunScheduler
import com.morainet.mcos.runtime.core.scheduler.SchedulerConfig
import com.morainet.mcos.runtime.core.scheduler.SchedulerLane
import com.morainet.mcos.runtime.core.scheduler.SubmitResult
import com.morainet.mcos.runtime.core.scheduler.WorkKind
import com.morainet.mcos.sdk.McosException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Scheduler-semantics conformance cases (03-runtime.md §8) — the Runtime
 * Semantics surface 10 §17.0 names ("scheduler lanes & cancellation") that,
 * until these cases, was pinned only by `mcos-runtime-core` unit tests and
 * had no case in the gate.
 *
 * Each case pins one observable §8 semantic through the public scheduler
 * surface ([RunScheduler] / [DeviceMutexMap]) — the same objects the Executor
 * and WorkflowEngine drive, so a semantic change in the scheduler fails the
 * gate's baseline even when every unit test was updated alongside it.
 *
 * Determinism strategy (mirrors `RunSchedulerTest`): bodies signal their start
 * via [CompletableDeferred]s and block on caller-held gates; "not started"
 * assertions use [withTimeoutOrNull] windows that are logically impossible
 * when the scheduler is correct (the permit or device mutex is provably held
 * elsewhere for the whole window); admission/backoff cases avoid the
 * worker-pickup race by never calling `start()` (enqueued items wait on the
 * lane channel — documented behavior).
 */
internal fun schedulerSemanticsCases(): List<ConformanceCase> = listOf(
    schedulerGlobalCapCase(),
    schedulerFullLaneBackoffCase(),
    schedulerExpeditedCancellationOnlyCase(),
    schedulerQueuedCancelCase(),
    deviceMutexSerialCase(),
    deviceMutexNestedAcquisitionCase(),
)

// ─── §8.2/§8.4: the global concurrency cap ─────────────────────────────

private fun schedulerGlobalCapCase(): ConformanceCase = object : ConformanceCase {
    override val id = "kernel-scheduler-global-cap"
    override val title = "the global concurrency cap serializes bodies across lanes"
    override val spec = "03 §8.2 (max parallel invocations globally) + §8.4 (shared semaphore)"
    override val category = "kernel"

    override fun run(): ConformanceCase.Result = runBlocking {
        val scheduler = RunScheduler(SchedulerConfig(maxConcurrentInvokes = 2))
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val releaseHolders = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "a", "fa") {
            firstStarted.complete(Unit)
            releaseHolders.await()
        }
        scheduler.enqueue(SchedulerLane.WORKFLOW, WorkKind.RUN, "b", "fb") {
            secondStarted.complete(Unit)
            releaseHolders.await()
        }
        // Both permits provably held before "c" is even enqueued.
        withTimeout(5_000) { firstStarted.await(); secondStarted.await() }

        // Different lane, free worker — only the shared semaphore can hold it back.
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "c", "fc") {
            thirdStarted.complete(Unit)
        }
        val leaked = withTimeoutOrNull(250) { thirdStarted.await() }
        releaseHolders.complete(Unit)

        return@runBlocking when {
            leaked != null -> ConformanceCase.Result.Fail(
                message = "a third body ran while two permit-holders were still blocked — " +
                    "the global concurrency cap (03 §8.2/§8.4) is no longer enforced across lanes",
            )
            else -> {
                withTimeout(5_000) { thirdStarted.await() }
                val dropped = scheduler.shutdown()
                if (dropped.isNotEmpty()) {
                    ConformanceCase.Result.Fail(message = "shutdown reported never-started runs: $dropped")
                } else {
                    ConformanceCase.Result.Pass
                }
            }
        }
    }
}

// ─── §8.4: full-lane admission + exponential backoff ───────────────────

private fun schedulerFullLaneBackoffCase(): ConformanceCase = object : ConformanceCase {
    override val id = "kernel-scheduler-full-lane-backoff"
    override val title = "a full lane rejects RATE_LIMITED with 500ms→doubling retryAfterMs"
    override val spec = "03 §8.4 (bounded lanes, exponential backoff)"
    override val category = "kernel"

    override fun run(): ConformanceCase.Result {
        // No start(): items wait on the lane channel, so capacity is observed
        // deterministically without the worker-pickup race.
        val scheduler = RunScheduler(SchedulerConfig(laneCapacity = 2, maxRetryMs = 2_000))
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "fill-1", "filler") {}
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "fill-2", "filler2") {}

        val first = scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r1", "same") {}
        val rejection = first as? SubmitResult.Rejected
            ?: return ConformanceCase.Result.Fail(message = "a full lane must reject, got $first")
        if (rejection.code != "RATE_LIMITED") {
            return ConformanceCase.Result.Fail(
                message = "a full lane must reject with RATE_LIMITED, got '${rejection.code}'",
            )
        }
        if (rejection.retryAfterMs != 500L) {
            return ConformanceCase.Result.Fail(
                message = "the first rejection's hint must be the 500 ms initial value " +
                    "(03 §8.4), got ${rejection.retryAfterMs} ms",
            )
        }
        val subsequent = (2..4).map { i ->
            val r = scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r$i", "same") {}
            (r as? SubmitResult.Rejected)?.retryAfterMs
                ?: return ConformanceCase.Result.Fail(message = "rejection $i must stay RATE_LIMITED, got $r")
        }
        val expected = listOf(1_000L, 2_000L, 2_000L) // doubling, capped at maxRetryMs
        return if (subsequent == expected) {
            ConformanceCase.Result.Pass
        } else {
            ConformanceCase.Result.Fail(
                message = "repeated rejection of the same submission must double the hint " +
                    "and cap it at maxRetryMs (03 §8.4): expected $expected, got $subsequent",
            )
        }
    }
}

// ─── §8.4: the expedited lane is cancellation-only ─────────────────────

private fun schedulerExpeditedCancellationOnlyCase(): ConformanceCase = object : ConformanceCase {
    override val id = "kernel-scheduler-expedited-cancellation-only"
    override val title = "non-cancellation work on the expedited lane is rejected with INTERNAL"
    override val spec = "03 §8.4 (expedited is cancellation run-requests only)"
    override val category = "kernel"

    override fun run(): ConformanceCase.Result {
        val scheduler = RunScheduler()
        val rejected = scheduler.enqueue(SchedulerLane.EXPEDITED, WorkKind.RUN, "r1", "f1") {}
        val rejection = rejected as? SubmitResult.Rejected
            ?: return ConformanceCase.Result.Fail(message = "a RUN on expedited must be rejected, got $rejected")
        return if (rejection.code == "INTERNAL" && rejection.retryAfterMs == null) {
            ConformanceCase.Result.Pass
        } else {
            ConformanceCase.Result.Fail(
                message = "expedited-lane misuse is a configuration bug and must surface " +
                    "INTERNAL with no backoff (03 §8.4), got '${rejection.code}' / ${rejection.retryAfterMs}",
            )
        }
    }
}

// ─── §8.3: two-phase cancellation of a queued run ──────────────────────

private fun schedulerQueuedCancelCase(): ConformanceCase = object : ConformanceCase {
    override val id = "kernel-scheduler-queued-cancel"
    override val title = "cancelling a queued run drops its body (never runs) and returns true"
    override val spec = "03 §8.3 (cancel of a queued item)"
    override val category = "kernel"

    override fun run(): ConformanceCase.Result = runBlocking {
        val scheduler = RunScheduler(SchedulerConfig(maxConcurrentInvokes = 1))
        val holderStarted = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val queuedBodyRan = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "holder", "fh") {
            holderStarted.complete(Unit)
            releaseHolder.await()
        }
        withTimeout(5_000) { holderStarted.await() } // the only permit is provably held

        // Same lane: with cap 1 there is exactly one worker, so "queued" waits
        // on the channel behind the holder.
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "queued", "fq") {
            queuedBodyRan.complete(Unit)
        }
        val cancelledByCaller = scheduler.cancel("queued")
        releaseHolder.complete(Unit)

        val ran = withTimeoutOrNull(250) { queuedBodyRan.await() }
        val dropped = scheduler.shutdown()
        return@runBlocking when {
            !cancelledByCaller -> ConformanceCase.Result.Fail(
                message = "cancel of a queued item must return true so the caller publishes " +
                    "the terminal RunCancelled event (03 §8.3)",
            )
            ran != null -> ConformanceCase.Result.Fail(
                message = "the cancelled body ran — a queued cancel must drop the item " +
                    "before its body ever starts (03 §8.3)",
            )
            dropped.isNotEmpty() -> ConformanceCase.Result.Fail(
                message = "shutdown reported the cancelled run as never-started: $dropped — " +
                    "its terminal event was already the caller's responsibility",
            )
            else -> ConformanceCase.Result.Pass
        }
    }
}

// ─── §8.5: per-device serialization ────────────────────────────────────

private fun deviceMutexSerialCase(): ConformanceCase = object : ConformanceCase {
    override val id = "kernel-scheduler-device-mutex-serial"
    override val title = "two runs targeting the same device serialize on its mutex"
    override val spec = "03 §8.5 (IoT control serial per device id)"
    override val category = "kernel"

    override fun run(): ConformanceCase.Result = runBlocking {
        val map = DeviceMutexMap()
        val firstIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondIn = CompletableDeferred<Unit>()

        val first = launch {
            map.withDevices("run-1", listOf("device-A")) {
                firstIn.complete(Unit)
                releaseFirst.await()
            }
        }
        withTimeout(5_000) { firstIn.await() } // device-A provably held

        val second = async {
            map.withDevices("run-2", listOf("device-A")) { secondIn.complete(Unit) }
        }
        val raced = withTimeoutOrNull(250) { secondIn.await() }
        releaseFirst.complete(Unit)

        return@runBlocking when {
            raced != null -> ConformanceCase.Result.Fail(
                message = "two runs entered the same device concurrently — the per-device " +
                    "serial rule (03 §8.5) is no longer enforced",
            )
            else -> {
                withTimeout(5_000) { second.await(); first.join() }
                if (map.heldDevices("run-1").isNotEmpty() || map.heldDevices("run-2").isNotEmpty()) {
                    ConformanceCase.Result.Fail(message = "device mutexes leaked after both runs finished")
                } else {
                    ConformanceCase.Result.Pass
                }
            }
        }
    }
}

// ─── §8.5: no nested acquisition ───────────────────────────────────────

private fun deviceMutexNestedAcquisitionCase(): ConformanceCase = object : ConformanceCase {
    override val id = "kernel-scheduler-device-mutex-nested"
    override val title = "a nested same-run device acquisition is rejected with CONFLICT/device_locked"
    override val spec = "03 §8.5 (strict no-nested-acquisition rule)"
    override val category = "kernel"

    override fun run(): ConformanceCase.Result = runBlocking {
        val map = DeviceMutexMap()
        val firstIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val outer = launch {
            map.withDevices("run-1", listOf("device-A")) {
                firstIn.complete(Unit)
                releaseFirst.await()
            }
        }
        withTimeout(5_000) { firstIn.await() }

        val nested = runCatching {
            map.withDevices("run-1", listOf("device-B")) { }
        }
        releaseFirst.complete(Unit)
        withTimeout(5_000) { outer.join() }

        val e = nested.exceptionOrNull()
        return@runBlocking when {
            nested.isSuccess -> ConformanceCase.Result.Fail(
                message = "a nested acquisition by the same runId was accepted — the " +
                    "no-nested-acquisition rule (03 §8.5) must reject it with CONFLICT",
            )
            e !is McosException -> ConformanceCase.Result.Fail(
                message = "nested acquisition must fail as McosException, got ${e!!.javaClass.simpleName}",
            )
            e.code != "CONFLICT" -> ConformanceCase.Result.Fail(
                message = "nested acquisition must surface CONFLICT, got '${e.code}'",
            )
            else -> {
                val reason = e.details["reason"]?.toString()?.trim('"')
                if (reason == "device_locked") {
                    ConformanceCase.Result.Pass
                } else {
                    ConformanceCase.Result.Fail(
                        message = "the CONFLICT details must carry reason=device_locked " +
                            "with held/requested/runId (03 §8.5), got details=${e.details}",
                    )
                }
            }
        }
    }
}
