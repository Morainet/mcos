package com.mcos.runtime.memory

import com.mcos.sdk.MemoryCategory
import com.mcos.sdk.MemoryConflict
import com.mcos.sdk.MemoryFacade
import com.mcos.sdk.MemoryWriteResult
import com.mcos.sdk.ResolveResult
import com.mcos.sdk.WriteStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * In-memory key-value store with semantic indexing and TTL support.
 *
 * Implements [MemoryFacade] for plugin read access and provides write
 * operations for the runtime. Supports:
 * - Hierarchical paths (e.g., "user.address.home")
 * - Semantic tags for reference resolution (e.g., "home" -> address fact)
 * - Optional TTL expiry per entry
 * - Prefix listing and bulk export
 *
 * Upgrade path to persistent storage: Room + SQLCipher with same API surface.
 *
 * Matches [01-architecture.md Memory], [03-runtime.md 12].
 */
class MemoryStore : MemoryFacade {

    /**
     * Guards all shared state ([entries], [semanticIndex], [superseded]).
     * Every public method acquires this lock before touching shared state,
     * following the same JVM-monitor convention as RateLimiter and
     * PermissionKernel. Critical sections are short and never suspend while
     * holding the lock.
     */
    private val lock = Any()

    /** Stored entries indexed by path */
    private val entries = mutableMapOf<String, MemoryEntry>()

    /** Semantic index: tag -> set of paths */
    private val semanticIndex = mutableMapOf<String, MutableSet<String>>()

    /** Soft-deleted (superseded) values: "path@ISO-timestamp" -> entry */
    private val superseded = mutableMapOf<String, MemoryEntry>()

    // ─── Write API (runtime use) ────────────────────────────────────────

    /**
     * Store a value at [path], per [07-memory.md 5.1].
     *
     * Same-path writes supersede the old value (kept in history) and return
     * UPDATED. Writes to a *new* path run a cross-path semantic dedup check
     * against existing entries in the same [category]; a high-similarity hit
     * returns CONFLICT and the write is withheld.
     *
     * @param path Dot-separated hierarchical path, e.g. "user.preferences.theme"
     * @param value The JSON value to store
     * @param ttlMs Optional time-to-live in milliseconds
     * @param tags Optional semantic tags for reference resolution
     * @param category Risk category; drives conflict policy ([07-memory.md 5.2])
     * @param checkConflict Set false to skip the cross-path dedup check
     */
    suspend fun put(
        path: String,
        value: JsonElement,
        ttlMs: Long? = null,
        tags: Set<String> = emptySet(),
        category: MemoryCategory = MemoryCategory.OTHER,
        checkConflict: Boolean = true
    ): MemoryWriteResult = synchronized(lock) {
        val now = currentTimeMs()
        val existing = entries[path]

        // Step 1: same-path write is always an update — soft-delete the old
        // value into history, the new value becomes current.
        if (existing != null) {
            val supersededPath = "$path@${isoTimestamp(now)}"
            superseded[supersededPath] = existing
            entries[path] = MemoryEntry(value, now, ttlMs, tags, category)
            removeFromIndex(path)
            for (tag in tags) {
                semanticIndex.getOrPut(tag) { mutableSetOf() }.add(path)
            }
            return@synchronized MemoryWriteResult(WriteStatus.UPDATED, supersededPath = supersededPath)
        }

        // Step 2: new path → cross-path semantic dedup within the category.
        if (checkConflict) {
            val text = valueToString(value)
            val best = entries.entries
                .filter { it.value.category == category && !it.value.isExpired(now) }
                .map { (otherPath, otherEntry) ->
                    otherPath to fuzzyScore(text, valueToString(otherEntry.value))
                }
                .maxByOrNull { it.second }
            if (best != null && best.second >= CONFLICT_THRESHOLD) {
                val existingEntry = entries[best.first]!!
                return@synchronized MemoryWriteResult(
                    status = WriteStatus.CONFLICT,
                    conflict = MemoryConflict(
                        existingPath = best.first,
                        existingValue = existingEntry.value,
                        similarity = best.second,
                        category = category,
                    ),
                )
            }
        }

        // Step 3: no conflict — create the new fact.
        entries[path] = MemoryEntry(value, now, ttlMs, tags, category)
        for (tag in tags) {
            semanticIndex.getOrPut(tag) { mutableSetOf() }.add(path)
        }
        MemoryWriteResult(WriteStatus.CREATED)
    }

