package com.morainet.mcos.android.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Item 47: the at-rest crypto for [AndroidSecureStore] — envelope framing
 * ([AesGcmValueCipher]) and the stored-string codec ([AndroidSecureStore]'s
 * internal encode/decode). Pure JCA so everything runs on the JVM; the
 * AndroidKeyStore/SharedPreferences glue is on-device verification surface.
 */
class AndroidSecureStoreTest {

    private val cipher = AesGcmValueCipher(aesKey())

    private fun aesKey(bits: Int = 256): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(bits) }.generateKey()

    // ─── AesGcmValueCipher: the envelope ─────────────────────────────────

    @Test
    fun as1RoundTripPreservesArbitraryBytes() {
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x00, 0xFF.toByte(), 0xFE.toByte()) +
            "空调 token ✓".toByteArray() + byteArrayOf(0x80.toByte())
        assertArrayEquals(
            "sealed values must decrypt byte-identical, non-UTF-8 included",
            payload,
            cipher.decrypt(cipher.encrypt(payload)),
        )
    }

    @Test
    fun as2FreshIvSealsIdenticalPlaintextToDistinctEnvelopes() {
        val payload = "same-secret".toByteArray()
        val first = cipher.encrypt(payload)
        val second = cipher.encrypt(payload)
        assertNotEquals("random IV per seal — no ciphertext equality oracle", first.toList(), second.toList())
        assertArrayEquals("both envelopes decrypt to the plaintext", payload, cipher.decrypt(first))
        assertArrayEquals("both envelopes decrypt to the plaintext", payload, cipher.decrypt(second))
    }

    @Test
    fun as3TamperedEnvelopeFailsLoudly() {
        val envelope = cipher.encrypt("bearer tok-9".toByteArray())
        val tamperedCiphertext = envelope.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 0x40).toByte() }
        val tamperedTag = envelope.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tamperedCiphertext) }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tamperedTag) }
    }

    @Test
    fun as4WrongKeyCannotDecrypt() {
        val stranger = AesGcmValueCipher(aesKey())
        assertThrows(
            GeneralSecurityException::class.java,
        ) { stranger.decrypt(cipher.encrypt("cross-key".toByteArray())) }
    }

    @Test
    fun as5DecryptRejectsUnsealedInput() {
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("legacy".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(ByteArray(5)) }
    }

    @Test
    fun as6IsSealedPredicateMarksVersionAndLength() {
        assertTrue("a real envelope is sealed", AesGcmValueCipher.isSealed(cipher.encrypt(ByteArray(0))))
        val notEnvelope = ByteArray(AesGcmValueCipher.MIN_ENVELOPE_BYTES) { 0x02 }
        assertEquals("wrong version byte is not sealed", false, AesGcmValueCipher.isSealed(notEnvelope))
        assertEquals("short input is not sealed", false, AesGcmValueCipher.isSealed(byteArrayOf(0x01) + ByteArray(10)))
        // The documented 1/256 legacy-collision rule: first byte 0x01 + length clears the floor → sealed.
        val collisionShaped = byteArrayOf(AesGcmValueCipher.VERSION) + ByteArray(AesGcmValueCipher.MIN_ENVELOPE_BYTES - 1)
        assertTrue("envelope-shaped legacy reads as sealed (fails closed at decrypt)", AesGcmValueCipher.isSealed(collisionShaped))
    }

    // ─── AndroidSecureStore codec: stored-string rules ───────────────────

    @Test
    fun ss1LegacyPlaintextPassesThroughUntouched() {
        val legacySeed = ByteArray(32) { (it * 7).toByte() } // pre-item-47 raw bytes on disk
        val decoded = AndroidSecureStore.decodeStored(Base64.getEncoder().encodeToString(legacySeed), cipher)
        assertArrayEquals("pre-item-47 values must read back as-is (no migration step)", legacySeed, decoded)
    }

    @Test
    fun ss2StoredRoundTripIsSealedOnDisk() {
        val payload = "mcp token 空调".toByteArray()
        val stored = AndroidSecureStore.encodeStored(payload, cipher)
        val onDisk = Base64.getDecoder().decode(stored)
        assertEquals("stored form must carry the envelope version byte", 0x01, onDisk[0].toInt())
        assertArrayEquals("codec round-trips through the sealed form", payload, AndroidSecureStore.decodeStored(stored, cipher))
    }

    @Test
    fun ss3NullAndCorruptBase64ReadAsAbsent() {
        assertNull("absent key", AndroidSecureStore.decodeStored(null, cipher))
        assertNull("corrupt base64 is absent, not a crash", AndroidSecureStore.decodeStored("%%not-base64%%", cipher))
    }

    @Test
    fun ss4LegacyValueIsResealedOnRewrite() {
        val legacy = "legacy-api-key".toByteArray()
        val stored = Base64.getEncoder().encodeToString(legacy)
        val readBack = AndroidSecureStore.decodeStored(stored, cipher)
        assertNotNull(readBack)
        val rewritten = AndroidSecureStore.encodeStored(readBack!!, cipher)
        assertArrayEquals(
            "a rewrite seals the value without changing its bytes",
            legacy,
            AndroidSecureStore.decodeStored(rewritten, cipher),
        )
        assertTrue("the rewrite is now encrypted at rest", AesGcmValueCipher.isSealed(Base64.getDecoder().decode(rewritten)))
    }

    @Test
    fun ss5EnvelopeShapedLegacyFailsClosedAtDecrypt() {
        val collision = byteArrayOf(AesGcmValueCipher.VERSION) + ByteArray(40) { 0x00 }
        val stored = Base64.getEncoder().encodeToString(collision)
        assertThrows(
            "an envelope-shaped legacy value surfaces loudly, never a silent misread",
            GeneralSecurityException::class.java,
        ) { AndroidSecureStore.decodeStored(stored, cipher) }
    }
}
