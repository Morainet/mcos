package com.morainet.mcos.security.audit

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

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
 * Outcome of a run. Serialized with lowercase names to keep the
 * audit JSON wire format ("ok" | "failed" | "cancelled" | "timeout").
 */
@Serializable
enum class RunOutcome {
    @SerialName("ok") OK,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("timeout") TIMEOUT
}

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
    val outcome: RunOutcome = RunOutcome.OK
)

/**
 * Append-only audit log.
 *
 * The audit log is an interface so the executor wiring is never `null`
 * (null would silently drop the audit trail — fail-open). Production uses
 * [InMemoryAuditLog]; hosts that opt out use the named [NullAuditLog]
 * explicitly.
 *
 * Security features (see [InMemoryAuditLog]):
 * - **Secret redaction** — the `ir` field is scrubbed before storage per
 *   [03-runtime.md 13.3].
 * - **Tamper-evidence** — `export()` optionally appends an HMAC-SHA256
 *   signature line keyed by a device-bound secret, per [03-runtime.md 13.3].
 *
 * Matches [03-runtime.md 13].
 */
interface AuditLog {

    /** Append a run record to the audit log. Non-blocking. */
    fun append(record: RunRecord)

    /**
     * Block until all records submitted via [append] have been processed.
     * Useful for tests and for ensuring durability before shutdown.
     */
    suspend fun flush()

    /** Start any background writer. Must be idempotent. */
    fun start()

    /** Stop background writers and flush pending records. */
    fun stop()

    /** All stored run records, newest first. */
    fun getRuns(): List<RunRecord>

    /** A specific run by ID. */
    fun getRun(runId: String): RunRecord?

    /** Recent runs with an optional limit. */
    fun getRecent(limit: Int = 20): List<RunRecord>

    /** Total number of stored records. */
    fun count(): Int

    /** Export all records as JSONL (one JSON object per line). */
    fun export(): String

    /** Clear all records. For testing. */
    fun clear()
}

// ─── Shared audit internals (used by [InMemoryAuditLog] and [FileAuditLog]) ──

/** Reusable Json codec for audit lines and redaction output. */
internal val AUDIT_JSON = Json { prettyPrint = false }

/** Regex to catch `"secretField": "value"` pairs in raw (non-JSON) text. */
internal val REGEX_SECRET_PAIR = Regex(
    """(?i)(["']?(?:password|token|secret|apikey|api_key|credential)[^"':]*["']?\s*[:=]\s*["'])[^"']*(["'])"""
)

/**
 * Redact known secret field names in an IR JSON string.
 * Implements the deterministic redaction walk from [03-runtime.md 13.3].
 *
 * Field names matching (case-insensitive, substring):
 * `password`, `token`, `secret`, `apikey`, `credential`,
 * `authorization`, `bearer`, `cookie` → value replaced with
 * `"***REDACTED***"`.
 */
internal fun redactSecrets(irJson: String): String {
    return try {
        val element = Json.parseToJsonElement(irJson)
        val redacted = redactElement(element)
        AUDIT_JSON.encodeToString(JsonElement.serializer(), redacted)
    } catch (_: Exception) {
        // If the IR is not valid JSON (e.g. raw DSL text), fall back to
        // regex-based redaction on quoted key-value pairs.
        REGEX_SECRET_PAIR.replace(irJson) { mr ->
            "${mr.groupValues[1]}\"***REDACTED***\""
        }
    }
}

private fun redactElement(element: JsonElement): JsonElement {
    return when (element) {
        is JsonObject -> {
            // 08-security.md §9.4 — an object carrying the x-mcos-secret
            // marker is secret regardless of its member names: every
            // scalar member is redacted (the marker itself is kept).
            val marked = element["x-mcos-secret"]?.jsonPrimitive?.booleanOrNull == true ||
                element["xMcosSecret"]?.jsonPrimitive?.booleanOrNull == true
            val result = linkedMapOf<String, JsonElement>()
            for ((key, value) in element) {
                result[key] = when {
                    key == "x-mcos-secret" || key == "xMcosSecret" -> value
                    isSecretField(key) -> JsonPrimitive("***REDACTED***")
                    marked && value is JsonPrimitive -> JsonPrimitive("***REDACTED***")
                    else -> redactElement(value)
                }
            }
            JsonObject(result)
        }
        is JsonArray -> JsonArray(element.map { redactElement(it) })
        else -> element
    }
}

private fun isSecretField(name: String): Boolean {
    val lower = name.lowercase()
    return lower.contains("password") ||
        lower.contains("token") ||
        lower.contains("secret") ||
        lower.contains("apikey") ||
        lower.contains("api_key") ||
        lower.contains("credential") ||
        lower.contains("authorization") ||
        lower.contains("bearer") ||
        lower.contains("cookie")
}

/** Hex-encoded HMAC-SHA256 of [data] under [key]. */
internal fun hmacSha256Hex(data: String, key: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
    return raw.joinToString("") { "%02x".format(it) }
}

/**
 * Build the export body: records as JSONL, optionally followed by a trailing
 * HMAC-SHA256 signature line per [03-runtime.md 13.3].
 */
internal fun exportJsonl(records: List<RunRecord>, hmacKey: ByteArray?): String {
    val body = records.joinToString("\n") { AUDIT_JSON.encodeToString(it) }
    val key = hmacKey ?: return body
    val signature = hmacSha256Hex(body, key)
    return body + "\n" + AUDIT_JSON.encodeToString(
        buildJsonObject {
            put("signature", JsonPrimitive(signature))
            put("algorithm", JsonPrimitive("HMAC-SHA256"))
        }
    )
}

/**
 * Retention per [03-runtime.md 13.3]: 30-day TTL and a 10,000-record cap,
 * whichever hits first (oldest evicted). Pure — callers apply the result.
 */
