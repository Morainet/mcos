package com.morainet.mcos.runtime.core.memory

import kotlinx.serialization.Serializable

/**
 * A vector clock — a map of `deviceId -> lamport clock` ([07-memory.md 11.1]).
 *
 * Used for last-writer-wins conflict resolution during memory sync:
 * - [tick] increments one device's counter on a local write;
 * - [isAfter] answers "does this clock dominate [other]?" (strict dominance);
 * - [isConcurrentWith] answers "were the two writes concurrent?" — neither
 *   dominates and the clocks differ; such conflicts are surfaced to the user;
 * - [merge] takes the component-wise max, used after applying a remote value
 *   so this device's clock catches up (monotonic LWW).
 *
 * CRDTs are deliberately NOT adopted ([07-memory.md 11.1]): for factual
 * key-value memory "latest correct value" is the desired semantics, and
 * vector-clock LWW + user resolution for true conflicts is the standard,
 * lighter approach.
 */
@Serializable
data class VectorClock(val clocks: Map<String, Long> = emptyMap()) {

    /** Return a clock with [deviceId]'s lamport counter incremented. */
    fun tick(deviceId: String): VectorClock =
        VectorClock(clocks + (deviceId to (clocks[deviceId] ?: 0L) + 1L))

    /**
     * Strict dominance: `this >= other` on every component and strictly
     * greater on at least one component of the union. Missing components
     * count as `0`, and equal clocks dominate nothing.
     */
    fun isAfter(other: VectorClock): Boolean {
        if (this == other) return false
        for ((dev, v) in other.clocks) {
            if ((clocks[dev] ?: 0L) < v) return false
        }
        val union = clocks.keys + other.clocks.keys
        return union.any { (clocks[it] ?: 0L) > (other.clocks[it] ?: 0L) }
    }

    /**
     * Concurrent: neither clock dominates the other and they are not equal
     * ([07-memory.md 11.1] — surface to user: keep local, remote, or both).
     */
    fun isConcurrentWith(other: VectorClock): Boolean =
        this != other && !isAfter(other) && !other.isAfter(this)

    /** Component-wise max — applied to both clocks after a remote write. */
    fun merge(other: VectorClock): VectorClock {
        val keys = clocks.keys + other.clocks.keys
        return VectorClock(keys.associateWith { maxOf(clocks[it] ?: 0L, other.clocks[it] ?: 0L) })
    }

    companion object {
        /** The zero clock: no writer has ever touched the entry. */
        val ZERO = VectorClock(emptyMap())
    }
}
