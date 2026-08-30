package com.morainet.mcos.android

import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.marketplace.MarketplaceHttpTransport
import com.morainet.mcos.marketplace.MarketplaceIndex
import com.morainet.mcos.security.InMemoryPublisherKeyStore

/**
 * Marketplace trust maintenance for a host process (09 §6.3): pulls the
 * operator's revoked-key list and marks matching keys REVOKED, so installs
 * (and restart rehydration re-verification) reject artifacts signed by a
 * revoked key. Extracted from the demo shell's ViewModel (item 40); the
 * revoked list is process-cached by [MarketplaceIndex], so repeated calls
 * are cheap.
 */
object MarketplaceTrust {

    /**
     * Best-effort revocation refresh. Failures are swallowed — trust refresh
     * must not block browsing, and an already-applied revocation stays
     * applied (stale-ok, §6.3).
     *
     * @return the number of revoked keys applied (0 when the list is empty),
     *         or null when the refresh failed.
     */
    suspend fun refreshRevokedKeys(
        baseUrl: String,
        transport: MarketplaceHttpTransport,
        blocklistVerifier: BlocklistVerifier,
        keyStore: InMemoryPublisherKeyStore,
    ): Int? = try {
        val revoked = MarketplaceIndex(
            baseUrl = baseUrl,
            transport = transport,
            blocklistVerifier = blocklistVerifier,
        ).fetchRevokedKeys()
        if (revoked.isNotEmpty()) keyStore.applyRevoked(revoked)
        revoked.size
    } catch (_: Exception) {
        null
    }
}
