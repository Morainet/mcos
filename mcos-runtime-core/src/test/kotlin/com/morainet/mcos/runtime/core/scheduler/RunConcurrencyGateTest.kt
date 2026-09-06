package com.morainet.mcos.runtime.core.scheduler

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Semantics of the scheduler's resizable global-permit gate — the primitive
 * behind [RunScheduler.reconfigure]'s live `maxParallel` (03-runtime.md §19.1):
 * an upsize hands permits to waiting acquirers, a downsize revokes only idle
 * permits (in-flight runs are never interrupted), FIFO waiters are served in
 * order and a cancelled waiter never loses its slot.
 */
class RunConcurrencyGateTest {

    @Test
    fun `G1-initial ceiling bounds concurrent holders`() = runBlocking<Unit> {
        val gate = RunConcurrencyGate(2)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        fun enter(): Unit = synchronized(this) {
            val c = active.incrementAndGet()
            peak.accumulateAndGet(c) { a, b -> maxOf(a, b) }
        }
        fun exit() = active.decrementAndGet()

        val jobs = (1..8).map {
            launch {
                gate.acquire()
                enter()
                delay(20)
                exit()
                gate.release()
            }
        }
        jobs.forEach { it.join() }
        assertEquals(2, peak.get())
        assertTrue(active.get() == 0, "all holders released")
    }

    @Test
    fun `G2-upsize hands new permits to waiting acquirers`() = runBlocking<Unit> {
        val gate = RunConcurrencyGate(1)
        val firstHeld = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            gate.acquire()
            firstHeld.complete(Unit)
            releaseFirst.await()
            gate.release()
        }
        withTimeout(5_000) { firstHeld.await() }
        val second = launch {
            gate.acquire()
            secondEntered.complete(Unit)
            gate.release()
        }
        // Second is blocked under the old ceiling of 1.
        assertEquals(1, gate.capacity())

        gate.resize(2)
        // The upsize wakes the oldest waiter — it proceeds while first still holds.
        withTimeout(5_000) { secondEntered.await() }
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(2, gate.capacity())
    }

    @Test
    fun `G3-downsize never revokes in-flight and decays to the new ceiling`() = runBlocking<Unit> {
        val gate = RunConcurrencyGate(2)
        val held = (1..2).map { CompletableDeferred<Unit>() }
        val release = (1..2).map { CompletableDeferred<Unit>() }
        val runners = held.mapIndexed { i, d ->
            launch {
                gate.acquire()
                d.complete(Unit)
                release[i].await()
                gate.release()
            }
        }
        held.forEach { withTimeout(5_000) { it.await() } }

        gate.resize(1) // both permits are in-flight: nothing idle to revoke.
        val thirdEntered = CompletableDeferred<Unit>()
        val third = launch {
            gate.acquire()
            thirdEntered.complete(Unit)
            gate.release()
        }
        // No idle permit exists: third must wait.
        assertEquals(false, thirdEntered.isCompleted)

        // Releasing runner 1 hands its permit to the waiter (FIFO) — third runs
        // while runner 2 is still in-flight (concurrency transiently above 1,
        // exactly the §19.1 "decay as bodies finish" semantics).
        release[0].complete(Unit)
        withTimeout(5_000) { thirdEntered.await() }

        release[1].complete(Unit)
        runners.forEach { it.join() }
        third.join()

        // Now at ceiling 1: a new acquirer proceeds immediately and alone.
        val fourthEntered = CompletableDeferred<Unit>()
        val fourth = launch {
            gate.acquire()
            fourthEntered.complete(Unit)
            gate.release()
        }
        withTimeout(5_000) { fourthEntered.await() }
        fourth.join()
        assertEquals(1, gate.capacity())
    }

    @Test
    fun `G4-resize up wakes multiple waiters in FIFO order`() = runBlocking<Unit> {
        val gate = RunConcurrencyGate(1)
        val firstHeld = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = launch {
            gate.acquire()
            firstHeld.complete(Unit)
            releaseFirst.await()
            gate.release()
        }
        withTimeout(5_000) { firstHeld.await() }

        val order = mutableListOf<Int>()
        // Unconfined: each waiter runs to its suspension point (inside acquire)
        // synchronously in launch order, so the FIFO queue is deterministic.
        val waiters = (1..3).map { n ->
            launch(Dispatchers.Unconfined) {
                gate.acquire()
                order.add(n)
                gate.release()
            }
        }
        // Waiters 1..3 are all queued under the old ceiling of 1.
        gate.resize(4) // delta 3 → all three waiters wake, oldest first.
        waiters.forEach { withTimeout(5_000) { it.join() } }
        releaseFirst.complete(Unit)
        first.join()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `G5-cancelled waiter does not lose its permit slot`() = runBlocking<Unit> {
        val gate = RunConcurrencyGate(1)
        val firstHeld = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = launch {
            gate.acquire()
            firstHeld.complete(Unit)
            releaseFirst.await()
            gate.release()
        }
        withTimeout(5_000) { firstHeld.await() }

        val secondEntered = CompletableDeferred<Unit>()
        val second: Job = launch {
            gate.acquire()
            secondEntered.complete(Unit)
            gate.release()
        }
        // Let second actually block, then cancel it.
        delay(50)
        second.cancel()
        second.join()

        releaseFirst.complete(Unit)
        first.join()

        // The cancelled waiter removed itself: the permit survives for the next
        // acquirer instead of being handed to a dead waiter (no lost-permit leak).
        val thirdEntered = CompletableDeferred<Unit>()
        val third = launch {
            gate.acquire()
            thirdEntered.complete(Unit)
            gate.release()
        }
        withTimeout(5_000) { thirdEntered.await() }
        third.join()
    }

    @Test
    fun `G6-resize enforces a positive ceiling and reports capacity`() = runBlocking<Unit> {
        val gate = RunConcurrencyGate(3)
        assertEquals(3, gate.capacity())
        gate.resize(1)
        assertEquals(1, gate.capacity())
        assertFailsWith<IllegalArgumentException> { gate.resize(0) }
        assertFailsWith<IllegalArgumentException> { gate.resize(-2) }
        assertEquals(1, gate.capacity())
    }
}
