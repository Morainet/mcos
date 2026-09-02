package com.morainet.mcos.runtime.core.scheduler

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.*

/**
 * Conformance tests for [RunScheduler] — the Stage-7 admission + dispatch
 * layer (03-runtime.md §8.1-§8.4).
 *
 * Determinism strategy: real dispatcher but explicit synchronization — bodies
 * signal their start via [CompletableDeferred]s and block on caller-held
 * gates, "not started" assertions use [withTimeoutOrNull] windows that are
 * logically impossible when the scheduler is correct (the permit is provably
 * held elsewhere for the whole window), and capacity/backoff tests avoid the
 * worker-pickup race by not calling [RunScheduler.start] (enqueued items wait
 * on the lane channel — documented behavior).
 */
class RunSchedulerTest {

    private fun config(
        maxConcurrentInvokes: Int = 4,
        laneCapacity: Int = 64,
        backpressureThreshold: Int = 32,
        initialRetryMs: Long = 500,
        maxRetryMs: Long = 30_000,
        drainGraceMs: Long = 5_000,
    ) = SchedulerConfig(
        maxConcurrentInvokes = maxConcurrentInvokes,
        laneCapacity = laneCapacity,
        backpressureThreshold = backpressureThreshold,
        initialRetryMs = initialRetryMs,
        maxRetryMs = maxRetryMs,
        drainGraceMs = drainGraceMs,
    )

