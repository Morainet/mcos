package com.morainet.mcos.android

import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.marketplace.MarketplaceHttpTransport
import com.morainet.mcos.marketplace.MarketplaceHttpResponse
import com.morainet.mcos.security.InMemoryPublisherKeyStore
import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PublisherKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * MarketplaceTrust (item 40): the host pulls the operator's revoked-key list
 * and marks matching keys REVOKED so installs and restart re-verification
 * reject artifacts signed by a revoked key (09 §6.3). The refresh is
 * best-effort — a failing fetch returns null and never undoes an
 * already-applied revocation. Extracted from the demo ViewModel.
 */
class MarketplaceTrustTest {

    /** Serves a fixed body for every GET; null body = endpoint down. */
    private class FakeTransport(private val body: String?) : MarketplaceHttpTransport {
        var calls = 0
            private set

        override suspend fun getJson(
            url: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): MarketplaceHttpResponse {
            calls++
            return body?.let { MarketplaceHttpResponse(statusCode = 200, body = it) }
                ?: throw IOException("marketplace unreachable")
        }
    }

    private fun key(keyId: String, status: KeyStatus = KeyStatus.ACTIVE) = PublisherKey(
        keyId = keyId,
        publisherId = "pub_1",
        publicKeyFingerprint = "ff".repeat(32),
        algorithm = "Ed25519",
        // Never parsed on this path (no artifact verification here) — any valid
        // base64 SPKI stands in for the marketplace well-known key.
        publicKeyEncoded = "MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w2l3WF5mXXpB44BvOoHUYhGQvl",
        createdAt = "2026-01-01T00:00:00Z",
        status = status,
    )

    private fun verifier() = BlocklistVerifier(key("marketplace"))

    @Test
    fun refreshAppliesRevokedKeys() = runTest {
        val revoked = key("key_2026_01")
        val store = InMemoryPublisherKeyStore().apply { put(key("key_2026_01")) }
        val body = Json.encodeToString(ListSerializer(PublisherKey.serializer()), listOf(revoked))
        val transport = FakeTransport(body)

        val applied = MarketplaceTrust.refreshRevokedKeys(
            baseUrl = "https://mp.example.com",
            transport = transport,
            blocklistVerifier = verifier(),
            keyStore = store,
        )

        assertEquals(1, applied)
        assertEquals(1, transport.calls)
        assertEquals(KeyStatus.REVOKED, store.get("key_2026_01")?.status)
    }

    @Test
    fun refreshFailureIsStaleOkAndReturnsNull() = runTest {
        val store = InMemoryPublisherKeyStore().apply { put(key("key_2026_01", status = KeyStatus.REVOKED)) }
        val transport = FakeTransport(body = null)

        val applied = MarketplaceTrust.refreshRevokedKeys(
            baseUrl = "https://mp.example.com",
            transport = transport,
            blocklistVerifier = verifier(),
            keyStore = store,
        )

        assertNull(applied)
        // An already-applied revocation must survive a failed refresh.
        assertEquals(KeyStatus.REVOKED, store.get("key_2026_01")?.status)
    }

    @Test
    fun emptyRevocationListAppliesNothing() = runTest {
        val store = InMemoryPublisherKeyStore().apply { put(key("key_2026_01")) }
        val transport = FakeTransport(body = "[]")

        val applied = MarketplaceTrust.refreshRevokedKeys(
            baseUrl = "https://mp.example.com",
            transport = transport,
            blocklistVerifier = verifier(),
            keyStore = store,
        )

        assertEquals(0, applied)
        assertEquals(KeyStatus.ACTIVE, store.get("key_2026_01")?.status)
    }
}
