package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.sdk.ResolveResult
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.sdk.AuthStamp
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IsolatedFacadeServer (item 41, slice 3a): the main-process half of the
 * §8.3 facade. Identity gate first (§8.2 check 1), then per-call
 * stamp-scope egress (checks 2-4 via the runtime's StampScopedNetService),
 * namespaced sandbox, and forwarded secureStore/clock/memory — with every
 * denial surfaced as the shared error envelope instead of an exception.
 */
class IsolatedFacadeServerTest {

    private val pluginId = "com.example.plugin"
    private val admittedUid = 10_101
    private val signer = HmacAuthStampSigner("test-key".toByteArray())
    private val net = CapturingNetService()
    private val secureStore = MapSecureStore()
    private val sandbox = FakeFlatSandbox()
    private val host = FakeHostServices(net = net, secureStore = secureStore, sandbox = sandbox)
    private val now = mutableListOf(1_700_000_100_000L)
    private val server = IsolatedFacadeServer(host, signer, pluginId, admittedUid) { now.last() }

    private fun signedStamp(
        grants: Set<String> = setOf("network.api.example.test"),
        expiresAt: Long = 1_700_000_300_000L,
    ): AuthStamp {
        val unsigned = AuthStamp(
            runId = "run-1",
            commandId = "example.fetch",
            pluginId = pluginId,
            grantsUsed = grants,
            issuedAt = 1_700_000_000_000L,
            expiresAt = expiresAt,
            signature = "",
        )
        return signer.sign(unsigned)
    }

    private suspend fun callNet(stamp: AuthStamp?, url: String = "https://api.example.test/v1") =
        server.handle(
            IsolationOps.OP_NET_REQUEST,
            IsolationCodec.encodeCall(
                buildJsonObject {
                    put("method", "GET")
                    put("url", url)
                },
                stamp,
            ),
            callingUid = admittedUid,
        )

    private fun errorOf(reply: JsonObject) =
        IsolationCodec.decodeError(reply)!!

    // ── §8.2 check 1: identity first ────────────────────────────────────

    @Test
    fun identityMismatchDeniesBeforeTouchingTheHost() = runTest {
        val reply = server.handle(
            IsolationOps.OP_CLOCK_NOW,
            IsolationCodec.encodeCall(buildJsonObject { }, null),
            callingUid = admittedUid + 1,
        )
        val err = errorOf(reply)
        assertEquals("PERMISSION_DENIED", err.code)
        assertEquals("plugin.identity_mismatch", err.details["reason"]!!.jsonPrimitive.content)
        // The host facade was never reached — not even the clock.
        assertEquals(0, net.calls)
    }

    // ── net.request: §8.2 checks 2-4 + §9.2 secret resolution ───────────

    @Test
    fun netRequestWithValidStampReachesHostAndResolvesSecretTemplates() = runTest {
        secureStore.put("apiToken", "tok-123".toByteArray())
        val reply = server.handle(
            IsolationOps.OP_NET_REQUEST,
            IsolationCodec.encodeCall(
                buildJsonObject {
                    put("method", "POST")
                    put("url", "https://api.example.test/v1")
                    put(
                        "bodyB64",
                        Base64.getEncoder().encodeToString("{\"token\":\"{{secret.apiToken}}\"}".toByteArray()),
                    )
                    put("headers", buildJsonObject { put("Authorization", "Bearer {{secret.apiToken}}") })
                },
                signedStamp(),
            ),
            callingUid = admittedUid,
        )
        assertEquals(200L, reply["status"]!!.jsonPrimitive.longOrNull)
        assertEquals("net-ok", String(Base64.getDecoder().decode(reply["bodyB64"]!!.jsonPrimitive.content)))
        assertEquals("Bearer tok-123", net.lastHeaders["Authorization"])
        assertEquals("{\"token\":\"tok-123\"}", net.lastBody)
    }

    @Test
    fun netRequestWithoutStampDeniesWithStampMissing() = runTest {
        val err = errorOf(callNet(null))
        assertEquals("PERMISSION_DENIED", err.code)
        assertEquals("stamp_missing", err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, net.calls)
    }

    @Test
    fun netRequestWithForgedSignatureDenies() = runTest {
        val forged = signedStamp().copy(signature = "deadbeef")
        val err = errorOf(callNet(forged))
        assertEquals("stamp_signature_invalid", err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, net.calls)
    }

    @Test
    fun netRequestWithExpiredStampDenies() = runTest {
        val expired = signedStamp(expiresAt = 1_700_000_050_000L)
        val err = errorOf(callNet(expired))
        assertEquals("stamp_expired", err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, net.calls)
    }

    @Test
    fun netRequestOutsideGrantedScopeDenies() = runTest {
        val err = errorOf(callNet(signedStamp(grants = setOf("network.other.example.test"))))
        assertEquals("stamp_scope_mismatch", err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, net.calls)
    }

    // ── sandbox: namespaced per connection plugin ────────────────────────

    @Test
    fun sandboxWritesLandInsideThePluginNamespace() = runTest {
        val writeReply = server.handle(
            IsolationOps.OP_SANDBOX_WRITE,
            IsolationCodec.encodeCall(
                buildJsonObject {
                    put("path", "notes/a.txt")
                    put("dataB64", Base64.getEncoder().encodeToString("hello".toByteArray()))
                    put("append", false)
                },
                null,
            ),
            callingUid = admittedUid,
        )
        assertTrue(IsolationCodec.decodeError(writeReply) == null)
        // Stored under <pluginId>/ — the flat host has no other view.
        assertEquals("hello", String(sandbox.files["$pluginId/notes/a.txt"]!!))

        val readReply = server.handle(
            IsolationOps.OP_SANDBOX_READ,
            IsolationCodec.encodeCall(buildJsonObject { put("path", "notes/a.txt") }, null),
            callingUid = admittedUid,
        )
        assertEquals(
            "hello",
            String(Base64.getDecoder().decode(readReply["dataB64"]!!.jsonPrimitive.content)),
        )
    }

    @Test
    fun sandboxListReportsPluginRelativePaths() = runTest {
        sandbox.files["$pluginId/x.txt"] = ByteArray(1)
        sandbox.files["other.plugin/y.txt"] = ByteArray(1)
        val reply = server.handle(
            IsolationOps.OP_SANDBOX_LIST,
            IsolationCodec.encodeCall(buildJsonObject { put("dir", "") }, null),
            callingUid = admittedUid,
        )
        val entries = reply["entries"] as JsonArray
        assertEquals(listOf("x.txt"), entries.map { it.jsonObject["path"]!!.jsonPrimitive.content })
    }

    @Test
    fun sandboxOpWithoutHostSandboxIsUnavailable() = runTest {
        val bareHost = FakeHostServices(sandbox = null)
        val bareServer = IsolatedFacadeServer(bareHost, signer, pluginId, admittedUid)
        val err = errorOf(
            bareServer.handle(
                IsolationOps.OP_SANDBOX_READ,
                IsolationCodec.encodeCall(buildJsonObject { put("path", "a") }, null),
                callingUid = admittedUid,
            ),
        )
        assertEquals("UNAVAILABLE", err.code)
    }

    // ── secureStore / clock / memory forwarding ──────────────────────────

    @Test
    fun secureStoreRoundTripForwardsToHost() = runTest {
        server.handle(
            IsolationOps.OP_SECURE_PUT,
            IsolationCodec.encodeCall(
                buildJsonObject {
                    put("key", "k")
                    put("valueB64", Base64.getEncoder().encodeToString("v1".toByteArray()))
                },
                null,
            ),
            callingUid = admittedUid,
        )
        assertEquals("v1", secureStore.values["k"]?.decodeToString())
        val get = server.handle(
            IsolationOps.OP_SECURE_GET,
            IsolationCodec.encodeCall(buildJsonObject { put("key", "k") }, null),
            callingUid = admittedUid,
        )
        assertEquals("v1", String(Base64.getDecoder().decode(get["valueB64"]!!.jsonPrimitive.content)))
        server.handle(
            IsolationOps.OP_SECURE_REMOVE,
            IsolationCodec.encodeCall(buildJsonObject { put("key", "k") }, null),
            callingUid = admittedUid,
        )
        assertNull(secureStore.values["k"])
    }

    @Test
    fun clockNowForwardsToTheHostClock() = runTest {
        val clockHost = FakeHostServices(
            net = net, secureStore = secureStore, sandbox = sandbox, now = { 1_700_000_777_000L },
        )
        val clockServer = IsolatedFacadeServer(clockHost, signer, pluginId, admittedUid) { now.last() }
        val reply = clockServer.handle(
            IsolationOps.OP_CLOCK_NOW,
            IsolationCodec.encodeCall(buildJsonObject { }, null),
            callingUid = admittedUid,
        )
        assertEquals(1_700_000_777_000L, reply["nowMs"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun memoryResolveRefPreservesAllThreeResultKinds() = runTest {
        val memoryHost = FakeHostServices(
            memory = CannedMemory(
                facts = mapOf("prefs.theme" to JsonPrimitive("dark")),
                resolver = { ref ->
                    when (ref) {
                        "tom" -> ResolveResult.Resolved("people/tom", 0.9f)
                        "pat" -> ResolveResult.Ambiguous(listOf("people/pat-a", "people/pat-b"))
                        else -> ResolveResult.NotFound("filtered_out")
                    }
                },
            ),
        )
        val s = IsolatedFacadeServer(memoryHost, signer, pluginId, admittedUid)
        suspend fun resolve(ref: String) = s.handle(
            IsolationOps.OP_MEMORY_RESOLVE,
            IsolationCodec.encodeCall(buildJsonObject { put("ref", ref) }, null),
            admittedUid,
        )["resolved"]!!.jsonObject
        assertEquals("resolved", resolve("tom")["kind"]!!.jsonPrimitive.content)
        assertEquals("people/tom", resolve("tom")["id"]!!.jsonPrimitive.content)
        assertEquals("ambiguous", resolve("pat")["kind"]!!.jsonPrimitive.content)
        assertEquals("filtered_out", resolve("gone")["reason"]!!.jsonPrimitive.content)

        val got = s.handle(
            IsolationOps.OP_MEMORY_GET,
            IsolationCodec.encodeCall(buildJsonObject { put("path", "prefs.theme") }, null),
            admittedUid,
        )
        assertEquals("dark", got["value"]!!.jsonPrimitive.content)
    }

    // ── envelope discipline ──────────────────────────────────────────────

    @Test
    fun unknownOpIsUnavailableAndHandleNeverThrows() = runTest {
        val err = errorOf(
            server.handle("bogus.op", IsolationCodec.encodeCall(buildJsonObject { }, null), admittedUid),
        )
        assertEquals("UNAVAILABLE", err.code)
    }

    @Test
    fun codecAndServerAgreeOnTheErrorEnvelope() = runTest {
        // A denial encoded by the server must decode through the shared codec
        // with code and reason intact — the same envelope the plugin proxy
        // re-throws from.
        val err = errorOf(callNet(null))
        assertEquals(
            "stamp_missing",
            IsolationCodec.decodeError(IsolationCodec.encodeError(err.code, err.message, err.retryable, err.details))
                ?.details?.get("reason")!!.jsonPrimitive.content,
        )
    }
}
