package com.morainet.mcos.security.audit

import com.morainet.mcos.security.RedactionLevel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RL1-RL7 — the §19 [RedactionLevel] audit-scrub levels, wired as a live
 * supplier on both audit sinks (03-runtime.md §13.3 / §19).
 */
class RedactionLevelTest {

    private val dir = createTempDirectory("mcos-redaction-test")
    private val file = File(dir.toFile(), "audit.jsonl")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val secretJson = """{"user":"ada","password":"hunter2","note":"ok"}"""

    // ─── DEFAULT is the historical behaviour ───────────────────────────────

    @Test
    fun `RL1-DEFAULT is byte-identical to the historical walk`() {
        val historical = redactSecrets(secretJson) // legacy no-arg default
        val explicit = redactSecrets(secretJson, RedactionLevel.DEFAULT)
        assertEquals(historical, explicit)
        assertTrue(explicit.contains("***REDACTED***"))
        assertTrue(!explicit.contains("hunter2"))
    }

    // ─── OFF stores raw ─────────────────────────────────────────────────────

    @Test
    fun `RL2-OFF returns the input verbatim - the documented opt-out`() {
        val off = redactSecrets(secretJson, RedactionLevel.OFF)
        assertEquals(secretJson, off)
        assertTrue(off.contains("hunter2"))
    }

    // ─── STRICT catches regex-only leakage ──────────────────────────────────

    @Test
    fun `RL3-STRICT scrubs a quoted secret pair embedded inside a string value`() {
        // The structured walk only redacts secret-NAMED object members. A
        // quoted `token: "..."` pair buried inside a string VALUE (under an
        // innocuous key) survives the DEFAULT walk — the walk sees one opaque
        // string — but the STRICT regex pass over the serialized output catches
        // it. Escaped quotes model how such a nested blob is actually stored.
        val embedded = """{"note":"token: 'hunter2' was used"}"""
        val default = redactSecrets(embedded, RedactionLevel.DEFAULT)
        val strict = redactSecrets(embedded, RedactionLevel.STRICT)
        assertTrue(default.contains("hunter2"), "DEFAULT leaves embedded secret")
        assertTrue(!strict.contains("hunter2"), "STRICT scrubs embedded secret")
    }

    @Test
    fun `RL4-STRICT still redacts the structured secret fields`() {
        val strict = redactSecrets(secretJson, RedactionLevel.STRICT)
        assertTrue(!strict.contains("hunter2"))
        assertTrue(strict.contains("***REDACTED***"))
    }

    // ─── supplier hot-swap on both sinks ────────────────────────────────────

    @Test
    fun `RL5-InMemoryAuditLog honours the live redaction level on the next append`() = runBlocking {
        val level = arrayOf(RedactionLevel.DEFAULT)
        val log = InMemoryAuditLog().apply { start() }
        try {
            log.redactionLevel = { level[0] }
            val now = System.currentTimeMillis()
            log.append(RunRecord(runId = "r1", timestamp = now, ir = secretJson))
            log.flush()
            assertTrue(!log.getRun("r1")!!.ir!!.contains("hunter2"))

            level[0] = RedactionLevel.OFF
            log.append(RunRecord(runId = "r2", timestamp = now, ir = secretJson))
            log.flush()
            assertTrue(log.getRun("r2")!!.ir!!.contains("hunter2"))
        } finally {
            log.stop()
        }
    }

    @Test
    fun `RL6-FileAuditLog honours the live redaction level on the next append`() = runBlocking {
        var level = RedactionLevel.OFF
        val log = FileAuditLog(file, redactionLevel = { level }).apply { start() }
        try {
            log.append(RunRecord(runId = "r1", timestamp = System.currentTimeMillis(), ir = secretJson))
            log.flush()
            assertTrue(log.getRun("r1")!!.ir!!.contains("hunter2"), "OFF stores raw")

            level = RedactionLevel.STRICT
            log.append(RunRecord(runId = "r2", timestamp = System.currentTimeMillis(), ir = secretJson))
            log.flush()
            assertTrue(!log.getRun("r2")!!.ir!!.contains("hunter2"), "STRICT scrubs")
        } finally {
            log.stop()
        }
    }

    @Test
    fun `RL7-ordinal ordering is OFF less than DEFAULT less than STRICT`() {
        assertTrue(RedactionLevel.OFF.ordinal < RedactionLevel.DEFAULT.ordinal)
        assertTrue(RedactionLevel.DEFAULT.ordinal < RedactionLevel.STRICT.ordinal)
    }
}
