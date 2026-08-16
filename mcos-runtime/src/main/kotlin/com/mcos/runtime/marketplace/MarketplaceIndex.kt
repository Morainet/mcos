package com.mcos.runtime.marketplace

import com.mcos.runtime.security.PublisherKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed error from the marketplace client ([09-marketplace.md §11]).
 *
 * @param statusCode HTTP status when the failure came from the server; 0 for transport errors.
 * @param code stable machine-readable error code, e.g. `NOT_FOUND`, `RATE_LIMITED`, `MARKETPLACE_TIMEOUT`.
 */
class MarketplaceIndexException(
    val statusCode: Int,
    val code: String,
    override val message: String,
    val retryable: Boolean = true,
) : Exception(message)

/**
 * Caching client for the marketplace index API ([09-marketplace.md §4, §11]).
 *
 * Cache policy ([09-marketplace.md §4.4]):
 *  - search results / package details: 24h (hard TTL, no stale-on-error)
 *  - blocklist: 1h, stale tolerated (an expired blocklist is safer than none)
 *  - publisher key registry: cached indefinitely until a rotation event
 *    (the client re-fetches when a package arrives signed by an unknown key,
 *    and after pulling the revoked-keys list).
 *
 * The transport is injected so JVM and Android builds can provide their own
 * implementations ([MarketplaceHttpTransport]).
 */
class MarketplaceIndex(
    private val baseUrl: String,
    private val transport: MarketplaceHttpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val searchCacheTtlMs: Long = 24 * 60 * 60 * 1000,
    private val blocklistCacheTtlMs: Long = 60 * 60 * 1000,
) {

    private data class CacheEntry<T>(val value: T, val fetchedAtMs: Long)

    private val searchCache = ConcurrentHashMap<String, CacheEntry<SearchResponse>>()
    private val packageCache = ConcurrentHashMap<String, CacheEntry<PackageMetadata?>>()
    private val blocklistCache = ConcurrentHashMap<String, CacheEntry<Blocklist>>()
    private val revokedKeysCache = ConcurrentHashMap<String, CacheEntry<List<PublisherKey>>>()

    private fun base(path: String): String = baseUrl.trimEnd('/') + path

    private suspend fun <T> getTyped(path: String, decoder: (String) -> T): T {
        val response = try {
            transport.getJson(base(path), CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS)
        } catch (e: MarketplaceTransportException) {
            throw MarketplaceIndexException(0, e.code, e.message, e.retryable)
        } catch (e: java.net.ConnectException) {
            throw MarketplaceIndexException(0, "MARKETPLACE_UNREACHABLE", "Cannot reach marketplace: ${e.message}", true)
        } catch (e: java.io.IOException) {
            throw MarketplaceIndexException(0, "MARKETPLACE_IO", "Marketplace I/O error: ${e.message}", true)
        }

        if (response.statusCode == 404) {
            throw MarketplaceIndexException(404, "NOT_FOUND", "Marketplace resource not found", false)
        }
        if (response.statusCode == 429) {
            throw MarketplaceIndexException(429, "RATE_LIMITED", "Marketplace rate limit exceeded", true)
        }
        if (response.statusCode !in 200..299) {
            throw MarketplaceIndexException(
                response.statusCode,
                "HTTP_${response.statusCode}",
                "Marketplace returned HTTP ${response.statusCode}",
                response.statusCode >= 500,
            )
        }

        return try {
            decoder(response.body)
        } catch (e: Exception) {
            throw MarketplaceIndexException(200, "BAD_RESPONSE", "Marketplace response could not be parsed", false)
        }
    }

    private suspend fun <T> cached(
        cache: ConcurrentHashMap<String, CacheEntry<T>>,
        key: String,
        ttlMs: Long,
        staleOk: Boolean,
        fetch: suspend () -> T,
    ): T {
        val now = clock()
        cache[key]?.let { entry ->
            if (now - entry.fetchedAtMs < ttlMs) return entry.value
            if (staleOk) return entry.value
        }
        val value = fetch()
        cache[key] = CacheEntry(value, now)
        return value
    }

    /**
     * Search the marketplace ([09-marketplace.md §11.1]).
     *
     * @throws MarketplaceIndexException on failure.
     */
    suspend fun search(
        query: String? = null,
        category: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): SearchResponse {
        val params = buildList {
            query?.takeIf { it.isNotBlank() }?.let { add("query=${encode(it)}") }
            category?.takeIf { it.isNotBlank() }?.let { add("category=${encode(it)}") }
            add("page=$page")
            add("pageSize=$pageSize")
        }.joinToString("&")

        val key = "$params"
        return cached(searchCache, key, searchCacheTtlMs, staleOk = false) {
            getTyped("/v1/plugins?$params") { body ->
                json.decodeFromString<SearchResponse>(body)
            }
        }
    }

    /**
     * Fetch metadata for a package ([09-marketplace.md §11.2]).
     *
     * @return null when the package does not exist.
     */
    suspend fun getPackage(packageId: String): PackageMetadata? {
        val key = packageId
        return cached<PackageMetadata?>(packageCache, key, searchCacheTtlMs, staleOk = false) {
            try {
                getTyped("/v1/plugins/${encode(packageId)}") { body ->
                    json.decodeFromString<PackageMetadata>(body)
                }
            } catch (e: MarketplaceIndexException) {
                if (e.statusCode == 404) return@cached null
                throw e
            }
        }
    }

    /**
     * Fetch the signed blocklist ([09-marketplace.md §11.4, §14.0]).
     *
     * Cache TTL is 1h and stale entries are served if the refresh fails.
     */
    suspend fun fetchBlocklist(): Blocklist {
        return cached(blocklistCache, "blocklist", blocklistCacheTtlMs, staleOk = true) {
            getTyped("/v1/blocklist") { body ->
                json.decodeFromString<Blocklist>(body)
            }
        }
    }

    /**
     * Fetch the list of revoked publisher keys ([09-marketplace.md §6.3, §11.3]).
     *
     * Cached indefinitely; callers re-fetch when a signature arrives from an
     * unknown key and after applying a rotation.
     */
    suspend fun fetchRevokedKeys(): List<PublisherKey> {
        return cached(revokedKeysCache, "revoked", Long.MAX_VALUE, staleOk = false) {
            getTyped("/v1/keys/revoked") { body ->
                json.decodeFromString<List<PublisherKey>>(body)
            }
        }
    }

    /** Force a re-fetch of the revoked-keys list (used after a rotation event). */
    suspend fun refreshRevokedKeys(): List<PublisherKey> {
        val keys = getTyped("/v1/keys/revoked") { body ->
            json.decodeFromString<List<PublisherKey>>(body)
        }
        revokedKeysCache["revoked"] = CacheEntry(keys, clock())
        return keys
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
