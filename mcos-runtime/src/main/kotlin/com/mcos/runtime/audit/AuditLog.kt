package com.mcos.runtime.audit

import com.mcos.runtime.error.McosErrorCode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Per-step audit record within a run.
 */
@Serializable
data class StepRecord(
    val commandId: String,
    val pluginId: String,
    val ok: Boolean,
    val code: String? = null,
    val message: String? = null,
    val durationMs: Long,
    val artifacts: List<ArtifactRecord> = emptyList()
)

@Serializable
data class ArtifactRecord(
    val type: String,
    val uri: String,
    val mimeType: String? = null
)

/**
 * Complete run audit record.
 * Matches [03-runtime.md 13.1].
 */
@Serializable
data class RunRecord(
    val runId: String,
    val timestamp: Long,
    val source: String = "CLI",
    val commandId: String? = null,
    val ir: String? = null,
    val steps: List<StepRecord> = emptyList(),
    val totalDurationMs: Long = 0,
    val outcome: String = "ok" // "ok" | "failed" | "cancelled" | "timeout"
)

/**
 * Append-only audit log.
 *
 * MVP implementation: in-memory storage with single-writer coroutine.
 * Production upgrade path: Room + SQLCipher with the same append semantics.
 *
 * Matches [03-runtime.md 13].
 */
class AuditLog {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writerChannel = Channel<RunRecord>(capacity = Channel.UNLIMITED)
    private val records = mutableListOf<RunRecord>()

    /** Max records before oldest are evicted (MVP default: 10,000) */
    var maxRecords: Int = 10_000

    /** Max age of records in milliseconds (MVP default: 30 days) */
    var maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000

    private var writerJob: Job? = null

    /**
     * Start the single-writer coroutine. Must be called before [append].
     */
    fun start() {
        if (writerJob?.isActive == true) return
        writerJob = scope.launch {
            for (record in writerChannel) {
                writeRecord(record)
            }
        }
    }

    /**
     * Stop the writer coroutine and flush pending records.
     */
    fun stop() {
        writerChannel.close()
        writerJob?.cancel()
        scope.cancel()
    }

    /**
     * Append a run record to the audit log. Non-blocking.
     */
    fun append(record: RunRecord) {
        writerChannel.trySend(record)
    }

    /**
     * Get all stored run records, newest first.
     */
    fun getRuns(): List<RunRecord> = records.toList().sortedByDescending { it.timestamp }

    /**
     * Get a specific run by ID.
     */
    fun getRun(runId: String): RunRecord? = records.find { it.runId == runId }

    /**
     * Get recent runs with an optional limit.
     */
    fun getRecent(limit: Int = 20): List<RunRecord> =
        records.takeLast(limit).reversed()

    /**
     * Get the total number of stored records.
     */
    fun count(): Int = records.size

    /**
     * Export all records as JSONL (one JSON object per line).
     */
    fun export(): String {
        val json = Json { prettyPrint = false }
        return records.joinToString("\n") { json.encodeToString(it) }
    }

    /**
     * Clear all records. For testing.
     */
    fun clear() {
        records.clear()
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private fun writeRecord(record: RunRecord) {
        records.add(record)
        evict()
    }

    private fun evict() {
        // Evict by count
        while (records.size > maxRecords) {
            records.removeAt(0)
        }
        // Evict by age
        val cutoff = System.currentTimeMillis() - maxAgeMs
        records.removeAll { it.timestamp < cutoff }
    }
}
