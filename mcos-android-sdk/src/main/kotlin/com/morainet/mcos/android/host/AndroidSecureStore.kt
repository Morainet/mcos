package com.morainet.mcos.android.host

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.morainet.mcos.sdk.SecureStore
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest value encryption seam for [AndroidSecureStore] (04-plugin-sdk.md §6.4
 * hardening, 11-implementation-status item 47). Implementations own the wire
 * format; [AesGcmValueCipher] defines the envelope and [isSealed][AesGcmValueCipher.isSealed]
 * is the single detector shared by store and cipher so read/write can never disagree.
 */
interface ValueCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(envelope: ByteArray): ByteArray
}

/**
 * AES-256-GCM envelope: `[0x01][12-byte IV][ciphertext ‖ 16-byte tag]`. Pure JCA —
 * the framing and crypto are JVM-unit-testable; production keys come from
 * [AndroidKeystoreValueCipher]. A fresh random IV per [encrypt] means identical
 * plaintexts seal to distinct envelopes; any tamper inside a sealed envelope
 * surfaces as a loud [GeneralSecurityException] (fail-closed, never a silent null).
 */
class AesGcmValueCipher(private val key: SecretKey) : ValueCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, SecureRandom())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH_BYTES) { "GCM IV must be $IV_LENGTH_BYTES bytes, was ${iv.size}" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(envelope: ByteArray): ByteArray {
        require(isSealed(envelope)) { "not a sealed envelope (${envelope.size} bytes)" }
        val iv = envelope.copyOfRange(VERSION_BYTES, VERSION_BYTES + IV_LENGTH_BYTES)
        val ciphertext = envelope.copyOfRange(VERSION_BYTES + IV_LENGTH_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BYTES * Byte.SIZE_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        const val VERSION: Byte = 1
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BYTES = 16

        /** Smallest legal envelope: version byte + IV + empty plaintext + GCM tag. */
        const val MIN_ENVELOPE_BYTES = 1 + IV_LENGTH_BYTES + TAG_LENGTH_BYTES
        private const val VERSION_BYTES = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Envelope detector. Legacy pre-item-47 plaintext that happens to start with
         * the version byte and clear the length floor (1/256 × size) is treated as
         * sealed and fails decryption loudly — documented availability cost of the
         * one-time dev-device migration window, deliberate over silent misreads.
         */
        fun isSealed(bytes: ByteArray): Boolean =
            bytes.size >= MIN_ENVELOPE_BYTES && bytes[0] == VERSION
    }
}

/**
 * Production [ValueCipher]: the AES key is generated inside (and never leaves)
 * AndroidKeyStore — hardware-backed where the device offers it. Thin by design;
 * everything testable lives in [AesGcmValueCipher]. First use generates the key
 * under the alias; the Keystore/SharedPreferences glue itself is on-device
 * verification surface (JVM unit tests never load "AndroidKeyStore").
 */
class AndroidKeystoreValueCipher(
    alias: String = DEFAULT_ALIAS,
) : ValueCipher by AesGcmValueCipher(loadAndroidKeystoreKey(alias)) {

    companion object {
        private const val DEFAULT_ALIAS = "mcos.securestore.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_KEY_BITS = 256
        private val keyLock = Any()

        private fun loadAndroidKeystoreKey(alias: String): SecretKey = synchronized(keyLock) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_BITS)
                    .build(),
            )
            generator.generateKey()
        }
    }
}

/**
 * SecureStore over app-private SharedPreferences: values are sealed with the
 * injected [ValueCipher] (04-plugin-sdk.md §6.4 — secrets are byte-valued, now
 * encrypted at rest with an AndroidKeyStore-held key). Namespacing follows the
 * caller's key scheme. Honesty notes: (1) values written before item 47 sit on
 * disk as Base64 plaintext and are passed through as-is on read, re-sealed on
 * the next write — no migration step, no data loss on dev devices; (2) the
 * prefs/Keystore glue runs only on a device, JVM tests cover the codec below.
 */
class AndroidSecureStore(
    context: Context,
    private val cipher: ValueCipher = AndroidKeystoreValueCipher(),
) : SecureStore {

    private val prefs = context.getSharedPreferences("mcos_secure", Context.MODE_PRIVATE)

    override suspend fun get(key: String): ByteArray? = decodeStored(prefs.getString(key, null), cipher)

    override suspend fun put(key: String, value: ByteArray) {
        prefs.edit().putString(key, encodeStored(value, cipher)).apply()
    }

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override suspend fun keys(): Set<String> = prefs.all.keys

    companion object {
        /**
         * Stored-string decoder, extracted so the JVM tests can pin the legacy
         * rules: sealed envelopes decrypt, pre-item-47 plaintext passes through,
         * null / corrupt Base64 read as absent.
         */
        internal fun decodeStored(stored: String?, cipher: ValueCipher): ByteArray? {
            val decoded = stored?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
            return if (AesGcmValueCipher.isSealed(decoded)) cipher.decrypt(decoded) else decoded
        }

        internal fun encodeStored(value: ByteArray, cipher: ValueCipher): String =
            Base64.getEncoder().encodeToString(cipher.encrypt(value))
    }
}
