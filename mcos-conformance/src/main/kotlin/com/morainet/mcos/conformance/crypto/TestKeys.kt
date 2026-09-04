package com.morainet.mcos.conformance.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.Signature
import java.util.Base64

/**
 * In-process keypairs for the conformance trust suite.
 *
 * Generates one Ed25519 + one RSA-PSS-4096 pair on first call and
 * caches them — the conformance run is single-process and the pairs
 * are deterministic per JVM invocation. Real keypairs never leave this
 * class.
 */
object TestKeys {

    val ed25519: Pair by lazy { generateEd25519() }
    val rsaPss4096: Pair by lazy { generateRsaPss4096() }

    /** Generated keypair + base64-encoded X.509 SubjectPublicKeyInfo. */
    data class Pair(
        val algorithm: String,
        val keyId: String,
        val privateKey: java.security.PrivateKey,
        val publicKeyEncodedB64: String,
    ) {
        fun sign(payload: ByteArray): String {
            val signer = when {
                algorithm.equals("Ed25519", ignoreCase = true) ->
                    Signature.getInstance("Ed25519")
                algorithm.equals("RSA-PSS-4096", ignoreCase = true) ->
                    Signature.getInstance("RSASSA-PSS").apply {
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
                else -> error("unknown algorithm $algorithm")
            }
            signer.initSign(privateKey)
            signer.update(payload)
            return Base64.getEncoder().encodeToString(signer.sign())
        }

        fun publicKeyFingerprint(): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val der = Base64.getDecoder().decode(publicKeyEncodedB64)
            return md.digest(der).joinToString("") { "%02x".format(it) }
        }
    }

    private fun generateEd25519(): Pair {
        val gen = KeyPairGenerator.getInstance("Ed25519")
        val kp: KeyPair = gen.generateKeyPair()
        val encoded = Base64.getEncoder().encodeToString(kp.public.encoded)
        return Pair(
            algorithm = "Ed25519",
            keyId = "test-key-ed25519",
            privateKey = kp.private,
            publicKeyEncodedB64 = encoded,
        )
    }

    private fun generateRsaPss4096(): Pair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(4096)
        val kp = gen.generateKeyPair()
        val encoded = Base64.getEncoder().encodeToString(kp.public.encoded)
        return Pair(
            algorithm = "RSA-PSS-4096",
            keyId = "test-key-rsa-pss-4096",
            privateKey = kp.private,
            publicKeyEncodedB64 = encoded,
        )
    }

    /**
     * Decode an X.509 SubjectPublicKeyInfo base64 into a [java.security.PublicKey]
     * — kept here so [Pair] can compute fingerprints and so test code
     * doesn't have to import JDK internals.
     */
    fun publicKeyOf(algorithm: String, b64: String): java.security.PublicKey {
        val der = Base64.getDecoder().decode(b64)
        val jca = if (algorithm.equals("RSA-PSS-4096", ignoreCase = true)) "RSA" else algorithm
        return KeyFactory.getInstance(jca).generatePublic(X509EncodedKeySpec(der))
    }
}