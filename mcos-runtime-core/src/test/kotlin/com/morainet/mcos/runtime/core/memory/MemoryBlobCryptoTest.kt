package com.morainet.mcos.runtime.core.memory

import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Conformance tests for the memory sync encryption layer
 * ([07-memory.md 10.1], [07-memory.md 11.0]).
 *
 * B1-B3: HKDF-SHA256 ([RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869)).
 * C1-C7: `MemoryBlobCrypto` — round-trip, randomness, tamper detection,
 * version gating, key separation, plaintext opacity.
 */
class MemoryBlobCryptoTest {

    private val accountKey = ByteArray(32) { it.toByte() }
    private fun crypto() = MemoryBlobCrypto(SecretAccountKeyProvider(accountKey))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ════════════════════════════════════════════════════════════════════
    // B1-B3: HKDF-SHA256 ([RFC 5869])
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `B1-extract matches RFC 5869 test case 1 PRK`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = unhex("000102030405060708090a0b0c")
        val prk = HkdfSha256.extract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            hex(prk),
        )
    }

    @Test
    fun `B2-expand matches RFC 5869 test case 1 OKM`() {
        val prk = unhex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val info = unhex("f0f1f2f3f4f5f6f7f8f9")
        val okm = HkdfSha256.expand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
        )
    }

    @Test
    fun `B3-expand rejects out-of-range lengths`() {
        val prk = ByteArray(32) { 1 }
        assertFailsWith<IllegalArgumentException> { HkdfSha256.expand(prk, ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { HkdfSha256.expand(prk, ByteArray(0), 255 * 32 + 1) }
    }

    // ════════════════════════════════════════════════════════════════════
    // C1-C7: MemoryBlobCrypto
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `C1-encrypt-decrypt round-trip restores plaintext`() {
        val plaintext = """{"prefs.theme":"dark","note":"hi"}""".toByteArray()
        val blob = crypto().encrypt(plaintext)
        assertContentEquals(plaintext, crypto().decrypt(blob))
    }

    @Test
    fun `C2-same plaintext twice yields different ciphertexts (fresh IV)`() {
        val plaintext = "stable value".toByteArray()
        val a = crypto().encrypt(plaintext)
        val b = crypto().encrypt(plaintext)
        assertNotEquals(a.iv, b.iv)
        assertNotEquals(a.ciphertext, b.ciphertext)
        // Both still decrypt to the same plaintext.
        assertContentEquals(plaintext, crypto().decrypt(a))
        assertContentEquals(plaintext, crypto().decrypt(b))
    }

    @Test
    fun `C3-tampered ciphertext fails authentication`() {
        val blob = crypto().encrypt("secret".toByteArray())
        val tampered = blob.copy(ciphertext = tamper(blob.ciphertext))
        assertFailsWith<BlobIntegrityException> { crypto().decrypt(tampered) }
    }

    @Test
    fun `C4-tampered IV fails authentication`() {
        val blob = crypto().encrypt("secret".toByteArray())
        val tampered = blob.copy(iv = tamper(blob.iv))
        assertFailsWith<BlobIntegrityException> { crypto().decrypt(tampered) }
    }

    @Test
    fun `C5-unsupported version is rejected before decryption`() {
        val blob = crypto().encrypt("secret".toByteArray()).copy(version = 99)
        val e = assertFailsWith<UnsupportedBlobVersionException> { crypto().decrypt(blob) }
        assertEquals(99, e.version)
    }

    @Test
    fun `C6-different account keys cannot decrypt each other's blobs`() {
        val alice = MemoryBlobCrypto(SecretAccountKeyProvider(ByteArray(32) { 1 }))
        val bob = MemoryBlobCrypto(SecretAccountKeyProvider(ByteArray(32) { 2 }))
        val blob = alice.encrypt("for alice only".toByteArray())
        assertFailsWith<BlobIntegrityException> { bob.decrypt(blob) }
        assertContentEquals("for alice only".toByteArray(), alice.decrypt(blob))
    }

    @Test
    fun `C7-wire format stays opaque - no plaintext leaks into the blob`() {
        val secret = "sync-token-9f3a-ultimate-secret".toByteArray()
        val blob = crypto().encrypt(secret)
        val wire = Json.encodeToString(EncryptedBlob.serializer(), blob)
        assertFalse(wire.contains("sync-token-9f3a"), "wire must not leak plaintext")
        // Server-side view: only Base64 iv + ciphertext + version.
        val decoded = Json.decodeFromString<EncryptedBlob>(wire)
        assertContentEquals(secret, crypto().decrypt(decoded))
    }

    private fun tamper(base64: String): String {
        val raw = java.util.Base64.getDecoder().decode(base64)
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        return java.util.Base64.getEncoder().encodeToString(raw)
    }
}
