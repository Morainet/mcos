package com.morainet.mcos.plugin.mcp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * McpCircuitBreaker (04 §10 connection management). Deterministic via an
 * injected clock — no real waiting. CB1-CB5.
 */
class McpCircuitBreakerTest {

    private var t = 0L
    private fun clock() = t

    @Test fun `CB1 opens only once the failure run reaches the threshold`() {
        val b = McpCircuitBreaker(failureThreshold = 3, cooldownMs = 1000, now = ::clock)
        assertFalse(b.isOpen())
        b.recordFailure(); assertFalse(b.isOpen())
        b.recordFailure(); assertFalse(b.isOpen())
        b.recordFailure(); assertTrue(b.isOpen())
    }

    @Test fun `CB2 a success resets the run so isolated failures never open`() {
        val b = McpCircuitBreaker(failureThreshold = 3, cooldownMs = 1000, now = ::clock)
        b.recordFailure(); b.recordFailure()
        b.recordSuccess()
        b.recordFailure(); b.recordFailure()
        assertFalse(b.isOpen()) // only two in the fresh run
        b.recordFailure(); assertTrue(b.isOpen())
    }

    @Test fun `CB3 the circuit half-opens once the cooldown elapses`() {
        val b = McpCircuitBreaker(failureThreshold = 1, cooldownMs = 1000, now = ::clock)
        b.recordFailure(); assertTrue(b.isOpen())
        t += 999; assertTrue(b.isOpen())
        t += 1; assertFalse(b.isOpen()) // now == openUntil → probe allowed
    }

    @Test fun `CB4 a probe failure re-opens for another cooldown`() {
        val b = McpCircuitBreaker(failureThreshold = 1, cooldownMs = 1000, now = ::clock)
        b.recordFailure() // open until 1000
        t = 1000; assertFalse(b.isOpen()) // probe window
        b.recordFailure() // probe failed → open until 2000
        assertTrue(b.isOpen())
        t = 1999; assertTrue(b.isOpen())
        t = 2000; assertFalse(b.isOpen())
    }

    @Test fun `CB5 a probe success closes the circuit`() {
        val b = McpCircuitBreaker(failureThreshold = 1, cooldownMs = 1000, now = ::clock)
        b.recordFailure() // open until 1000
        t = 1000; assertFalse(b.isOpen())
        b.recordSuccess() // probe succeeded → closed
        b.recordFailure() // a fresh single failure trips again (open until 2000)
        t = 1999; assertTrue(b.isOpen())
        t = 2000; assertFalse(b.isOpen())
    }
}
