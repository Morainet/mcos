package com.morainet.mcos.runtime.memory

import com.morainet.mcos.runtime.audit.InMemoryAuditLog
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/**
 * End-to-end tests for encrypted memory sync against a reference `mcos-server`
 * ([07-memory.md 11.0]: the server stores **opaque encrypted blobs only** —
 * it never sees plaintext).
 *
 * E1-E9: full device A → encrypt → HTTP upload → store → HTTP download →
 * decrypt → device B, plus privacy, LWW, policy and idempotency invariants.
 */
class MemorySyncE2ETest {

    /** Minimal in-process `mcos-server`: stores opaque blobs, parses nothing. */
    private class ReferenceBlobServer : AutoCloseable {
        val stored = ConcurrentHashMap<String, ByteArray>()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String

        init {
            http.createContext("/blobs/") { exchange ->
                val blobId = exchange.requestURI.path.removePrefix("/blobs/")
                try {
                    when (exchange.requestMethod) {
                        "PUT" -> {
                            stored[blobId] = exchange.requestBody.readBytes()
                            exchange.sendResponseHeaders(204, -1)
                        }
                        "GET" -> {
                            val body = stored[blobId]
                            if (body == null) {
                                exchange.sendResponseHeaders(404, -1)
                            } else {
                                exchange.sendResponseHeaders(200, body.size.toLong())
                                exchange.responseBody.use { it.write(body) }
                            }
                        }
                        "DELETE" -> {
                            stored.remove(blobId)
                            exchange.sendResponseHeaders(204, -1)
                        }
                        else -> exchange.sendResponseHeaders(405, -1)
                    }
                } finally {
                    exchange.close()
                }
            }
            http.start()
            url = "http://127.0.0.1:${http.address.port}"
        }

        override fun close() = http.stop(0)
    }

    /** Two devices sharing one account key, both talking to the same server. */
    private class SyncHarness(server: ReferenceBlobServer) {
        private val accountKey = ByteArray(32) { it.toByte() }
        private fun crypto() = MemoryBlobCrypto(SecretAccountKeyProvider(accountKey))

        val storeA = MemoryStore("devA")
        val storeB = MemoryStore("devB")
        val clientA = MemorySyncClient(MemorySync(storeA), crypto(), JdkSyncBlobTransport(server.url))
        val clientB = MemorySyncClient(MemorySync(storeB), crypto(), JdkSyncBlobTransport(server.url))
    }

    // ════════════════════════════════════════════════════════════════════
    // E1-E2: happy path + privacy invariant ([07-memory.md 11.0])
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `E1-device A pushes and device B pulls the entry end to end`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val h = SyncHarness(server)
            h.storeA.putString("prefs.theme", "dark", syncable = true)

            val blobId = h.clientA.push()
            assertTrue(server.stored.containsKey(blobId), "server must have stored the blob")

