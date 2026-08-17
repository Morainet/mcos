package com.morainet.mcos.runtime.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Crash-loop quarantine per [08-security.md §15.3].
 *
 * Tracks crash frequency per plugin. A plugin that crashes >= threshold
 * times within a sliding window is quarantined: its commands are removed
 * from the registry, an audit event is emitted, and it refuses to load
 * until the user explicitly re-enables it or a new verified version passes
 * its first invocation without crashing.
 *
 * The quarantine is an interface so the executor wiring is never `null`
 * (null would silently disable crash-loop isolation). Production uses
 * [SlidingWindowCrashQuarantine]; hosts that opt out use the named
 * [NoopCrashQuarantine] explicitly.
 */
interface CrashQuarantine {

    /** Whether [pluginId] is currently quarantined. */
    fun isQuarantined(pluginId: String): Boolean

    /** Human-readable reason for the quarantine, if quarantined. */
    fun quarantineReason(pluginId: String): String?

    /** All currently quarantined plugin IDs. */
    fun quarantinedPlugins(): Set<String>

    /**
     * Record a crash for [pluginId].
     *
     * @return true if this crash pushed the plugin over the threshold and
     *         quarantined it (only the first crossing reports true).
     */
    fun recordCrash(pluginId: String, stackTrace: String): Boolean

    /** A successful invocation resets the crash window (§15.3). */
    fun recordSuccess(pluginId: String)

    /** Lift a quarantine (user re-enable or new version passing first invoke). */
    fun lift(pluginId: String)
}

/**
 * Production [CrashQuarantine]: a plugin that crashes >= [threshold] times
 * within a sliding [windowMs] window is quarantined. A successful invocation
 * resets the crash window (§15.3).
 */
class SlidingWindowCrashQuarantine(
    private val windowMs: Long = 60_000,
    private val threshold: Int = 3,
    private val timeSource: () -> Long = System::currentTimeMillis,
) : CrashQuarantine {

    private data class CrashState(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        var reason: String? = null,
    )

    private val states = ConcurrentHashMap<String, CrashState>()
    private val lock = Any()

    override fun isQuarantined(pluginId: String): Boolean = states[pluginId]?.reason != null

    override fun quarantineReason(pluginId: String): String? = states[pluginId]?.reason

    override fun quarantinedPlugins(): Set<String> = states.filterValues { it.reason != null }.keys

    override fun recordCrash(pluginId: String, stackTrace: String): Boolean {
        val now = timeSource()
        synchronized(lock) {
            val state = states.getOrPut(pluginId) { CrashState() }
            if (state.reason != null) return false // already quarantined
            state.timestamps.addLast(now)
            while (state.timestamps.isNotEmpty() && state.timestamps.first() < now - windowMs) {
                state.timestamps.removeFirst()
            }
            if (state.timestamps.size >= threshold) {
                state.reason = buildReason(stackTrace)
                state.timestamps.clear()
                return true
            }
            return false
        }
    }

    override fun recordSuccess(pluginId: String) {
        synchronized(lock) {
            states[pluginId]?.timestamps?.clear()
        }
    }

    override fun lift(pluginId: String) {
        synchronized(lock) {
            states.remove(pluginId)
        }
    }

    private fun buildReason(stackTrace: String): String {
        // Keep the audit message bounded; stack traces are truncated, never
        // expanded into unbounded output.
        val trace = stackTrace.lineSequence().take(5).joinToString("\n")
        return "Plugin quarantined after $threshold crashes within ${windowMs / 1000}s. $trace"
    }
}
