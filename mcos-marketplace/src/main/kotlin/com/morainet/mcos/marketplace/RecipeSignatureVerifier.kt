package com.morainet.mcos.marketplace

import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PublisherKey

/**
 * Result of [RecipeSignatureVerifier.verify].
 */
sealed interface RecipeSignatureResult {
    /** Signature is present, well-formed, and valid under the marketplace key. */
    data object Verified : RecipeSignatureResult

    /** Signature is missing, malformed, or invalid — caller must fail closed. */
    data class Rejected(val reason: String) : RecipeSignatureResult
}

/**
 * Verifies the marketplace signature over a recipe envelope before the
 * Runtime compiles it ([05-workflow.md §14.1] constraint 3, [09-marketplace.md
 * §8.3] step 5).
 *
 * Recipes are high-value attack targets: a forged envelope could inject
 * arbitrary workflow IR (command calls, secret access, data exfiltration)
 * into a trusted plugin context. Verification is therefore **fail-closed** —
 * a missing, undecodable, or invalid signature rejects the envelope and the
 * caller must not compile it.
 *
 * The signature is computed over [RecipeEnvelope.canonicalPayload] (all
 * fields except `signature`, serialized the way the client parses it), so
 * verification does not depend on transport-level whitespace or field
 * ordering. Signing is a marketplace-server concern; clients only verify
 * against the marketplace's well-known public key bundled with the client.
 *
 * Supported algorithms mirror the artifact and blocklist pipelines
 * ([09-marketplace.md §6.2]/[§14.3]): Ed25519 (preferred) and RSA-PSS-4096 /
 * SHA-256.
 */
class RecipeSignatureVerifier(
    /** The marketplace's well-known public key, bundled with the client. */
    private val marketplaceKey: PublisherKey,
) {

    /**
     * Verify [recipe.signature] over the recipe's canonical payload.
     *
     * Fail-closed: returns [RecipeSignatureResult.Rejected] for a missing,
     * undecodable, unknown-key, algorithm-mismatched, or invalid signature.
     * Never throws.
     */
    fun verify(recipe: RecipeEnvelope): RecipeSignatureResult {
        val sig = recipe.signature ?: return RecipeSignatureResult.Rejected("missing_signature")
        if (sig.signingKeyId != marketplaceKey.keyId) {
            return RecipeSignatureResult.Rejected("unknown_signing_key")
        }
        if (!sig.algorithm.equals(marketplaceKey.algorithm, ignoreCase = true)) {
            return RecipeSignatureResult.Rejected("algorithm_mismatch")
        }
        if (marketplaceKey.status == KeyStatus.REVOKED) {
            return RecipeSignatureResult.Rejected("signing_key_revoked")
        }
        val valid = BlocklistVerifier(marketplaceKey).verify(recipe.canonicalPayload(), sig.signature)
        return if (valid) {
            RecipeSignatureResult.Verified
        } else {
            RecipeSignatureResult.Rejected("invalid_signature")
        }
    }
}
