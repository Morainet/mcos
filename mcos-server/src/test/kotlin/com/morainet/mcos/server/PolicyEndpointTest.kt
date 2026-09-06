package com.morainet.mcos.server

import com.morainet.mcos.runtime.core.policy.JdkEnterprisePolicyHttpTransport
import com.morainet.mcos.security.EnterprisePolicy
import com.morainet.mcos.security.HttpEnterprisePolicySource
import com.morainet.mcos.security.PolicyEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * E2E tests for the `/enterprise/policy` management channel (08-security
 * §13.3 "delivered via mcos-server"): upload-time validation plus interop
 * with the REAL client chain — [HttpEnterprisePolicySource] over
 * [JdkEnterprisePolicyHttpTransport] — so any drift between the server
 * contract and the client's fail-closed fetch semantics fails here.
 */
class PolicyEndpointTest {

    private val token = "test-token-0123456789"
    private lateinit var tempDir: Path
    private lateinit var server: BlobServer
    private var port: Int = 0

    @BeforeTest
    fun start() {
        tempDir = Files.createTempDirectory("mcos-policy-endpoint-test")
        server = BlobServer(BlobStore(tempDir), token)
        port = server.port
    }

    @AfterTest
    fun stop() {
        server.close()
    }

    private fun policyUrl() = "http://127.0.0.1:$port/enterprise/policy"

    private fun client(refreshIntervalMs: Long = 60_000, clientToken: String? = token): HttpEnterprisePolicySource =
        HttpEnterprisePolicySource(
            policyUrl(),
            JdkEnterprisePolicyHttpTransport(token = clientToken),
            refreshIntervalMs = refreshIntervalMs,
        )

    private fun minimalPolicy(version: String = "1.0", issuedBy: String = "mdm") =
        """{"version": "$version", "issuedBy": "$issuedBy"}"""

    private fun raw(
        method: String,
        body: ByteArray? = null,
        requestToken: String? = token,
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(policyUrl()))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
            .apply { requestToken?.let { header("Authorization", "Bearer $it") } }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun putRaw(body: String, requestToken: String? = token) =
        raw("PUT", body.toByteArray(Charsets.UTF_8), requestToken)

    // ─── EP1-EP3: real-client interop (fetch / 404 / no-token) ───────────────

    @Test
    fun `EP1-stored policy is fetched and parsed by the real client chain`() {
        val put = putRaw(minimalPolicy(issuedBy = "server-mdm"))
        assertEquals(204, put.statusCode())

        val src = client()
        val policy = src.current()
        assertEquals("1.0", policy.version)
        assertEquals("server-mdm", policy.issuedBy)
        assertIs<PolicyEvent.PolicyUpdated>(src.lastEvent)
    }

    @Test
    fun `EP2-client refresh picks up a policy update after the interval`() {
        putRaw(minimalPolicy(issuedBy = "v1"))
        val src = client(refreshIntervalMs = 1)
        assertEquals("v1", src.current().issuedBy)

        putRaw(minimalPolicy(issuedBy = "v2"))
        Thread.sleep(20) // let the refresh window pass
        assertEquals("v2", src.current().issuedBy)
        val event = assertIs<PolicyEvent.PolicyUpdated>(src.lastEvent)
        assertEquals("1.0", event.previousVersion)
        assertEquals("v2", event.issuedBy)
    }

    @Test
    fun `EP3-missing policy fails the client closed over a real 404`() {
        val src = client()
        assertEquals(EnterprisePolicy.FAIL_CLOSED, src.current())
        val event = assertIs<PolicyEvent.PolicyFetchFailed>(src.lastEvent)
        assertTrue(event.reason.contains("404"), "reason was: ${event.reason}")
    }

    @Test
    fun `EP4-unauthorized fetch fails the client closed over a real 401`() {
        putRaw(minimalPolicy())
        val src = client(clientToken = "wrong-token")
        assertEquals(EnterprisePolicy.FAIL_CLOSED, src.current())
        val event = assertIs<PolicyEvent.PolicyFetchFailed>(src.lastEvent)
        assertTrue(event.reason.contains("401"), "reason was: ${event.reason}")
    }

    // ─── EP5-EP8: upload-time validation ────────────────────────────────────

    @Test
    fun `EP5-malformed policy document is refused with 400 and does not clobber the stored one`() {
        putRaw(minimalPolicy(issuedBy = "good"))
        val bad = putRaw("{broken json")
        assertEquals(400, bad.statusCode())
        assertTrue(bad.body().toString(Charsets.UTF_8).isNotEmpty())

        // The previously stored policy is untouched.
        val src = client()
        assertEquals("good", src.current().issuedBy)
    }

    @Test
    fun `EP6-unsupported schema version is refused with 400`() {
        val response = putRaw(minimalPolicy(version = "9.9"))
        assertEquals(400, response.statusCode())
        assertTrue(
            response.body().toString(Charsets.UTF_8).contains("Unsupported"),
            "body was: ${response.body().toString(Charsets.UTF_8)}",
        )
    }

    @Test
    fun `EP7-oversized policy document is refused with 413`() {
        val big = """{"issuedBy": "${"x".repeat(BlobServer.MAX_POLICY_BYTES.toInt())}"}"""
        val response = raw("PUT", big.toByteArray(Charsets.UTF_8))
        assertEquals(413, response.statusCode())
    }

    @Test
    fun `EP8-writes and reads require the bearer token`() {
        assertEquals(401, raw("PUT", minimalPolicy().toByteArray(Charsets.UTF_8), requestToken = null).statusCode())
        assertEquals(401, raw("GET", requestToken = null).statusCode())
        assertEquals(401, raw("DELETE", requestToken = null).statusCode())
    }

    // ─── EP9-EP10: lifecycle + persistence ──────────────────────────────────

    @Test
    fun `EP9-delete removes the policy and get returns 404 afterwards`() {
        putRaw(minimalPolicy())
        assertEquals(204, raw("DELETE").statusCode())
        assertEquals(404, raw("GET").statusCode())
        // Delete is idempotent.
        assertEquals(204, raw("DELETE").statusCode())
    }

    @Test
    fun `EP10-policy survives a server restart`() {
        putRaw(minimalPolicy(issuedBy = "persistent"))
        server.close()

        val restarted = BlobServer(BlobStore(tempDir), token)
        try {
            val src = HttpEnterprisePolicySource(
                "http://127.0.0.1:${restarted.port}/enterprise/policy",
                JdkEnterprisePolicyHttpTransport(token = token),
            )
            assertEquals("persistent", src.current().issuedBy)
        } finally {
            restarted.close()
        }
    }
}