    // ═══════════════════════════════════════════════════════════════
    // SC1-SC3: Lanes, global cap, admission
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SC1-lane preserves FIFO order under single permit`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1))
        val order = ConcurrentLinkedQueue<String>()
        val done = (1..3).map { CompletableDeferred<Unit>() }

        done.forEachIndexed { i, d ->
            val result = scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "run-${i + 1}", "f${i + 1}") {
                order.add("run-${i + 1}")
                d.complete(Unit)
            }
            assertIs<SubmitResult.Admitted>(result)
        }
        scheduler.start()

        withTimeout(5_000) { done.awaitAll() }
        assertEquals(listOf("run-1", "run-2", "run-3"), order.toList())
        assertEquals(0, scheduler.shutdown().size)
    }

    @Test
    fun `SC2-global semaphore serializes bodies across lanes`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1))
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "a", "fa") {
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        // "a" provably holds the only permit before "b" is even enqueued —
        // whichever lane's worker reaches the semaphore first is irrelevant.
        withTimeout(5_000) { firstStarted.await() }

        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "b", "fb") {
            secondStarted.complete(Unit)
        }
        assertNull(withTimeoutOrNull(200) { secondStarted.await() })

        releaseFirst.complete(Unit)
        withTimeout(5_000) { secondStarted.await() }
        assertEquals(0, scheduler.shutdown().size)
    }

    @Test
    fun `SC3-full lane rejects with RATE_LIMITED and 500ms hint`() {
        val scheduler = RunScheduler(config(laneCapacity = 2))
        // No start(): items wait on the channel, so capacity is observed
        // deterministically without the worker-pickup race.
        assertIs<SubmitResult.Admitted>(scheduler.enqueue(SchedulerLane.WORKFLOW, WorkKind.RUN, "r1", "f1") {})
        assertIs<SubmitResult.Admitted>(scheduler.enqueue(SchedulerLane.WORKFLOW, WorkKind.RUN, "r2", "f2") {})
        val rejected = scheduler.enqueue(SchedulerLane.WORKFLOW, WorkKind.RUN, "r3", "f3") {}

        val rejection = assertIs<SubmitResult.Rejected>(rejected)
        assertEquals("RATE_LIMITED", rejection.code)
        assertEquals(500L, rejection.retryAfterMs)
        assertTrue(rejection.message.contains("WORKFLOW"))
    }

    // ═══════════════════════════════════════════════════════════════
    // SC4-SC7: §8.4 exponential backoff
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SC4-repeated rejection of same fingerprint doubles the hint`() {
        val scheduler = RunScheduler(config(laneCapacity = 1))
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r1", "other") {}

        val hints = (1..4).map {
            val r = scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r-${it + 1}", "same") {}
            assertIs<SubmitResult.Rejected>(r).retryAfterMs
        }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L), hints)
    }

    @Test
    fun `SC5-backoff hint is capped at maxRetryMs`() {
        val scheduler = RunScheduler(config(laneCapacity = 1, maxRetryMs = 2_000))
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r1", "other") {}

        val hints = (1..5).map {
            val r = scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r-${it + 1}", "same") {}
            assertIs<SubmitResult.Rejected>(r).retryAfterMs
        }
        assertEquals(listOf(500L, 1_000L, 2_000L, 2_000L, 2_000L), hints)
    }

    @Test
    fun `SC6-distinct fingerprints get fresh hints`() {
        val scheduler = RunScheduler(config(laneCapacity = 1))
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r1", "other") {}

        listOf("a", "a", "b").forEachIndexed { i, fp ->
            val r = scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r-${i + 2}", fp) {}
            val hint = assertIs<SubmitResult.Rejected>(r).retryAfterMs
            if (i == 1) assertEquals(1_000L, hint) else assertEquals(500L, hint)
        }
    }

    @Test
    fun `SC7-successful admission resets the fingerprint escalation`() = runBlocking<Unit> {
        // capacity 2 + 1 worker: a gated body pins the worker, two fillers
        // fill the channel — further submissions are rejected until space
        // opens. The reset is then verified with a second pinned body so the
        // final rejection is race-free.
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1, laneCapacity = 2))
        val runnerStarted = CompletableDeferred<Unit>()
        val releaseRunner = CompletableDeferred<Unit>()
        val fillersDrained = CompletableDeferred<Unit>()
        val pinnedStarted = CompletableDeferred<Unit>()
        val releasePinned = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "runner", "fr") {
            runnerStarted.complete(Unit)
            releaseRunner.await()
        }
        withTimeout(5_000) { runnerStarted.await() }

        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "filler-1", "filler") {}
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "filler-2", "filler") { fillersDrained.complete(Unit) }
        // Channel full now: "payload" is rejected twice → 500, 1000.
        repeat(2) {
            val r = scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "payload", "payload") {}
            assertIs<SubmitResult.Rejected>(r)
        }

        releaseRunner.complete(Unit)
        withTimeout(5_000) { fillersDrained.await() } // queue drained → channel empty

        // A gated admission of the escalation's fingerprint resets it — and
        // pins the worker so the channel state is stable afterwards.
        val pinned = scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "pinned", "payload") {
            pinnedStarted.complete(Unit)
            releasePinned.await()
        }
        assertIs<SubmitResult.Admitted>(pinned)
        withTimeout(5_000) { pinnedStarted.await() }

        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "filler-3", "filler") {}
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "filler-4", "filler") {}
        // Channel full again: the next rejection starts fresh at 500ms.
        val r = scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "payload-3", "payload") {}
        assertEquals(500L, assertIs<SubmitResult.Rejected>(r).retryAfterMs)

        releasePinned.complete(Unit)
        scheduler.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════
    // SC8-SC9: Expedited guard, shutdown rejection
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SC8-expedited lane admits cancellations only`() {
        val scheduler = RunScheduler()

        val run = scheduler.enqueue(SchedulerLane.EXPEDITED, WorkKind.RUN, "r1", "f1") {}
        val rejection = assertIs<SubmitResult.Rejected>(run)
        assertEquals("INTERNAL", rejection.code)
        assertNull(rejection.retryAfterMs)

        val cancel = scheduler.enqueue(SchedulerLane.EXPEDITED, WorkKind.CANCELLATION, "r2", "f2") {}
        assertIs<SubmitResult.Admitted>(cancel)
    }

    @Test
    fun `SC9-enqueue after shutdown is rejected with UNAVAILABLE`() {
        val scheduler = RunScheduler()
        scheduler.start()
        scheduler.shutdown()

        val r = scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "late", "f") {}
        val rejection = assertIs<SubmitResult.Rejected>(r)
        assertEquals("UNAVAILABLE", rejection.code)
        assertNull(rejection.retryAfterMs)
    }

    // ═══════════════════════════════════════════════════════════════
    // SC10-SC11: Cancellation (§8.3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SC10-cancel of queued item drops the body and returns true`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1))
        val cancelledRan = CompletableDeferred<Unit>()
        val sentinelDone = CompletableDeferred<Unit>()

        // No start(): both items sit on the channel; cancel drops the first.
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "doomed", "f1") { cancelledRan.complete(Unit) }
        assertTrue(scheduler.cancel("doomed"))
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "sentinel", "f2") { sentinelDone.complete(Unit) }
        scheduler.start()

        withTimeout(5_000) { sentinelDone.await() } // FIFO: "doomed" was processed (and skipped) before "sentinel"
        assertNull(withTimeoutOrNull(200) { cancelledRan.await() })
        assertEquals(0, scheduler.shutdown().size)
    }

    @Test
    fun `SC11-cancel of running item cancels its body and returns false`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1))
        val bodyStarted = CompletableDeferred<Unit>()
        val bodyCancelled = CompletableDeferred<Unit>()
        val releaseBody = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "runner", "f") {
            bodyStarted.complete(Unit)
            try {
                releaseBody.await()
            } catch (e: CancellationException) {
                bodyCancelled.complete(Unit)
                throw e
            }
        }
        withTimeout(5_000) { bodyStarted.await() }

        assertFalse(scheduler.cancel("runner")) // running → body emits the terminal event itself
        withTimeout(5_000) { bodyCancelled.await() }
        scheduler.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════
    // SC12-SC13: Metrics & backpressure episodes (§8.4)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SC12-metrics expose lane depth, in-flight and semaphore stats`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1))
        val bodyStarted = CompletableDeferred<Unit>()
        val releaseBody = CompletableDeferred<Unit>()
        val tailDone = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "runner", "f1") {
            bodyStarted.complete(Unit)
            releaseBody.await()
        }
        withTimeout(5_000) { bodyStarted.await() }
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "queued-1", "f2") {}
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "queued-2", "f3") { tailDone.complete(Unit) }

        val m = scheduler.metrics()
        assertEquals(2, m.laneDepth[SchedulerLane.INTERACTIVE]) // two admitted, not yet picked up
        assertEquals(1, m.laneInFlight[SchedulerLane.INTERACTIVE]) // the gated body
        assertEquals(0, m.laneDepth[SchedulerLane.BACKGROUND])
        assertTrue(m.semaphoreAcquisitions >= 1)

        releaseBody.complete(Unit)
        withTimeout(5_000) { tailDone.await() }
        assertEquals(0, scheduler.metrics().laneDepth[SchedulerLane.INTERACTIVE])
        scheduler.shutdown()
    }

    @Test
    fun `SC13-backpressure fires once per episode and re-arms after recovery`() = runBlocking<Unit> {
        val episodes = ConcurrentLinkedQueue<Int>()
        val scheduler = RunScheduler(
            config = config(maxConcurrentInvokes = 1, laneCapacity = 8, backpressureThreshold = 2),
            onBackpressure = { _, depth -> episodes.add(depth) },
        )
        val bodyStarted = CompletableDeferred<Unit>()
        val releaseBody = CompletableDeferred<Unit>()
        val tailDone = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "runner", "f0") {
            bodyStarted.complete(Unit)
            releaseBody.await()
        }
        withTimeout(5_000) { bodyStarted.await() }

        // Depth 3 (runner in flight, 3 admitted) > threshold 2 → episode #1.
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "q1", "f1") {}
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "q2", "f2") {}
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "q3", "f3") { tailDone.complete(Unit) }
        withTimeout(5_000) {
            while (episodes.isEmpty()) delay(20) // the callback lands on Dispatchers.Default
        }
        assertEquals(3, episodes.poll())

        // Deeper into the same episode: no refire while still pressured.
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "q4", "f4") {}
        assertNull(
            withTimeoutOrNull(200) {
                while (episodes.size < 2) delay(20)
                Unit
            }
        )
        assertEquals(0, episodes.size) // episode #1 was polled off; no #2 fired

        releaseBody.complete(Unit) // drain the lane
        withTimeout(5_000) { tailDone.await() }
        withTimeout(2_000) {
            while (scheduler.metrics().laneDepth[SchedulerLane.BACKGROUND]!! > 0) delay(20)
        }

        // Re-pressurize → episode #2 fires again (re-armed).
        val secondGate = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "p0", "g0") { secondGate.await() }
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "p1", "g1") {}
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "p2", "g2") {}
        scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "p3", "g3") { secondDone.complete(Unit) }
        withTimeout(5_000) {
            while (episodes.isEmpty()) delay(20) // the callback lands on Dispatchers.Default
        }
        assertEquals(3, episodes.poll())

        secondGate.complete(Unit)
        withTimeout(5_000) { secondDone.await() }
        scheduler.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════
    // SC14-SC17: Shutdown semantics
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SC14-shutdown drains in-flight work within the grace window`() {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1, drainGraceMs = 2_000))
        val bodyStarted = CompletableDeferred<Unit>()
        val releaseBody = CompletableDeferred<Unit>()
        val bodyFinished = CompletableDeferred<Unit>()

        runBlocking {
            scheduler.start()
            scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "runner", "f") {
                bodyStarted.complete(Unit)
                releaseBody.await()
                bodyFinished.complete(Unit)
            }
            withTimeout(5_000) { bodyStarted.await() }
        }

        val result = arrayOfNulls<List<String>>(1)
        val shutdownThread = Thread { result[0] = scheduler.shutdown() }
        shutdownThread.start()
        Thread.sleep(100) // shutdown() is now parked in its grace drain
        releaseBody.complete(Unit)
        shutdownThread.join(5_000)

        assertEquals(emptyList(), result[0]) // body finished in time → nothing dropped
        assertTrue(bodyFinished.isCompleted)
    }

    @Test
    fun `SC15-shutdown drops queued items and reports their runIds`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(drainGraceMs = 100))
        val neverRan = CompletableDeferred<Unit>()

        // Never started: workers are not running, so both items stay queued.
        scheduler.enqueue(SchedulerLane.WORKFLOW, WorkKind.RUN, "q1", "f1") { neverRan.complete(Unit) }
        scheduler.enqueue(SchedulerLane.WORKFLOW, WorkKind.RUN, "q2", "f2") {}

        val dropped = scheduler.shutdown()
        assertEquals(setOf("q1", "q2"), dropped.toSet())
        assertNull(withTimeoutOrNull(200) { neverRan.await() })
    }

    @Test
    fun `SC16-shutdown cancels work exceeding the grace window`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1, drainGraceMs = 100))
        val bodyStarted = CompletableDeferred<Unit>()
        val bodyCancelled = CompletableDeferred<Unit>()
        val releaseBody = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "runner", "f") {
            bodyStarted.complete(Unit)
            try {
                releaseBody.await()
            } catch (e: CancellationException) {
                bodyCancelled.complete(Unit)
                throw e
            }
        }
        withTimeout(5_000) { bodyStarted.await() }

        val dropped = scheduler.shutdown() // grace 100ms elapses, body still gated
        assertTrue(dropped.isEmpty()) // the body HAD started — not "never ran"
        withTimeout(5_000) { bodyCancelled.await() }
    }

    @Test
    fun `SC17-shutdown is idempotent`() {
        val scheduler = RunScheduler()
        scheduler.start()
        assertEquals(0, scheduler.shutdown().size)
        assertEquals(0, scheduler.shutdown().size) // second call: no-op
    }

    // ═══════════════════════════════════════════════════════════════
    // SC18-SC19: Resilience & pre-start enqueue
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SC18-throwing body does not kill sibling runs`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 2))
        val bomberStarted = CompletableDeferred<Unit>()
        val siblingDone = CompletableDeferred<Unit>()

        scheduler.start()
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "bomber", "f1") {
            bomberStarted.complete(Unit)
            throw RuntimeException("boom")
        }
        scheduler.enqueue(SchedulerLane.INTERACTIVE, WorkKind.RUN, "sibling", "f2") {
            siblingDone.complete(Unit)
        }

        withTimeout(5_000) { bomberStarted.await() }
        withTimeout(5_000) { siblingDone.await() } // SupervisorJob: the failure is contained
        scheduler.shutdown()
    }

    @Test
    fun `SC19-items enqueued before start run once workers spin up`() = runBlocking<Unit> {
        val scheduler = RunScheduler(config(maxConcurrentInvokes = 1))
        val done = (1..2).map { CompletableDeferred<Unit>() }

        done.forEachIndexed { i, d ->
            scheduler.enqueue(SchedulerLane.BACKGROUND, WorkKind.RUN, "r${i + 1}", "f${i + 1}") { d.complete(Unit) }
        }
        scheduler.start() // 03 §"startup" step 5

        withTimeout(5_000) { done.awaitAll() }
        assertEquals(0, scheduler.shutdown().size)
    }
}
