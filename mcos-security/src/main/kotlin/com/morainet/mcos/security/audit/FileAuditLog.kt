package com.morainet.mcos.security.audit

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persistent [AuditLog]: append-only JSONL file plus an in-memory query
 * index replayed on [start], so audit records survive process restarts and
 * Android configuration changes (03-runtime.md §13.2 "append-oriented local
 * store"; Room + SQLCipher remains the encrypted upgrade path).
 *
 * Architecture mirrors [InMemoryAuditLog]: a single-writer coroutine on
 * `Dispatchers.IO.limitedParallelism(1)` fed by an unbounded channel
 * (03-runtime.md §13.3) — ordered, non-blocking writes that never stall the
 * success path.
 *
 * - **Durability** — each line is flushed to the OS as it is written, so a
 *   process kill never loses an already-reported record (no fsync per record;
 *   that trade-off keeps [append] off the hot path).
 * - **Retention** — 30-day TTL + record cap, whichever hits first. Eviction
 *   rewrites the file atomically (tmp + rename), so a crash mid-compaction
 *   never leaves a partial log.
 * - **Corrupt tolerance** — [start] skips malformed lines instead of failing:
 *   a damaged audit file must not crash the host. [corruptedLinesOnLoad]
 *   reports how many were skipped.
 * - **Redaction / tamper-evidence** — identical to [InMemoryAuditLog]
 *   (shared helpers): `ir` scrubbed before persistence, `export()` may carry
 *   a trailing HMAC-SHA256 signature line.
 */
class FileAuditLog(
    /** The JSONL file backing this log. Parent directories are created on [start]. */
    val file: File,
    /** Max records before oldest are evicted (03-runtime.md §13.3 default: 10,000). */
    var maxRecords: Int = 10_000,
    /** Max age of records in milliseconds (03-runtime.md §13.3 default: 30 days). */
    var maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
    /** HMAC signing key; if null, `export()` omits the signature line. */
    var hmacKey: ByteArray? = null,
) : AuditLog {

    private sealed interface ChannelMsg {
        data class Record(val record: RunRecord) : ChannelMsg
        data class Flush(val done: CompletableDeferred<Unit>) : ChannelMsg
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val channel = Channel<ChannelMsg>(capacity = Channel.UNLIMITED)

    // CopyOnWriteArrayList for safe concurrent reads while the writer mutates.
    // Insertion-ordered (replay restores file order), matching InMemoryAuditLog.
    private val records = CopyOnWriteArrayList<RunRecord>()

    private var writerJob: Job? = null

    /** Append-mode handle owned by the single writer thread; volatile for [clear]. */
    @Volatile private var writer: BufferedWriter? = null

    @Volatile private var replayed = false

    /** Malformed lines skipped while replaying [file] in the last [start]. */
    var corruptedLinesOnLoad: Int = 0
        private set

    /**
     * Start the single-writer coroutine. Idempotent. The writer replays the
     * backing file into the query index (once per instance) before it starts
     * draining [append]ed records.
     */
    override fun start() {
        if (writerJob?.isActive == true) return
        writerJob = scope.launch {
            replayIfNeeded()
            for (msg in channel) {
                when (msg) {
                    is ChannelMsg.Flush -> {
                        runCatching { writer?.flush() }
                        msg.done.complete(Unit)
                    }
                    is ChannelMsg.Record -> writeRecord(msg.record)
                }
            }
        }
    }

    /**
     * Stop the writer coroutine, flush and close the file handle. The channel
     * closes with it; persistence continues via a new instance replaying the
     * same [file] (the supported lifecycle — e.g. one [FileAuditLog] per
     * Android Activity instance).
     */
    override fun stop() {
        channel.close()
        writerJob?.cancel()
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
    }

    /** Append a run record. Non-blocking; `ir` is redacted before persistence. */
    override fun append(record: RunRecord) {
        val redacted = record.copy(ir = record.ir?.let { redactSecrets(it) })
        channel.trySend(ChannelMsg.Record(redacted))
    }

    /**
     * Block until all records submitted via [append] have been written.
     * Returns immediately when no writer is running instead of deadlocking
     * on a sentinel nothing will consume (P0-C5).
     */
    override suspend fun flush() {
        val job = writerJob
        if (job == null || !job.isActive) return
        val sentinel = CompletableDeferred<Unit>()
        channel.trySend(ChannelMsg.Flush(sentinel))
        sentinel.await()
    }

    override fun getRuns(): List<RunRecord> = records.sortedByDescending { it.timestamp }

    override fun getRun(runId: String): RunRecord? = records.find { it.runId == runId }

    override fun getRecent(limit: Int): List<RunRecord> =
        records.takeLast(minOf(limit, records.size)).reversed()

    override fun count(): Int = records.size

    /** Export all records as JSONL (+ optional trailing HMAC signature line). */
    override fun export(): String = exportJsonl(records, hmacKey)

    /** Clear the in-memory index and delete the backing file. For testing. */
    override fun clear() {
        records.clear()
        runCatching { writer?.close() }
        writer = null
        file.delete()
    }

    // ─── Writer-thread internals ────────────────────────────────────────

    /** Replay the backing file into [records], apply retention, open the writer. */
    private fun replayIfNeeded() {
        if (replayed) return
        replayed = true
        corruptedLinesOnLoad = 0
        try {
            file.parentFile?.mkdirs()
            if (file.isFile) {
                file.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine
                    try {
                        records.add(AUDIT_JSON.decodeFromString(RunRecord.serializer(), trimmed))
                    } catch (_: Exception) {
                        corruptedLinesOnLoad++
                    }
                }
                // Retention applies on load too: an aged-out backlog (e.g. the
                // app was unused for months) compacts immediately.
                val retained = retainedRecords(records, maxRecords, maxAgeMs)
                if (retained.size != records.size) {
                    records.clear()
                    records.addAll(retained)
                    rewriteFileLocked()
                }
            }
        } catch (_: IOException) {
            // Unreadable file: keep whatever was replayed; a rewrite on the
            // next eviction will heal the file.
        }
        openWriter()
    }

    private fun openWriter(): BufferedWriter? {
        writer?.let { return it }
        return try {
            FileOutputStream(file, true).bufferedWriter().also { writer = it }
        } catch (_: IOException) {
            null // Memory-only until the file becomes writable again.
        }
    }

    private fun writeRecord(record: RunRecord) {
        records.add(record)
        val w = writer ?: openWriter()
        if (w != null) {
            try {
                w.write(AUDIT_JSON.encodeToString(record))
                w.write("\n")
                w.flush()
            } catch (_: IOException) {
                runCatching { w.close() }
                writer = null
            }
        }
        evict()
    }

    private fun evict() {
        val retained = retainedRecords(records, maxRecords, maxAgeMs)
        if (retained.size != records.size) {
            records.clear()
            records.addAll(retained)
            rewriteFileLocked()
        }
    }

    /**
     * Rewrite the backing file from [records] atomically (tmp + rename, with
     * a non-atomic fallback when the filesystem rejects ATOMIC_MOVE — same
     * pattern as mcos-server's BlobStore). Writer-thread only.
     */
    private fun rewriteFileLocked() {
        runCatching { writer?.close() }
        writer = null
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.bufferedWriter().use { out ->
                for (rec in records) {
                    out.write(AUDIT_JSON.encodeToString(rec))
                    out.write("\n")
                }
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: IOException) {
            runCatching { tmp.delete() }
        }
        openWriter()
    }
}
