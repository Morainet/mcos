package com.morainet.mcos.runtime.security

import kotlin.test.*

/**
 * Unit tests for [CrashQuarantine] — crash-loop detection per
 * 08-security.md §15.3.
 */
class CrashQuarantineTest {

    @Test
    fun `fewer than threshold crashes does not quarantine`() {
        val q = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 3)
        assertFalse(q.recordCrash("p", "trace1"))
        assertFalse(q.recordCrash("p", "trace2"))
        assertFalse(q.isQuarantined("p"))
        assertTrue(q.quarantinedPlugins().isEmpty())
    }

    @Test
    fun `third crash within window quarantines plugin`() {
        val q = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 3)
        assertFalse(q.recordCrash("p", "t1"))
        assertFalse(q.recordCrash("p", "t2"))
        assertTrue(q.recordCrash("p", "t3"))
        assertTrue(q.isQuarantined("p"))
        assertNotNull(q.quarantineReason("p"))
        assertEquals(setOf("p"), q.quarantinedPlugins())
    }

    @Test
    fun `crashes outside the window do not accumulate`() {
        var now = 1_000L
        val q = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 3, timeSource = { now })
        q.recordCrash("p", "t1")
        now += 61_000 // outside the sliding window
        q.recordCrash("p", "t2")
        now += 61_000
        assertFalse(q.recordCrash("p", "t3"))
        assertFalse(q.isQuarantined("p"))
    }

    @Test
    fun `successful invoke resets the crash window`() {
        val q = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 3)
        q.recordCrash("p", "t1")
        q.recordCrash("p", "t2")
        q.recordSuccess("p")
        assertFalse(q.recordCrash("p", "t3"))
        assertFalse(q.isQuarantined("p"))
    }

    @Test
    fun `already quarantined plugin does not re-trigger`() {
        val q = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 2)
        q.recordCrash("p", "t1")
        assertTrue(q.recordCrash("p", "t2"))
        assertFalse(q.recordCrash("p", "t3"))
        assertTrue(q.isQuarantined("p"))
    }

    @Test
    fun `lift clears quarantine and crash history`() {
        val q = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 2)
        q.recordCrash("p", "t1")
        q.recordCrash("p", "t2")
        q.lift("p")
        assertFalse(q.isQuarantined("p"))
        assertTrue(q.quarantinedPlugins().isEmpty())
        // after lift the crash history is gone — counting starts fresh
        assertFalse(q.recordCrash("p", "t1"))
    }
}
