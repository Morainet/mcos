package com.morainet.mcos.marketplace

import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PublisherKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import kotlin.test.*

/**
 * Unit tests for [RecipeSignatureVerifier] — marketplace signature over the
 * recipe envelope, verified before compile ([05-workflow.md §14.1] constraint
 * 3, [09-marketplace.md §8.3] step 5).
 */
class RecipeSignatureVerifierTest {

    private fun keyPair(algorithm: String): KeyPair =
        KeyPairGenerator.getInstance(algorithm).apply { if (algorithm == "RSA") initialize(2048) }.generateKeyPair()

    private fun pubKey(
        pair: KeyPair,
        algorithm: String = "Ed25519",
        keyId: String = "key_marketplace_1",
        status: KeyStatus = KeyStatus.ACTIVE,
    ): PublisherKey = PublisherKey(
        keyId = keyId,
        publisherId = "pub_marketplace",
        publicKeyFingerprint = "ff".repeat(32),
        algorithm = algorithm,
        publicKeyEncoded = Base64.getEncoder().encodeToString(pair.public.encoded),
        createdAt = "2026-01-01T00:00:00Z",
        status = status,
    )

    private fun jca(algorithm: String): String =
        if (algorithm.equals("RSA-PSS-4096", ignoreCase = true)) "RSASSA-PSS" else algorithm

    private fun sign(pair: KeyPair, algorithm: String, payload: ByteArray): String {
        val signer = Signature.getInstance(jca(algorithm)).apply {
            if (algorithm.equals("RSA-PSS-4096", ignoreCase = true)) {
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
        }
        signer.initSign(pair.private)
        signer.update(payload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun signedEnvelope(
        pair: KeyPair,
        algorithm: String = "Ed25519",
        keyId: String = "key_marketplace_1",
        signatureOverride: String? = null,
    ): RecipeEnvelope {
        val base = RecipeEnvelope(
            recipeId = "com.example.photo.compress",
            name = "Photo Compressor",
            version = "1.2.0",
            workflow = Json.parseToJsonElement("""{"step":{"command":"photo.compress"}}""").jsonObject,
        )
        val sig = signatureOverride ?: sign(pair, algorithm, base.canonicalPayload())
        return base.copy(
            signature = RecipeEnvelopeSignature(
                signingKeyId = keyId,
                algorithm = algorithm,
                signedAt = "2026-02-01T00:00:00Z",
                signature = sig,
            ),
        )
    }

    @Test
    fun `V1-valid Ed25519 signature verifies`() {
        val pair = keyPair("Ed25519")
        val verifier = RecipeSignatureVerifier(pubKey(pair))

        val result = verifier.verify(signedEnvelope(pair))

        assertEquals(RecipeSignatureResult.Verified, result)
    }

    @Test
    fun `V2-valid RSA-PSS-4096 signature verifies`() {
        val pair = keyPair("RSA")
        val verifier = RecipeSignatureVerifier(pubKey(pair, algorithm = "RSA-PSS-4096"))

        val result = verifier.verify(signedEnvelope(pair, algorithm = "RSA-PSS-4096"))

        assertEquals(RecipeSignatureResult.Verified, result)
    }

    @Test
    fun `V3-missing signature fails closed`() {
        val pair = keyPair("Ed25519")
        val verifier = RecipeSignatureVerifier(pubKey(pair))
        val unsigned = RecipeEnvelope(
            recipeId = "com.example.photo.compress",
            name = "Photo Compressor",
            version = "1.2.0",
            workflow = Json.parseToJsonElement("""{"step":{"command":"photo.compress"}}""").jsonObject,
        )

        val result = verifier.verify(unsigned)

        val rejected = assertIs<RecipeSignatureResult.Rejected>(result)
        assertEquals("missing_signature", rejected.reason)
    }

    @Test
    fun `V4-tampered workflow payload is rejected`() {
        val pair = keyPair("Ed25519")
        val verifier = RecipeSignatureVerifier(pubKey(pair))
        val tampered = signedEnvelope(pair).copy(
            workflow = Json.parseToJsonElement("""{"step":{"command":"malicious.command"}}""").jsonObject,
        )

        val result = verifier.verify(tampered)

        val rejected = assertIs<RecipeSignatureResult.Rejected>(result)
        assertEquals("invalid_signature", rejected.reason)
    }

    @Test
    fun `V5-tampered metadata is rejected`() {
        val pair = keyPair("Ed25519")
        val verifier = RecipeSignatureVerifier(pubKey(pair))
        val tampered = signedEnvelope(pair).copy(name = "Renamed")

        val result = verifier.verify(tampered)

        assertIs<RecipeSignatureResult.Rejected>(result)
    }

    @Test
    fun `V6-unknown signing key id is rejected`() {
        val pair = keyPair("Ed25519")
        val verifier = RecipeSignatureVerifier(pubKey(pair, keyId = "key_expected_1"))

        val result = verifier.verify(signedEnvelope(pair, keyId = "key_other_1"))

        val rejected = assertIs<RecipeSignatureResult.Rejected>(result)
        assertEquals("unknown_signing_key", rejected.reason)
    }

    @Test
    fun `V7-algorithm mismatch is rejected`() {
        val pair = keyPair("Ed25519")
        val verifier = RecipeSignatureVerifier(pubKey(pair, algorithm = "Ed25519"))
        val envelope = signedEnvelope(pair).copy(
            signature = signedEnvelope(pair).signature!!.copy(algorithm = "RSA-PSS-4096"),
        )

        val result = verifier.verify(envelope)

        val rejected = assertIs<RecipeSignatureResult.Rejected>(result)
        assertEquals("algorithm_mismatch", rejected.reason)
    }

    @Test
    fun `V8-revoked signing key is rejected`() {
        val pair = keyPair("Ed25519")
        val verifier = RecipeSignatureVerifier(pubKey(pair, status = KeyStatus.REVOKED))

        val result = verifier.verify(signedEnvelope(pair))

        val rejected = assertIs<RecipeSignatureResult.Rejected>(result)
        assertEquals("signing_key_revoked", rejected.reason)
    }

    @Test
    fun `V9-base64 garbage signature is rejected`() {
        val pair = keyPair("Ed25519")
        val verifier = RecipeSignatureVerifier(pubKey(pair))

        val result = verifier.verify(signedEnvelope(pair, signatureOverride = "not-base64!!"))

        val rejected = assertIs<RecipeSignatureResult.Rejected>(result)
        assertEquals("invalid_signature", rejected.reason)
    }

    @Test
    fun `V10-canonical payload is stable across identical envelopes`() {
        val a = RecipeEnvelope(
            recipeId = "r",
            name = "n",
            version = "1.0.0",
            workflow = Json.parseToJsonElement("""{"a":1,"b":{"c":2}}""").jsonObject,
        )
        val b = RecipeEnvelope(
            recipeId = "r",
            name = "n",
            version = "1.0.0",
            workflow = Json.parseToJsonElement("""{"a":1,"b":{"c":2}}""").jsonObject,
        )

        assertContentEquals(a.canonicalPayload(), b.canonicalPayload())
        assertContentEquals(a.canonicalPayload(), a.copy(signature = RecipeEnvelopeSignature(
            signingKeyId = "k",
            algorithm = "Ed25519",
            signedAt = "2026-01-01T00:00:00Z",
            signature = "sig",
        )).canonicalPayload(), "signature field must be excluded from the payload")
    }
}
