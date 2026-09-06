package com.morainet.mcos.runtime.core.policy

import com.morainet.mcos.security.PolicyFetchResult
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [JdkEnterprisePolicyHttpTransport] against a live
 * `com.sun.net.httpserver` stub — the contract the mcos-server interop tests
 * then pin end to end.
 */
class JdkEnterprisePolicyHttpTransportTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private var handlerStatus = 200
    private var handlerBody = ""
    private var receivedAuth: String? = null

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/enterprise/policy") { exchange ->
            receivedAuth = exchange.requestHeaders.getFirst("Authorization")
            val bytes = handlerBody.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(handlerStatus, if (handlerStatus == 200) bytes.size.toLong() else -1)
            if (handlerStatus == 200) {
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun url() = "http://127.0.0.1:$port/enterprise/policy"

    private fun handler(status: Int, body: String) {
        handlerStatus = status
        handlerBody = body
        receivedAuth = null
    }

    // --- JT1-JT4: the fetch contract (2xx / non-2xx / fault / auth) ---

    @Test
    fun `JT1-2xx response yields the raw document`() {
        handler(200, """{"version":"1.0","issuedBy":"mdm"}""")
        val result = JdkEnterprisePolicyHttpTransport().fetch(url())
        val success = assertIs<PolicyFetchResult.Success>(result)
        assertEquals("""{"version":"1.0","issuedBy":"mdm"}""", success.document)
    }

    @Test
    fun `JT2-non-2xx status is a failure carrying the status`() {
        handler(404, "no policy")
        val result = JdkEnterprisePolicyHttpTransport().fetch(url())
        val failure = assertIs<PolicyFetchResult.Failure>(result)
        assertTrue(failure.reason.contains("404"))
    }

    @Test
    fun `JT3-unreachable server is a failure, never an exception`() {
        // Grab an ephemeral port, close it, then fetch at it → connection refused.
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val deadPort = dead.address.port
        dead.stop(0)

        val result = JdkEnterprisePolicyHttpTransport().fetch("http://127.0.0.1:$deadPort/enterprise/policy")
        assertIs<PolicyFetchResult.Failure>(result)
    }

    @Test
    fun `JT4-token rides as bearer auth header only when configured`() {
        handler(200, "{}")
        val anonymous = JdkEnterprisePolicyHttpTransport().fetch(url())
        assertIs<PolicyFetchResult.Success>(anonymous)
        assertNull(receivedAuth)

        handler(200, "{}")
        val bearer = JdkEnterprisePolicyHttpTransport(token = "s3cret").fetch(url())
        assertIs<PolicyFetchResult.Success>(bearer)
        assertEquals("Bearer s3cret", receivedAuth)
    }
}
