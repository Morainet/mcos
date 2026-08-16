package com.mcos.runtime.marketplace

import com.mcos.runtime.security.PublisherKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import kotlin.test.*

/**
 * Unit tests for [MarketplaceIndex] — caching client for the marketplace
 * index API ([09-marketplace.md §4.4, §11]).
 */
class MarketplaceIndexTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Well-known marketplace root key pair used to sign test blocklists. */
    private val blocklistKeyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val blocklistVerifier = BlocklistVerifier(marketplaceKey(blocklistKeyPair))

    private fun marketplaceKey(keyPair: KeyPair) = PublisherKey(
        keyId = "mcos_marketplace_root",
        publisherId = "mcos",
        publicKeyFingerprint = "00".repeat(32),
        algorithm = "Ed25519",
        publicKeyEncoded = Base64.getEncoder().encodeToString(keyPair.public.encoded),
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun signEd25519(payload: ByteArray, privateKey: PrivateKey): String {
        val s = Signature.getInstance("Ed25519")
        s.initSign(privateKey)
        s.update(payload)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    /**
     * Serializes [blocklist] the way the client rebuilds the payload (§14.3:
     * all fields except `signature`), signs those bytes, and embeds the
     * signature — mirroring what the marketplace server produces.
     */
    private fun signedBlocklistBody(blocklist: Blocklist, keyPair: KeyPair = blocklistKeyPair): String {
        val payload = json.encodeToString(Blocklist.serializer(), blocklist.copy(signature = null))
            .toByteArray(Charsets.UTF_8)
        return json.encodeToString(Blocklist.serializer(), blocklist.copy(signature = signEd25519(payload, keyPair.private)))
    }

    private fun samplePackage(packageId: String, version: String = "1.0.0") = PackageMetadata(
        packageId = packageId,
        name = packageId,
        version = version,
        minRuntimeVersion = "0.9.0",
        publisherId = "pub_1",
        publisherName = "Pub",
        summary = "s",
        artifact = ArtifactRef(
            url = "https://cdn.example.com/$packageId-$version.mcos",
            sha256 = "ab".repeat(32),
            signature = "sig",
            signingKeyId = "key_2026_01",
        ),
        publishedAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private open inner class FakeTransport : MarketplaceHttpTransport {
        val requestLog = mutableListOf<String>()
        var searchBody: String = """{"results":[],"total":0,"page":1,"pageSize":20,"cacheTtlSeconds":86400}"""
        var packageBody: String = ""
        var packageStatusCode: Int = 200
        var blocklistBody: String = signedBlocklistBody(
            Blocklist(emptyList(), "v1", "2026-01-01T00:00:00Z", null),
        )
        var revokedKeysBody: String = "[]"
        var blocklistRequestCount = 0
        var failAfterRequests = Int.MAX_VALUE
        var transportException: MarketplaceTransportException? = null

        override suspend fun getJson(
            url: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): MarketplaceHttpResponse {
            requestLog += url
            transportException?.let { throw it }
            return when {
                url.contains("/v1/plugins?") -> MarketplaceHttpResponse(200, searchBody)
                url.contains("/v1/plugins/") -> MarketplaceHttpResponse(packageStatusCode, packageBody)
                url.contains("/v1/blocklist") -> {
                    blocklistRequestCount++
                    if (blocklistRequestCount > failAfterRequests) {
                        throw MarketplaceTransportException("MARKETPLACE_TIMEOUT", "timeout", true)
                    }
                    MarketplaceHttpResponse(200, blocklistBody)
                }
                url.contains("/v1/keys/revoked") -> MarketplaceHttpResponse(200, revokedKeysBody)
                else -> error("unexpected url: $url")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // T1-T2: Search caching (24h hard TTL)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T1-search caches within TTL window`() {
        var now = 1_000_000L
        val transport = FakeTransport().apply {
            searchBody = json.encodeToString(
                SearchResponse.serializer(),
                SearchResponse(listOf(samplePackage("com.example.a")), 1, 1, 20),
            )
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier, clock = { now }, searchCacheTtlMs = 86_400_000)

        runBlocking {
            val first = index.search(query = "weather")
            val second = index.search(query = "weather")

            assertEquals(1, first.total)
            assertEquals(1, second.total)
            assertEquals(1, transport.requestLog.count { it.contains("/v1/plugins?") })
        }
    }

    @Test
    fun `T2-search refetches after TTL expires`() {
        var now = 1_000_000L
        val transport = FakeTransport().apply {
            searchBody = json.encodeToString(
                SearchResponse.serializer(),
                SearchResponse(listOf(samplePackage("com.example.a")), 1, 1, 20),
            )
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier, clock = { now }, searchCacheTtlMs = 86_400_000)

        runBlocking {
            index.search(query = "weather")
            now += 86_400_001 // one TTL + epsilon
            index.search(query = "weather")

            assertEquals(2, transport.requestLog.count { it.contains("/v1/plugins?") })
        }
    }

    @Test
    fun `T3-search different queries are cached independently`() {
        val transport = FakeTransport().apply {
            searchBody = json.encodeToString(
                SearchResponse.serializer(),
                SearchResponse(emptyList(), 0, 1, 20),
            )
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier)

        runBlocking {
            index.search(query = "weather")
            index.search(query = "files")

            assertEquals(2, transport.requestLog.count { it.contains("/v1/plugins?") })
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // T4-T5: Package details
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T4-getPackage returns null on 404`() {
        val transport = FakeTransport().apply {
            packageStatusCode = 404
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier)

        runBlocking {
            val pkg = index.getPackage("com.example.missing")

            assertNull(pkg)
        }
    }

    @Test
    fun `T5-getPackage caches within TTL window`() {
        var now = 1_000_000L
        val transport = FakeTransport().apply {
            packageBody = json.encodeToString(PackageMetadata.serializer(), samplePackage("com.example.a"))
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier, clock = { now }, searchCacheTtlMs = 86_400_000)

        runBlocking {
            index.getPackage("com.example.a")
            index.getPackage("com.example.a")

            assertEquals(1, transport.requestLog.count { it.contains("/v1/plugins/com.example.a") })
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // T6-T7: Blocklist caching (1h, stale tolerated)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T6-blocklist is cached within 1h TTL`() {
        var now = 1_000_000L
        val transport = FakeTransport()
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier, clock = { now }, blocklistCacheTtlMs = 3_600_000)

        runBlocking {
            index.fetchBlocklist()
            index.fetchBlocklist()

            assertEquals(1, transport.requestLog.count { it.contains("/v1/blocklist") })
        }
    }

    @Test
    fun `T7-expired blocklist serves stale entry when refresh fails`() {
        var now = 1_000_000L
        val transport = FakeTransport().apply { failAfterRequests = 1 }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier, clock = { now }, blocklistCacheTtlMs = 3_600_000)

        runBlocking {
            val first = index.fetchBlocklist()
            now += 3_600_001 // expire
            val stale = index.fetchBlocklist() // refresh fails → stale served

            assertEquals(first, stale)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // T8-T9: Revoked keys
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T8-revoked keys are fetched and cached indefinitely`() {
        var now = 1_000_000L
        val key = PublisherKey(
            keyId = "key_2026_01",
            publisherId = "pub_1",
            publicKeyFingerprint = "ff".repeat(32),
            algorithm = "Ed25519",
            publicKeyEncoded = "base64",
            createdAt = "2026-01-01T00:00:00Z",
        )
        val transport = FakeTransport().apply {
            revokedKeysBody = json.encodeToString(listOf(key))
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier, clock = { now })

        runBlocking {
            val first = index.fetchRevokedKeys()
            now += 1_000_000_000L // way past any TTL
            val second = index.fetchRevokedKeys()

            assertEquals(1, first.size)
            assertEquals(1, second.size)
            assertEquals(1, transport.requestLog.count { it.contains("/v1/keys/revoked") })
        }
    }

    @Test
    fun `T9-refreshRevokedKeys forces a refetch`() {
        val transport = FakeTransport()
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier)

        runBlocking {
            index.fetchRevokedKeys()
            index.refreshRevokedKeys()

            assertEquals(2, transport.requestLog.count { it.contains("/v1/keys/revoked") })
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // T10-T11: Error mapping
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T10-429 maps to rate limited error`() {
        val transport = object : FakeTransport() {
            override suspend fun getJson(url: String, connectTimeoutMs: Long, requestTimeoutMs: Long): MarketplaceHttpResponse {
                requestLog += url
                return MarketplaceHttpResponse(429, """{"error":"slow down"}""")
            }
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier)

        runBlocking {
            val error = assertFailsWith<MarketplaceIndexException> { index.search() }
            assertEquals(429, error.statusCode)
            assertEquals("RATE_LIMITED", error.code)
            assertTrue(error.retryable)
        }
    }

    @Test
    fun `T11-transport exception maps to typed index exception`() {
        val transport = FakeTransport().apply {
            transportException = MarketplaceTransportException("MARKETPLACE_TIMEOUT", "boom", true)
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier)

        runBlocking {
            val error = assertFailsWith<MarketplaceIndexException> { index.search() }
            assertEquals(0, error.statusCode)
            assertEquals("MARKETPLACE_TIMEOUT", error.code)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // T12-T14: Blocklist signature verification (§14.3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T12-blocklist with valid signature replaces cache`() {
        var now = 1_000_000L
        val v1 = Blocklist(emptyList(), "v1", "2026-01-01T00:00:00Z", null)
        val v2 = Blocklist(
            listOf(
                BlocklistEntry(
                    packageId = "com.example.mal",
                    versionRange = "*",
                    reason = BlocklistReason.SECURITY_VULNERABILITY,
                    blockedAt = "2026-02-01T00:00:00Z",
                ),
            ),
            "v2",
            "2026-02-01T00:00:00Z",
            null,
        )
        val transport = FakeTransport().apply { blocklistBody = signedBlocklistBody(v1) }
        val index = MarketplaceIndex(
            "https://market.example",
            transport,
            json,
            blocklistVerifier = blocklistVerifier,
            clock = { now },
            blocklistCacheTtlMs = 3_600_000,
        )

        runBlocking {
            val first = index.fetchBlocklist()
            assertEquals("v1", first.version)

            now += 3_600_001 // expire
            transport.blocklistBody = signedBlocklistBody(v2)
            val second = index.fetchBlocklist()

            assertEquals("v2", second.version) // signed refresh replaces cache
            assertEquals(2, transport.blocklistRequestCount)
        }
    }

    @Test
    fun `T13-tampered blocklist keeps previous cache`() {
        var now = 1_000_000L
        val v1 = Blocklist(emptyList(), "v1", "2026-01-01T00:00:00Z", null)
        val forged = Blocklist(emptyList(), "v2", "2026-02-01T00:00:00Z", null)
        val otherKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val transport = FakeTransport().apply { blocklistBody = signedBlocklistBody(v1) }
        val index = MarketplaceIndex(
            "https://market.example",
            transport,
            json,
            blocklistVerifier = blocklistVerifier,
            clock = { now },
            blocklistCacheTtlMs = 3_600_000,
        )

        runBlocking {
            val first = index.fetchBlocklist()
            assertEquals("v1", first.version)

            now += 3_600_001 // expire
            transport.blocklistBody = signedBlocklistBody(forged, otherKeyPair) // wrong key → invalid signature
            val stale = index.fetchBlocklist()

            assertEquals("v1", stale.version) // previous cache kept, no update
            assertEquals(2, transport.blocklistRequestCount)

            now += 3_600_001 // still expired: refresh attempted again, still rejected
            val stillStale = index.fetchBlocklist()
            assertEquals("v1", stillStale.version)
            assertEquals(3, transport.blocklistRequestCount)
        }
    }

    @Test
    fun `T14-unsigned blocklist without cache raises BLOCKLIST_SIGNATURE_INVALID`() {
        val transport = FakeTransport().apply {
            blocklistBody = """{"entries":[],"version":"v1","issuedAt":"2026-01-01T00:00:00Z","signature":null}"""
        }
        val index = MarketplaceIndex("https://market.example", transport, json, blocklistVerifier = blocklistVerifier)

        runBlocking {
            val error = assertFailsWith<MarketplaceIndexException> { index.fetchBlocklist() }
            assertEquals("BLOCKLIST_SIGNATURE_INVALID", error.code)
            assertEquals(false, error.retryable)
        }
    }

}
