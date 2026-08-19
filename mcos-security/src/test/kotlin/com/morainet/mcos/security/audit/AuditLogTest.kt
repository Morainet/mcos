package com.morainet.mcos.security.audit

import kotlin.test.*
import kotlinx.coroutines.*

/**
 * Conformance tests for AuditLog v0.1.
 * Matches [03-runtime.md 13].
 */
class AuditLogTest {

    private lateinit var auditLog: InMemoryAuditLog

    @BeforeTest
    fun setUp() {
        auditLog = InMemoryAuditLog()
        auditLog.start()
    }

    @AfterTest
    fun tearDown() {
        auditLog.stop()
    }

    // ═══════════════════════════════════════════════════════════════
    // A1-A3: Basic append and query
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A1-append and retrieve single run`() = runBlocking {
        val record = RunRecord(
            runId = "run_001",
            timestamp = System.currentTimeMillis(),
            commandId = "hello.world",
            steps = listOf(
                StepRecord("hello.world", "example.hello", ok = true, durationMs = 50)
            ),
            totalDurationMs = 50,
            outcome = RunOutcome.OK
        )
        auditLog.append(record)
        auditLog.flush() // ensure writer has processed all records

        assertEquals(1, auditLog.count())
        val retrieved = auditLog.getRun("run_001")
        assertNotNull(retrieved)
        assertEquals("hello.world", retrieved.commandId)
        assertEquals(RunOutcome.OK, retrieved.outcome)
    }

    @Test
    fun `A2-append multiple runs and order newest first`() = runBlocking {
        for (i in 1..5) {
            auditLog.append(
                RunRecord(
                    runId = "run_00$i",
                    timestamp = System.currentTimeMillis() - (5 - i) * 1000L,
                    commandId = "cmd.$i",
                    totalDurationMs = 10,
                    outcome = RunOutcome.OK
                )
            )
        }
        auditLog.flush()

        val runs = auditLog.getRuns()
        assertEquals(5, runs.size)
        assertEquals("cmd.5", runs.first().commandId) // newest first
    }

    @Test
    fun `A3-getRecent with limit`() = runBlocking {
        for (i in 1..10) {
            auditLog.append(
                RunRecord(
                    runId = "run_$i",
                    timestamp = System.currentTimeMillis() + i * 1000,
                    commandId = "cmd.$i",
                    totalDurationMs = 10,
                    outcome = RunOutcome.OK
                )
            )
        }
        auditLog.flush()

        val recent = auditLog.getRecent(3)
        assertEquals(3, recent.size)
        assertEquals("cmd.10", recent[0].commandId)
        assertEquals("cmd.9", recent[1].commandId)
    }

    // ═══════════════════════════════════════════════════════════════
    // A4-A5: Step records
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A4-step with error records code and message`() = runBlocking {
        val record = RunRecord(
            runId = "run_err",
            timestamp = System.currentTimeMillis(),
            commandId = "cmd.fail",
            steps = listOf(
                StepRecord(
                    commandId = "cmd.fail",
                    pluginId = "example.test",
                    ok = false,
                    code = "PLUGIN_ERROR",
                    message = "something broke",
                    durationMs = 120
                )
            ),
            totalDurationMs = 120,
            outcome = RunOutcome.FAILED
        )
        auditLog.append(record)
        auditLog.flush()

        val run = auditLog.getRun("run_err")
        assertNotNull(run)
        assertEquals(1, run.steps.size)
        assertFalse(run.steps[0].ok)
        assertEquals("PLUGIN_ERROR", run.steps[0].code)
    }

    @Test
    fun `A5-step with artifacts`() = runBlocking {
        val record = RunRecord(
            runId = "run_art",
            timestamp = System.currentTimeMillis(),
            commandId = "camera.capture",
            steps = listOf(
                StepRecord(
                    commandId = "camera.capture",
                    pluginId = "example.camera",
                    ok = true,
                    durationMs = 900,
                    artifacts = listOf(
                        ArtifactRecord("image", "file:///photo.jpg", "image/jpeg")
                    )
                )
            ),
            totalDurationMs = 900,
            outcome = RunOutcome.OK
        )
        auditLog.append(record)
        auditLog.flush()

        val run = auditLog.getRun("run_art")
        assertNotNull(run)
        assertEquals(1, run.steps[0].artifacts.size)
        assertEquals("image", run.steps[0].artifacts[0].type)
        assertEquals("file:///photo.jpg", run.steps[0].artifacts[0].uri)
    }

