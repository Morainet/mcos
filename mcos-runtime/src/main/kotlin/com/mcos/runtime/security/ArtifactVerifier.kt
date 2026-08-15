package com.mcos.runtime.security

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Outcome of [ArtifactVerifier.verify].
 */
sealed class VerifyResult {
    /** Signature is valid; artifact is trusted. */
    data class Verified(
        val keyId: String,
        val algorithm: String,
        /** True if this came from the [VerificationCache] (offline load). */
        val fromCache: Boolean = false,
    ) : VerifyResult()

    /** Signature rejected for the given machine-readable reason. */
    data class Rejected(
        val reason: String,
        val keyId: String? = null,
    ) : VerifyResult()
}

/**
 * Client-side artifact signature verification, per [09-marketplace.md §6.2]
 * and aligned with the runtime verification cache ([03-runtime.md §16.2]).
 *
 * Verification pipeline:
 * 1. SHA-256 integrity check against the declared payload hash.
 * 2. Resolve the signing key from the [PublisherKeyStore].
 * 3. Key status check — REVOKED keys are rejected.
 * 4. Signature verification (Ed25519 preferred, RSA-PSS-4096 legacy).
 * 5. Blocklist check by (packageId, version).
 * 6. Cache the result keyed by (signingKeyId, payloadSha256).
 *
 * Failures are fail-closed: any step failure yields [VerifyResult.Rejected].
 */
