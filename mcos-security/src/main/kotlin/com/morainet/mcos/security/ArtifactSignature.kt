package com.morainet.mcos.security

import kotlinx.serialization.Serializable

/**
 * Signature envelope attached to a plugin artifact (`.mcos` package),
 * as defined in [09-marketplace.md §4.0] `ArtifactRef` and [09-marketplace.md §6.2].
 *
 * @param payloadSha256 hex-encoded SHA-256 of the artifact bytes (integrity).
 * @param signature base64-encoded publisher signature over the artifact bytes.
 * @param signingKeyId which [PublisherKey] signed this artifact.
 * @param algorithm signing algorithm: "Ed25519" (preferred) or "RSA-PSS-4096".
 * @param signedAt ISO-8601 timestamp when the artifact was signed.
 */
@Serializable
data class ArtifactSignature(
    val payloadSha256: String,
    val signature: String,
    val signingKeyId: String,
    val algorithm: String,
    val signedAt: String,
)
