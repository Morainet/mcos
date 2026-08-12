package com.mcos.runtime.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Outcome of a past run, per 07-memory.md §8.0.
 */
enum class EpisodicOutcome { SUCCESS, PARTIAL, FAILED, CANCELLED }

/**
 * A summary of a significant past run (07-memory.md §8.0). Lives in the
 * Archival tier — not pinned, retrieved on demand.
 */
data class EpisodicRecord(
    val runId: String,                 // correlates with 03 §13 Audit Log
    val timestamp: Long,               // epoch millis
    val summary: String,               // human-readable, e.g. "Compressed 12 photos and emailed Tom"
    val commandIds: List<String>,      // commands executed, e.g. ["photo.search", "compress.images", "mail.send"]
    val entities: List<String>,        // memory paths referenced, e.g. ["people.tom", "places.office"]
    val outcome: EpisodicOutcome,      // SUCCESS | PARTIAL | FAILED | CANCELLED
    val embedding: FloatArray? = null, // computed from summary; null until indexed
)

/**
 * A retrieval result: an episode plus its final `similarity × decay` score.
 */
data class EpisodicHit(
    val record: EpisodicRecord,
    val score: Float,
)

/**
 * In-memory episodic memory (07-memory.md §8): stores summaries of past runs
 * and retrieves them with **time-decay ranking** (§8.1) so recent behavior
 * outranks old, even at equal similarity.
 *
 * Retention (§8.2) is enforced on every write: records older than [maxAgeMs]
 * are evicted; when the count exceeds [maxRecords], the oldest batch
 * ([summarizeBatch]) is compressed into [summarizeKeep] summary records.
 *
 * Retrieval uses [fuzzyScore] as the on-device stand-in for dense embeddings
 * (§6.0 Step 3) until an embedding provider lands. [EpisodicMemory] is a
 * runtime-internal component: episode creation happens on workflow completion
 * (Summarizer, §13) and recall is driven by the Planner (§8.3).
 *
 * Not thread-confined: all mutations and reads are guarded by an internal
 * lock.
 */