    /**
     * Store a simple string value. Convenience wrapper around [put].
     */
    suspend fun putString(
        path: String,
        value: String,
        ttlMs: Long? = null,
        tags: Set<String> = emptySet(),
        category: MemoryCategory = MemoryCategory.OTHER,
        checkConflict: Boolean = true
    ): MemoryWriteResult = put(path, JsonPrimitive(value), ttlMs, tags, category, checkConflict)

    /**
     * Store a structured JSON object value.
     */
    suspend fun putObject(
        path: String,
        value: Map<String, String>,
        ttlMs: Long? = null,
        tags: Set<String> = emptySet(),
        category: MemoryCategory = MemoryCategory.OTHER,
        checkConflict: Boolean = true
    ): MemoryWriteResult {
        val jsonObj = buildJsonObject {
            value.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return put(path, jsonObj, ttlMs, tags, category, checkConflict)
    }

    // ─── Read API (MemoryFacade impl) ───────────────────────────────────

    override suspend fun get(path: String): JsonElement? = synchronized(lock) {
        val entry = entries[path] ?: return@synchronized null
        if (entry.isExpired(currentTimeMs())) {
            entries.remove(path)
            removeFromIndex(path)
            return@synchronized null
        }
        entry.value
    }

    override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = synchronized(lock) {
        val now = currentTimeMs()

        // Step 1-3: score every tag; exact label match → 1.0, alias/abbreviation
        // and character-similarity matches above the threshold → fuzzy score.
        val tagScores = mutableMapOf<String, Float>()
        for (tag in semanticIndex.keys) {
            val score = fuzzyScore(ref, tag)
            if (score >= FUZZY_THRESHOLD) tagScores[tag] = score
        }

        // Map tags back to paths (best score per path), filtering expired
        // entries and, when given, entries lacking the semantic-type tag.
        val pathScores = mutableMapOf<String, Float>()
        for ((tag, score) in tagScores) {
            for (path in semanticIndex[tag].orEmpty()) {
                val entry = entries[path] ?: continue
                if (entry.isExpired(now)) continue
                if (semanticType != null && !entry.tags.contains(semanticType)) continue
                pathScores[path] = maxOf(pathScores[path] ?: 0f, score)
            }
        }
        val ranked = pathScores.entries.sortedByDescending { it.value }

        // Step 4: resolve — one clear winner, ambiguous cluster, or nothing.
        when {
            ranked.isEmpty() -> ResolveResult.NotFound("ref_unresolvable")
            ranked.size == 1 -> ResolveResult.Resolved(ranked[0].key, ranked[0].value)
            else -> {
                val gap = ranked[0].value - ranked[1].value
                if (gap < AMBIGUITY_THRESHOLD) {
                    ResolveResult.Ambiguous(ranked.map { it.key })
                } else {
                    ResolveResult.Resolved(ranked[0].key, ranked[0].value)
                }
            }
        }
    }

    // ─── Management API ─────────────────────────────────────────────────

    /**
     * Delete an entry by path and remove from semantic index.
     */
    suspend fun delete(path: String) = synchronized(lock) {
        entries.remove(path)
        removeFromIndex(path)
    }

    /**
     * List all paths under a prefix. Useful for namespace operations.
     */
    suspend fun list(prefix: String = ""): List<String> = synchronized(lock) {
        val now = currentTimeMs()
        entries.entries
            .filter { it.key.startsWith(prefix) && !it.value.isExpired(now) }
            .map { it.key }
            .sorted()
    }

    /**
     * Get all entries (including metadata) under a prefix.
     */
    suspend fun listEntries(prefix: String = ""): List<MemoryEntryWithPath> = synchronized(lock) {
        val now = currentTimeMs()
        entries.entries
            .filter { it.key.startsWith(prefix) && !it.value.isExpired(now) }
            .map { (path, entry) ->
                MemoryEntryWithPath(path, entry)
            }
            .sortedBy { it.path }
    }

    /**
     * Check if a path has a valid (non-expired) entry.
     */
    suspend fun has(path: String): Boolean = synchronized(lock) {
        val entry = entries[path] ?: return@synchronized false
        !entry.isExpired(currentTimeMs())
    }

    /**
     * Get all semantic tags currently in use.
     */
    fun tags(): Set<String> = synchronized(lock) {
        semanticIndex.keys.toSet()
    }

    /**
     * Count of non-expired entries.
     */
    suspend fun count(): Int = synchronized(lock) {
        val now = currentTimeMs()
        entries.values.count { !it.isExpired(now) }
    }

    /**
     * Clear all entries, the semantic index and the superseded history.
     */
    suspend fun clear() = synchronized(lock) {
        entries.clear()
        semanticIndex.clear()
        superseded.clear()
    }

    /**
     * Soft-deleted (superseded) versions of [path], oldest first. Each pair is
     * the history key (e.g. "people.tom.phone@2026-07-01T...") and the entry.
     */
    fun history(path: String): List<Pair<String, MemoryEntry>> = synchronized(lock) {
        superseded.entries
            .filter { it.key.startsWith("$path@") }
            .sortedBy { it.key }
            .map { it.key to it.value }
    }

    /**
     * Evict all expired entries.
     */
    suspend fun evictExpired() = synchronized(lock) {
        val now = currentTimeMs()
        val expired = entries.entries.filter { it.value.isExpired(now) }
        for ((path, _) in expired) {
            entries.remove(path)
            removeFromIndex(path)
        }
    }

    // ─── Export ─────────────────────────────────────────────────────────

    /**
     * Export all entries as a JSON object for serialization/sync.
     */
    fun export(): JsonObject = synchronized(lock) {
        val now = currentTimeMs()
        buildJsonObject {
            for ((path, entry) in entries) {
                if (!entry.isExpired(now)) {
                    put(path, buildJsonObject {
                        put("value", entry.value)
                        put("createdAt", JsonPrimitive(entry.createdAt))
                        entry.ttlMs?.let { put("ttlMs", JsonPrimitive(it)) }
                        if (entry.tags.isNotEmpty()) {
                            put("tags", JsonPrimitive(entry.tags.joinToString(",")))
                        }
                    })
                }
            }
        }
    }

    // ─── Internal helpers ───────────────────────────────────────────────

    /**
     * Local character-level similarity in `[0..1]`, used as a stand-in for
     * embedding similarity until the embedding provider lands ([07-memory.md
     * 6.0 Step 3]). Exact match → 1.0; containment (alias/abbreviation like
     * "客厅灯" vs "客厅的灯") → 0.85; otherwise Dice coefficient over
     * character bigrams.
     */
    private fun fuzzyScore(query: String, candidate: String): Float {
        if (query == candidate) return 1f
        if (query.isEmpty() || candidate.isEmpty()) return 0f
        if (query.length >= 2 && candidate.contains(query)) return 0.85f
        if (candidate.length >= 2 && query.contains(candidate)) return 0.85f
        val bigramsQuery = query.windowed(2).toSet()
        val bigramsCandidate = candidate.windowed(2).toSet()
        if (bigramsQuery.isEmpty() || bigramsCandidate.isEmpty()) return 0f
        val intersection = bigramsQuery.intersect(bigramsCandidate).size
        return 2f * intersection / (bigramsQuery.size + bigramsCandidate.size)
    }

    /** Flatten a JSON value to text for similarity comparison. */
    private fun valueToString(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    /** "2026-07-01T12:34:56.123Z" style timestamp, as used in history keys. */
    private fun isoTimestamp(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).toString()

    private fun removeFromIndex(path: String) {
        for ((_, paths) in semanticIndex) {
            paths.remove(path)
        }
        // Clean up empty tag entries
        semanticIndex.entries.removeAll { it.value.isEmpty() }
    }

    private fun currentTimeMs(): Long = System.currentTimeMillis()

    companion object {
        /** Fuzzy reference-match threshold (07-memory.md 6.0 Step 3: > 0.75). */
        const val FUZZY_THRESHOLD = 0.75f

        /** Gap below which the top two candidates are ambiguous (Step 4: < 0.05). */
        const val AMBIGUITY_THRESHOLD = 0.05f

        /** Cross-path conflict similarity threshold (07-memory.md 5.1: ≥ 0.85). */
        const val CONFLICT_THRESHOLD = 0.85f
    }
}

/**
 * A single memory entry with value and metadata.
 */
@Serializable
data class MemoryEntry(
    val value: JsonElement,
    val createdAt: Long,
    val ttlMs: Long? = null,
    val tags: Set<String> = emptySet(),
    val category: MemoryCategory = MemoryCategory.OTHER
) {
    fun isExpired(now: Long): Boolean {
        return ttlMs?.let { now - createdAt > it } ?: false
    }
}

/**
 * A memory entry paired with its path.
 */
data class MemoryEntryWithPath(
    val path: String,
    val entry: MemoryEntry
)
