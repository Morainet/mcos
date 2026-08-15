package com.mcos.runtime.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** Transport-level failure with a typed error code (mirrors `LlmTransportException`). */
class SyncBlobException(
    val code: String,
    override val message: String,
    val retryable: Boolean = true,
) : Exception(message)

/**
 * Pluggable blob transport to `mcos-server` ([07-memory.md 11.0]: the server
 * stores opaque encrypted blobs; it never sees plaintext).
 *
 * The JVM default is [JdkSyncBlobTransport] backed by `java.net.http.HttpClient`.
 * Android does not ship that module, so Android builds inject an
 * `HttpURLConnection`-based transport (same pattern as `LlmHttpTransport`).
 */
interface SyncBlobTransport {
    /**
     * Store [blob] under [blobId]. Implementations MUST throw
     * [SyncBlobException] for non-2xx responses; [IOException] may propagate.
     */
    suspend fun upload(blobId: String, blob: ByteArray)

    /**
     * Fetch the opaque blob stored under [blobId]. Throws
     * [SyncBlobException] with `code = "NOT_FOUND"` (non-retryable) when the
     * server has no such blob.
     */
    suspend fun download(blobId: String): ByteArray

    /** Remove [blobId]. Idempotent — missing blobs are a no-op. */
    suspend fun delete(blobId: String)
}

/**
 * Default [SyncBlobTransport] using the JDK 11+ [HttpClient].
 *
 * REST contract: `PUT|GET|DELETE {baseUrl}/blobs/{blobId}` with an
 * `application/octet-stream` body. On Android this class is never loaded
 * (Android has no `java.net.http` module); the Android module provides its
 * own transport instead.
 */
class JdkSyncBlobTransport(
    private val baseUrl: String,
    private val token: String? = null,
    private val connectTimeoutMs: Long = 5_000,
    private val requestTimeoutMs: Long = 15_000,
) : SyncBlobTransport {

    override suspend fun upload(blobId: String, blob: ByteArray) = withContext(Dispatchers.IO) {
        val response = client().send(
            request(blobId, "PUT")
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(blob))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (response.statusCode() !in 200..299) {
            throw SyncBlobException("UPLOAD_FAILED", "upload $blobId -> HTTP ${response.statusCode()}")
        }
    }

    override suspend fun download(blobId: String): ByteArray = withContext(Dispatchers.IO) {
        val response = client().send(
            request(blobId, "GET").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        when (response.statusCode()) {
            in 200..299 -> response.body()
            404 -> throw SyncBlobException("NOT_FOUND", "blob $blobId does not exist", retryable = false)
            else -> throw SyncBlobException("DOWNLOAD_FAILED", "download $blobId -> HTTP ${response.statusCode()}")
        }
    }

    override suspend fun delete(blobId: String): Unit = withContext(Dispatchers.IO) {
        client().send(
            request(blobId, "DELETE").DELETE().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        Unit
    }

    private fun client(): HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()

    private fun request(blobId: String, method: String): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/blobs/$blobId"))
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .header("X-Mcos-Blob-Method", method)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
}

/**
 * Device-side sync client bridging [MemorySync] (plaintext snapshot in/out)
 * and the opaque-blob transport — encryption/decryption never leaves the
 * device ([07-memory.md 11.0]).
 *
 * - [push] exports the syncable snapshot, E2E-encrypts it and uploads an
 *   opaque blob, returning the `blobId` to share with peer devices.
 * - [pull] downloads the blob, authenticates/decrypts it locally, and feeds
 *   the plaintext into [MemorySync.importSnapshot] (vector-clock LWW +
 *   enterprise policy still apply end to end).
 */
class MemorySyncClient(
    private val sync: MemorySync,
    private val crypto: MemoryBlobCrypto,
    private val transport: SyncBlobTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val entryListSerializer = ListSerializer(SyncEntry.serializer())

    /** Export → encrypt → upload. Returns the server-side [blobId]. */
    suspend fun push(): String {
        val snapshot = sync.exportSnapshot()
        val plaintext = json.encodeToString(entryListSerializer, snapshot).toByteArray(Charsets.UTF_8)
        val blob = crypto.encrypt(plaintext)
        val wire = json.encodeToString(EncryptedBlob.serializer(), blob).toByteArray(Charsets.UTF_8)
        val blobId = UUID.randomUUID().toString()
        transport.upload(blobId, wire)
        return blobId
    }

    /** Download → decrypt → import. Returns the LWW [SyncReport]. */
    suspend fun pull(blobId: String, policy: SyncPolicy = SyncPolicy()): SyncReport {
        val wire = transport.download(blobId)
        val blob = json.decodeFromString(EncryptedBlob.serializer(), wire.toString(Charsets.UTF_8))
        val plaintext = crypto.decrypt(blob)
        val snapshot = json.decodeFromString(entryListSerializer, plaintext.toString(Charsets.UTF_8))
        return sync.importSnapshot(snapshot, policy)
    }

    /** Delete a blob from the server once both peers have pulled it. */
    suspend fun delete(blobId: String) = transport.delete(blobId)
}
