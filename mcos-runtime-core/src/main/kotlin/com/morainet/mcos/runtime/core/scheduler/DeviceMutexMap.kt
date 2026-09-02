package com.morainet.mcos.runtime.core.scheduler

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.sdk.McosException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
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
 *    that acquires different devices. A nested-acquisition attempt (a second
 *    [withDevices] by the same [runId] while one is still held) is rejected
 *    with `CONFLICT` / `device_locked` and the §02 error shape
 *    (`heldDevice`, `requestedDevice`, `runId`). Workflows that genuinely need
 *    to act on two devices must declare both in a single call.
 *
 * Honest boundary (as-built): the workflow-step `requiresDevices` declaration
 * and its Stage-4 resolution from `x-mcos-semantic: "device"` args (02 §9 /
 * 04 §`deviceId`) are a cross-protocol change and land in a follow-up slice;
 * this class is the runtime primitive they will drive.
 */
class DeviceMutexMap {

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val heldByRun = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Acquire all [devices] (deduplicated, sorted) for [runId], run [block],
     * release. Nested acquisition by the same [runId] throws
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
        val held = heldByRun[runId]
        if (held != null && held.isNotEmpty()) {
            throw McosException(
                code = McosErrorCode.CONFLICT.name,
                message = "Device mutex nested acquisition rejected for run '$runId': " +
                    "held=${held.toList()}, requested=$requested (03 §8.5 — declare all " +
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
        // Sorted acquire-all-at-once; on cancellation mid-way, release what was taken.
        val acquired = mutableListOf<Mutex>()
        try {
            for (device in requested) {
                val mutex = mutexes.computeIfAbsent(device) { Mutex() }
                mutex.lock()
                acquired.add(mutex)
            }
        } catch (e: CancellationException) {
            releaseAll(acquired)
            throw e
        }
        heldByRun[runId] = ConcurrentHashMap.newKeySet<String>().apply { addAll(requested) }
        try {
            return block()
        } finally {
            heldByRun.remove(runId)
            releaseAll(acquired)
        }
    }

    /** Devices currently held by [runId] (diagnostics; empty when none). */
    fun heldDevices(runId: String): List<String> = heldByRun[runId]?.toList()?.sorted() ?: emptyList()

    private fun releaseAll(acquired: List<Mutex>) {
        // Reverse order for symmetry with the sorted acquisition; every mutex in
        // the list was locked by this call, so unconditional unlock is safe.
        for (mutex in acquired.asReversed()) {
            mutex.unlock()
        }
    }
}