    // ═══════════════════════════════════════════════════════════════
    // A6-A7: Eviction
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A6-maxRecords evicts oldest entries`() = runBlocking {
        auditLog.maxRecords = 3

        for (i in 1..5) {
            auditLog.append(
                RunRecord(
                    runId = "run_$i",
                    timestamp = System.currentTimeMillis() + i * 1000,
                    commandId = "cmd.$i",
                    totalDurationMs = 10,
                    outcome = RunOutcome.OK
                )
            )
        }
        auditLog.flush()

        assertEquals(3, auditLog.count())
        val runs = auditLog.getRuns()
        assertEquals("cmd.5", runs[0].commandId)
        assertEquals("cmd.4", runs[1].commandId)
        assertEquals("cmd.3", runs[2].commandId)
        assertNull(auditLog.getRun("run_1"))
    }

    @Test
    fun `A7-maxAgeMs evicts old records`() = runBlocking {
        val now = System.currentTimeMillis()
        auditLog.maxAgeMs = 1000 // 1 second

        auditLog.append(
            RunRecord(
                runId = "old_run",
                timestamp = now - 5000, // 5 seconds ago
                commandId = "old.cmd",
                totalDurationMs = 10,
                outcome = RunOutcome.OK
            )
        )
        auditLog.append(
            RunRecord(
                runId = "new_run",
                timestamp = now,
                commandId = "new.cmd",
                totalDurationMs = 10,
                outcome = RunOutcome.OK
            )
        )
        auditLog.flush()

        assertEquals(1, auditLog.count())
        assertNotNull(auditLog.getRun("new_run"))
        assertNull(auditLog.getRun("old_run"))
    }

    // ═══════════════════════════════════════════════════════════════
    // A8-A9: Export
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A8-export produces JSONL`() = runBlocking {
        auditLog.append(
            RunRecord(
                runId = "exp_1",
                timestamp = System.currentTimeMillis(),
                commandId = "cmd.a",
                totalDurationMs = 50,
                outcome = RunOutcome.OK
            )
        )
        auditLog.append(
            RunRecord(
                runId = "exp_2",
                timestamp = System.currentTimeMillis(),
                commandId = "cmd.b",
                totalDurationMs = 80,
                outcome = RunOutcome.FAILED
            )
        )
        auditLog.flush()

        val exported = auditLog.export()
        val lines = exported.trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("exp_1"))
        assertTrue(lines[1].contains("exp_2"))
    }

    @Test
    fun `A9-export empty log produces empty string`() {
        assertEquals("", auditLog.export())
    }

    // ═══════════════════════════════════════════════════════════════
    // A10: Outcome tracking
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A10-outcome values`() = runBlocking {
        val outcomes = listOf(RunOutcome.OK, RunOutcome.FAILED, RunOutcome.CANCELLED, RunOutcome.TIMEOUT)
        for ((i, outcome) in outcomes.withIndex()) {
            auditLog.append(
                RunRecord(
                    runId = "out_$i",
                    timestamp = System.currentTimeMillis(),
                    commandId = "cmd.$i",
                    totalDurationMs = 10,
                    outcome = outcome
                )
            )
        }
        auditLog.flush()

        val runs = auditLog.getRuns()
        assertEquals(outcomes.size, runs.size)
        for (outcome in outcomes) {
            assertTrue(runs.any { it.outcome == outcome })
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // A11: Secret redaction (§9.4 x-mcos-secret marker)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A11-x-mcos-secret marker redacts scalar members`() {
        val ir = """{"auth":{"x-mcos-secret":true,"value":"s3cr3t","mode":"bearer"},"ok":true}"""
        val out = redactSecrets(ir)
        assertTrue(out.contains("\"value\":\"***REDACTED***\""))
        assertTrue(out.contains("\"mode\":\"***REDACTED***\""))
        assertFalse(out.contains("s3cr3t"))
        // the marker itself is kept so schema semantics remain visible
        assertTrue(out.contains("\"x-mcos-secret\":true"))
        // sibling non-secret object member is not touched
        assertTrue(out.contains("\"ok\":true"))
    }

    @Test
    fun `A11-name-based redaction still applies alongside the marker`() {
        val ir = """{"password":"hunter2","x-mcos-secret":true,"note":"hi"}"""
        val out = redactSecrets(ir)
        assertTrue(out.contains("\"password\":\"***REDACTED***\""))
        assertTrue(out.contains("\"note\":\"***REDACTED***\""))
        assertFalse(out.contains("hunter2"))
    }

    @Test
    fun `A11-object without marker keeps scalar values`() {
        val ir = """{"auth":{"value":"s3cr3t","mode":"bearer"},"ok":true}"""
        val out = redactSecrets(ir)
        assertTrue(out.contains("s3cr3t"))
        assertFalse(out.contains("REDACTED"))
    }

    // ═══════════════════════════════════════════════════════════════
    // A12-A13: flush() deadlock guard + expanded redaction (P0-C5)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A12-flush returns immediately when writer is not running`() = runBlocking {
        // P0-C5 regression: flush() must NOT hang when no writer coroutine is
        // draining the channel. Previously it awaited a sentinel that would
        // never be consumed, deadlocking the caller forever.
        val log = InMemoryAuditLog()
        // Intentionally do NOT call start() — there is no writer.
        log.append(RunRecord(runId = "r1", timestamp = 1, commandId = "c"))
        // This must return promptly instead of hanging.
        log.flush()
        assertTrue(true) // reached here without deadlock
        log.stop()
    }

    @Test
    fun `A13-authorization bearer and cookie fields are redacted`() {
        // P0-C5 regression: the secret-field set must cover authorization,
        // bearer, and cookie (in addition to password/token/secret/apikey/
        // api_key/credential). All should be scrubbed from the audit trail.
        val ir = """{"authorization":"Bearer abc123","refresh_cookie":"sid=xyz","api_key":"k1"}"""
        val out = redactSecrets(ir)
        assertFalse(out.contains("abc123"), "authorization value must be redacted: $out")
        assertFalse(out.contains("sid=xyz"), "cookie value must be redacted: $out")
        assertFalse(out.contains("\"k1\""), "api_key value must be redacted: $out")
        assertTrue(out.contains("REDACTED"))
    }

    @Test
    fun `A14-evict bulk-replaces records preserving order and correctness`() = runBlocking {
        // P2-F2 regression: evict() was rewritten from per-element removeAt/
        // remove (O(n²) on CopyOnWriteArrayList) to a single filter + clear
        // + addAll. Verify the new path still correctly evicts by both count
        // and age, and preserves chronological order.
        auditLog.maxRecords = 5
        auditLog.maxAgeMs = Long.MAX_VALUE // disable age eviction; test count only

        val base = System.currentTimeMillis()
        for (i in 1..10) {
            auditLog.append(
                RunRecord(
                    runId = "run_$i",
                    timestamp = base + i * 1000,
                    commandId = "cmd.$i",
                    totalDurationMs = 10,
                    outcome = RunOutcome.OK
                )
            )
        }
        auditLog.flush()

        // Only the 5 most-recent should survive, in newest-first order.
        assertEquals(5, auditLog.count())
        val runs = auditLog.getRuns()
        assertEquals("cmd.10", runs[0].commandId)
        assertEquals("cmd.6", runs[4].commandId)
        assertNull(auditLog.getRun("run_1"))
        assertNull(auditLog.getRun("run_5"))
        Unit
    }

    @Test
    fun `A15-evict by age removes all expired records in one pass`() = runBlocking {
        // Verify that age-based eviction works after the bulk rewrite even
        // when ALL records are expired.
        val now = System.currentTimeMillis()
        auditLog.maxRecords = Int.MAX_VALUE
        auditLog.maxAgeMs = 1000

        for (i in 1..5) {
            auditLog.append(
                RunRecord(
                    runId = "old_$i",
                    timestamp = now - 5000, // 5s ago → expired
                    commandId = "cmd.old.$i",
                    totalDurationMs = 10,
                    outcome = RunOutcome.OK
                )
            )
        }
        auditLog.flush()

        assertEquals(0, auditLog.count(), "all expired records should be evicted")
        Unit
    }
}
