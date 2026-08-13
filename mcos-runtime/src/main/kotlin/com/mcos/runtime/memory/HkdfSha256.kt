package com.mcos.runtime.memory

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 ([RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869)).
 *
 * The JDK exposes HmacSHA256 but no HKDF primitive, so we implement the two
 * RFC steps directly:
 *
 * 1. **Extract** — `PRK = HMAC-SHA256(salt, IKM)` (or `HASH_LEN` zero bytes
 *    when `salt` is empty).
 * 2. **Expand** — `OKM = T(1) || T(2) || …`, where `T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)`.
 *
 * Used by [MemoryBlobCrypto] for key derivation per [07-memory.md 10.1]:
 * the device-local **account key** (high-entropy; never derived from a
 * low-entropy passphrase, so PBKDF2/Argon2 are deliberately not used) is the
 * IKM, and a purpose-specific salt/info separates the sync encryption key
 * from any other key the same account key may derive.
 */
object HkdfSha256 {

    /** SHA-256 output length in bytes. */
    const val HASH_LEN = 32

    private val HMAC = "HmacSHA256"

    /**
     * RFC 5869 `HKDF-Extract`: `PRK = HMAC(salt, IKM)`, with an all-zero
     * salt of [HASH_LEN] bytes when [salt] is empty.
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(effectiveSalt, ikm)
    }

    /**
     * RFC 5869 `HKDF-Expand`: derive [length] bytes of output keying material
     * from a pseudorandom key [prk]. [length] must be in `1..255 * HASH_LEN`.
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "length must be in 1..${255 * HASH_LEN}" }
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(prk, HMAC))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copy = minOf(t.size, length - offset)
            t.copyInto(okm, offset, 0, copy)
            offset += copy
            counter++
        }
        return okm
    }

    /**
     * Convenience one-shot: `extract` then `expand` with a 32-byte output
     * (a full AES-256 key) unless [length] is given.
     */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = HASH_LEN): ByteArray =
        expand(extract(salt, ikm), info, length)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data)
    }
}
