package com.mcos.runtime.marketplace

import com.mcos.runtime.security.PublisherKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    private val blocklistVerifier: BlocklistVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val searchCacheTtlMs: Long = 24 * 60 * 60 * 1000,
    private val blocklistCacheTtlMs: Long = 60 * 60 * 1000,
) {

    private data class CacheEntry<T>(val value: T, val fetchedAtMs: Long)

    private val searchCache = ConcurrentHashMap<String, CacheEntry<SearchResponse>>()
    private val byCommandCache = ConcurrentHashMap<String, CacheEntry<List<PackageMetadata>>>()
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
        } catch (e: MarketplaceIndexException) {
            throw e
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
        val existing = cache[key]
        if (existing != null && now - existing.fetchedAtMs < ttlMs) return existing.value
        try {
            val value = fetch()
            cache[key] = CacheEntry(value, now)
            return value
        } catch (e: Exception) {
            // Stale-ok caches (blocklist) fall back to the previous entry when the
            // refresh fails — an expired blocklist is safer than none (§4.4).
            if (staleOk && existing != null) return existing.value
            throw e
        }
    }

    /**
     * Search the marketplace ([09-marketplace.md §11.1]).
     *
     * [sort] defaults to `relevance` (the server default), which is omitted
     * from the query string. [minRuntimeVersion] is a SemVer filter.
     *
     * @throws MarketplaceIndexException on failure.
     */
    suspend fun search(
        query: String? = null,
        category: String? = null,
        sort: SearchSort = SearchSort.relevance,
        minRuntimeVersion: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): SearchResponse {
        val params = buildList {
            query?.takeIf { it.isNotBlank() }?.let { add("query=${encode(it)}") }
            category?.takeIf { it.isNotBlank() }?.let { add("category=${encode(it)}") }
            if (sort != SearchSort.relevance) add("sort=${sort.name}")
            minRuntimeVersion?.takeIf { it.isNotBlank() }?.let { add("minRuntimeVersion=${encode(it)}") }
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
     * Find packages that provide [commandId] ([09-marketplace.md §11.1]
     * `GET /v1/plugins/by-command/{commandId}`) — the candidate source for
     * recommendations ([09-marketplace.md §9.2]).
     *
     * Returns an empty list when no package provides the command; that is a
     * common non-error case for recommendations.
     */
    suspend fun byCommand(commandId: String): List<PackageMetadata> {
        return cached(byCommandCache, commandId, searchCacheTtlMs, staleOk = false) {
            try {
                getTyped("/v1/plugins/by-command/${encode(commandId)}") { body ->
                    json.decodeFromString<List<PackageMetadata>>(body)
                }
            } catch (e: MarketplaceIndexException) {
                if (e.statusCode == 404) return@cached emptyList()
                throw e
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
     * The response is verified against the marketplace's well-known public key
     * ([09-marketplace.md §14.3]); an invalid signature is rejected and the
     * previously accepted blocklist is kept. Cache TTL is 1h and stale entries
     * are served if the refresh fails (§4.4).
     *
     * @throws MarketplaceIndexException with code `BLOCKLIST_SIGNATURE_INVALID`
     *   when no trusted blocklist is available.
     */
    suspend fun fetchBlocklist(): Blocklist {
        return cached(blocklistCache, "blocklist", blocklistCacheTtlMs, staleOk = true) {
            getTyped("/v1/blocklist") { body ->
                val blocklist = json.decodeFromString<Blocklist>(body)
                val payload = json.encodeToString(Blocklist.serializer(), blocklist.copy(signature = null))
                    .toByteArray(Charsets.UTF_8)
                if (!blocklistVerifier.verify(payload, blocklist.signature)) {
                    throw MarketplaceIndexException(
                        200,
                        "BLOCKLIST_SIGNATURE_INVALID",
                        "Blocklist signature verification failed; keeping previous blocklist",
                        false,
                    )
                }
                blocklist
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
