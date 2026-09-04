package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.marketplace.JdkMarketplaceHttpTransport
import com.morainet.mcos.marketplace.MarketplaceIndex
import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PublisherKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crypto + `.mcos`-package builders for the index-server interop suite
 * (12-index-server.md §9). The pipeline under test re-derives every value
 * from the wire, so these helpers must produce exactly what a real
 * publisher toolchain would: a PKCS#8/X.509 operator PEM, a signed
 * `.mcos` zip and a matching [ArtifactSignature] envelope over the artifact
 * bytes.
 */
internal object IndexTestKit {

    fun nowIso(): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace("Z", "+00:00")

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun publicKeyB64(key: KeyPair): String = b64(key.public.encoded)

    fun pem(label: String, der: ByteArray): String {
        val body = b64(der).chunked(64)
        return buildString {
            append("-----BEGIN $label-----\n")
            body.forEach { append(it).append('\n') }
            append("-----END $label-----\n")
        }
    }

    /** Ed25519 signature over [payload], base64 — mirrors a publisher signing tool. */
    fun signEd25519(privateKey: java.security.PrivateKey, payload: ByteArray): String {
        val signature = java.security.Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(payload)
        return b64(signature.sign())
    }

    /** Builds a `.mcos` zip whose root [plugin.json] carries [manifestJson]. */
    fun mcosPackage(manifestJson: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    /** Wire JSON for an [com.morainet.mcos.security.ArtifactSignature] envelope. */
    fun signatureJson(payload: ByteArray, key: KeyPair, keyId: String): String =
        """{"payloadSha256":"${sha256Hex(payload)}",""" +
            """"signature":"${signEd25519(key.private, payload)}",""" +
            """"signingKeyId":"$keyId","algorithm":"Ed25519",""" +
            """"signedAt":"${nowIso()}"}"""
}

/**
 * A disposable index-server instance bound to a loopback ephemeral port,
 * plus a raw JDK HTTP client for the management/publisher write surface
 * (the shipped [MarketplaceIndex] client covers the read/report surface).
 *
 * Fixture knobs:
 *  - [withOperatorKey]: write operator PEMs so `/v1/blocklist` signing works.
 *  - [withAvDenylist]: wire the sha256 AV seam — otherwise gate 9 reports
 *    UNSCANNED and every submission lands in HUMAN_REVIEW (the escalation
 *    tests use `withAvDenylist = false` deliberately).
 */
internal class ServerFixture(
    private val withOperatorKey: Boolean = true,
    private val withAvDenylist: Boolean = true,
) : AutoCloseable {

    private val dir: Path = Files.createTempDirectory("mcos-index-server-test")
    val adminToken: String = "admin-token-${UUID.randomUUID()}"
    val operatorKp: KeyPair? = if (withOperatorKey) IndexTestKit.ed25519() else null

    private val operatorPrivatePem: Path? = operatorKp?.let { kp ->
        val file = dir.resolve("operator-private.pem")
        Files.writeString(file, IndexTestKit.pem("PRIVATE KEY", kp.private.encoded))
        file
    }
    private val operatorPublicPem: Path? = operatorKp?.let { kp ->
        val file = dir.resolve("operator-public.pem")
        Files.writeString(file, IndexTestKit.pem("PUBLIC KEY", kp.public.encoded))
        file
    }
    private val avDenylistFile: Path? = if (withAvDenylist) {
        // A non-empty denylist activates the sha256 AV seam (12-index-server.md
        // §6 gate 9); an unrelated hash ⇒ CLEAN verdict for our artifacts.
        val file = dir.resolve("av-denylist.sha256")
        Files.writeString(
            file,
            "0000000000000000000000000000000000000000000000000000000000000000\n",
        )
        file
    } else null

    private val server: IndexServer
    val port: Int
    val baseUrl: String

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    init {
        server = IndexServer(
            dataDir = dir,
            adminToken = adminToken,
            operatorPrivatePem = operatorPrivatePem,
            operatorPublicPem = operatorPublicPem,
            avDenylistFile = avDenylistFile,
        )
        port = server.start()
        baseUrl = "http://127.0.0.1:$port"
    }

    /** The operator public key as the client's pinned trust anchor. */
    fun operatorPublisherKey(): PublisherKey {
        val kp = operatorKp ?: error("fixture started without an operator key")
        return PublisherKey(
            keyId = "operator-test",
            publisherId = "operator",
            publicKeyFingerprint = IndexTestKit.sha256Hex(kp.public.encoded),
            algorithm = "Ed25519",
            publicKeyEncoded = IndexTestKit.b64(kp.public.encoded),
            createdAt = IndexTestKit.nowIso(),
            status = KeyStatus.ACTIVE,
        )
    }

    /** The real shipped client pointed at this server. */
    fun client(): MarketplaceIndex = MarketplaceIndex(
        baseUrl = baseUrl,
        transport = JdkMarketplaceHttpTransport(),
        blocklistVerifier = BlocklistVerifier(operatorPublisherKey()),
    )

    data class Raw(val status: Int, val body: String) {
        fun ok(): Raw {
            check(status in 200..299) { "expected 2xx, got $status: $body" }
            return this
        }

        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

        fun field(name: String): String? =
            (json()[name] as? JsonPrimitive)?.content

        fun contains(needle: String): Raw {
            check(body.contains(needle)) { "body does not contain '$needle': $body" }
            return this
        }
    }

    fun send(
        method: String,
        path: String,
        token: String? = null,
        body: ByteArray? = null,
        contentType: String = "application/json",
    ): Raw {
        val builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (contentType.isNotBlank()) builder.header("Content-Type", contentType)
        val request = when (method) {
            "GET" -> builder.GET().build()
            "DELETE" -> builder.DELETE().build()
            else -> builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0))).build()
        }
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return Raw(response.statusCode(), response.body())
    }

    fun get(path: String, token: String? = null): Raw = send("GET", path, token)

    fun post(path: String, token: String?, json: String): Raw =
        send("POST", path, token, json.toByteArray(Charsets.UTF_8))

    fun delete(path: String, token: String?): Raw = send("DELETE", path, token)

    /** Multipart upload used by the publisher submit endpoint (§5.2). */
    fun postMultipart(path: String, token: String, fields: List<Pair<String, ByteArray>>): Raw {
        val boundary = "----mcos-interop-${UUID.randomUUID().toString().replace("-", "")}"
        val baos = ByteArrayOutputStream()
        fields.forEach { (name, bytes) ->
            baos.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
            baos.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.US_ASCII))
            baos.write(bytes)
            baos.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        baos.write("--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
        return send(
            method = "POST",
            path = path,
            token = token,
            body = baos.toByteArray(),
            contentType = "multipart/form-data; boundary=$boundary",
        )
    }

    fun downloadBytes(artifactUrl: String): ByteArray {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + artifactUrl))
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body()
    }

    fun readDataFile(name: String): String? {
        val file = dir.resolve(name)
        return if (Files.exists(file)) Files.readString(file) else null
    }

    override fun close() {
        server.stop()
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
