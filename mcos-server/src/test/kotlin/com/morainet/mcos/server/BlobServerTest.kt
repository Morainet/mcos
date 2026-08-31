package com.morainet.mcos.server

import com.morainet.mcos.runtime.core.memory.JdkSyncBlobTransport
import com.morainet.mcos.runtime.core.memory.SyncBlobException
import com.morainet.mcos.runtime.core.memory.SyncBlobTransport
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2E contract + auth tests for the standalone `mcos-server`.
 *
 * Interop tests drive the *real* device-side [JdkSyncBlobTransport] against a
 * live server instance, so any drift between the client contract and the
 * server implementation fails here. Raw [HttpClient] is used only where the
 * transport deliberately hides details (401/405/healthz semantics).
 */
class BlobServerTest {

    private val token = "test-token-0123456789"
    private lateinit var tempDir: Path
    private lateinit var server: BlobServer
    private var port: Int = 0

    @BeforeTest
    fun start() {
        tempDir = Files.createTempDirectory("mcos-server-test")
        server = BlobServer(BlobStore(tempDir), token)
        port = server.port
    }

    @AfterTest
    fun stop() {
        server.close()
    }

    private fun transport(withToken: String? = token): SyncBlobTransport =
        JdkSyncBlobTransport("http://127.0.0.1:$port", token = withToken)

    // --- S1-S3: authenticated round-trip via the real device transport ---

    @Test
    fun `S1 upload and download round-trip over authenticated transport`() = runBlocking {
        val t = transport()
        val payload = "{\"entries\":[],\"hk\":\"x\"}".toByteArray()
        t.upload("s1-blob", payload)
        assertContentEquals(payload, t.download("s1-blob"))
    }

    @Test
    fun `S2 server stores opaque bytes without transformation`() {
        val payload = ByteArray(2048) { (it * 31 % 251).toByte() } // no ASCII plaintext patterns
        runBlocking { transport().upload("s2-blob", payload) }
        val raw = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/blobs/s2-blob"))
                .header("Authorization", "Bearer $token")
                .GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, raw.statusCode())
        assertContentEquals(payload, raw.body())
    }

    @Test
    fun `S3 delete is idempotent`() = runBlocking {
        val t = transport()
        t.upload("s3-blob", byteArrayOf(1, 2, 3))
        t.delete("s3-blob")
        t.delete("s3-blob") // second delete is a no-op
        val e = assertFailsWith<SyncBlobException> { t.download("s3-blob") }
        assertEquals("NOT_FOUND", e.code)
    }

    // --- S4-S7: auth failures ---

    @Test
    fun `S4 wrong token is rejected with 401`() = runBlocking {
        val t = transport(withToken = "wrong-token")
        val e = assertFailsWith<SyncBlobException> { t.upload("s4-blob", byteArrayOf(1)) }
        assertEquals("UPLOAD_FAILED", e.code)
    }

    @Test
    fun `S5 missing token is rejected with 401`() = runBlocking {
        val t = transport(withToken = null)
        val e = assertFailsWith<SyncBlobException> { t.download("s5-blob") }
        assertEquals("DOWNLOAD_FAILED", e.code)
    }

    @Test
    fun `S6 unauthorized response carries WWW-Authenticate challenge`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/blobs/s6-blob")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(401, response.statusCode())
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer "))
    }

    @Test
    fun `S7 server refuses blank token at startup`() {
        assertFailsWith<IllegalArgumentException> {
            BlobServer(BlobStore(tempDir), "   ")
        }
    }

    // --- S8-S9: contract error semantics ---

    @Test
    fun `S8 downloading a missing blob yields NOT_FOUND non-retryable`() = runBlocking {
        val e = assertFailsWith<SyncBlobException> { transport().download("s8-missing") }
        assertEquals("NOT_FOUND", e.code)
        assertFalse(e.retryable)
    }

    @Test
    fun `S9 unsupported method and unknown path return 405 and 404`() {
        val client = HttpClient.newHttpClient()
        val patch = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/blobs/s9-blob"))
                .header("Authorization", "Bearer $token")
                .method("PATCH", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(405, patch.statusCode())
        val unknown = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/nope"))
                .header("Authorization", "Bearer $token").GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(404, unknown.statusCode())
    }

    // --- S10: health endpoint is unauthenticated ---

    @Test
    fun `S10 health endpoint is reachable without auth`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/healthz")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertEquals("ok", response.body())
    }

    // --- S11: disk persistence across server restart ---

    @Test
    fun `S11 blobs survive a server restart`() = runBlocking {
        val id = "s11-blob"
        val payload = "persistent-payload-{α}".toByteArray()
        transport().upload(id, payload)
        server.close()

        val restarted = BlobServer(BlobStore(tempDir), token)
        try {
            val t = JdkSyncBlobTransport("http://127.0.0.1:${restarted.port}", token = token)
            assertContentEquals(payload, t.download(id))
        } finally {
            restarted.close()
        }
    }

    // --- S12-S13: path-traversal hardening (raw HTTP) ---

    @Test
    fun `S12 encoded dot segments are rejected with 400`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/blobs/..%2F..%2Fsecret"))
                .header("Authorization", "Bearer $token").GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(400, response.statusCode())
    }

    @Test
    fun `S13 blob id allowlist rejects separators and empty ids`() {
        assertTrue(BlobStore.isValidBlobId("abc-123_DEF"))
        assertFalse(BlobStore.isValidBlobId(""))
        assertFalse(BlobStore.isValidBlobId(".."))
        assertFalse(BlobStore.isValidBlobId("a/b"))
        assertFalse(BlobStore.isValidBlobId("a b"))
        assertFalse(BlobStore.isValidBlobId("a".repeat(129)))
    }
}
