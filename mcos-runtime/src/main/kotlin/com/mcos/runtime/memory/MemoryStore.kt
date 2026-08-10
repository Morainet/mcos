package com.mcos.runtime.memory

import com.mcos.sdk.MemoryFacade
import com.mcos.sdk.ResolveResult
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
 * Matches [01-architecture.md §Memory], [03-runtime.md §12].
 */
class MemoryStore : MemoryFacade {

    /** Stored entries indexed by path */
    private val entries = mutableMapOf<String, MemoryEntry>()

    /** Semantic index: tag -> set of paths */
    private val semanticIndex = mutableMapOf<String, MutableSet<String>>()

    // ─── Write API (runtime use) ────────────────────────────────────────

    /**
     * Store a value at [path].
     *
     * @param path Dot-separated hierarchical path, e.g. "user.preferences.theme"
     * @param value The JSON value to store
     * @param ttlMs Optional time-to-live in milliseconds
     * @param tags Optional semantic tags for reference resolution
     */
    suspend fun put(
        path: String,
        value: JsonElement,
        ttlMs: Long? = null,
        tags: Set<String> = emptySet()
    ) {
        val entry = MemoryEntry(
            value = value,
            createdAt = currentTimeMs(),
            ttlMs = ttlMs,
            tags = tags
        )
        entries[path] = entry

        // Update semantic index
        for (tag in tags) {
            semanticIndex.getOrPut(tag) { mutableSetOf() }.add(path)
        }
    }

    /**
     * Store a simple string value. Convenience wrapper around [put].
     */
    suspend fun putString(path: String, value: String, ttlMs: Long? = null, tags: Set<String> = emptySet()) {
        put(path, JsonPrimitive(value), ttlMs, tags)
    }

    /**
     * Store a structured JSON object value.
     */
    suspend fun putObject(path: String, value: Map<String, String>, ttlMs: Long? = null, tags: Set<String> = emptySet()) {
        val jsonObj = buildJsonObject {
            value.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        put(path, jsonObj, ttlMs, tags)
    }

    // ─── Read API (MemoryFacade impl) ───────────────────────────────────

    override suspend fun get(path: String): JsonElement? {
        val entry = entries[path] ?: return null
        if (entry.isExpired(currentTimeMs())) {
            entries.remove(path)
            removeFromIndex(path)
            return null
        }
        return entry.value
    }

    override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult {
        val candidates = semanticIndex[ref]?.toList() ?: emptyList()

        // If semanticType specified, filter by matching type tag
        val filtered = if (semanticType != null) {
            candidates.filter { path ->
                entries[path]?.tags?.contains(semanticType) == true
            }
        } else {
            candidates
        }

        // Filter out expired entries
        val now = currentTimeMs()
        val valid = filtered.filter { path ->
            !(entries[path]?.isExpired(now) ?: true)
        }

        return when (valid.size) {
            0 -> ResolveResult.NotFound
            1 -> ResolveResult.Resolved(valid.first())
            else -> ResolveResult.Ambiguous(valid)
        }
    }

    // ─── Management API ─────────────────────────────────────────────────

    /**
     * Delete an entry by path and remove from semantic index.
     */
    suspend fun delete(path: String) {
        entries.remove(path)
        removeFromIndex(path)
    }

    /**
     * List all paths under a prefix. Useful for namespace operations.
     */
    suspend fun list(prefix: String = ""): List<String> {
        val now = currentTimeMs()
        return entries.entries
            .filter { it.key.startsWith(prefix) && !it.value.isExpired(now) }
            .map { it.key }
            .sorted()
    }

    /**
     * Get all entries (including metadata) under a prefix.
     */
    suspend fun listEntries(prefix: String = ""): List<MemoryEntryWithPath> {
        val now = currentTimeMs()
        return entries.entries
            .filter { it.key.startsWith(prefix) && !it.value.isExpired(now) }
            .map { (path, entry) ->
                MemoryEntryWithPath(path, entry)
            }
            .sortedBy { it.path }
    }

    /**
     * Check if a path has a valid (non-expired) entry.
     */
    suspend fun has(path: String): Boolean {
        val entry = entries[path] ?: return false
        return !entry.isExpired(currentTimeMs())
    }

    /**
     * Get all semantic tags currently in use.
     */
    fun tags(): Set<String> = semanticIndex.keys.toSet()

    /**
     * Count of non-expired entries.
     */
    suspend fun count(): Int {
        val now = currentTimeMs()
        return entries.values.count { !it.isExpired(now) }
    }

    /**
     * Clear all entries and the semantic index.
     */
    suspend fun clear() {
        entries.clear()
        semanticIndex.clear()
    }

    /**
     * Evict all expired entries.
     */
    suspend fun evictExpired() {
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
    fun export(): JsonObject {
        val now = currentTimeMs()
        return buildJsonObject {
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

    private fun removeFromIndex(path: String) {
        for ((_, paths) in semanticIndex) {
            paths.remove(path)
        }
        // Clean up empty tag entries
        semanticIndex.entries.removeAll { it.value.isEmpty() }
    }

    private fun currentTimeMs(): Long = System.currentTimeMillis()
}

/**
 * A single memory entry with value and metadata.
 */
@Serializable
data class MemoryEntry(
    val value: JsonElement,
    val createdAt: Long,
    val ttlMs: Long? = null,
    val tags: Set<String> = emptySet()
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
