package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.ResolveResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IsolatedHostServicesProxy (item 41, slice 3a): loop the plugin-process
 * facade against a real IsolatedFacadeServer over an in-memory channel —
 * the exact byte round-trip a Binder transport will carry. Proves members
 * forward, bytes/Base64 survive, denials re-materialize as McosException
 * with the true reason, and honestly-unavailable members stay honest.
 */
class IsolatedHostServicesProxyTest {

    private val pluginId = "com.example.plugin"
    private val admittedUid = 10_101
    private val signer = HmacAuthStampSigner("proxy-test".toByteArray())
    private val net = CapturingNetService()
    private val secureStore = MapSecureStore()
    private val sandbox = FakeFlatSandbox()
    private val host = FakeHostServices(net = net, secureStore = secureStore, sandbox = sandbox)

    private fun stamp(): AuthStamp = signer.sign(
        AuthStamp(
            runId = "run-9",
            commandId = "example.fetch",
            pluginId = pluginId,
            grantsUsed = setOf("network.api.example.test"),
            issuedAt = 1_700_000_000_000L,
            expiresAt = 1_700_000_300_000L,
            signature = "",
        ),
    )

    /** In-memory transport: a correct Binder would report the admitted uid. */
    private fun loopback(callingUid: Int = admittedUid): IsolationChannel {
        // Fixed clock inside the stamp TTL so the §8.2 gate judges scope, not expiry.
        val server = IsolatedFacadeServer(host, signer, pluginId, admittedUid) { 1_700_000_100_000L }
        return IsolationChannel { op, envelope -> server.handle(op, envelope, callingUid) }
    }

    @Test
    fun netForwardsThroughTheStampScopeGate() = runTest {
        secureStore.put("apiToken", "tok-9")
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        val response = proxy.net.request(
            method = "POST",
            url = "https://api.example.test/v1/thing",
            body = "{{secret.apiToken}}",
            headers = mapOf("Authorization" to "Bearer {{secret.apiToken}}"),
        )
        assertEquals(200, response.status)
        assertEquals("net-ok", response.body)
        assertEquals("Bearer tok-9", net.lastHeaders["Authorization"])
        assertEquals("tok-9", net.lastBody)
    }

