package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.executor.UserGrantRegistry
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.HttpRequest
import com.morainet.mcos.sdk.HttpResponse
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.ResolveResult
import com.morainet.mcos.sdk.StaticUserFileGrantService
import com.morainet.mcos.sdk.UserFileGrant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    private fun loopback(
        callingUid: Int = admittedUid,
        host: HostServices = this.host,
        pluginId: String = this.pluginId,
        userGrantRegistry: UserGrantRegistry? = null,
    ): IsolationChannel {
        // Fixed clock inside the stamp TTL so the §8.2 gate judges scope, not expiry.
        val server = IsolatedFacadeServer(
            host = host,
            signer = signer,
            pluginId = pluginId,
            expectedUid = admittedUid,
            userGrantRegistry = userGrantRegistry,
        ) { 1_700_000_100_000L }
        return IsolationChannel { op, envelope -> server.handle(op, envelope, callingUid) }
    }

    @Test
    fun netForwardsThroughTheStampScopeGate() = runTest {
        secureStore.put("apiToken", "tok-9".toByteArray())
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        val response = proxy.net.request(
            HttpRequest(
                method = "POST",
                url = "https://api.example.test/v1/thing",
                body = "{{secret.apiToken}}".toByteArray(),
                headers = mapOf("Authorization" to "Bearer {{secret.apiToken}}"),
            ),
        )
        assertEquals(200, response.status)
        assertEquals("net-ok", response.bodyText)
        assertEquals("Bearer tok-9", net.lastHeaders["Authorization"])
        assertEquals("tok-9", net.lastBody)
    }

    @Test
    fun netDenialReThrowsAsMcosExceptionWithReason() = runTest {
        // A proxy without a stamp can never pass the §8.2 gate.
        val proxy = IsolatedHostServicesProxy(loopback(), null)
        val e = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking {
                proxy.net.request(HttpRequest(url = "https://api.example.test/"))
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
        proxy.secureStore.put("apiKey", "v-1".toByteArray())
        assertEquals("v-1", secureStore.values["apiKey"]?.decodeToString())
        assertEquals("v-1", proxy.secureStore.get("apiKey")?.decodeToString())
        proxy.secureStore.remove("apiKey")
        assertEquals(null, secureStore.values["apiKey"])
    }

    @Test
    fun secureStoreKeysEnumeratesAcrossTheWire() = runTest {
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())
        proxy.secureStore.put("mcp.secret.alpha", "a".toByteArray())
        proxy.secureStore.put("mcp.secret.beta", byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte()))
        assertEquals(setOf("mcp.secret.alpha", "mcp.secret.beta"), proxy.secureStore.keys())
        proxy.secureStore.remove("mcp.secret.alpha")
        assertEquals(setOf("mcp.secret.beta"), proxy.secureStore.keys())
    }

    @Test
    fun netBinaryBodyAndMultiValueHeadersSurviveTheWire() = runTest {
        // Bytes that are NOT valid UTF-8 plus repeated response headers —
        // exactly the payload shapes the item-46 byte wire must not flatten.
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte())
        val replyBytes = byteArrayOf(0x01, 0x02, 0xFF.toByte())
        val cookies = mapOf("set-cookie" to listOf("session=abc; Path=/", "tracker=xyz; Path=/"))
        net.response = HttpResponse(status = 200, headers = cookies, body = replyBytes)
        val proxy = IsolatedHostServicesProxy(loopback(), stamp())

        val response = proxy.net.request(
            HttpRequest(method = "POST", url = "https://api.example.test/upload", body = payload),
        )

        assertTrue("binary request body must cross the wire byte-identical", payload.contentEquals(net.lastRawBody))
        assertEquals(cookies, response.headers)
        assertTrue("binary response body must not be text-flattened", replyBytes.contentEquals(response.body))
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

    // ══════════════════════════════════════════════════════════════════════
    // Malformed replies — a missing field is a protocol error, never a
    // plausible-looking value (Kernel 1.0 gate, 10 §17.1: no fabricated
    // success anywhere in the tree).
    // ══════════════════════════════════════════════════════════════════════

    /** Answers every op with a fixed reply that carries no "error" envelope. */
    private fun stubbedChannel(reply: JsonObject): IsolationChannel =
        IsolationChannel { _, _ -> reply }

    @Test
    fun aReplyWithoutStatusIsUnavailableNotATransportFailure() = runTest {
        // `status = 0` is the *contract* for a failed transport (04 §6.2), so
        // defaulting a missing status to it would report a failure the host
        // never had — the opposite of an honest boundary.
        val proxy = IsolatedHostServicesProxy(stubbedChannel(JsonObject(emptyMap())), stamp())
        val e = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking {
                proxy.net.request(HttpRequest(url = "https://api.example.test/"))
            }
        }
        assertEquals("UNAVAILABLE", e.code)
    }

    @Test
    fun aReplyMissingNowMsIsUnavailableNotEpochZero() {
        // Epoch 0 would be a fabricated 1970 timestamp, poisoning caches,
        // expiry checks and audit entries far away from the actual fault.
        val proxy = IsolatedHostServicesProxy(stubbedChannel(JsonObject(emptyMap())), stamp())
        val e = assertThrows(McosException::class.java) { proxy.clock.now() }
        assertEquals("UNAVAILABLE", e.code)
    }

    @Test
    fun aReplyMissingEntriesIsUnavailableNotAnEmptyDirectory() = runTest {
        // An empty directory replies with a present-but-empty "entries" array,
        // so a missing field is malformed and must not read as "empty".
        val proxy = IsolatedHostServicesProxy(stubbedChannel(JsonObject(emptyMap())), stamp())
        val e = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking { proxy.sandbox.list("") }
        }
        assertEquals("UNAVAILABLE", e.code)
    }

    @Test
    fun aResolvedReferenceWithoutAnIdIsNotFoundNotAnEmptyIdentifier() = runTest {
        // An empty-string id would be a fabricated identifier: device mutex
        // keys and memory paths would all treat "" as something real.
        val reply = buildJsonObject {
            put("resolved", buildJsonObject { put("kind", "resolved") })
        }
        val proxy = IsolatedHostServicesProxy(stubbedChannel(reply), stamp())
        val result = proxy.memory.resolveRef("AC", "device")
        assertTrue(result is ResolveResult.NotFound)
    }

    // ══════════════════════════════════════════════════════════════════════
    // User-granted files across the boundary (04 §6.1). The picker runs in
    // the MAIN process — an isolated plugin has no UI — so the token is
    // minted there, and a foreign token is judged by the SAME authority the
    // in-process Stage-4 facade uses.
    // ══════════════════════════════════════════════════════════════════════

    private fun seededGrants(): Pair<StaticUserFileGrantService, ByteArray> {
        val service = StaticUserFileGrantService()
        val content = "picked-content".toByteArray()
        service.seed(
            UserFileGrant(
                ref = "content://raw/picked",
                name = "notes.txt",
                mimeType = "text/plain",
                sizeBytes = content.size.toLong(),
            ),
            content,
        )
        return service to content
    }

    @Test
    fun userFilesPickMintsATokenAndReadRedeemsIt() = runTest {
        val (grants, content) = seededGrants()
        val proxy = IsolatedHostServicesProxy(
            loopback(host = FakeHostServices(userFiles = grants), userGrantRegistry = UserGrantRegistry()),
            stamp(),
        )

        val picked = proxy.userFiles.pickForRead(listOf("text/plain"))
        assertNotNull("pick must return a grant for a seeded pick", picked)
        val grant = requireNotNull(picked)
        // The host's raw ref never crosses the boundary.
        assertTrue("expected an opaque token, got '${grant.ref}'", grant.ref.startsWith("ug-"))
        assertNotEquals("content://raw/picked", grant.ref)
        assertEquals("notes.txt", grant.name)
        assertEquals(content.toList(), proxy.userFiles.readGranted(grant.ref)?.toList())
    }

    @Test
    fun userFilesReleaseDropsTheToken() = runTest {
        val (grants, _) = seededGrants()
        val proxy = IsolatedHostServicesProxy(
            loopback(host = FakeHostServices(userFiles = grants), userGrantRegistry = UserGrantRegistry()),
            stamp(),
        )
        val picked = proxy.userFiles.pickForRead()
        assertNotNull(picked)
        val token = requireNotNull(picked).ref
        assertTrue(proxy.userFiles.releaseGranted(token))
        assertFalse(proxy.userFiles.releaseGranted(token))
    }

    @Test
    fun userFilesForeignTokenIsAHardDenial() = runTest {
        // ONE registry, two plugins: B must not redeem A's token — that is
        // the whole reason the table is shared rather than per-connection.
        val (grants, _) = seededGrants()
        val registry = UserGrantRegistry()
        val proxyA = IsolatedHostServicesProxy(
            loopback(
                host = FakeHostServices(userFiles = grants),
                pluginId = "com.example.a",
                userGrantRegistry = registry,
            ),
            stamp(),
        )
        val pickedByA = proxyA.userFiles.pickForRead()
        assertNotNull(pickedByA)
        val tokenOfA = requireNotNull(pickedByA).ref

        val proxyB = IsolatedHostServicesProxy(
            loopback(
                host = FakeHostServices(userFiles = grants),
                pluginId = "com.example.b",
                userGrantRegistry = registry,
            ),
            stamp(),
        )
        val e = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking { proxyB.userFiles.readGranted(tokenOfA) }
        }
        assertEquals("PERMISSION_DENIED", e.code)
        assertEquals("grant_not_authorized", e.details["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun userFilesIsUnavailableWithoutAPickerOrWithoutTheSharedRegistry() = runTest {
        // (a) No picker on the host.
        val noPicker = IsolatedHostServicesProxy(
            loopback(host = FakeHostServices(userFiles = null), userGrantRegistry = UserGrantRegistry()),
            stamp(),
        )
        val e1 = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking { noPicker.userFiles.pickForRead() }
        }
        assertEquals("UNAVAILABLE", e1.code)

        // (b) Picker present, but the boundary was wired without the shared
        // table: minting tokens the runtime cannot validate would be exactly
        // the fake success this project refuses.
        val (grants, _) = seededGrants()
        val noRegistry = IsolatedHostServicesProxy(
            loopback(host = FakeHostServices(userFiles = grants), userGrantRegistry = null),
            stamp(),
        )
        val e2 = assertThrows(McosException::class.java) {
            kotlinx.coroutines.runBlocking { noRegistry.userFiles.pickForRead() }
        }
        assertEquals("UNAVAILABLE", e2.code)
    }
}
