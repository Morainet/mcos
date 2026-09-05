package com.morainet.mcos.indexserver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `IndexServer` boots on a real socket, binds the requested host, honours a
 * seeded operator key, and serves the public read surface — the deployment
 * contract the `Main` entry point wires (12-index-server.md §8.1). This is the
 * smoke test that the module actually *runs*, not just that its handlers work
 * against the loopback fixture.
 */
class ServerLifecycleTest {

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun seedKeysDir(dir: Path): Path {
        val keysDir = Files.createDirectories(dir.resolve("keys"))
        val kp = IndexTestKit.ed25519()
        Files.writeString(keysDir.resolve("operator-private.pem"), IndexTestKit.pem("PRIVATE KEY", kp.private.encoded))
        Files.writeString(keysDir.resolve("operator-public.pem"), IndexTestKit.pem("PUBLIC KEY", kp.public.encoded))
        return keysDir
    }

    @Test
    fun `server binds a real port and serves the public read surface`() {
        val dir = Files.createTempDirectory("mcos-index-lifecycle")
        val keysDir = seedKeysDir(dir)
        val server = IndexServer(
            dataDir = dir,
            adminToken = "ops-secret",
            operatorPrivatePem = keysDir.resolve("operator-private.pem"),
            operatorPublicPem = keysDir.resolve("operator-public.pem"),
            port = 0, // ephemeral so the test never collides with a real deployment
            bindHost = "127.0.0.1",
        )
        val port = server.start()
        try {
            assertTrue(port in 1..65535, "expected a bound ephemeral port, got $port")

            // Empty index: search returns an empty result envelope, not a 404.
            val search = get("http://127.0.0.1:$port/v1/plugins")
            assertEquals(200, search.statusCode())
            assertTrue(search.body().contains("\"results\""), search.body())
            assertEquals("1", search.headers().firstValue("X-MCOS-INDEX").orElse(""))

            // The seeded operator key means the blocklist is signed and served
            // (an empty document still carries a non-empty operator signature).
            val blocklist = get("http://127.0.0.1:$port/v1/blocklist")
            assertEquals(200, blocklist.statusCode())
            assertTrue(blocklist.body().contains("\"signature\""), blocklist.body())

            // Management surface refuses an anonymous caller.
            assertEquals(401, get("http://127.0.0.1:$port/v1/admin/registry").statusCode())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a fresh deployment with no seeded key still boots and serves reads`() {
        val dir = Files.createTempDirectory("mcos-index-nokey")
        val server = IndexServer(
            dataDir = dir,
            adminToken = "ops-secret",
            operatorPrivatePem = null,
            operatorPublicPem = null,
            port = 0,
        )
        val port = server.start()
        try {
            assertEquals(200, get("http://127.0.0.1:$port/v1/plugins").statusCode())
        } finally {
            server.stop()
        }
    }

    private fun get(url: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