class EpisodicMemory(
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val summarizeBatch: Int = DEFAULT_SUMMARIZE_BATCH,
    private val summarizeKeep: Int = DEFAULT_SUMMARIZE_KEEP,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val records = ArrayList<EpisodicRecord>()
    private val lock = Any()

    /**
     * Append a run summary and enforce the retention policy (§8.2). The
     * record's [EpisodicRecord.timestamp] defaults to the current clock.
     */
    fun record(
        runId: String,
        summary: String,
        commandIds: List<String> = emptyList(),
        entities: List<String> = emptyList(),
        outcome: EpisodicOutcome = EpisodicOutcome.SUCCESS,
        timestamp: Long = clock(),
    ): EpisodicRecord {
        val rec = EpisodicRecord(runId, timestamp, summary, commandIds, entities, outcome)
        synchronized(lock) {
            records.add(rec)
            // Retention is evaluated against the wall clock, not the record's
            // own (possibly historic) timestamp.
            enforceRetention(clock())
        }
        return rec
    }

    /**
     * Retrieve episodes ranked by `similarity × decay_weight` (§8.1).
     * Similarity is the best [fuzzyScore] across summary, command ids and
     * entity paths; episodes with zero similarity are excluded. Results are
     * capped at [topK].
     */
    fun search(query: String, topK: Int = 5): List<EpisodicHit> {
        if (query.isBlank()) return emptyList()
        val now = clock()
        return synchronized(lock) {
            records
                .map { rec ->
                    val fields = listOf(rec.summary) + rec.commandIds + rec.entities
                    val similarity = fields.maxOf { fuzzyScore(query, it) }
                    EpisodicHit(rec, similarity * decayWeight(now - rec.timestamp))
                }
                .filter { it.score > 0f }
                .sortedByDescending { it.score }
                .take(topK.coerceAtLeast(1))
        }
    }

    /**
     * Episodes that executed [commandId], newest first. Supports the
     * Planner's first-use check (§8.3): a command absent from every episode
     * is a first use and warrants an explicit `confirm`.
     */
    fun byCommand(commandId: String): List<EpisodicRecord> =
        synchronized(lock) {
            records.filter { it.commandIds.contains(commandId) }
                .sortedByDescending { it.timestamp }
        }

    /** First-use check (§8.3): has [commandId] ever run in a recorded episode? */
    fun hasExecuted(commandId: String): Boolean = byCommand(commandId).isNotEmpty()

    /** Number of stored records (after retention policy is in force). */
    fun count(): Int = synchronized(lock) { records.size }

    fun clear() = synchronized(lock) { records.clear() }

    /**
     * Export episodes as the `episodic` member of `MemoryExport` (07-memory.md
     * §4.0 / §8.0 JSON shape). Embeddings are never exported.
     */
    fun exportEpisodic(): JsonArray = synchronized(lock) {
        buildJsonArray {
            records.sortedBy { it.timestamp }.forEach { rec ->
                add(
                    buildJsonObject {
                        put("runId", JsonPrimitive(rec.runId))
                        put("timestamp", JsonPrimitive(rec.timestamp))
                        put("summary", JsonPrimitive(rec.summary))
                        put("commandIds", JsonArray(rec.commandIds.map { JsonPrimitive(it) }))
                        put("entities", JsonArray(rec.entities.map { JsonPrimitive(it) }))
                        put("outcome", JsonPrimitive(rec.outcome.name))
                    }
                )
            }
        }
    }

    // ─── Retention policy (§8.2) ─────────────────────────────────────────

    /**
     * 1) Evict records older than [maxAgeMs].
     * 2) While over [maxRecords], compress the oldest [summarizeBatch]
     *    records into [summarizeKeep] summaries. Batch entries are merged by
     *    time-chunk: runId/timestamp come from the chunk's oldest record,
     *    commands/entities are deduplicated, outcome is the majority.
     */
    private fun enforceRetention(now: Long) {
        records.removeAll { it.timestamp < now - maxAgeMs }
        while (records.size > maxRecords) {
            val batch = records.take(summarizeBatch)
            if (batch.size < 2) break
            records.removeAll(batch.toSet())
            records.addAll(summarize(batch))
            records.sortBy { it.timestamp }
        }
    }

    private fun summarize(batch: List<EpisodicRecord>): List<EpisodicRecord> {
        val sorted = batch.sortedBy { it.timestamp }
        val groupSize = (sorted.size + summarizeKeep - 1) / summarizeKeep
        return sorted.chunked(groupSize).map { group ->
            val first = group.first()
            EpisodicRecord(
                runId = "summary_${first.timestamp}",
                timestamp = first.timestamp,
                summary = "Summarized ${group.size} past runs: " +
                    group.joinToString("; ") { it.summary }.take(MAX_SUMMARY_CHARS),
                commandIds = group.flatMap { it.commandIds }.distinct(),
                entities = group.flatMap { it.entities }.distinct(),
                outcome = group.groupingBy { it.outcome }.eachCount()
                    .maxByOrNull { it.value }!!.key,
            )
        }
    }

    // ─── Time decay (§8.1) ───────────────────────────────────────────────

    private fun decayWeight(ageMs: Long): Float = when {
        ageMs < 0 -> 1.0f
        ageMs < DAY_MS * 7 -> 1.0f   // 0–7 days
        ageMs < DAY_MS * 30 -> 0.5f  // 7–30 days
        ageMs < DAY_MS * 90 -> 0.2f  // 30–90 days
        else -> 0.05f                // > 90 days
    }

    companion object {
        /** Max episodic records (07-memory.md §8.2 default). */
        const val DEFAULT_MAX_RECORDS = 1000

        /** Max episode age (07-memory.md §8.2 default: 90 days). */
        const val DEFAULT_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

        /** Oldest batch compressed at once (§8.2: 50 records). */
        const val DEFAULT_SUMMARIZE_BATCH = 50

        /** Batch compression target (§8.2: compress 50 → 5 summaries). */
        const val DEFAULT_SUMMARIZE_KEEP = 5

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Summary text truncation guard for compressed episodes. */
        private const val MAX_SUMMARY_CHARS = 300
    }
}
