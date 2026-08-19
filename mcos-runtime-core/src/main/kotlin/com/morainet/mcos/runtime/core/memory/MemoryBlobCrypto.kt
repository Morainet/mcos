package com.morainet.mcos.runtime.core.memory

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Source of the per-account master key material used to derive the sync
 * encryption key ([07-memory.md 11.0]).
 *
 * The decryption key must be derivable on **every device of the same
 * account** — unlike device-specific keystore keys — so devices exchange
 * opaque blobs with each other via `mcos-server` and all decrypt with the
 * same account-derived key. Android builds wrap this key in the platform
 * Keystore (e.g. `AndroidSecureStore`); the JVM default is
 * [SecretAccountKeyProvider].
 */
fun interface AccountKeyProvider {
    /** 32+ bytes of high-entropy account key material. */
    fun accountKey(): ByteArray
}

/**
 * [AccountKeyProvider] backed by caller-supplied key material (Base64
 * text, environment-provided secret, …). Test/desktop convenience; Android
 * deployments supply a Keystore-backed provider instead.
 */
class SecretAccountKeyProvider(key: ByteArray) : AccountKeyProvider {
    private val key: ByteArray = key.copyOf()

    init {
        require(key.size >= HkdfSha256.HASH_LEN) { "account key must be at least ${HkdfSha256.HASH_LEN} bytes" }
    }

    override fun accountKey(): ByteArray = key.copyOf()
}

/**
 * The opaque E2E-encrypted payload stored by `mcos-server`
 * ([07-memory.md 11.0]: *"server NEVER sees plaintext; stores opaque
 * blobs"*). JSON-friendly wire format: Base64 `iv` + `ciphertext` plus a
 * `version` field bound into the GCM AAD so version downgrades are detected.
 */
@Serializable
data class EncryptedBlob(
    val version: Int = CURRENT_VERSION,
    val iv: String,
    val ciphertext: String,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** The blob failed GCM authentication — corrupted or tampered in transit. */
class BlobIntegrityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The blob carries a `version` this build does not understand. */
class UnsupportedBlobVersionException(val version: Int) :
    Exception("unsupported memory sync blob version: $version")

/**
 * Device-local end-to-end encryption for memory sync blobs
 * ([07-memory.md 10.1], [07-memory.md 11.0]).
 *
 * - **Cipher:** AES-256-GCM, random 12-byte IV per encryption (so identical
 *   plaintexts produce different ciphertexts; the server sees no repeats).
 * - **Key derivation:** HKDF-SHA256 over the device-local account key
 *   ([AccountKeyProvider]) with a purpose-specific salt — the account key
 *   itself is never transmitted and never used directly.
 * - **Integrity:** the blob `version` is bound as GCM AAD; any bit flip in
 *   IV, ciphertext, or AAD fails authentication with [BlobIntegrityException].
 */
class MemoryBlobCrypto(
    private val keyProvider: AccountKeyProvider,
    private val random: SecureRandom = SecureRandom(),
) {
    private val aesKey: ByteArray by lazy {
        HkdfSha256.derive(
            ikm = keyProvider.accountKey(),
            salt = KDF_SALT,
            info = KDF_INFO,
        )
    }

    /** Encrypt [plaintext] into an opaque [EncryptedBlob]. */
    fun encrypt(plaintext: ByteArray): EncryptedBlob {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedBlob(
            version = EncryptedBlob.CURRENT_VERSION,
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )
    }

    /**
     * Decrypt [blob] back to plaintext. Throws [BlobIntegrityException] on
     * any authentication failure and [UnsupportedBlobVersionException] for
     * versions this build cannot read.
     */
    fun decrypt(blob: EncryptedBlob): ByteArray {
        if (blob.version != EncryptedBlob.CURRENT_VERSION) {
            throw UnsupportedBlobVersionException(blob.version)
        }
        val iv = decode(blob.iv, "iv")
        val ciphertext = decode(blob.ciphertext, "ciphertext")
        val cipher = Cipher.getInstance(CIPHER)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw BlobIntegrityException("memory sync blob failed authentication (tampered or corrupted)", e)
        }
    }

    private fun decode(base64: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(base64)
    } catch (e: IllegalArgumentException) {
        throw BlobIntegrityException("malformed Base64 in blob field '$field'", e)
    }

    private companion object {
        const val CIPHER = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_LENGTH = 12

        /** Purpose separation salt ([07-memory.md 10.1] HKDF). */
        val KDF_SALT = "mcos-memory-sync-v1".toByteArray()
        val KDF_INFO = "blob-encryption-key".toByteArray()

        /** Version bound into the GCM AAD — tamper/downgrade detectable. */
        val AAD = "mcos-sync-blob-v$VERSION".toByteArray()
        const val VERSION = "1"
    }
}
