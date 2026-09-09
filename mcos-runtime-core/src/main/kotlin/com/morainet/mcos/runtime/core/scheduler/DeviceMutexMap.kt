package com.morainet.mcos.runtime.core.scheduler

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.sdk.McosException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DeviceMutexMap — the §8.5 deadlock-prevention primitive: "IoT control serial
 * per device id", enforced by a `Mutex<DeviceId>` map.
 *
 * Locking discipline (leveled, deadlock-free):
 *  - All devices declared for a step are acquired **atomically** in **sorted**
 *    order — the standard acquire-all-at-once discipline that makes cross-run
 *    deadlock impossible regardless of declaration order.
 *  - A run **MUST NOT** hold device mutexes across a step boundary into a step
 *    that acquires different devices. A nested or concurrent acquisition
 *    attempt (a second [withDevices] by the same [runId] while one is held or
 *    being acquired) is rejected with `CONFLICT` / `device_locked` and the
 *    02 error shape (`heldDevice`, `requestedDevice`, `runId`). The run's
 *    intent is registered under a short bookkeeping lock **before** any
 *    device wait, so two concurrent same-run acquisitions (e.g. parallel
 *    workflow branches) resolve deterministically instead of racing. Workflows
 *    that genuinely need to act on two devices must declare both in a single
 *    call.
 *
 * Cancellation discipline (load-bearing): every release path runs the
 * synchronous `releaseAll` **before** the bookkeeping cleanup, and the
 * bookkeeping lock is a monitor rather than a coroutine `Mutex`. A
 * `Mutex.withLock` inside a `finally` is a cancellable suspension point — if
 * the run is already cancelled (Executor `withTimeout`, or an explicit
 * `cancel()`) it throws and every statement after it is skipped, which would
 * leak the device locks forever and permanently block every future run that
 * needs those devices.
 *
 * Driven by the WorkflowEngine: a step's device set is its literal
 * `requiresDevices` declaration (05 §5.0) plus ids resolved from the
 * command's `x-mcos-semantic: "device"` schema fields (03 §8.5 / 04 §4.5).
 */
class DeviceMutexMap {

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val heldByRun = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Guards the heldByRun check-and-register / remove only (never a device
     * wait). Deliberately a plain monitor rather than a coroutine `Mutex`:
     * nothing under this lock suspends, and a monitor is not a cancellation
     * point — so no `finally` block can skip the bookkeeping cleanup.
     */
    private val bookkeeping = Any()

    /**
     * Acquire all [devices] (deduplicated, sorted) for [runId], run [block],
     * release. A nested or concurrent acquisition by the same [runId] throws
     * [McosException] `CONFLICT`/`device_locked` (§8.5, 02 §error-shape).
     */
    suspend fun <T> withDevices(
        runId: String,
        devices: Collection<String>,
        block: suspend () -> T,
    ): T {
        val requested = devices.distinct().sorted()
        if (requested.isEmpty()) {
            return block()
        }
        // Register intent BEFORE waiting on any device mutex: a concurrent
        // second acquisition by the same runId sees the registration and is
        // rejected deterministically, rather than both racing past the check
        // and corrupting the held-by-run bookkeeping.
        val held: List<String>? = synchronized(bookkeeping) {
            val existing = heldByRun[runId]
            if (existing.isNullOrEmpty()) {
                heldByRun[runId] = ConcurrentHashMap.newKeySet<String>().apply { addAll(requested) }
                null
            } else {
                existing.toList()
            }
        }
        if (held != null) {
            throw McosException(
                code = McosErrorCode.CONFLICT.name,
                message = "Device mutex nested acquisition rejected for run '$runId': " +
                    "held=$held, requested=$requested (03 §8.5 — declare all " +
                    "devices of a step in one requiresDevices list)",
                retryable = false,
                details = buildJsonObject {
                    put("reason", "device_locked")
                    put("heldDevice", JsonPrimitive(held.first()))
                    put("requestedDevice", JsonPrimitive(requested.first()))
                    put("runId", JsonPrimitive(runId))
                },
            )
        }
        // Sorted acquire-all-at-once; on failure mid-way, release what was
        // taken and clear the run's registration. `releaseAll` runs FIRST and
        // is plain synchronous work: it can never be skipped by cancellation,
        // so a cancelled run cannot leak its device locks.
        val acquired = mutableListOf<Mutex>()
        try {
            for (device in requested) {
                val mutex = mutexes.computeIfAbsent(device) { Mutex() }
                mutex.lock()
                acquired.add(mutex)
            }
        } catch (e: Throwable) {
            releaseAll(acquired)
            synchronized(bookkeeping) { heldByRun.remove(runId) }
            throw e
        }
        try {
            return block()
        } finally {
            releaseAll(acquired)
            synchronized(bookkeeping) { heldByRun.remove(runId) }
        }
    }

    /**
     * Devices currently held (or being acquired) by [runId] (diagnostics;
     * empty when none).
     */
    fun heldDevices(runId: String): List<String> =
        synchronized(bookkeeping) { heldByRun[runId]?.toList()?.sorted() ?: emptyList() }

    private fun releaseAll(acquired: List<Mutex>) {
        // Reverse order for symmetry with the sorted acquisition; every mutex in
        // the list was locked by this call, so unconditional unlock is safe.
        for (mutex in acquired.asReversed()) {
            mutex.unlock()
        }
    }
}
