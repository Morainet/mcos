package com.morainet.mcos.security.audit

import com.morainet.mcos.security.NullAuditLog
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fail-closed Stage-10 audit semantics ([03-runtime.md §13.3]): the sinks'
 * durability report behind `SecurityConfig.auditFailClosed`. Each sink must
 * answer honestly whether an appendVerified record was durably accepted —
 * the Executor relies on that answer to fail the run rather than silently
 * dropping its audit record.
 */
class AuditFailClosedTest {

    private fun tempFile(): File =
        File.createTempFile("mcos-audit-fc", ".jsonl").apply { delete() }

    /** An audit file whose parent is an existing FILE — it can never be opened. */
    private fun unwritableFile(): Pair<File, File> {
        val dir = File.createTempFile("mcos-audit-blocker", ".tmp").apply { delete(); mkdirs() }
        val blocker = File(dir, "blocker").apply { createNewFile() }
        return dir to File(blocker, "audit.jsonl")
    }

    private fun record(id: String = "run-1") = RunRecord(
        runId = id,
        timestamp = System.currentTimeMillis(),
        ir = """{"token":"TOP-SECRET-VALUE"}""",
    )

    @Test
    fun `healthy file sink verifies a durable append`() = runBlocking {
        val file = tempFile()
        try {
            val log = FileAuditLog(file).also { it.start() }
            assertTrue(log.appendVerified(record("run-ok")))
            log.flush()
            assertEquals(1, log.count())
            assertTrue(file.readText().contains("\"run-ok\""))
            log.stop()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `verified append redacts secrets before persisting`() = runBlocking {
        val file = tempFile()
        try {
            val log = FileAuditLog(file).also { it.start() }
            assertTrue(log.appendVerified(record("run-secret")))
            log.flush()
            val text = file.readText()
            assertTrue(text.contains("REDACTED"))
            assertFalse(text.contains("TOP-SECRET-VALUE"))
            log.stop()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `unwritable file sink cannot verify an append`() = runBlocking {
        val (dir, file) = unwritableFile()
        try {
            val log = FileAuditLog(file).also { it.start() }
            assertFalse(log.appendVerified(record("run-lost")))
            log.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stopped file sink cannot verify an append`() = runBlocking {
        val file = tempFile()
        try {
            val log = FileAuditLog(file).also { it.start() }
            log.stop()
            assertFalse(log.appendVerified(record("run-stopped")))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `in-memory sink verifies while its writer is live and refuses when stopped`() = runBlocking {
        val log = InMemoryAuditLog()
        // Nothing draining the queue yet: a verified record would be lost.
        assertFalse(log.appendVerified(record("run-before-start")))
        log.start()
        assertTrue(log.appendVerified(record("run-live")))
        log.flush()
        assertTrue(log.getRuns().any { it.runId == "run-live" })
        log.stop()
        assertFalse(log.appendVerified(record("run-after-stop")))
    }

    @Test
    fun `null sink never verifies`() = runBlocking {
        assertFalse(NullAuditLog.appendVerified(record("run-null")))
    }
}
