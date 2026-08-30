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
 * NOTE (scaffold): the key material below is a **structurally valid
 * placeholder** — a real Ed25519 public key whose private half is NOT held by
 * any live server (there is no marketplace operator yet). It makes the trust
 * pipeline concrete and fail-closed (real blocklist/artifact signatures simply
 * will not verify against it) instead of relying on an empty-string key. At
 * release, replace [MARKETPLACE_PUBLIC_KEY_BASE64] with the operator's
 * published well-known key and add curated publisher anchors to [bundled].
 */
object TrustAnchors {

    /**
     * X.509 SubjectPublicKeyInfo (base64) of the marketplace's well-known
     * Ed25519 signing key. Replace with the operator's real key at release.
     */
    private const val MARKETPLACE_PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEA0C031h4QRcB7wrqTKGoyHP2syi7XQGhm4V0TG/SNdjU="

    /** SHA-256 (hex) of the DER public key above. */
    private const val MARKETPLACE_FINGERPRINT =
        "e240f31a64691fce6bf20bea9b0a2bb72e192657960d34f1811d9104ba167982"

    /** The marketplace's well-known key, used to verify the signed blocklist. */
    val marketplaceKey: PublisherKey = PublisherKey(
        keyId = "mcos_marketplace_wellknown_2026",
        publisherId = "marketplace",
        publicKeyFingerprint = MARKETPLACE_FINGERPRINT,
        algorithm = "Ed25519",
        publicKeyEncoded = MARKETPLACE_PUBLIC_KEY_BASE64,
        createdAt = "2026-01-01T00:00:00Z",
    )

    /**
     * Trust anchors seeded into the publisher key store at cold start. Curated
     * first-party publisher keys would be added here; for now it carries the
     * marketplace well-known key so the store is never empty.
     */
    fun bundled(): List<PublisherKey> = listOf(marketplaceKey)
}
