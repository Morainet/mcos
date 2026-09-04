package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.Blocklist
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Operator Ed25519 key handling + blocklist signing (12-index-server.md §7).
 *
 * The operator key is the marketplace anchor: its public half is the key every
 * client pins in `TrustAnchors`. The private half lives offline (ops §8.4) and
 * is loaded here from PKCS#8 / X.509 PEMs at startup.
 */
object OperatorKeys {
    /** Generates an ephemeral key pair (tests, first-boot dev). */
    fun generate(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** Loads an operator key pair from PKCS#8 + X.509 PEM files. */
    fun load(privatePem: Path?, publicPem: Path?): KeyPair? {
        if (privatePem == null) return null
        val private = parsePrivateKey(Files.readString(privatePem))
        val public = if (publicPem != null) parsePublicKey(Files.readString(publicPem)) else null
        return KeyPair(public, private)
    }

    fun parsePrivateKey(pem: String): PrivateKey {
        val der = decodePem(pem, "PRIVATE KEY")
        return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    fun parsePublicKey(pem: String): PublicKey {
        val der = decodePem(pem, "PUBLIC KEY")
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
    }

    fun encodePrivatePem(privateKey: PrivateKey): String = encodePem("PRIVATE KEY", privateKey.encoded)

    fun encodePublicPem(publicKey: PublicKey): String = encodePem("PUBLIC KEY", publicKey.encoded)

    fun decodePem(pem: String, label: String): ByteArray {
        val body = pem
            .replace("-----BEGIN $label-----", "")
            .replace("-----END $label-----", "")
            .replace(Regex("\\s"), "")
        return Base64.getDecoder().decode(body)
    }

    private fun encodePem(label: String, der: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(der).chunked(64)
        return buildString {
            append("-----BEGIN $label-----\n")
            b64.forEach { append(it).append('\n') }
            append("-----END $label-----\n")
        }
    }
}

/** Signs the canonical (signature=null) serialisation of [blocklist]. */
fun signBlocklist(privateKey: PrivateKey, blocklist: Blocklist): String {
    val canonical = IndexJson.api.encodeToString(Blocklist.serializer(), blocklist.copy(signature = null))
    val signature = Signature.getInstance("Ed25519")
    signature.initSign(privateKey)
    signature.update(canonical.toByteArray(Charsets.UTF_8))
    return base64(signature.sign())
}

/** SHA-256 fingerprint (hex) of a public key — the value clients pin by. */
fun publicKeyFingerprint(publicKey: PublicKey): String = sha256Hex(publicKey.encoded)
