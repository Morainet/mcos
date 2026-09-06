package com.morainet.mcos.runtime.core.scheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * A resizable counting gate — the scheduler's global-permit primitive
 * (03-runtime.md §8.2 / §8.4). Functionally the "global semaphore" that bounds
 * concurrent run bodies, but its permit count can change at runtime so that
 * [RunScheduler.reconfigure] can hot-tune §19.1's `maxParallel` without
 * touching in-flight runs:
 *
 *  - Raising the cap hands the extra permits to the oldest waiting acquirers
 *    first (FIFO), then to the free pool.
 *  - Lowering the cap revokes only *idle* permits — a permit already granted to
 *    an in-flight body is never revoked ("in-flight runs are NOT interrupted",
 *    03 §19.1); the effective concurrency decays to the new cap as bodies finish.
 *  - A waiter cancelled while queued removes itself, so its permit slot is
 *    never handed to a dead waiter (no lost-permit leak).
 *
 * All state is guarded by [lock] and no critical section suspends — [resize] and
 * [release] may be called from any thread; only [acquire] is suspending.
 */
internal class RunConcurrencyGate(initialPermits: Int) {

    private val lock = Any()

    /** Permit ceiling after the last [resize]. */
    private var cap: Int = initialPermits.coerceAtLeast(1)

    /** Idle permits currently in the pool (never exceeds [cap]). */
    private var free: Int = initialPermits.coerceAtLeast(1)

    /** Acquirers blocked when the pool was empty, oldest first. */
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    /** Acquire one permit, suspending until [release] or [resize] grants it. */
    suspend fun acquire() {
        var pending: CompletableDeferred<Unit>? = null
        val granted = synchronized(lock) {
            if (free > 0) {
                free -= 1
                true
            } else {
                val d = CompletableDeferred<Unit>()
                waiters.addLast(d)
                pending = d
                false
            }
        }
        if (granted) return
        try {
            pending!!.await()
        } catch (e: CancellationException) {
            // A cancelled waiter must drop itself from the queue so its permit
            // slot survives for the next acquirer (no lost-permit leak). A
            // waiter already handed a permit by release/resize is no longer
            // queued, so this removal is a no-op there.
            synchronized(lock) { waiters.remove(pending) }
            throw e
        }
    }

    /** Return one permit, handing it to the oldest waiter first (FIFO). */
    fun release() {
        var grant: CompletableDeferred<Unit>? = null
        synchronized(lock) {
            val head = waiters.removeFirstOrNull()
            if (head != null) {
                grant = head
            } else {
                free += 1
            }
        }
        grant?.complete(Unit)
    }

    /**
     * Resize the permit ceiling. Never blocks and never revokes an in-flight
     * permit (see the class KDoc). An upsize wakes waiting acquirers; a
     * downsize cuts only idle pool permits.
     */
    fun resize(newPermits: Int) {
        require(newPermits >= 1) { "permit count must be >= 1; got $newPermits" }
        val granted = mutableListOf<CompletableDeferred<Unit>>()
        synchronized(lock) {
            if (newPermits == cap) return
            val delta = newPermits - cap
            if (delta > 0) {
                var remaining = delta
                while (remaining > 0 && waiters.isNotEmpty()) {
                    granted.add(waiters.removeFirst())
                    remaining -= 1
                }
                free += remaining
            } else {
                val cut = minOf(-delta, free)
                free -= cut
            }
            cap = newPermits
        }
        granted.forEach { it.complete(Unit) }
    }

    /** Current permit ceiling. */
    fun capacity(): Int = synchronized(lock) { cap }
}
