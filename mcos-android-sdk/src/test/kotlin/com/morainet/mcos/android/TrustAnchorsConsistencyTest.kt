package com.morainet.mcos.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Pins [TrustAnchors.marketplaceKey] to its own key material: the declared
 * fingerprint must be the SHA-256 of the declared public key, and that key
 * must parse as a real Ed25519 SPKI. The anchor is hand-pasted source, so a
 * transposed digit or stale fingerprint would otherwise compile fine and only
 * break signature verification at runtime — this fails it at build time
 * instead. The release workflow additionally refuses to ship the retired
 * scaffold key (see .github/workflows/release.yml).
 */
class TrustAnchorsConsistencyTest {

    @Test
    fun fingerprintMatchesSha256OfDeclaredPublicKey() {
        val der = Base64.getDecoder().decode(TrustAnchors.marketplaceKey.publicKeyEncoded)
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        val hex = digest.joinToString("") { "%02x".format(it) }
        assertEquals(
            "MARKETPLACE_FINGERPRINT must be SHA-256 of MARKETPLACE_PUBLIC_KEY_BASE64",
            TrustAnchors.marketplaceKey.publicKeyFingerprint,
            hex,
        )
    }

    @Test
    fun publicKeyParsesAsEd25519Spki() {
        val der = Base64.getDecoder().decode(TrustAnchors.marketplaceKey.publicKeyEncoded)
        // Throws InvalidKeySpecException on malformed material — the anchor is
        // never an empty or structurally invalid placeholder.
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        assertEquals("Ed25519", TrustAnchors.marketplaceKey.algorithm)
        assertArrayEquals(der, key.encoded)
    }
}
