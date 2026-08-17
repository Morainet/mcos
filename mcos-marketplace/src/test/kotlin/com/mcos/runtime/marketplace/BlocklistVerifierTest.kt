package com.mcos.runtime.marketplace

import com.mcos.runtime.security.PublisherKey
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [BlocklistVerifier] — marketplace signature verification over
 * blocklist documents ([09-marketplace.md §14.3]).
 */
class BlocklistVerifierTest {

    private val edKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun edKey(algorithm: String = "Ed25519", keyPair: KeyPair = edKeyPair) = PublisherKey(
        keyId = "mcos_marketplace_root",
        publisherId = "mcos",
        publicKeyFingerprint = "00".repeat(32),
        algorithm = algorithm,
        publicKeyEncoded = Base64.getEncoder().encodeToString(keyPair.public.encoded),
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun signEd25519(payload: ByteArray, privateKey: PrivateKey): String {
        val s = Signature.getInstance("Ed25519")
        s.initSign(privateKey)
        s.update(payload)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    private val payload = """{"entries":[],"version":"v2","issuedAt":"2026-02-01T00:00:00Z"}"""
        .toByteArray(Charsets.UTF_8)

    @Test
    fun `V1-valid Ed25519 signature verifies`() {
        val verifier = BlocklistVerifier(edKey())

        assertTrue(verifier.verify(payload, signEd25519(payload, edKeyPair.private)))
    }

    @Test
    fun `V2-tampered payload rejects`() {
        val verifier = BlocklistVerifier(edKey())
        val signature = signEd25519(payload, edKeyPair.private)
        val tampered = """{"entries":[],"version":"v9","issuedAt":"2026-02-01T00:00:00Z"}"""
            .toByteArray(Charsets.UTF_8)

        assertFalse(verifier.verify(tampered, signature))
    }

    @Test
    fun `V3-signature by a different key rejects`() {
        val verifier = BlocklistVerifier(edKey())
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        assertFalse(verifier.verify(payload, signEd25519(payload, other.private)))
    }

    @Test
    fun `V4-null or blank signature rejects fail-closed`() {
        val verifier = BlocklistVerifier(edKey())

        assertFalse(verifier.verify(payload, null))
        assertFalse(verifier.verify(payload, ""))
        assertFalse(verifier.verify(payload, "   "))
    }

    @Test
    fun `V5-malformed signature rejects`() {
        val verifier = BlocklistVerifier(edKey())

        assertFalse(verifier.verify(payload, "not-base64!!!"))
    }

    @Test
    fun `V6-unknown key algorithm rejects`() {
        val verifier = BlocklistVerifier(edKey(algorithm = "RS-1024-WTF"))

        assertFalse(verifier.verify(payload, signEd25519(payload, edKeyPair.private)))
    }

    @Test
    fun `V7-RSA-PSS-4096 signature verifies`() {
        val rsaKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
        val verifier = BlocklistVerifier(edKey(algorithm = "RSA-PSS-4096", keyPair = rsaKeyPair))
        val s = Signature.getInstance("RSASSA-PSS").apply {
            setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        }
        s.initSign(rsaKeyPair.private)
        s.update(payload)
        val signature = Base64.getEncoder().encodeToString(s.sign())

        assertTrue(verifier.verify(payload, signature))
    }
}
