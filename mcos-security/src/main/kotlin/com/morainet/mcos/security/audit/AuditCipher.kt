package com.morainet.mcos.security.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest encryption seam for the persistent audit log (08-security.md §14 /
 * 03-runtime.md §13.3). A [FileAuditLog] with a cipher wired seals every JSONL
 * line before it touches disk; the in-memory query index and [export] stay
 * plaintext (the export is tamper-evidenced by HMAC, not encrypted).
 *
 * Secure-by-default with a named opt-out: `FileAuditLog.cipher` defaults to
 * `null` (plaintext, byte-identical to the historical behaviour), and the
 * only shipped implementation is the loud [AesGcmAuditCipher]. There is no
 * silent "encryption off" object to grep past — a null cipher is the visible
 * opt-out.
 */
interface AuditCipher {
    /** Seal one plaintext JSONL line into a single-line on-disk envelope. */
    fun seal(plaintextLine: String): String

    /**
     * Open a sealed line back to plaintext, or `null` when the line cannot be
     * authenticated (tampered ciphertext/IV/marker, malformed envelope, wrong
     * key). A `null` is treated by the caller as a corrupt line — never a
     * silent misread.
     */
    fun open(sealedLine: String): String?
}

/**
 * AES-256-GCM per-line audit cipher (pure JCA, zero third-party deps).
 *
 * Mirrors [com.morainet.mcos.runtime.core.memory.MemoryBlobCrypto]: a random
 * 12-byte IV per line (identical plaintexts seal to distinct ciphertexts), a
 * 128-bit tag, and the algorithm marker bound as GCM AAD so envelope tampering
 * fails authentication. The envelope is itself a JSON object, so a sealed file
 * stays valid JSONL (one object per line).
 *
 * On-disk envelope:
 * ```json
 * {"_mcosAuditEnc":"AES-256-GCM-v1","iv":"<base64 12B>","ct":"<base64 ct‖tag>"}
 * ```
 */
class AesGcmAuditCipher(
    key: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) : AuditCipher {

    private val aesKey: ByteArray = key.copyOf()

    init {
        require(key.size == KEY_BYTES) { "audit cipher key must be $KEY_BYTES bytes (AES-256), got ${key.size}" }
    }

    override fun seal(plaintextLine: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(plaintextLine.toByteArray(Charsets.UTF_8))
        val obj = buildJsonObject {
            put(MARKER, ALGORITHM)
            put("iv", b64.encodeToString(iv))
            put("ct", b64.encodeToString(ct))
        }
        // Compact, single-line — JSONL requires no embedded newlines.
        return WIRE.encodeToString(JsonObject.serializer(), obj)
    }

    override fun open(sealedLine: String): String? {
        val obj = runCatching { WIRE.parseToJsonElement(sealedLine) as? JsonObject }.getOrNull() ?: return null
        if (obj[MARKER]?.jsonPrimitive?.content != ALGORITHM) return null
        val iv = decode(obj["iv"]?.jsonPrimitive?.content) ?: return null
        val ct = decode(obj["ct"]?.jsonPrimitive?.content) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            null // tampered or wrong key — fail closed, never a misread
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(value: String?): ByteArray? =
        value?.let { runCatching { b64d.decode(it) }.getOrNull() }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val MARKER = "_mcosAuditEnc"
        const val ALGORITHM = "AES-256-GCM-v1"

        /** Algorithm marker bound into GCM AAD — marker/downgrade tamper detectable. */
        val AAD = ALGORITHM.toByteArray(Charsets.UTF_8)
        val WIRE = Json { prettyPrint = false }
        val b64: Base64.Encoder = Base64.getEncoder()
        val b64d: Base64.Decoder = Base64.getDecoder()
    }
}

/**
 * Whether [line] looks like an [AesGcmAuditCipher] envelope (carries the
 * `_mcosAuditEnc` marker key). Used by [FileAuditLog] replay to tell sealed
 * lines from legacy plaintext [RunRecord] JSON, which never carries this key.
 */
internal fun isSealedEnvelope(line: String): Boolean {
    val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return false
    return obj.containsKey("_mcosAuditEnc")
}
