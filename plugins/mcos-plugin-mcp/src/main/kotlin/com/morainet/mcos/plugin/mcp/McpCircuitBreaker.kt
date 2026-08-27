package com.morainet.mcos.plugin.mcp

/**
 * Per-server connection health gate ([04-plugin-sdk.md §10] connection
 * management). One instance is shared by every `mcp.<server>.*` handler of a
 * bridged server: after [failureThreshold] consecutive *retryable* failures
 * (connection faults or 5xx — the server is not serving), the circuit **opens**
 * for [cooldownMs] and handlers fast-fail `UNAVAILABLE` without touching the
 * network. Once the cooldown elapses the next call is allowed through as a
 * probe (half-open); its success [recordSuccess] closes the circuit, its
 * failure [recordFailure] re-opens it for another cooldown.
 *
 * The transport is stateless single-POST (no persistent socket), so
 * "disconnected" is inferred from recent failures rather than a live
 * connection — this is the P3 stand-in for "mark all `mcp.<server>.*`
 * UNAVAILABLE while disconnected" until session transport lands.
 */
class McpCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 30_000,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var consecutiveFailures = 0
    private var openUntil = 0L

    /** True while the circuit is open — callers should fast-fail without a request. */
    @Synchronized
    fun isOpen(): Boolean = now() < openUntil

    /** A healthy response reached us: close the circuit and clear the failure run. */
    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        openUntil = 0L
    }

    /** A retryable failure: count it and open the circuit once the run hits the threshold. */
    @Synchronized
    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openUntil = now() + cooldownMs
        }
    }
}