            val report = h.clientB.pull(blobId)
            assertTrue(report.applied.contains("prefs.theme"))
            assertEquals("dark", h.storeB.get("prefs.theme")!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `E2-server only ever sees opaque ciphertext - never plaintext`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val h = SyncHarness(server)
            h.storeA.putString("places.home.address", "北京市朝阳区望京SOHO", syncable = true)

            h.clientA.push()

            val wire = server.stored.values.single()
            val wireText = String(wire, Charsets.UTF_8)
            assertFalse(wireText.contains("北京市朝阳区望京SOHO"), "no plaintext value on the wire")
            assertFalse(wireText.contains("places.home.address"), "no plaintext path on the wire")

            // The stored blob still decrypts to the full plaintext device-side.
            val harnessCrypto = MemoryBlobCrypto(SecretAccountKeyProvider(ByteArray(32) { it.toByte() }))
            val blob = Json.decodeFromString<EncryptedBlob>(wireText)
            val plaintext = String(harnessCrypto.decrypt(blob), Charsets.UTF_8)
            assertTrue(plaintext.contains("北京市朝阳区望京SOHO"))
            assertTrue(plaintext.contains("places.home.address"))
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // E3-E5: vector-clock LWW across the wire ([07-memory.md 11.1])
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `E3-remote newer write wins when pulled`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val h = SyncHarness(server)
            h.storeA.putString("prefs.theme", "dark", syncable = true)
            val blob1 = h.clientA.push()
            h.clientB.pull(blob1) // device B baseline: {devA:1}

            h.storeA.putString("prefs.theme", "midnight", syncable = true) // devA:2
            val blob2 = h.clientA.push()

            val report = h.clientB.pull(blob2)
            assertTrue(report.applied.contains("prefs.theme"), "remote dominates -> applied")
            assertEquals("midnight", h.storeB.get("prefs.theme")!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `E4-local newer write is kept when pulling an older snapshot`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val h = SyncHarness(server)
            h.storeA.putString("prefs.theme", "dark", syncable = true)
            val blob = h.clientA.push()
            h.clientB.pull(blob) // device B: {devA:1}

            h.storeB.putString("prefs.theme", "light", syncable = true) // {devA:1, devB:1}
            val report = h.clientB.pull(blob) // re-pull the OLD snapshot

            assertTrue(report.keptLocal.contains("prefs.theme"), "local dominates -> keptLocal")
            assertEquals("light", h.storeB.get("prefs.theme")!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `E5-concurrent divergence surfaces a conflict end to end`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val h = SyncHarness(server)
            h.storeA.putString("places.home.address", "北京", syncable = true)
            val blob1 = h.clientA.push()
            h.clientB.pull(blob1) // shared baseline

            h.storeA.putString("places.home.address", "上海新家", syncable = true) // {devA:2}
            h.storeB.putString("places.home.address", "广州的家", syncable = true) // {devA:1, devB:1}
            val blob2 = h.clientA.push()

            val report = h.clientB.pull(blob2)
            val conflict = report.conflicts.single { it.path == "places.home.address" }
            assertEquals("广州的家", conflict.localValue.jsonPrimitive.content)
            assertEquals("上海新家", conflict.remoteValue.jsonPrimitive.content)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // E6-E7: syncable filter + enterprise policy ([07-memory.md 11.0/11.3])
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `E6-local_only entries never leave the device`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val h = SyncHarness(server)
            h.storeA.putString("local.secret", "只在本机", syncable = false)
            h.storeA.putString("prefs.theme", "dark", syncable = true)

            val blobId = h.clientA.push()

            val wire = String(server.stored.values.single(), Charsets.UTF_8)
            assertFalse(wire.contains("local.secret"), "local_only must not be encrypted into the blob")

            h.clientB.pull(blobId)
            assertNull(h.storeB.get("local.secret"), "device B must not receive local_only entries")
            assertEquals("dark", h.storeB.get("prefs.theme")!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `E7-disableCloudMemorySync blocks the pull and logs to audit`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val accountKey = ByteArray(32) { it.toByte() }
            val crypto = MemoryBlobCrypto(SecretAccountKeyProvider(accountKey))
            val transport = JdkSyncBlobTransport(server.url)

            val storeA = MemoryStore("devA")
            val clientA = MemorySyncClient(MemorySync(storeA), crypto, transport)
            storeA.putString("prefs.theme", "dark", syncable = true)
            val blobId = clientA.push()

            val audit = InMemoryAuditLog()
            audit.start()
            try {
                val storeB = MemoryStore("devB")
                val clientB = MemorySyncClient(MemorySync(storeB, audit), crypto, transport)
                val report = clientB.pull(blobId, policy = SyncPolicy(enabled = false))

                assertEquals(listOf("prefs.theme"), report.skipped)
                assertNull(storeB.get("prefs.theme"))
                audit.flush()
                assertTrue(audit.getRuns().any { it.ir?.contains("disableCloudMemorySync") == true })
            } finally {
                audit.stop()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // E8-E9: transport errors + idempotency
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `E8-pulling a missing blob yields a typed non-retryable NOT_FOUND`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val h = SyncHarness(server)
            val e = assertFailsWith<SyncBlobException> { h.clientB.pull("no-such-blob") }
            assertEquals("NOT_FOUND", e.code)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun `E9-re-pulling the same blob is idempotent`() = runBlocking {
        ReferenceBlobServer().use { server ->
            val h = SyncHarness(server)
            h.storeA.putString("prefs.theme", "dark", syncable = true)
            val blobId = h.clientA.push()

            h.clientB.pull(blobId)
            val second = h.clientB.pull(blobId)

            assertTrue(second.skipped.contains("prefs.theme"), "identical clocks -> skipped, no duplicate apply")
            assertTrue(second.applied.isEmpty())
        }
    }
}
