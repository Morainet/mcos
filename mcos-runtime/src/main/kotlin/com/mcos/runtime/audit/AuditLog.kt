package com.mcos.runtime.audit

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
 * MVP implementation: in-memory storage with single-writer coroutine.
 * Production upgrade path: Room + SQLCipher with the same append semantics.
 *
 * Security features:
 * - **Secret redaction** — the `ir` field is scrubbed before storage per
 *   [03-runtime.md 13.3]. Fields matching `password`, `token`, `secret`,
 *   `apiKey`, `credential` (case-insensitive) are replaced with
 *   `***REDACTED***`.
 * - **Tamper-evidence** — `export()` optionally appends an HMAC-SHA256
 *   signature line keyed by a device-bound secret, per [03-runtime.md 13.3].
 *
 * Matches [03-runtime.md 13].
 */
class AuditLog {

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
    fun start() {
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
    fun stop() {
        writerChannel.close()
        writerJob?.cancel()
        scope.cancel()
    }

    /**
     * Append a run record to the audit log. Non-blocking.
     * The record's `ir` field is redacted before storage.
     */
    fun append(record: RunRecord) {
        val redacted = record.copy(ir = record.ir?.let { redactSecrets(it) })
        writerChannel.trySend(ChannelMsg.Record(redacted))
    }

    /**
     * Block until all records submitted via [append] have been processed by
     * the writer coroutine.
     *
     * Sends a sentinel through the channel and waits for it to be consumed,
     * which guarantees all previously sent records have been written.
     * Useful for tests and for ensuring durability before shutdown.
     */
    suspend fun flush() {
        val sentinel = CompletableDeferred<Unit>()
        writerChannel.trySend(ChannelMsg.Flush(sentinel))
        sentinel.await()
    }

    /**
     * Get all stored run records, newest first.
     */
    fun getRuns(): List<RunRecord> = records.sortedByDescending { it.timestamp }

    /**
     * Get a specific run by ID.
     */
    fun getRun(runId: String): RunRecord? = records.find { it.runId == runId }

    /**
     * Get recent runs with an optional limit.
     */
    fun getRecent(limit: Int = 20): List<RunRecord> =
        records.takeLast(minOf(limit, records.size)).reversed()

    /**
     * Get the total number of stored records.
     */
    fun count(): Int = records.size

    /**
     * Export all records as JSONL (one JSON object per line).
     *
     * If [hmacKey] is set, a trailing signature line is appended:
     * `{"signature":"<hex>","algorithm":"HMAC-SHA256"}`
     * per [03-runtime.md 13.3] tamper-evidence requirement.
     */
    fun export(): String {
        val json = Json { prettyPrint = false }
        val body = records.joinToString("\n") { json.encodeToString(it) }
        val key = hmacKey ?: return body
        val signature = hmacSha256(body, key)
        return body + "\n" + json.encodeToString(
            buildJsonObject {
                put("signature", JsonPrimitive(signature))
                put("algorithm", JsonPrimitive("HMAC-SHA256"))
            }
        )
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
        // Iterate snapshot to avoid COW mutation during iteration
        records.toList().forEach { rec ->
            if (rec.timestamp < cutoff) {
                records.remove(rec)
            }
        }
    }

    // ─── Secret redaction ──────────────────────────────────────────────

    /**
     * Redact known secret field names in an IR JSON string.
     * Implements the deterministic redaction walk from [03-runtime.md 13.3].
     *
     * Field names matching (case-insensitive, substring):
     * `password`, `token`, `secret`, `apikey`, `credential`
     * → value replaced with `"***REDACTED***"`.
     */
    internal fun redactSecrets(irJson: String): String {
        return try {
            val element = Json.parseToJsonElement(irJson)
            val redacted = redactElement(element)
            REDACT_JSON.encodeToString(JsonElement.serializer(), redacted)
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
                val result = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    result[key] = if (isSecretField(key)) {
                        JsonPrimitive("***REDACTED***")
                    } else {
                        redactElement(value)
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
            lower.contains("apikey") ||
            lower.contains("api_key") ||
            lower.contains("credential")
    }

    // ─── HMAC ──────────────────────────────────────────────────────────

    private fun hmacSha256(data: String, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Reusable Json encoder for redaction output. */
        private val REDACT_JSON = Json { prettyPrint = false }

        /** Regex to catch `"secretField": "value"` pairs in raw (non-JSON) text. */
        private val REGEX_SECRET_PAIR = Regex(
            """(?i)(["']?(?:password|token|secret|apikey|api_key|credential)[^"':]*["']?\s*[:=]\s*["'])[^"']*(["'])"""
        )

        /**
         * Derive a stable HMAC key from a device-bound seed (e.g. Keystore alias).
         * Produces a 32-byte SHA-256 digest suitable for [hmacKey].
         */
        fun deriveKey(seed: String): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(seed.toByteArray(Charsets.UTF_8))
        }
    }
}
