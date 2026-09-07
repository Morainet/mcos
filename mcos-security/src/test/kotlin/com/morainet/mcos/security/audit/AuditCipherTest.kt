package com.morainet.mcos.security.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * AesGcmAuditCipher: AEAD round-trip and fail-closed authentication
 * (08-security.md §14). A sealed line that cannot be authenticated opens to
 * null — never a silent misread.
 */
class AuditCipherTest {

    private val key = deriveAuditCipherKey("audit-at-rest-seed")
    private val cipher: AuditCipher = AesGcmAuditCipher(key)
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()
    private val json = Json { prettyPrint = false }

    // ═══════════════════════════════════════════════════════════════
    // AC1: round-trip
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `AC1-seal then open recovers the plaintext line`() {
        val plain = """{"runId":"r-1","timestamp":123}"""
        val sealed = cipher.seal(plain)
        assertTrue(isSealedEnvelope(sealed), "sealed output must carry the envelope marker")
        assertFalse(sealed.contains("r-1"), "runId must not appear in ciphertext")
        assertEquals(plain, cipher.open(sealed))
    }

    // ═══════════════════════════════════════════════════════════════
    // AC2-AC4: tamper detection (ct / iv / marker-in-AAD)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `AC2-tampered ciphertext opens to null`() {
        val sealed = cipher.seal("""{"runId":"r-1"}""")
        val obj = json.parseToJsonElement(sealed) as JsonObject
        val ct = b64d.decode(obj["ct"]!!.jsonPrimitive.content)
        ct[0] = (ct[0].toInt() xor 0x01).toByte()
        val tampered = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("_mcosAuditEnc", obj["_mcosAuditEnc"]!!.jsonPrimitive.content)
            put("iv", obj["iv"]!!.jsonPrimitive.content)
            put("ct", b64.encodeToString(ct))
        })
        assertNull(cipher.open(tampered), "flipped ciphertext byte must fail the GCM tag")
    }

    @Test
    fun `AC3-tampered iv opens to null`() {
        val sealed = cipher.seal("""{"runId":"r-1"}""")
        val obj = json.parseToJsonElement(sealed) as JsonObject
        val iv = b64d.decode(obj["iv"]!!.jsonPrimitive.content)
        iv[0] = (iv[0].toInt() xor 0x01).toByte()
        val tampered = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("_mcosAuditEnc", obj["_mcosAuditEnc"]!!.jsonPrimitive.content)
            put("iv", b64.encodeToString(iv))
            put("ct", obj["ct"]!!.jsonPrimitive.content)
        })
        assertNull(cipher.open(tampered), "flipped IV byte must fail the GCM tag")
    }

    @Test
    fun `AC4-tampered marker breaks the AAD binding`() {
        val sealed = cipher.seal("""{"runId":"r-1"}""")
        val obj = json.parseToJsonElement(sealed) as JsonObject
        // Downgrade the algorithm marker: still an envelope, but the AAD no
        // longer matches what was sealed → authentication must fail.
        val tampered = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("_mcosAuditEnc", "AES-256-GCM-v0")
            put("iv", obj["iv"]!!.jsonPrimitive.content)
            put("ct", obj["ct"]!!.jsonPrimitive.content)
        })
        assertNull(cipher.open(tampered), "marker/downgrade tamper must fail authentication")
    }

    // ═══════════════════════════════════════════════════════════════
    // AC5: malformed envelopes
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `AC5-malformed base64 and non-envelope input open to null`() {
        val notEnvelope = """{"runId":"r-1","timestamp":1}"""
        assertNull(cipher.open(notEnvelope), "a plaintext record is not a sealed envelope")
        assertNull(cipher.open("this is not json"))
        val badB64 = """{"_mcosAuditEnc":"AES-256-GCM-v1","iv":"!!!","ct":"!!!"}"""
        assertNull(cipher.open(badB64), "undecodable base64 must not throw")
    }

    // ═══════════════════════════════════════════════════════════════
    // AC6: fresh IV per seal
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `AC6-sealing the same line twice yields distinct ciphertexts`() {
        val plain = """{"runId":"r-1","timestamp":1}"""
        val a = cipher.seal(plain)
        val b = cipher.seal(plain)
        assertNotEquals(a, b, "a fresh random IV per line must produce distinct ciphertexts")
        assertEquals(plain, cipher.open(a))
        assertEquals(plain, cipher.open(b))
    }

    @Test
    fun `AC7-key of the wrong size is rejected`() {
        val ex = runCatching { AesGcmAuditCipher(ByteArray(16)) }.exceptionOrNull()
        assertNotNull(ex, "a 16-byte key must be rejected (AES-256 requires 32)")
        assertTrue(ex is IllegalArgumentException)
    }
}
