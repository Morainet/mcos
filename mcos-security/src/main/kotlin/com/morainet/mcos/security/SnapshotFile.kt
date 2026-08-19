package com.morainet.mcos.security

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Single-document snapshot file with atomic rewrite and optional
 * tamper-evidence — the persistence primitive behind
 * [com.morainet.mcos.security.audit.FileAuditLog]'s compaction, factored
 * out for the grant table and install records.
 *
 * File format: one JSON payload line, optionally followed by an
 * HMAC-SHA256 signature line over the payload bytes. When [read] is given
 * a key and the signature does not verify, the file is treated as absent
 * (callers fail closed).
 */
object SnapshotFile {

    /** Write [payload] (plus its HMAC line when [hmacKey] is set) atomically. */
    fun write(file: File, payload: String, hmacKey: ByteArray?) {
        val body = if (hmacKey != null) "$payload\n${hmacHex(payload, hmacKey)}\n" else "$payload\n"
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(body)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: IOException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: IOException) {
            runCatching { tmp.delete() }
            // Snapshot stays stale on disk; callers keep their in-memory
            // state and retry on the next write.
        }
    }

    /**
     * Read the payload line, or `null` when the file is missing, unreadable,
     * unsigned while a key is given, or fails HMAC verification.
     */
    fun read(file: File, hmacKey: ByteArray?): String? {
        val lines = try {
            if (!file.isFile) return null
            file.readLines().filter { it.isNotBlank() }
        } catch (_: IOException) {
            return null
        }
        if (lines.isEmpty()) return null
        if (hmacKey != null) {
            if (lines.size < 2) return null
            val payload = lines[0]
            val expected = lines[1]
            if (!MessageDigest.isEqual(
                    hmacHex(payload, hmacKey).toByteArray(),
                    expected.toByteArray(),
                )
            ) return null
        }
        return lines[0]
    }

    /** Hex-encoded HMAC-SHA256 of [payload] under [key]. */
    fun hmacHex(payload: String, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Derive an HMAC key from a persisted seed string. Hosts generate the
     * seed once (random, device-bound via their SecureStore) and derive per
     * purpose — same construction as
     * [com.morainet.mcos.security.audit.deriveAuditHmacKey].
     */
    fun deriveHmacKey(seed: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
}
