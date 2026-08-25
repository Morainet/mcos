package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * Conformance tests for [ScheduleTriggerManager] (05-workflow.md §9.3,
 * 08-security.md §10.0).
 *
 * The manager is constructed in **manual mode** (`pollMs = null`) with an
 * injected fixed clock, and every fire is driven by calling [tick] with
 * explicit timestamps — no driver coroutine, no real-time waits, fully
 * deterministic. (The driver itself is covered end-to-end by the facade test
 * in mcos-runtime.)
 */
class ScheduleTriggerManagerTest {

    private class RecordingAuditLog : AuditLog {
        val records = CopyOnWriteArrayList<RunRecord>()
        override fun append(record: RunRecord) { records.add(record) }
        override suspend fun flush() {}
        override fun start() {}
        override fun stop() {}
        override fun getRuns(): List<RunRecord> = records.reversed()
        override fun getRun(runId: String): RunRecord? = records.lastOrNull { it.runId == runId }
        override fun getRecent(limit: Int): List<RunRecord> = records.takeLast(limit).reversed()
        override fun count(): Int = records.size
        override fun export(): String = records.joinToString("\n") { it.toString() }
        override fun clear() { records.clear() }
    }

    private val SH = ZoneId.of("Asia/Shanghai")

    /** Monday 2026-08-24 00:00 Asia/Shanghai — the fixed epoch base. */
    private val T0 = ZonedDateTime.of(2026, 8, 24, 0, 0, 0, 0, SH).toInstant().toEpochMilli()

    private fun min(n: Long): Long = n * 60_000L

    private lateinit var audit: RecordingAuditLog

    /** (workflowId, inputs, preAuthorized) per launch. */
    private val launched = CopyOnWriteArrayList<Triple<String, JsonObject, Boolean>>()

    @BeforeTest
    fun setUp() {
        audit = RecordingAuditLog()
    }

    private fun manager(
        limits: TriggerLimits = TriggerLimits(),
        clock: () -> Long = { T0 },
    ): ScheduleTriggerManager = ScheduleTriggerManager(audit, limits, clock, pollMs = null)

    private fun arm(
        manager: ScheduleTriggerManager,
        workflowId: String = "wf",
        cron: String = "*/5 * * * *",
        tz: String = "Asia/Shanghai",
        policy: String = "skip",
        preAuthorized: Boolean = false,
    ): TriggerArmResult = manager.arm(workflowId, Trigger.Schedule(cron, tz, policy), preAuthorized) {
        id, inputs, pre -> launched.add(Triple(id, inputs, pre))
    }

    private fun tick(manager: ScheduleTriggerManager, now: Long) {
        runBlocking { manager.tick(now) }
    }

    /** Assert the result is a rejection and return its reason code. */
    private fun rejectReason(result: TriggerArmResult): String =
        assertIs<TriggerArmResult.Rejected>(result).reason

    // ─── Arming & validation ────────────────────────────────────────────

