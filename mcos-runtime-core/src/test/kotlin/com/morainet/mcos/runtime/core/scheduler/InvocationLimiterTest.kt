package com.morainet.mcos.runtime.core.scheduler

import com.morainet.mcos.sdk.SideEffectClass
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.*

/**
 * Conformance tests for [InvocationLimiter] — the §8.2 per-invocation
 * concurrency caps (per-plugin 2, destructive 1 globally by default).
 *
 * Deterministic via gated bodies: each body reports entry, then parks on a
 * caller-held gate; "a third caller is waiting" assertions rely on the two
 * permits being provably held for the whole window.
 */
class InvocationLimiterTest {

    private val limiter = InvocationLimiter()

    @Test
    fun `L1-third invocation of same plugin waits for a permit`() = runBlocking<Unit> {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val twoEntered = CompletableDeferred<Unit>()
        val thirdEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val jobs = (1..3).map { n ->
            async {
                limiter.withPermits("plugin.a", SideEffectClass.read) {
                    val now = inFlight.incrementAndGet()
                    maxInFlight.accumulateAndGet(now) { cur, new -> maxOf(cur, new) }
                    if (now == 2) twoEntered.complete(Unit)
                    if (now == 3) thirdEntered.complete(Unit)
                    try {
                        release.await()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            }
        }

        withTimeout(5_000) { twoEntered.await() }
        assertNull(withTimeoutOrNull(200) { thirdEntered.await() }) // cap 2: the third must still be waiting

        release.complete(Unit)
        withTimeout(5_000) { jobs.awaitAll() }
        assertEquals(2, maxInFlight.get())
    }

    @Test
    fun `L2-destructive invocations serialize globally across plugins`() = runBlocking<Unit> {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val oneEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val jobs = listOf("plugin.a", "plugin.b").map { plugin ->
            async {
                limiter.withPermits(plugin, SideEffectClass.destructive) {
                    val now = inFlight.incrementAndGet()
                    maxInFlight.accumulateAndGet(now) { cur, new -> maxOf(cur, new) }
                    if (now == 1) oneEntered.complete(Unit)
                    if (now == 2) secondEntered.complete(Unit)
                    try {
                        release.await()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            }
        }

        withTimeout(5_000) { oneEntered.await() }
        assertNull(withTimeoutOrNull(200) { secondEntered.await() }) // global destructive cap 1

        release.complete(Unit)
        withTimeout(5_000) { jobs.awaitAll() }
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun `L3-different plugins do not block each other for read class`() = runBlocking<Unit> {
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)

        val jobs = listOf("plugin.a", "plugin.b").map { plugin ->
            async {
                limiter.withPermits(plugin, SideEffectClass.read) {
                    if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                    release.await()
                }
            }
        }

        withTimeout(5_000) { bothEntered.await() } // no cross-plugin contention
        release.complete(Unit)
        withTimeout(5_000) { jobs.awaitAll() }
    }

    @Test
    fun `L4-permits are released when the body throws`() = runBlocking<Unit> {
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        // supervisorScope: the bomber's failure must not cancel the successor.
        supervisorScope {
            val bomber = async {
                limiter.withPermits("plugin.a", SideEffectClass.destructive) {
                    firstEntered.complete(Unit)
                    release.await()
                    throw RuntimeException("boom")
                }
            }
            withTimeout(5_000) { firstEntered.await() }

            val successor = async {
                limiter.withPermits("plugin.a", SideEffectClass.destructive) {
                    secondEntered.complete(Unit)
                }
            }
            assertNull(withTimeoutOrNull(200) { secondEntered.await() }) // still held

            release.complete(Unit)
            assertFailsWith<RuntimeException> { bomber.await() }
            withTimeout(5_000) { secondEntered.await() } // released on the exception path
            successor.await()
        }
    }

    @Test
    fun `L5-read class does not contend the global destructive slot`() = runBlocking<Unit> {
        val destructiveEntered = CompletableDeferred<Unit>()
        val readEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val destructive = async {
            limiter.withPermits("plugin.a", SideEffectClass.destructive) {
                destructiveEntered.complete(Unit)
                release.await()
            }
        }
        withTimeout(5_000) { destructiveEntered.await() }

        val read = async {
            limiter.withPermits("plugin.a", SideEffectClass.read) {
                readEntered.complete(Unit)
            }
        }
        withTimeout(5_000) { readEntered.await() } // same plugin, second slot: no destructive contention

        release.complete(Unit)
        withTimeout(5_000) { awaitAll(destructive, read) }
    }
}
