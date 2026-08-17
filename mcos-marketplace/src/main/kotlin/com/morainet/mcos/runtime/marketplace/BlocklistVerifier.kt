package com.morainet.mcos.runtime.marketplace

import com.morainet.mcos.runtime.security.PublisherKey
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies the marketplace signature over a blocklist document
 * ([09-marketplace.md §14.3]).
 *
 * The blocklist is a high-value attack target: an attacker who can inject a
 * fake blocklist could disable legitimate plugins (DoS) or strip real entries
 * to re-enable revoked malware. Verification is therefore **fail-closed** —
 * any missing, undecodable, or invalid signature rejects the document and the
 * caller keeps its previously accepted blocklist.
 *
 * The signature is computed over the canonical document payload (all fields
 * except `signature`, serialized the same way the client parses it), so
 * verification does not depend on transport-level whitespace or field
 * ordering. Signing is a marketplace-server concern; clients only verify
 * against the marketplace's well-known public key bundled with the client.
 *
 * Supported algorithms mirror the artifact pipeline ([09-marketplace.md §6.2]):
 * Ed25519 (preferred) and RSA-PSS-4096 / SHA-256.
 */
class BlocklistVerifier(
    /** The marketplace's well-known public key, bundled with the client. */
    private val marketplaceKey: PublisherKey,
) {

    /**
     * @return `true` if [signature] is a valid base64 signature over [payload]
     *   made by [marketplaceKey]; `false` on any failure (never throws).
     */
    fun verify(payload: ByteArray, signature: String?): Boolean {
        if (signature.isNullOrBlank()) return false
        val publicKey = try {
            val der = Base64.getDecoder().decode(marketplaceKey.publicKeyEncoded)
            val factory = KeyFactory.getInstance(jcaName(marketplaceKey.algorithm))
            factory.generatePublic(X509EncodedKeySpec(der)) as PublicKey
        } catch (_: Exception) {
            return false
        }
        val signatureBytes = try {
            Base64.getDecoder().decode(signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val verifier = try {
            signatureAlgorithm()?.apply {
                initVerify(publicKey)
                update(payload)
            }
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            verifier.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun signatureAlgorithm(): Signature? = try {
        when {
            marketplaceKey.algorithm.equals("Ed25519", ignoreCase = true) ->
                Signature.getInstance("Ed25519")

            marketplaceKey.algorithm.equals("RSA-PSS-4096", ignoreCase = true) ->
                Signature.getInstance("RSASSA-PSS").apply {
                    setParameter(
                        PSSParameterSpec(
                            "SHA-256",
                            "MGF1",
                            MGF1ParameterSpec.SHA256,
                            32,
                            1,
                        ),
                    )
                }

            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun jcaName(algorithm: String): String =
        if (algorithm.equals("RSA-PSS-4096", ignoreCase = true)) "RSA" else algorithm
}