class ArtifactVerifier(
    private val keyStore: PublisherKeyStore,
    private val cache: VerificationCache = VerificationCache(),
    private val blocklist: Blocklist = EmptyBlocklist,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Verify an artifact's signature.
     *
     * @param payload artifact bytes (the `.mcos` package body).
     * @param signature the signature envelope.
     * @param packageId optional package id used for blocklist checks.
     * @param version optional package version used for blocklist checks.
     */
    fun verify(
        payload: ByteArray,
        signature: ArtifactSignature,
        packageId: String? = null,
        version: String? = null,
    ): VerifyResult {
        // 1. SHA-256 integrity.
        val computedHash = sha256Hex(payload)
        if (!computedHash.equals(signature.payloadSha256, ignoreCase = true)) {
            return VerifyResult.Rejected(
                reason = "hash_mismatch",
                keyId = signature.signingKeyId,
            )
        }

        // 2. Resolve signing key.
        val key = keyStore.get(signature.signingKeyId)
        if (key == null) {
            return VerifyResult.Rejected(reason = "unknown_key", keyId = signature.signingKeyId)
        }
        if (!key.algorithm.equals(signature.algorithm, ignoreCase = true)) {
            return VerifyResult.Rejected(
                reason = "algorithm_mismatch",
                keyId = signature.signingKeyId,
            )
        }

        // 3. Key status check.
        if (key.status != KeyStatus.ACTIVE) {
            return VerifyResult.Rejected(reason = "key_revoked", keyId = signature.signingKeyId)
        }

        // 4. Check the verification cache first (offline fast path).
        val cached = cache.get(signature.signingKeyId, computedHash)
        if (cached != null) {
            return if (cached.trusted) {
                VerifyResult.Verified(signature.signingKeyId, signature.algorithm, fromCache = true)
            } else {
                VerifyResult.Rejected(reason = "previously_rejected", keyId = signature.signingKeyId)
            }
        }

        // 5. Verify signature cryptographically.
        val publicKey = try {
            keyStore.publicKey(signature.signingKeyId)
        } catch (_: Exception) {
            null
        }
        if (publicKey == null) {
            return VerifyResult.Rejected(reason = "key_unavailable", keyId = signature.signingKeyId)
        }
        val valid = verifySignature(publicKey, signature.algorithm, payload, signature.signature)
        if (!valid) {
            cache.put(signature.signingKeyId, computedHash, VerifyCacheEntry(clock(), trusted = false))
            return VerifyResult.Rejected(reason = "signature_invalid", keyId = signature.signingKeyId)
        }

        // 6. Blocklist check.
        if (blocklist.isBlocklisted(packageId, version)) {
            cache.put(signature.signingKeyId, computedHash, VerifyCacheEntry(clock(), trusted = false))
            return VerifyResult.Rejected(reason = "blocklisted", keyId = signature.signingKeyId)
        }

        // 7. Cache the successful verification.
        cache.put(signature.signingKeyId, computedHash, VerifyCacheEntry(clock(), trusted = true))
        return VerifyResult.Verified(signature.signingKeyId, signature.algorithm)
    }

    /** Computes the hex-encoded SHA-256 of [data]. */
    private fun sha256Hex(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun verifySignature(
        publicKey: PublicKey,
        algorithm: String,
        payload: ByteArray,
        signatureBase64: String,
    ): Boolean {
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val verifier = when {
            algorithm.equals("Ed25519", ignoreCase = true) ->
                Signature.getInstance("Ed25519")
            algorithm.equals("RSA-PSS-4096", ignoreCase = true) ->
                Signature.getInstance("RSASSA-PSS").apply {
                    setParameter(
                        PSSParameterSpec(
                            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1
                        )
                    )
                }
            else -> return false
        }
        return try {
            verifier.initVerify(publicKey)
            verifier.update(payload)
            verifier.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Abstraction over the marketplace blocklist ([09-marketplace.md §14]).
 * Implementations may come from local cache or push notification.
 */
fun interface Blocklist {
    /** True if the (packageId, version) pair is revoked/unlisted. */
    fun isBlocklisted(packageId: String?, version: String?): Boolean
}

/** A blocklist that rejects nothing. */
val EmptyBlocklist: Blocklist = Blocklist { _, _ -> false }

/**
 * Source of [PublisherKey] metadata and decoded public keys.
 */
interface PublisherKeyStore {
    /** Returns key metadata, or null if [keyId] is unknown. */
    fun get(keyId: String): PublisherKey?

    /**
     * Returns the decoded public key for [keyId], or null if unavailable.
     * Throws if the key is present but cannot be decoded — callers may
     * choose to treat that as [VerifyResult.Rejected] "key_unavailable".
     */
    fun publicKey(keyId: String): PublicKey?
}

/** In-memory [PublisherKeyStore] for tests and single-process usage. */
class InMemoryPublisherKeyStore : PublisherKeyStore {
    private val keys = mutableMapOf<String, PublisherKey>()

    fun put(key: PublisherKey) {
        keys[key.keyId] = key
    }

    /**
     * Bootstrap the store with bundled public keys ([09-marketplace.md §6.3]
     * "initial trust"). Idempotent: existing keys are preserved, new keys
     * added.
     */
    fun bootstrap(bundled: List<PublisherKey>) {
        for (key in bundled) {
            keys.putIfAbsent(key.keyId, key)
        }
    }

    /**
     * Apply a revocation list pulled from `GET /v1/keys/revoked`
     * ([09-marketplace.md §6.3]). Keys present in the list are marked
     * [KeyStatus.REVOKED] (overwriting an ACTIVE entry); keys absent are
     * untouched so unrelated publishers keep working.
     */
    fun applyRevoked(revoked: List<PublisherKey>) {
        for (key in revoked) {
            keys[key.keyId] = key.copy(status = KeyStatus.REVOKED)
        }
    }

    override fun get(keyId: String): PublisherKey? = keys[keyId]

    override fun publicKey(keyId: String): PublicKey? {
        val key = keys[keyId] ?: return null
        val der = Base64.getDecoder().decode(key.publicKeyEncoded)
        val factory = KeyFactory.getInstance(jcaName(key.algorithm))
        return factory.generatePublic(X509EncodedKeySpec(der))
    }

    /**
     * Maps the protocol-level algorithm label ("RSA-PSS-4096") to the
     * corresponding JCA key-factory name ("RSA"). "Ed25519" is unchanged.
     */
    private fun jcaName(algorithm: String): String =
        if (algorithm.equals("RSA-PSS-4096", ignoreCase = true)) "RSA" else algorithm
}
