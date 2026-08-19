package com.morainet.mcos.security

import kotlinx.serialization.Serializable

/**
 * Publisher key metadata as defined in [09-marketplace.md §6.0].
 *
 * The public key itself is stored (encoded) alongside the metadata so the
 * runtime can verify artifact signatures without contacting the marketplace
 * for every install (cached per [03-runtime.md §16.2]).
 */
@Serializable
data class PublisherKey(
    /** Stable unique key id, e.g. "key_2026_01" — unique per publisher. */
    val keyId: String,
    /** Marketplace publisher id. */
    val publisherId: String,
    /** SHA-256 of the public key (hex). */
    val publicKeyFingerprint: String,
    /** "Ed25519" (preferred) or "RSA-PSS-4096" (legacy). */
    val algorithm: String,
    /** X.509 SubjectPublicKeyInfo, base64-encoded. */
    val publicKeyEncoded: String,
    /** ISO-8601 creation timestamp. */
    val createdAt: String,
    /** Previous keyId this replaced (audit chain), if any. */
    val rotatedFrom: String? = null,
    val status: KeyStatus = KeyStatus.ACTIVE,
)

/** Key lifecycle status ([09-marketplace.md §6.3]). */
enum class KeyStatus { ACTIVE, REVOKED }