    @Test
    fun netDenialReThrowsAsMcosExceptionWithReason() = runTest {
        // A proxy without a stamp can never pass the §8.2 gate.
        val proxy = IsolatedHostServicesProxy(loopback(), null)
        val e = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking {
                proxy.net.request("GET", "https://api.example.test/")
            }
        }
        assertEquals("PERMISSION_DENIED", e.code)
        assertEquals("stamp_missing", e.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, net.calls)
    }

    @Test
    fun identityMismatchSurfacesAsIdentityReason() = runTest {
        val proxy = IsolatedHostServicesProxy(loopback(callingUid = admittedUid + 5), stamp())
        val e = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking { proxy.secureStore.get("k") }
        }
        assertEquals("PERMISSION_DENIED", e.code)
        assertEquals("plugin.identity_mismatch", e.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, net.calls)
    }

    @Test
    fun secureStoreMembersForwardLosslessly() = runTest {
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        proxy.secureStore.put("apiKey", "v-1")
        assertEquals("v-1", secureStore.values["apiKey"])
        assertEquals("v-1", proxy.secureStore.get("apiKey"))
        proxy.secureStore.remove("apiKey")
        assertEquals(null, secureStore.values["apiKey"])
    }

    @Test
    fun sandboxBytesSurviveTheBase64RoundTripInsideTheNamespace() = runTest {
        val payload = ByteArray(256) { it.toByte() } // exercise non-ASCII bytes
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        proxy.sandbox.write("bin/blob.dat", payload, append = false)
        // The host stores it namespaced, invisible to other plugins.
        assertTrue(sandbox.files.containsKey("$pluginId/bin/blob.dat"))
        assertTrue(payload.contentEquals(proxy.sandbox.read("bin/blob.dat")))

        val stat = proxy.sandbox.stat("bin/blob.dat")!!
        assertEquals("bin/blob.dat", stat.path)
        assertEquals(256L, stat.size)
    }

    @Test
    fun sandboxListReturnsPluginRelativeEntriesAndDeleteWorks() = runTest {
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        proxy.sandbox.write("a.txt", "1".toByteArray(), append = false)
        proxy.sandbox.write("b.txt", "2".toByteArray(), append = false)
        assertEquals(setOf("a.txt", "b.txt"), proxy.sandbox.list("").map { it.path }.toSet())
        assertTrue(proxy.sandbox.delete("a.txt"))
        assertEquals(null, proxy.sandbox.read("a.txt"))
    }

    @Test
    fun sandboxTempFileReservesANameInsideTheNamespace() = runTest {
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        val name = proxy.sandbox.tempFile("job", ".tmp")
        assertTrue(name.startsWith("job") && name.endsWith(".tmp"))
        assertTrue(sandbox.files.containsKey("$pluginId/$name"))
    }

    @Test
    fun clockForwardsToTheHostClock() {
        val fixed = FakeHostServices(now = { 1_700_000_555_000L })
        val server = IsolatedFacadeServer(fixed, signer, pluginId, admittedUid)
        val proxy = IsolatedHostServicesProxy(IsolationChannel { op, env -> server.handle(op, env, admittedUid) }, null)
        assertEquals(1_700_000_555_000L, proxy.clock.nowMs())
    }

    @Test
    fun memoryGetAndResolveRefForwardAllResultKinds() = runTest {
        val memoryHost = FakeHostServices(
            memory = CannedMemory(
                facts = mapOf("prefs/lang" to JsonPrimitive("zh")),
                resolver = { ref ->
                    when (ref) {
                        "tom" -> ResolveResult.Resolved("people/tom", 0.75f)
                        "pat" -> ResolveResult.Ambiguous(listOf("people/pat.a", "people/pat.b"))
                        else -> ResolveResult.NotFound("ref_unresolvable")
                    }
                },
            ),
        )
        val server = IsolatedFacadeServer(memoryHost, signer, pluginId, admittedUid)
        val proxy = IsolatedHostServicesProxy(
            IsolationChannel { op, env -> server.handle(op, env, admittedUid) },
            null,
        )
        assertEquals("zh", proxy.memory.get("prefs/lang")!!.jsonPrimitive.content)
        assertEquals(null, proxy.memory.get("missing"))

        assertEquals(ResolveResult.Resolved("people/tom", 0.75f), proxy.memory.resolveRef("tom"))
        assertEquals(ResolveResult.Ambiguous(listOf("people/pat.a", "people/pat.b")), proxy.memory.resolveRef("pat"))
        assertEquals(ResolveResult.NotFound("ref_unresolvable"), proxy.memory.resolveRef("nobody"))
    }

    @Test
    fun jsonIsServedLocallyWithoutAChannelCall() {
        var calls = 0
        val counting: IsolationChannel = IsolationChannel { _, _ -> calls++; JsonObject(emptyMap()) }
        val proxy = IsolatedHostServicesProxy(counting, null)
        assertEquals(42, proxy.json.parse("""{"n":42}""").jsonObject["n"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, calls)
    }

    @Test
    fun filesAndUiSurfaceHonestUnavailableErrors() = runTest {
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        val filesError = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking { proxy.files.list("media://images") }
        }
        assertEquals("UNAVAILABLE", filesError.code)

        val uiError = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking { proxy.ui.startActivityForResult(mapOf("action" to "PICK")) }
        }
        assertEquals("UNAVAILABLE", uiError.code)
    }

    @Test
    fun optionalCapabilitiesAreNullNotFabricated() {
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        assertEquals(null, proxy.notifications)
        assertEquals(null, proxy.media)
        assertEquals(null, proxy.deviceInfo)
        assertEquals(null, proxy.clipboard)
        assertEquals(null, proxy.haptics)
        assertEquals(null, proxy.events)
    }
}
