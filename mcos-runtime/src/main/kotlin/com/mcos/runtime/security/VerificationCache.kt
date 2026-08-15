package com.mcos.runtime.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Result of a verification recorded in the [VerificationCache].
 *
 * @param verifiedAt epoch millis when verification succeeded.
 * @param trusted whether the artifact was verified as trusted.
 */
data class VerifyCacheEntry(
    val verifiedAt: Long,
    val trusted: Boolean,
)

/**
 * Signature-verification cache per [03-runtime.md §16.2].
 *
 * Keyed by `(signingKeyId, payloadSha256)` so a previously verified artifact
 * can be loaded offline without re-verifying against the marketplace. Entries
 * expire after [ttlMillis] (default 7 days, aligned with the marketplace
 * revocation TTL from [09-marketplace.md §6.3]).
 *
 * Thread safety: backed by [ConcurrentHashMap]; read/write are atomic and
 * lazy expiry is handled at read time.
 */
class VerificationCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = ConcurrentHashMap<CacheKey, VerifyCacheEntry>()

    private data class CacheKey(val keyId: String, val payloadSha256: String)

    /**
     * Returns the cached entry if present and not yet expired, else null.
     * Expired entries are evicted lazily.
     */
    fun get(keyId: String, payloadSha256: String): VerifyCacheEntry? {
        val key = CacheKey(keyId, payloadSha256)
        val entry = store[key] ?: return null
        if (clock() - entry.verifiedAt > ttlMillis) {
            store.remove(key, entry)
            return null
        }
        return entry
    }

    /** Stores or refreshes a verification result. */
    fun put(keyId: String, payloadSha256: String, entry: VerifyCacheEntry) {
        store[CacheKey(keyId, payloadSha256)] = entry
    }

    /** Clears all cached entries. */
    fun clear() = store.clear()

    /** Current number of cached entries (for tests/diagnostics). */
    fun size(): Int = store.size

    companion object {
        /** 7 days, aligned with the marketplace revocation TTL (09 §6.3). */
        const val DEFAULT_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