    @Test
    fun `TS1-arm validates cron, timezone and satisfiability`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m))
        assertEquals(listOf("wf"), m.armed())

        // Blank cron parses in WorkflowJson today — arm is where it dies.
        assertEquals(ScheduleTriggerManager.REASON_CRON_INVALID, rejectReason(arm(m, cron = "")))
        assertEquals(ScheduleTriggerManager.REASON_CRON_INVALID, rejectReason(arm(m, cron = "not a cron")))
        assertEquals(
            ScheduleTriggerManager.REASON_TIMEZONE_INVALID,
            rejectReason(arm(m, tz = "Mars/Olympus")),
        )
        assertEquals(ScheduleTriggerManager.REASON_TIMEZONE_INVALID, rejectReason(arm(m, tz = "")))
        // Feb 31 parses but can never fire — rejected, not silently dead.
        assertEquals(
            ScheduleTriggerManager.REASON_CRON_UNSATISFIABLE,
            rejectReason(arm(m, cron = "0 0 31 2 *")),
        )

        // The successfully armed "wf" is the only entry; rejects added nothing.
        assertEquals(listOf("wf"), m.armed())
        assertTrue(launched.isEmpty())
        assertTrue(audit.records.isEmpty())
    }

    // ─── Firing & dedup ─────────────────────────────────────────────────

    @Test
    fun `TS2-tick fires each boundary exactly once`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m))  // */5 → first boundary 00:05

        tick(m, T0 + min(4))                       // before the boundary
        assertTrue(launched.isEmpty())

        tick(m, T0 + min(5))                       // on the boundary
        assertEquals(1, launched.size)

        tick(m, T0 + min(5) + 10_000)              // same boundary, later tick
        assertEquals(1, launched.size)

        tick(m, T0 + min(10))                      // next boundary
        assertEquals(2, launched.size)
    }

    @Test
    fun `TS3-inputs are always the empty object and preAuth is carried`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m, preAuthorized = true))

        tick(m, T0 + min(5))
        assertEquals(1, launched.size)
        // 05 §6.2: schedule runs carry no __input.
        assertEquals(JsonObject(emptyMap()), launched[0].second)
        assertEquals("wf", launched[0].first)
        assertTrue(launched[0].third)
    }

    @Test
    fun `TS4-poll jitter within the 60s tolerance still fires normally`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m))

        tick(m, T0 + min(5) + 45_000)  // 45s late — still the scheduled minute
        assertEquals(1, launched.size)
        val fired = audit.records.single()
        assertEquals("workflow.trigger_fired", fired.commandId)
        assertTrue(fired.ir!!.contains("latenessMs=45000"))
    }

    // ─── Misfire policies ───────────────────────────────────────────────

    @Test
    fun `TS5-skip policy audits the misfire and resumes from now`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m, cron = "* * * * *"))  // boundary 00:01

        tick(m, T0 + min(6))  // 5 minutes late → misfire, skipped
        assertTrue(launched.isEmpty())
        val misfire = audit.records.single()
        assertEquals("workflow.trigger_misfire", misfire.commandId)
        assertEquals("SCHEDULE", misfire.source)
        assertTrue(misfire.ir!!.contains("policy=skip"))
        assertTrue(misfire.ir!!.contains("latenessMs=${min(5)}"))
        // scheduledAt is the missed boundary, ISO-8601 (05 §7.5).
        assertTrue(misfire.ir!!.contains("scheduledAt=${Instant.ofEpochMilli(T0 + min(1))}"))

        // Resumed: the boundary after the recovery tick fires normally.
        tick(m, T0 + min(7))
        assertEquals(1, launched.size)
    }

    @Test
    fun `TS6-fire-and-forget coalesces any number of missed boundaries`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m, cron = "* * * * *", policy = "fire-and-forget"))

        tick(m, T0 + min(11))  // 10 boundaries missed → ONE recovery fire
        assertEquals(1, launched.size)
        assertEquals("workflow.trigger_fired", audit.records.single().commandId)

        tick(m, T0 + min(12))  // back on schedule
        assertEquals(2, launched.size)
    }

    @Test
    fun `TS7-if-window fires while still before the next scheduled point`() {
        val m = manager()
        // Hourly at :00 → first boundary 01:00, next after it 02:00.
        assertIs<TriggerArmResult.Armed>(arm(m, cron = "0 * * * *", policy = "fire-and-forget-if-window"))

        tick(m, T0 + min(62))  // 01:02 — still inside the 01:00 window
        assertEquals(1, launched.size)
        assertEquals("workflow.trigger_fired", audit.records.single().commandId)
    }

    @Test
    fun `TS8-if-window skips once the next scheduled point has passed`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m, cron = "0 * * * *", policy = "fire-and-forget-if-window"))

        tick(m, T0 + min(121))  // 02:01 — past the 02:00 successor → skip
        assertTrue(launched.isEmpty())
        val misfire = audit.records.single()
        assertEquals("workflow.trigger_misfire", misfire.commandId)
        assertTrue(misfire.ir!!.contains("policy=fire-and-forget-if-window"))

        // Resumed from now: the 03:00 boundary fires on time.
        tick(m, T0 + min(180))
        assertEquals(1, launched.size)
    }

    // ─── Rate limiting ──────────────────────────────────────────────────

    @Test
    fun `TS9-sliding 1h window caps fires per workflow`() {
        val m = manager(limits = TriggerLimits(maxBackgroundFiresPerHour = 2))
        assertIs<TriggerArmResult.Armed>(arm(m, cron = "* * * * *", policy = "fire-and-forget"))

        tick(m, T0 + min(1))
        tick(m, T0 + min(2))
        tick(m, T0 + min(3))  // third fire within the hour → rate limited
        assertEquals(2, launched.size)
        assertEquals(
            listOf("workflow.trigger_fired", "workflow.trigger_fired", "workflow.trigger_rate_limited"),
            audit.records.map { it.commandId },
        )

        // An hour later the window has slid empty — a (mis)fire goes through.
        tick(m, T0 + min(1) + 62 * 60_000L)
        assertEquals(3, launched.size)
    }

    // ─── Disarm / re-arm ────────────────────────────────────────────────

    @Test
    fun `TS10-disarm stops firing and disarmAll clears everything`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m, workflowId = "a"))
        assertIs<TriggerArmResult.Armed>(arm(m, workflowId = "b"))

        assertTrue(m.disarm("a"))
        tick(m, T0 + min(5))
        assertEquals(listOf("b"), launched.map { it.first })

        m.disarmAll()
        assertTrue(m.armed().isEmpty())
        assertFalse(m.disarm("b"))  // already gone
        tick(m, T0 + min(10))
        assertEquals(1, launched.size)
    }

    @Test
    fun `TS11-re-arming replaces the schedule`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m, cron = "*/5 * * * *"))
        // Replace with an every-minute schedule (boundary 00:01).
        assertIs<TriggerArmResult.Armed>(arm(m, cron = "* * * * *"))
        assertEquals(listOf("wf"), m.armed())  // one entry, not two

        tick(m, T0 + min(1))
        assertEquals(1, launched.size)  // fired at :01 — the */5 boundary (00:05) is gone
    }

    // ─── Audit shape & timezone ─────────────────────────────────────────

    @Test
    fun `TS12-audit records use the trigger runId convention and source`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m))

        tick(m, T0 + min(5))
        val r = audit.records.single()
        assertTrue(r.runId.matches(Regex("trigger:\\d+")))
        assertEquals("SCHEDULE", r.source)
        assertEquals("workflow.trigger_fired", r.commandId)
        assertEquals(T0, r.timestamp)  // the injected clock
        assertTrue(r.ir!!.contains("workflow=wf"))
        assertTrue(r.ir!!.contains("scheduledAt=${Instant.ofEpochMilli(T0 + min(5))}"))
    }

    @Test
    fun `TS13-boundaries are computed in the trigger's timezone`() {
        val m = manager()
        // Noon in Asia/Shanghai (= 04:00 UTC) vs noon in New York (EDT,
        // = 16:00 UTC = midnight next day Shanghai) — same expression,
        // different fire instants.
        assertIs<TriggerArmResult.Armed>(arm(m, workflowId = "sh", cron = "0 12 * * *", tz = "Asia/Shanghai"))
        assertIs<TriggerArmResult.Armed>(arm(m, workflowId = "ny", cron = "0 12 * * *", tz = "America/New_York"))

        tick(m, T0 + min(720))       // 12:00 Shanghai — NY boundary not due
        assertEquals(listOf("sh"), launched.map { it.first })

        tick(m, T0 + min(1440))      // T0 + 24h = noon EDT = 00:00 next day Shanghai
        assertEquals(listOf("sh", "ny"), launched.map { it.first })
    }

    @Test
    fun `TS14-arm alone never fires — only tick does`() {
        val m = manager()
        assertIs<TriggerArmResult.Armed>(arm(m, workflowId = "wf"))                  // */5 → :05
        assertIs<TriggerArmResult.Armed>(arm(m, workflowId = "other", cron = "5 * * * *"))  // :05

        // Manual mode: no driver, so nothing can fire between arm and tick.
        assertTrue(launched.isEmpty())
        assertTrue(audit.records.isEmpty())

        // One tick fires every due schedule, in a deterministic (sorted) order.
        tick(m, T0 + min(5))
        assertEquals(listOf("other", "wf"), launched.map { it.first })
    }
}
