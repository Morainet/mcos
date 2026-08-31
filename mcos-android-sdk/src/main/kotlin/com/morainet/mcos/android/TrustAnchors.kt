package com.morainet.mcos.android

import com.morainet.mcos.security.PublisherKey

/**
 * Bundled trust roots for the marketplace client — the "initial trust"
 * ([09-marketplace.md §6.3]). These ship with the app so the runtime can
 * verify signatures (and the signed blocklist, [§14.3]) on a cold start,
 * before any install has pinned a publisher key.
 *
 * The key store is seeded from [bundled] at composition time
 * ([CompositionRoot]); [BlocklistVerifier][com.morainet.mcos.marketplace.BlocklistVerifier]
 * uses [marketplaceKey]. Bootstrap is idempotent, so install-pinned keys and a
 * later revoked-keys refresh ([MarketplaceViewModel.refreshKeyTrust]) both
 * layer on top without clobbering these anchors.
 *
 * [marketplaceKey] is the operator's real well-known key (generated
 * 2026-08-31; the private half is held offline by the operator and has never
 * been in this repository — the initial scaffold key was replaced before the
 * first release). [MARKETPLACE_FINGERPRINT] is pinned to the key material by
 * [TrustAnchorsConsistencyTest], so a paste error cannot silently ship a
 * mismatched pair. Curated first-party publisher anchors belong in [bundled]
 * as the marketplace onboards them.
 */
object TrustAnchors {

    /**
     * X.509 SubjectPublicKeyInfo (base64) of the marketplace's well-known
     * Ed25519 signing key.
     */
    private const val MARKETPLACE_PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEAOMYGP7TWtPI7MoSVekPAypVjM7JuntUclVGwLDqCBHQ="

    /** SHA-256 (hex) of the DER public key above. */
    private const val MARKETPLACE_FINGERPRINT =
        "3669aba4c6e24bb4c289586d125b4f1274d25f49c20fec976d9fbd6cd9095ec7"

    /** The marketplace's well-known key, used to verify the signed blocklist. */
    val marketplaceKey: PublisherKey = PublisherKey(
        keyId = "mcos_marketplace_wellknown_2026",
        publisherId = "marketplace",
        publicKeyFingerprint = MARKETPLACE_FINGERPRINT,
        algorithm = "Ed25519",
        publicKeyEncoded = MARKETPLACE_PUBLIC_KEY_BASE64,
        createdAt = "2026-08-31T00:00:00Z",
    )

    /**
     * Trust anchors seeded into the publisher key store at cold start. Curated
     * first-party publisher keys would be added here; for now it carries the
     * marketplace well-known key so the store is never empty.
     */
    fun bundled(): List<PublisherKey> = listOf(marketplaceKey)
}