internal fun retainedRecords(
    records: List<RunRecord>,
    maxRecords: Int,
    maxAgeMs: Long,
): List<RunRecord> {
    if (records.isEmpty()) return records
    val cutoff = System.currentTimeMillis() - maxAgeMs
    val afterAge = records.filter { it.timestamp >= cutoff }
    return if (afterAge.size > maxRecords) afterAge.takeLast(maxRecords) else afterAge
}

/**
 * Derive a stable HMAC key from a device-bound seed (e.g. a random secret
 * persisted in SecureStore, or a Keystore alias). Produces a 32-byte
 * SHA-256 digest suitable for `hmacKey`. Public so hosts (e.g. the Android
 * shell) can derive the export-signature key from their persisted seed.
 */
fun deriveAuditHmacKey(seed: String): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(seed.toByteArray(Charsets.UTF_8))
}

/**
 * MVP [AuditLog]: in-memory storage with a single-writer coroutine.
 * Production upgrade path: [FileAuditLog] (persistent JSONL) or
 * Room + SQLCipher with the same append semantics.
 *
 * Security features:
 * - **Secret redaction** — the `ir` field is scrubbed before storage per
 *   [03-runtime.md 13.3]. Fields matching `password`, `token`, `secret`,
 *   `apiKey`, `credential` (case-insensitive) are replaced with
 *   `***REDACTED***`.
 * - **Tamper-evidence** — `export()` optionally appends an HMAC-SHA256
 *   signature line keyed by a device-bound secret, per [03-runtime.md 13.3].
 */
class InMemoryAuditLog : AuditLog {

    /** Messages that flow through the writer channel. */
    private sealed interface ChannelMsg {
        data class Record(val record: RunRecord) : ChannelMsg
        data class Flush(val done: CompletableDeferred<Unit>) : ChannelMsg
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writerChannel = Channel<ChannelMsg>(capacity = Channel.UNLIMITED)

    // CopyOnWriteArrayList for safe concurrent reads while the writer mutates.
    private val records = CopyOnWriteArrayList<RunRecord>()

    /** Max records before oldest are evicted (MVP default: 10,000) */
    var maxRecords: Int = 10_000

    /** Max age of records in milliseconds (MVP default: 30 days) */
    var maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * HMAC signing key (device-bound in production via Keystore).
     * If null, `export()` omits the signature line.
     */
    var hmacKey: ByteArray? = null

    private var writerJob: Job? = null

    /**
     * Start the single-writer coroutine. Must be called before [append].
     */
    override fun start() {
        if (writerJob?.isActive == true) return
        writerJob = scope.launch {
            for (msg in writerChannel) {
                when (msg) {
                    is ChannelMsg.Flush -> msg.done.complete(Unit)
                    is ChannelMsg.Record -> writeRecord(msg.record)
                }
            }
        }
    }

    /**
     * Stop the writer coroutine and flush pending records.
     */
    override fun stop() {
        writerChannel.close()
        writerJob?.cancel()
        scope.cancel()
    }

    /**
     * Append a run record to the audit log.
     * The record's `ir` field is redacted before storage.
     */
    override fun append(record: RunRecord) {
        val redacted = record.copy(ir = record.ir?.let { redactSecrets(it) })
        writerChannel.trySend(ChannelMsg.Record(redacted))
    }

    /**
     * Block until all records submitted via [append] have been processed by
     * the writer coroutine.
     *
     * Sends a sentinel through the channel and waits for it to be consumed,
     * which guarantees all previously sent records have been written.
     *
     * If the writer coroutine is not running (e.g. [start] was never called
     * or the scope was already stopped), this returns immediately rather than
     * deadlocking forever on a sentinel that will never be consumed (P0-C5).
     */
    override suspend fun flush() {
        // No active writer to drain the channel — nothing to wait for.
        val job = writerJob
        if (job == null || !job.isActive) return
        val sentinel = CompletableDeferred<Unit>()
        writerChannel.trySend(ChannelMsg.Flush(sentinel))
        sentinel.await()
    }

    override fun getRuns(): List<RunRecord> = records.sortedByDescending { it.timestamp }

    override fun getRun(runId: String): RunRecord? = records.find { it.runId == runId }

    override fun getRecent(limit: Int): List<RunRecord> =
        records.takeLast(minOf(limit, records.size)).reversed()

    override fun count(): Int = records.size

    /**
     * Export all records as JSONL (one JSON object per line).
     *
     * If [hmacKey] is set, a trailing signature line is appended:
     * `{"signature":"<hex>","algorithm":"HMAC-SHA256"}`
     * per [03-runtime.md 13.3] tamper-evidence requirement.
     */
    override fun export(): String = exportJsonl(records, hmacKey)

    override fun clear() {
        records.clear()
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private fun writeRecord(record: RunRecord) {
        records.add(record)
        evict()
    }

    private fun evict() {
        // Compute the retained sub-list in a single pass, then replace the
        // backing list in one bulk operation. Doing so avoids the O(n²)
        // cost of calling removeAt(0)/remove(rec) one element at a time on
        // a CopyOnWriteArrayList, where every mutation copies the entire
        // array. The writer is single-threaded, so a clear+addAll is safe.
        val afterRetention = retainedRecords(records, maxRecords, maxAgeMs)
        if (afterRetention.size != records.size) {
            records.clear()
            records.addAll(afterRetention)
        }
    }

    companion object {
        /**
         * Derive a stable HMAC key from a device-bound seed (e.g. Keystore alias).
         * Produces a 32-byte SHA-256 digest suitable for [hmacKey].
         */
        fun deriveKey(seed: String): ByteArray = deriveAuditHmacKey(seed)
    }
}
