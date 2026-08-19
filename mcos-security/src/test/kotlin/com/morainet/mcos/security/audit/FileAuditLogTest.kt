package com.morainet.mcos.security.audit

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * FileAuditLog: persistence, replay, retention, and export semantics
 * (03-runtime.md §13). Mirrors the InMemoryAuditLog guarantees — the P0-C5
 * flush guard and §9.4 redaction are re-asserted here against the file path.
 */
class FileAuditLogTest {

    private val dir = createTempDirectory("mcos-audit-test")
    private val file = File(dir.toFile(), "audit.jsonl")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun log(
        maxRecords: Int = 10_000,
        maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
        hmacKey: ByteArray? = null,
    ): FileAuditLog = FileAuditLog(file, maxRecords, maxAgeMs, hmacKey)

    // ═══════════════════════════════════════════════════════════════
    // F1-F2: persistence + replay
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F1-records survive across instances with fields intact`() = runBlocking {
        val now = System.currentTimeMillis()
        val first = log().apply {
            start()
            append(
                RunRecord(
                    runId = "run-1", timestamp = now - 2_000, source = "CHAT", commandId = "camera.capture",
                    steps = listOf(
                        StepRecord(
                            commandId = "camera.capture", pluginId = "camera", ok = true,
                            durationMs = 42, artifacts = listOf(ArtifactRecord("image", "file:///x.jpg")),
                        )
                    ),
                    totalDurationMs = 50, outcome = RunOutcome.OK,
                )
            )
            append(RunRecord(runId = "run-2", timestamp = now - 1_000, outcome = RunOutcome.FAILED))
            flush()
            stop()
        }

        val second = log().apply { start() }
        withTimeout(5_000) { second.flush() }

        assertEquals(2, second.count(), "both records must replay from disk")
        val newest = second.getRuns().first()
        assertEquals("run-2", newest.runId)
        val round = assertNotNull(second.getRun("run-1"))
        assertEquals("CHAT", round.source)
        assertEquals(1, round.steps.size)
        assertEquals("file:///x.jpg", round.steps[0].artifacts[0].uri)
        assertEquals(RunOutcome.OK, round.outcome)
        second.stop()
        first.stop() // idempotent no-op on an already-stopped log
    }

    @Test
    fun `F2-replay skips malformed lines instead of crashing`() = runBlocking {
        val now = System.currentTimeMillis()
        file.writeText(
            """
            {"runId":"ok-1","timestamp":${now - 2_000}}
            this is not json
            {"runId":"ok-2","timestamp":${now - 1_000}}
            """.trimIndent() + "\n"
        )
        val l = log().apply { start() }
        withTimeout(5_000) { l.flush() }
        assertEquals(2, l.count())
        assertEquals(1, l.corruptedLinesOnLoad)
        assertNull(l.getRun("this"))
        l.stop()
    }

    // ═══════════════════════════════════════════════════════════════
    // F3, F7: retention (§13.3 — TTL + cap, whichever first)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F3-cap eviction rewrites the file with only retained records`() = runBlocking {
        val l = log(maxRecords = 2).apply { start() }
        appendWithTimestamp(l, "r-old", System.currentTimeMillis() - 3_000)
        appendWithTimestamp(l, "r-mid", System.currentTimeMillis() - 2_000)
        appendWithTimestamp(l, "r-new", System.currentTimeMillis() - 1_000)
        withTimeout(5_000) { l.flush() }

        assertEquals(listOf("r-new", "r-mid"), l.getRuns().map { it.runId })
        val onDisk = file.readText()
        assertTrue(onDisk.contains("r-new"))
        assertTrue(onDisk.contains("r-mid"))
        assertFalse(onDisk.contains("r-old"), "evicted record must be compacted out of the file")
        assertEquals(2, onDisk.trim().lines().size)
        l.stop()
    }

    @Test
    fun `F7-aged-out backlog is compacted on replay`() = runBlocking {
        val stale = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000
        file.writeText(
            AUDIT_JSON.encodeToString(RunRecord(runId = "stale", timestamp = stale)) + "\n" +
                AUDIT_JSON.encodeToString(RunRecord(runId = "fresh", timestamp = System.currentTimeMillis())) + "\n"
        )
        val l = log().apply { start() }
        withTimeout(5_000) { l.flush() }

        assertEquals(1, l.count(), "30-day TTL must drop the stale record on load")
        assertFalse(file.readText().contains("stale"), "file must be compacted after load-time eviction")
        l.stop()
    }

    // ═══════════════════════════════════════════════════════════════
    // F4-F6: export, flush guard, redaction-on-persist
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F4-export signature line verifies against the body`() = runBlocking {
        val key = deriveAuditHmacKey("device-seed")
        val l = log(hmacKey = key).apply { start() }
        appendWithTimestamp(l, "s-1", System.currentTimeMillis() - 2_000)
        appendWithTimestamp(l, "s-2", System.currentTimeMillis() - 1_000)
        withTimeout(5_000) { l.flush() }

        val lines = l.export().lines()
        assertEquals(3, lines.size, "2 records + 1 signature line")
        val expected = hmacSha256Hex(lines[0] + "\n" + lines[1], key)
        assertTrue(lines[2].contains("\"algorithm\":\"HMAC-SHA256\""))
        assertTrue(lines[2].contains(expected), "signature must be HMAC-SHA256 over the JSONL body")
        l.stop()
    }

    @Test
    fun `F5-flush without start returns immediately`() = runBlocking {
        val l = log()
        l.append(RunRecord(runId = "queued", timestamp = System.currentTimeMillis() - 1_000))
        withTimeout(1_000) { l.flush() } // P0-C5: must not hang with no writer
        assertEquals(0, l.count(), "nothing persisted until the writer starts")
        l.start()
        withTimeout(5_000) { l.flush() }
        assertEquals(1, l.count(), "records queued before start are drained once it runs")
        l.stop()
    }

    @Test
    fun `F6-secrets are redacted before hitting disk`() = runBlocking {
        val l = log().apply { start() }
        l.append(
            RunRecord(
                runId = "secret-run", timestamp = System.currentTimeMillis() - 1_000,
                ir = """{"password":"hunter2","note":"keep"}""",
            )
        )
        withTimeout(5_000) { l.flush() }

        val raw = file.readText()
        assertFalse(raw.contains("hunter2"), "raw file must never contain the secret")
        assertTrue(raw.contains("***REDACTED***"))
        assertTrue(raw.contains("keep"), "non-secret fields survive redaction")
        l.stop()
    }

    @Test
    fun `F9-export is empty for an empty log and clear removes the file`() = runBlocking {
        val l = log().apply { start() }
        withTimeout(5_000) { l.flush() }
        assertEquals("", l.export())
        appendWithTimestamp(l, "x", System.currentTimeMillis() - 1_000)
        withTimeout(5_000) { l.flush() }
        assertTrue(file.length() > 0)
        l.clear()
        assertEquals(0, l.count())
        assertFalse(file.exists())
        l.stop()
    }

    private fun appendWithTimestamp(l: FileAuditLog, runId: String, timestamp: Long) {
        l.append(RunRecord(runId = runId, timestamp = timestamp))
    }
}
