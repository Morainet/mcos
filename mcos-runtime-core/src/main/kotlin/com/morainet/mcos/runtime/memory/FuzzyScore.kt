package com.morainet.mcos.runtime.memory

/**
 * Local character-level similarity in `[0..1]`, used as a stand-in for
 * embedding similarity until the embedding provider lands ([07-memory.md
 * 6.0 Step 3]). Exact match → 1.0; containment (alias/abbreviation like
 * "客厅灯" vs "客厅的灯") → 0.85; otherwise Dice coefficient over
 * character bigrams.
 *
 * Shared by [MemoryStore] reference resolution and [EpisodicMemory] search.
 */
internal fun fuzzyScore(query: String, candidate: String): Float {
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
