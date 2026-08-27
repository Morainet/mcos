package com.morainet.mcos.runtime.core.workflow

import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [WakeScheduler] seam (durable schedule hosting, 10 §6): the manager asks
 * the host to wake at the earliest armed cron boundary, and re-asks whenever
 * the set of boundaries changes (arm / disarm / fire). Deterministic — manual
 * mode (`pollMs = null`), injected clock, driven by explicit `tick`s.
 */
class WakeSchedulerTest {

    private class FakeWakeScheduler : WakeScheduler {
        val wakes = CopyOnWriteArrayList<Long>()
        override fun scheduleWakeAt(epochMs: Long) { wakes.add(epochMs) }
        fun last(): Long? = wakes.lastOrNull()
    }

    private val SH = ZoneId.of("Asia/Shanghai")
    // 2026-08-24 00:02 Asia/Shanghai — offset off the boundary so the first
    // */5 fire is unambiguously 00:05, not "now".
    private val at0002 = ZonedDateTime.of(2026, 8, 24, 0, 2, 0, 0, SH).toInstant().toEpochMilli()
    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 8, 24, hour, minute, 0, 0, SH).toInstant().toEpochMilli()

    private val launched = CopyOnWriteArrayList<String>()

    private fun manager(wake: WakeScheduler) =
        ScheduleTriggerManager(clock = { at0002 }, pollMs = null, wakeScheduler = wake)

    private fun arm(m: ScheduleTriggerManager, id: String, cron: String) =
        m.arm(id, Trigger.Schedule(cron, "Asia/Shanghai"), preAuthorized = false) { wf, _, _ ->
            launched.add(wf)
        }

    @Test fun `WS1 arming requests a wake at the first boundary`() {
        val wake = FakeWakeScheduler()
        val m = manager(wake)
        arm(m, "a", "*/5 * * * *") // next boundary after 00:02 is 00:05
        assertEquals(at(0, 5), wake.last())
    }

    @Test fun `WS2 a fired tick re-requests a wake at the following boundary`() = runBlocking {
        val wake = FakeWakeScheduler()
        val m = manager(wake)
        arm(m, "a", "*/5 * * * *")
        m.tick(at(0, 5)) // fires the 00:05 boundary, advances to 00:10
        assertTrue(launched.contains("a"))
        assertEquals(at(0, 10), wake.last())
    }

    @Test fun `WS3 disarm re-requests the wake at the next remaining boundary`() {
        val wake = FakeWakeScheduler()
        val m = manager(wake)
        arm(m, "a", "*/5 * * * *")  // 00:05
        arm(m, "b", "*/10 * * * *") // 00:10
        assertEquals(at(0, 5), wake.last()) // earliest of the two

        assertTrue(m.disarm("a"))
        assertEquals(at(0, 10), wake.last()) // only b remains
    }

    @Test fun `WS4 no WakeScheduler means no wake requests (poll-only default)`() {
        val wake = FakeWakeScheduler()
        // A manager built without the scheduler must never call it.
        val m = ScheduleTriggerManager(clock = { at0002 }, pollMs = null)
        arm(m, "a", "*/5 * * * *")
        assertNull(wake.last())
    }
}
