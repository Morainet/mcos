package com.morainet.mcos.runtime.core.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun config(
    maxConcurrentInvokes: Int = 4,
    laneCapacity: Int = 64,
    initialRetryMs: Long = 500,
    maxRetryMs: Long = 5_000,
) = SchedulerConfig().copy(
    maxConcurrentInvokes = maxConcurrentInvokes,
    laneCapacity = laneCapacity,
    initialRetryMs = initialRetryMs,
    maxRetryMs = maxRetryMs,
)

/**
 * Live scheduler hot-retuning ([RunScheduler.reconfigure], 03-runtime.md §19.1):
 * a raised cap applies to runs acquiring after the change, a lowered cap never
 * interrupts in-flight runs (concurrency decays as they finish), the backoff
 * curve re-tunes from the next rejection, and invalid/drifting configs are
 * refused loudly.
 */
class RunSchedulerRetuneTest {

    @Test
    fun `RT1-hot raise applies to runs acquiring after the change and returns the previous config`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1))
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "first", "fp") {
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        withTimeout(5_000) { firstStarted.await() }

        // Lift 1 -> 2 while "first" holds the only permit.
        val previous = scheduler.reconfigure(config(maxConcurrentInvokes = 2))
        assertEquals(1, previous.maxConcurrentInvokes)

        val bStarted = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val cStarted = CompletableDeferred<Unit>()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "b", "fp-b") {
            bStarted.complete(Unit)
            releaseB.await()
        }
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "c", "fp-c") {
            cStarted.complete(Unit)
        }
        // Exactly one extra permit exists (first still holds one): b proceeds, c waits.
        withTimeout(5_000) { bStarted.await() }
        delay(150)
        assertTrue(!cStarted.isCompleted, "c must wait for a permit, not start immediately")

        releaseB.complete(Unit)
        withTimeout(5_000) { cStarted.await() }
        releaseFirst.complete(Unit)

        scheduler.shutdown()
    }

    @Test
    fun `RT2-hot shrink never interrupts in-flight and gates new runs until a permit frees`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 2))
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "a", "fp") {
            aStarted.complete(Unit)
            releaseA.await()
        }
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "b", "fp") {
            bStarted.complete(Unit)
            releaseB.await()
        }
        withTimeout(5_000) { aStarted.await() }
        withTimeout(5_000) { bStarted.await() }

        val previous = scheduler.reconfigure(config(maxConcurrentInvokes = 1))
        assertEquals(2, previous.maxConcurrentInvokes)

        val cStarted = CompletableDeferred<Unit>()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "c", "fp-c") {
            cStarted.complete(Unit)
        }
        // Both in-flight runs keep their permits (never interrupted); c waits.
        delay(150)
        assertTrue(!cStarted.isCompleted, "c must wait while a and b still hold permits")

        releaseA.complete(Unit) // the freed permit is handed to c (FIFO)
        withTimeout(5_000) { cStarted.await() }
        releaseB.complete(Unit)

        scheduler.shutdown()
    }

    @Test
    fun `RT3-hot backoff retune applies from the next rejection`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(laneCapacity = 1)) // not started: r1 pins the channel
        assertIs<SubmitResult.Admitted>(
            scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r1", "keep") {}
        )
        val first = scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r2", "x") {}
        assertEquals(500L, assertIs<SubmitResult.Rejected>(first).retryAfterMs)

        scheduler.reconfigure(config(laneCapacity = 1, initialRetryMs = 1_000, maxRetryMs = 4_000))
        // Same fingerprint, now counting a 2nd rejection under the new 1000ms base.
        val second = scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r3", "x") {}
        assertEquals(2_000L, assertIs<SubmitResult.Rejected>(second).retryAfterMs)
        // A fresh fingerprint starts at the new base.
        val fresh = scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r4", "fresh") {}
        assertEquals(1_000L, assertIs<SubmitResult.Rejected>(fresh).retryAfterMs)
        scheduler.shutdown()
    }

    @Test
    fun `RT4-invalid or drifting configs are refused loudly`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config())
        val e0 = assertFailsWith<IllegalArgumentException> {
            scheduler.reconfigure(config(maxConcurrentInvokes = 0))
        }
        assertTrue(e0.message!!.contains("1..16"), "msg: ${e0.message}")
        assertFailsWith<IllegalArgumentException> {
            scheduler.reconfigure(config(maxConcurrentInvokes = 17))
        }
        val drift = assertFailsWith<IllegalArgumentException> {
            scheduler.reconfigure(config(laneCapacity = 8))
        }
        assertTrue(drift.message!!.contains("builder-time"), "msg: ${drift.message}")
        assertFailsWith<IllegalArgumentException> {
            scheduler.reconfigure(config(initialRetryMs = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            scheduler.reconfigure(config(maxRetryMs = 100))
        }

        val other = RunScheduler(config())
        other.shutdown()
        assertFailsWith<IllegalArgumentException> {
            other.reconfigure(config(maxConcurrentInvokes = 2))
        }
        scheduler.shutdown()
    }
}
