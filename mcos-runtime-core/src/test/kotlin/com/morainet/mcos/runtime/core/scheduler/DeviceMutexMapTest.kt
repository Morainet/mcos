package com.morainet.mcos.runtime.core.scheduler

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.sdk.McosException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Conformance tests for [DeviceMutexMap] — the §8.5 per-device serialization
 * primitive (sorted atomic acquisition, nested-acquisition CONFLICT).
 */
class DeviceMutexMapTest {

    private val devices = DeviceMutexMap()

    @Test
    fun `DM1-same device is serialized across runs`() = runBlocking<Unit> {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val oneEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val jobs = listOf("run-1", "run-2").map { runId ->
            async {
                devices.withDevices(runId, listOf("light-1")) {
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
        assertNull(withTimeoutOrNull(200) { secondEntered.await() }) // light-1 is held by run-1

        release.complete(Unit)
        withTimeout(5_000) { jobs.awaitAll() }
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun `DM2-distinct devices run in parallel`() = runBlocking<Unit> {
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)

        val jobs = listOf("run-1" to "light-1", "run-2" to "light-2").map { (runId, device) ->
            async {
                devices.withDevices(runId, listOf(device)) {
                    if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                    release.await()
                }
            }
        }

        withTimeout(5_000) { bothEntered.await() } // no cross-device contention
        release.complete(Unit)
        withTimeout(5_000) { jobs.awaitAll() }
    }

    @Test
    fun `DM3-multi-device acquisition is atomic and order-independent`() = runBlocking<Unit> {
        val firstHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val overlappingDone = CompletableDeferred<Unit>()

        // Deliberately unsorted declaration: sorted acquisition normalizes it.
        val first = async {
            devices.withDevices("run-1", listOf("light-2", "light-1")) {
                firstHeld.complete(Unit)
                release.await()
            }
        }
        withTimeout(5_000) { firstHeld.await() }
        assertEquals(listOf("light-1", "light-2"), devices.heldDevices("run-1"))

        // Overlaps on light-2 AND holds light-3 too — must wait for ALL of
        // run-1's devices to release, then hold both of its own atomically.
        val second = async {
            devices.withDevices("run-2", listOf("light-3", "light-2")) {
                assertEquals(listOf("light-2", "light-3"), devices.heldDevices("run-2"))
                overlappingDone.complete(Unit)
            }
        }
        assertNull(withTimeoutOrNull(200) { overlappingDone.await() }) // light-2 still held

        release.complete(Unit)
        withTimeout(5_000) { overlappingDone.await() }
        withTimeout(5_000) { awaitAll(first, second) }
        assertTrue(devices.heldDevices("run-1").isEmpty())
        assertTrue(devices.heldDevices("run-2").isEmpty())
    }

    @Test
    fun `DM4-nested acquisition by the same run is CONFLICT device_locked`() = runBlocking<Unit> {
        devices.withDevices("run-1", listOf("light-1")) {
            val e = assertFailsWith<McosException> {
                devices.withDevices("run-1", listOf("light-2")) {}
            }
            assertEquals(McosErrorCode.CONFLICT.name, e.code)
            assertFalse(e.retryable)
            assertEquals("device_locked", e.details["reason"]!!.jsonPrimitive.content)
            assertEquals("light-1", e.details["heldDevice"]!!.jsonPrimitive.content)
            assertEquals("light-2", e.details["requestedDevice"]!!.jsonPrimitive.content)
            assertEquals("run-1", e.details["runId"]!!.jsonPrimitive.content)
        }
        // Outer release worked — a fresh run can take the device.
        devices.withDevices("run-2", listOf("light-1")) {}
    }

    @Test
    fun `DM5-devices are released when the body throws`() = runBlocking<Unit> {
        assertFailsWith<RuntimeException> {
            devices.withDevices("run-1", listOf("light-1")) { throw RuntimeException("boom") }
        }
        assertTrue(devices.heldDevices("run-1").isEmpty())
        // The mutex is free: a second run acquires immediately.
        val entered = CompletableDeferred<Unit>()
        withTimeout(5_000) {
            devices.withDevices("run-2", listOf("light-1")) { entered.complete(Unit) }
            entered.await()
        }
    }

    @Test
    fun `DM6-empty declaration is a no-op`() = runBlocking<Unit> {
        val ran = CompletableDeferred<Unit>()
        devices.withDevices("run-1", emptyList()) { ran.complete(Unit) }
        assertTrue(ran.isCompleted)
        assertTrue(devices.heldDevices("run-1").isEmpty())
    }
}
