package com.morainet.mcos.runtime.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import kotlin.test.*

/**
 * Unit tests for [ArtifactVerifier] — the client-side signature verification
 * pipeline of [09-marketplace.md §6.2] with the runtime cache of
 * [03-runtime.md §16.2].
 */
class ArtifactVerifierTest {

    // ═══════════════════════════════════════════════════════════════
    // Helpers — real Ed25519 / RSA-PSS keypairs
    // ═══════════════════════════════════════════════════════════════

    private fun genKeyPair(algorithm: String): KeyPair {
        // "RSA-PSS-4096" is our protocol-level label; the JCA generator uses "RSA".
        val jcaName = if (algorithm == "RSA-PSS-4096") "RSA" else algorithm
        val kpg = KeyPairGenerator.getInstance(jcaName)
        if (algorithm == "RSA-PSS-4096") {
            kpg.initialize(4096)
        }
        return kpg.generateKeyPair()
    }

    private fun encodePublic(pk: PublicKey): String =
        Base64.getEncoder().encodeToString(pk.encoded)

    private fun fingerprint(pk: PublicKey): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(pk.encoded).joinToString("") { "%02x".format(it) }
    }

    private fun signEd25519(payload: ByteArray, privateKey: PrivateKey): String {
        val s = Signature.getInstance("Ed25519")
        s.initSign(privateKey)
        s.update(payload)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    private fun signRsaPss(payload: ByteArray, privateKey: PrivateKey): String {
        val s = Signature.getInstance("RSASSA-PSS")
        s.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        s.initSign(privateKey)
        s.update(payload)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun keyStoreWith(
        algorithm: String = "Ed25519",
        keyPair: KeyPair = genKeyPair(algorithm),
        keyId: String = "key_2026_01",
        status: KeyStatus = KeyStatus.ACTIVE,
    ): Pair<InMemoryPublisherKeyStore, KeyPair> {
        val store = InMemoryPublisherKeyStore()
        store.put(
            PublisherKey(
                keyId = keyId,
                publisherId = "pub.example",
                publicKeyFingerprint = fingerprint(keyPair.public),
                algorithm = algorithm,
                publicKeyEncoded = encodePublic(keyPair.public),
                createdAt = "2026-01-01T00:00:00Z",
                status = status,
            )
        )
        return store to keyPair
    }

    private fun signatureFor(
        payload: ByteArray,
        keyPair: KeyPair,
        keyId: String = "key_2026_01",
        algorithm: String = "Ed25519",
    ): ArtifactSignature = ArtifactSignature(
        payloadSha256 = sha256Hex(payload),
        signature = if (algorithm == "Ed25519") signEd25519(payload, keyPair.private)
                    else signRsaPss(payload, keyPair.private),
        signingKeyId = keyId,
        algorithm = algorithm,
        signedAt = "2026-08-15T00:00:00Z",
    )

    // ═══════════════════════════════════════════════════════════════
    // V1-V5: Happy path & integrity (§6.2 step 1)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V1-Ed25519 signature verifies`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(payload, signatureFor(payload, keyPair))

        assertIs<VerifyResult.Verified>(result)
        assertEquals("key_2026_01", result.keyId)
        assertEquals("Ed25519", result.algorithm)
        assertFalse(result.fromCache)
    }

    @Test
    fun `V2-RSA-PSS-4096 signature verifies`() {
        val (store, keyPair) = keyStoreWith(algorithm = "RSA-PSS-4096")
        val payload = "plugin package".encodeToByteArray()
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(payload, signatureFor(payload, keyPair, algorithm = "RSA-PSS-4096"))

        assertIs<VerifyResult.Verified>(result)
        assertEquals("RSA-PSS-4096", result.algorithm)
    }

    @Test
    fun `V3-payload tampered fails hash check`() {
        val (store, keyPair) = keyStoreWith()
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val tampered = byteArrayOf(1, 2, 3, 4, 6)
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(tampered, signatureFor(original, keyPair))

        assertIs<VerifyResult.Rejected>(result)
        assertEquals("hash_mismatch", result.reason)
    }

    @Test
    fun `V4-wrong declared hash fails hash check even with valid signature`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1, 2, 3)
        val sig = signatureFor(payload, keyPair).copy(payloadSha256 = "00".repeat(32))
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(payload, sig)

        assertIs<VerifyResult.Rejected>(result)
        assertEquals("hash_mismatch", result.reason)
    }

    // ═══════════════════════════════════════════════════════════════
    // V6-V9: Key resolution & status (§6.2 step 2-3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V6-unknown key rejected`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1)
        val sig = signatureFor(payload, keyPair, keyId = "ghost_key")
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(payload, sig)

        assertIs<VerifyResult.Rejected>(result)
        assertEquals("unknown_key", result.reason)
    }

    @Test
    fun `V7-revoked key rejected`() {
        val (store, keyPair) = keyStoreWith(status = KeyStatus.REVOKED)
        val payload = byteArrayOf(1, 2)
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(payload, signatureFor(payload, keyPair))

        assertIs<VerifyResult.Rejected>(result)
        assertEquals("key_revoked", result.reason)
    }

    @Test
    fun `V8-algorithm mismatch rejected`() {
        val (store, keyPair) = keyStoreWith(algorithm = "Ed25519")
        val payload = byteArrayOf(1, 2)
        val sig = signatureFor(payload, keyPair).copy(algorithm = "RSA-PSS-4096")
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(payload, sig)

        assertIs<VerifyResult.Rejected>(result)
        assertEquals("algorithm_mismatch", result.reason)
    }

    @Test
    fun `V9-signature from wrong key rejected`() {
        val (store, _) = keyStoreWith()
        val otherKey = genKeyPair("Ed25519")
        val payload = byteArrayOf(1, 2, 3)
        val sig = signatureFor(payload, otherKey)
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(payload, sig)

        assertIs<VerifyResult.Rejected>(result)
        assertEquals("signature_invalid", result.reason)
    }

    @Test
    fun `V10-malformed signature base64 rejected`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1, 2, 3)
        val sig = signatureFor(payload, keyPair).copy(signature = "not-base64!!!")
        val verifier = ArtifactVerifier(store)

        val result = verifier.verify(payload, sig)

        assertIs<VerifyResult.Rejected>(result)
        assertEquals("signature_invalid", result.reason)
    }

    // ═══════════════════════════════════════════════════════════════
    // V11-V12: Blocklist (§6.2 step 5)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V11-blocklisted package rejected`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1, 2, 3)
        val blocklist = Blocklist { pkg, _ -> pkg == "evil.example" }
        val verifier = ArtifactVerifier(store, blocklist = blocklist)

        val result = verifier.verify(payload, signatureFor(payload, keyPair), packageId = "evil.example", version = "1.0.0")

        assertIs<VerifyResult.Rejected>(result)
        assertEquals("blocklisted", result.reason)
    }

    @Test
    fun `V12-non-blocklisted package passes`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1, 2, 3)
        val blocklist = Blocklist { pkg, _ -> pkg == "evil.example" }
        val verifier = ArtifactVerifier(store, blocklist = blocklist)

        val result = verifier.verify(payload, signatureFor(payload, keyPair), packageId = "good.example", version = "1.0.0")

        assertIs<VerifyResult.Verified>(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // V13-V15: Verification cache (§16.2)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V13-second verify comes from cache`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1, 2, 3)
        val verifier = ArtifactVerifier(store)

        assertIs<VerifyResult.Verified>(verifier.verify(payload, signatureFor(payload, keyPair)))
        val second = verifier.verify(payload, signatureFor(payload, keyPair))

        assertIs<VerifyResult.Verified>(second)
        assertTrue(second.fromCache)
    }

    @Test
    fun `V14-cache miss for different payload`() {
        val (store, keyPair) = keyStoreWith()
        val verifier = ArtifactVerifier(store)

        assertIs<VerifyResult.Verified>(verifier.verify(byteArrayOf(1), signatureFor(byteArrayOf(1), keyPair)))
        val different = verifier.verify(byteArrayOf(2), signatureFor(byteArrayOf(2), keyPair))

        assertIs<VerifyResult.Verified>(different)
        assertFalse(different.fromCache)
    }

    @Test
    fun `V15-cached rejection is not promoted to trust`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1, 2)
        val cache = VerificationCache()
        val verifier = ArtifactVerifier(store, cache = cache)

        // First verify: valid signature → cache trusted=true.
        assertIs<VerifyResult.Verified>(verifier.verify(payload, signatureFor(payload, keyPair)))
        // Corrupt the cache to trusted=false for the same key+hash.
        cache.put("key_2026_01", sha256Hex(payload), VerifyCacheEntry(System.currentTimeMillis(), trusted = false))

        val result = verifier.verify(payload, signatureFor(payload, keyPair))
        assertIs<VerifyResult.Rejected>(result)
        assertEquals("previously_rejected", result.reason)
    }

    @Test
    fun `V16-expired cache re-verifies`() {
        val (store, keyPair) = keyStoreWith()
        val payload = byteArrayOf(1, 2)
        var now = 1_000_000L
        val verifier = ArtifactVerifier(store, clock = { now })

        assertIs<VerifyResult.Verified>(verifier.verify(payload, signatureFor(payload, keyPair)))

        // Advance beyond the default 7-day TTL.
        now += VerificationCache.DEFAULT_TTL_MILLIS + 1
        val result = verifier.verify(payload, signatureFor(payload, keyPair))

        assertIs<VerifyResult.Verified>(result)
        assertFalse(result.fromCache)
    }
}
