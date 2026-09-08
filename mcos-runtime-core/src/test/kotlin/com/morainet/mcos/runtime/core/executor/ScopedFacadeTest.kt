package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.TrustingAuthStampSigner
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.FileService
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.HttpRequest
import com.morainet.mcos.sdk.HttpResponse
import com.morainet.mcos.sdk.JsonService
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.SideEffectClass
import com.morainet.mcos.sdk.UiService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AuthStamp facade scope gate (08-security.md §8.2, process-isolation
 * slice 2 — confused-deputy defense). SF1–SF9 exercise
 * [StampScopedNetService] directly; SF10–SF13 run the gate through the
 * [Executor]'s Stage-4 facade, including the trust-level routing
 * (non-`BUILTIN` gated, `BUILTIN` platform code not).
 */
class ScopedFacadeTest {

    private val signer = HmacAuthStampSigner()

    private fun stamp(
        grants: Set<String>,
        issuedAt: Long = 1_000L,
        expiresAt: Long = Long.MAX_VALUE,
        sign: Boolean = true,
    ): AuthStamp {
        val raw = AuthStamp(
            runId = "run-1",
            commandId = "mcp.demo.echo",
            pluginId = "mcp.plugin.mcp.demo",
            grantsUsed = grants,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )
        return if (sign) signer.sign(raw) else raw
    }

    /** Records the last request instead of performing it. */
    private class RecordingNetService : NetService {
        var requestCount = 0
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String? = null
        /** Raw bytes as received — the binary-passthrough assertion needs the
         *  exact payload, not a lossy text view. */
        var lastRawBody: ByteArray? = null

        override suspend fun request(req: HttpRequest): HttpResponse {
            requestCount++
            lastUrl = req.url
            lastBody = req.body?.decodeToString()
            lastRawBody = req.body
            lastHeaders = req.headers
            return HttpResponse(status = 200, body = "{}".encodeToByteArray())
        }
    }

    private fun denied(e: McosException): String {
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, e.code, "gate must deny with PERMISSION_DENIED")
        return e.details["reason"]!!.jsonPrimitive.content
    }

    // ─── SF1–SF9: the gate in isolation ─────────────────────────────────

    @Test
    fun `SF1-missing stamp denies the call without touching the delegate`() = runBlocking {
        val net = RecordingNetService()
        val gate = StampScopedNetService(net, stamp = null, signer = signer)

        val e = assertFailsWith<McosException> { gate.request(HttpRequest(url = "https://api.example.com/v1")) }
        assertEquals(StampScopeGateReason.MISSING, denied(e))
        assertEquals(0, net.requestCount, "no request may leave the runtime on a denied call")
    }

    @Test
    fun `SF2-invalid stamp signature is rejected`() = runBlocking {
        val net = RecordingNetService()
        val forged = stamp(setOf("network.*"), sign = false) // unsigned = attacker-forged
        val gate = StampScopedNetService(net, forged, signer)

        val e = assertFailsWith<McosException> { gate.request(HttpRequest(url = "https://api.example.com/v1")) }
        assertEquals(StampScopeGateReason.SIGNATURE_INVALID, denied(e))
        assertEquals(0, net.requestCount)
    }

    @Test
    fun `SF3-expired stamp is rejected`() = runBlocking {
        val net = RecordingNetService()
        val expired = stamp(setOf("network.*"), issuedAt = 0, expiresAt = 5_000)
        val gate = StampScopedNetService(net, expired, signer, nowMs = { 5_000 })

        val e = assertFailsWith<McosException> { gate.request(HttpRequest(url = "https://api.example.com/v1")) }
        assertEquals(StampScopeGateReason.EXPIRED, denied(e))
        assertEquals(0, net.requestCount)
    }

    @Test
    fun `SF4-stamp without network scope is the spec-named confused-deputy denial`() = runBlocking {
        val net = RecordingNetService()
        // A read-class command's stamp: explicit permissions only, no
        // `network.*` implicit scope (DefaultPermissionKernel §8.2 semantics).
        val readStamp = stamp(setOf("files.read"))
        val gate = StampScopedNetService(net, readStamp, signer)

        val e = assertFailsWith<McosException> { gate.request(HttpRequest(url = "https://api.example.com/v1")) }
        assertEquals(StampScopeGateReason.SCOPE_MISMATCH, denied(e))
        assertEquals(0, net.requestCount)
    }

    @Test
    fun `SF5-network wildcard scope allows any host and preserves the request`() = runBlocking {
        val net = RecordingNetService()
        val gate = StampScopedNetService(net, stamp(setOf("network.*")), signer)

        val response = gate.request(
            HttpRequest(
                method = "POST",
                url = "https://api.example.com/v1?x=1",
                body = "payload".encodeToByteArray(),
                headers = mapOf("Authorization" to "Bearer t"),
            )
        )

        assertEquals(200, response.status)
        assertEquals(1, net.requestCount)
        assertEquals("https://api.example.com/v1?x=1", net.lastUrl)
        assertEquals("payload", net.lastBody)
        assertEquals("Bearer t", net.lastHeaders["Authorization"])
    }

    @Test
    fun `SF6-exact domain scope matches its host only`() = runBlocking {
        val net = RecordingNetService()
        val gate = StampScopedNetService(net, stamp(setOf("network.api.example.com")), signer)

        gate.request(HttpRequest(url = "https://api.example.com/v1"))
        assertEquals(1, net.requestCount, "exact-granted host must pass")

        val e = assertFailsWith<McosException> { gate.request(HttpRequest(url = "https://evil.example.org/exfil")) }
        assertEquals(StampScopeGateReason.SCOPE_MISMATCH, denied(e))
        assertEquals(1, net.requestCount, "the out-of-scope host must not reach the wire")
    }

    @Test
    fun `SF7-subdomain glob scope matches subdomains only`() = runBlocking {
        val net = RecordingNetService()
        val gate = StampScopedNetService(net, stamp(setOf("network.*.example.com")), signer)

        gate.request(HttpRequest(url = "https://sub.example.com/v1"))
        assertEquals(1, net.requestCount, "a label under the suffix must pass")

        // The apex itself is NOT covered by `*.example.com` (12.1 semantics).
        val apex = assertFailsWith<McosException> { gate.request(HttpRequest(url = "https://example.com/")) }
        assertEquals(StampScopeGateReason.SCOPE_MISMATCH, denied(apex))

        // Neither is a lookalike TLD.
        val lookalike = assertFailsWith<McosException> { gate.request(HttpRequest(url = "https://sub.example.org/")) }
        assertEquals(StampScopeGateReason.SCOPE_MISMATCH, denied(lookalike))
        assertEquals(1, net.requestCount)
    }

    @Test
    fun `SF8-unparseable URL is denied fail-closed`() = runBlocking {
        val net = RecordingNetService()
        val gate = StampScopedNetService(net, stamp(setOf("network.*")), signer)

        val e = assertFailsWith<McosException> { gate.request(HttpRequest(url = "not a url")) }
        assertEquals(StampScopeGateReason.INVALID_URL, denied(e))
        assertEquals(0, net.requestCount)
    }

    @Test
    fun `SF9-trusting signer waives the signature but never the scope`() = runBlocking {
        val net = RecordingNetService()
        val unsigned = stamp(setOf("network.api.example.com"), sign = false)
        val gate = StampScopedNetService(net, unsigned, TrustingAuthStampSigner())

        gate.request(HttpRequest(url = "https://api.example.com/v1"))
        assertEquals(1, net.requestCount, "signature check waived by the named signer")

        val e = assertFailsWith<McosException> { gate.request(HttpRequest(url = "https://other.example.org/")) }
        assertEquals(StampScopeGateReason.SCOPE_MISMATCH, denied(e))
    }

    @Test
    fun `SF14-binary body without templates passes the secret resolver byte-identical`() = runBlocking {
        val net = RecordingNetService()
        val resolving = SecretResolvingNetService(net, MapSecureStore(mutableMapOf()))
        // PNG magic plus bytes that are NOT valid UTF-8 — a lossy
        // decode-then-reencode round trip would corrupt the payload.
        val payload = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte(),
        )

        resolving.request(HttpRequest(method = "POST", url = "https://api.example.com/upload", body = payload))

        assertEquals(1, net.requestCount)
        assertTrue(
            payload.contentEquals(net.lastRawBody),
            "template-free binary bodies must reach the delegate byte-identical",
        )
    }

    @Test
    fun `SF15-multi-value response headers survive the decorator stack`() = runBlocking {
        val cookieHeaders = mapOf("set-cookie" to listOf("session=abc; Path=/", "tracker=xyz; Path=/"))
        val net = object : NetService {
            override suspend fun request(req: HttpRequest): HttpResponse =
                HttpResponse(status = 200, headers = cookieHeaders, body = ByteArray(0))
        }
        val gate = StampScopedNetService(net, stamp(setOf("network.api.example.com")), signer)

        val response = gate.request(HttpRequest(url = "https://api.example.com/v1"))

        assertEquals(cookieHeaders, response.headers, "repeated headers must stay distinct values")
    }

    // ─── SF10–SF13: the gate through the Executor's Stage-4 facade ──────

    private class MapSecureStore(private val entries: MutableMap<String, ByteArray>) : SecureStore {
        override suspend fun get(key: String): ByteArray? = entries[key]
        override suspend fun put(key: String, value: ByteArray) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
        override suspend fun keys(): Set<String> = entries.keys
    }

    private class NetHostServices(
        private val netService: NetService,
        private val store: SecureStore,
    ) : HostServices {
        override val files: FileService get() = error("FileService not available in test")
        override val net: NetService get() = netService
        override val ui: UiService get() = error("UiService not available in test")
        override val secureStore: SecureStore get() = store
        override val clock: Clock get() = error("Clock not available in test")
        override val json: JsonService get() = error("JsonService not available in test")
        override val memory: MemoryFacade get() = error("MemoryFacade not available in test")
    }

    private fun registerPlugin(
        registry: CommandRegistry,
        id: String,
        commandId: String,
        sideEffectClass: SideEffectClass,
        trustLevel: TrustLevel,
        handler: CommandHandler,
    ) {
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = id, name = id, version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "Scoped facade test plugin",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.plugin.test.ScopedFacadeTest",
                commands = listOf(
                    CommandManifestEntry(
                        id = commandId,
                        version = "1.0.0",
                        title = commandId,
                        description = "Scoped facade test command",
                        sideEffectClass = sideEffectClass,
                    )
                ),
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin, trustLevel)
    }

    private fun netCallingHandler(url: String, headers: Map<String, String> = emptyMap()) =
        object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                ctx.services.net.request(HttpRequest(url = url, headers = headers))
                return CommandResult.Ok(JsonObject(emptyMap()))
            }
        }

    @Test
    fun `SF10-read-class non-BUILTIN command exfiltrating via the facade is denied and audited`() = runBlocking {
        val net = RecordingNetService()
        val auditLog = InMemoryAuditLog()
        auditLog.start()
        val registry = CommandRegistry()
        val executor = Executor(
            registry,
            NetHostServices(net, MapSecureStore(mutableMapOf())),
            // Permissive posture on purpose: even with every control named off,
            // the facade gate itself must stop the exfiltration.
            SecurityConfig.permissive().copy(auditLog = auditLog),
        )
        registerPlugin(
            registry, "third.party", "third.read", SideEffectClass.read,
            TrustLevel.SIDELOAD_DEBUG, netCallingHandler("https://evil.example.org/exfil"),
        )

        val result = executor.execute("third.read")

        val err = assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, err.code)
        assertEquals(StampScopeGateReason.SCOPE_MISMATCH, err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, net.requestCount, "the confused-deputy request never leaves the runtime")
        auditLog.flush()
        // (A `plugin.isolation_fallback` record is also present — no isolation
        // host is wired, so the command runs the best-effort in-process path
        // — which is exactly the posture the facade gate defends.)
        val step = auditLog.getRuns()
            .single { run -> run.steps.any { it.code == McosErrorCode.PERMISSION_DENIED.name } }
            .steps
            .single { it.commandId == "third.read" }
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, step.code, "the denial lands in the Stage-10 audit")
    }

    @Test
    fun `SF11-network-class non-BUILTIN command with a signed network scope passes the gate`() = runBlocking {
        val net = RecordingNetService()
        val registry = CommandRegistry()
        val executor = Executor(
            registry,
            NetHostServices(net, MapSecureStore(mutableMapOf())),
            SecurityConfig.permissive().copy(signer = signer),
        )
        registerPlugin(
            registry, "third.party", "third.net", SideEffectClass.network,
            TrustLevel.MARKETPLACE_VERIFIED, netCallingHandler("https://api.weather.example/feed"),
        )

        val auth = signer.sign(
            AuthStamp(
                runId = "", // bound by the Executor
                commandId = "third.net",
                pluginId = "third.party",
                grantsUsed = setOf("network.*"),
                issuedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 60_000,
            )
        )
        val result = executor.execute("third.net", auth = auth)

        assertIs<CommandResult.Ok>(result)
        assertEquals(1, net.requestCount)
        assertEquals("https://api.weather.example/feed", net.lastUrl)
    }

    @Test
    fun `SF12-BUILTIN read-class command keeps the ungated platform posture`() = runBlocking {
        val net = RecordingNetService()
        val registry = CommandRegistry()
        val executor = Executor(
            registry,
            NetHostServices(net, MapSecureStore(mutableMapOf())),
            SecurityConfig.permissive(),
        )
        registerPlugin(
            registry, "mcos.plugin.system", "sys.read", SideEffectClass.read,
            TrustLevel.BUILTIN, netCallingHandler("https://api.example.com/ok"),
        )

        val result = executor.execute("sys.read")

        assertIs<CommandResult.Ok>(result)
        assertEquals(1, net.requestCount, "§8.2 governs plugin-process calls; BUILTIN is platform code (§7.2)")
    }

    @Test
    fun `SF13-gate composes with secret resolution on the non-BUILTIN path`() = runBlocking {
        val net = RecordingNetService()
        val store = MapSecureStore(mutableMapOf("token" to "top-secret".encodeToByteArray()))
        val registry = CommandRegistry()
        val executor = Executor(
            registry,
            NetHostServices(net, store),
            SecurityConfig.permissive().copy(signer = signer),
        )
        registerPlugin(
            registry, "third.party", "third.secret", SideEffectClass.network,
            TrustLevel.SIDELOAD_DEBUG,
            netCallingHandler("https://api.example.com/v1", mapOf("Authorization" to "Bearer {{secret.token}}")),
        )

        val auth = signer.sign(
            AuthStamp(
                runId = "",
                commandId = "third.secret",
                pluginId = "third.party",
                grantsUsed = setOf("network.api.example.com"),
                issuedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 60_000,
            )
        )
        val result = executor.execute("third.secret", auth = auth)

        assertIs<CommandResult.Ok>(result)
        assertEquals(1, net.requestCount)
        assertEquals("Bearer top-secret", net.lastHeaders["Authorization"], "inner §9.2 resolution still runs")
        assertNull(net.lastBody)
    }

    // ─── SF16–SF24: user-file grant scoping (04-plugin-sdk.md 6.1) ──────

    /** Wraps the static reference impl and counts delegate touches. */
    private class CountingGrantService : com.morainet.mcos.sdk.UserFileGrantService {
        val delegate = com.morainet.mcos.sdk.StaticUserFileGrantService()
        var statTouches = 0
        var readTouches = 0

        fun seed(ref: String, content: ByteArray, mime: String? = "text/plain") {
            delegate.seed(
                com.morainet.mcos.sdk.UserFileGrant(ref = ref, name = "picked.bin", mimeType = mime),
                content,
            )
        }

        override suspend fun pickForRead(mimeTypes: List<String>): com.morainet.mcos.sdk.UserFileGrant? =
            delegate.pickForRead(mimeTypes)

        override suspend fun statGranted(ref: String): com.morainet.mcos.sdk.UserFileGrant? {
            statTouches++
            return delegate.statGranted(ref)
        }

        override suspend fun readGranted(ref: String): ByteArray? {
            readTouches++
            return delegate.readGranted(ref)
        }

        override suspend fun releaseGranted(ref: String): Boolean = delegate.releaseGranted(ref)
    }

    @Test
    fun `SF16-pick mints an opaque token and preserves the grant metadata`() = runBlocking {
        val host = CountingGrantService().apply { seed("content://docs/123", "hello".toByteArray()) }
        val scoped = PluginScopedUserFileGrants(host, "some.plugin", UserGrantRegistry())

        val grant = scoped.pickForRead()!!

        assertTrue(grant.ref.startsWith("ug-"), "token must be runtime-minted: ${grant.ref}")
        assertTrue(grant.ref != "content://docs/123", "the raw ref must never cross to the plugin")
        assertEquals("picked.bin", grant.name)
    }

    @Test
    fun `SF17-a token redeems across decorator instances sharing the registry`() = runBlocking {
        // file.pick and file.read_granted are separate command executions —
        // two decorator instances (fresh per command) over one registry.
        val host = CountingGrantService().apply { seed("content://docs/123", "hello".toByteArray()) }
        val shared = UserGrantRegistry()
        val picker = PluginScopedUserFileGrants(host, "some.plugin", shared)
        val reader = PluginScopedUserFileGrants(host, "some.plugin", shared)

        val token = picker.pickForRead()!!.ref
        assertEquals("hello".toByteArray().toList(), reader.readGranted(token)!!.toList())
    }

    @Test
    fun `SF18-an unknown token resolves to null without touching the delegate`() = runBlocking {
        val host = CountingGrantService()
        val scoped = PluginScopedUserFileGrants(host, "some.plugin", UserGrantRegistry())

        assertNull(scoped.statGranted("ug-ffffffff-ffff-ffff-ffff-ffffffffffff"))
        assertNull(scoped.readGranted("ug-ffffffff-ffff-ffff-ffff-ffffffffffff"))
        assertEquals(0, host.statTouches)
        assertEquals(0, host.readTouches)
    }

    @Test
    fun `SF19-a raw content uri smuggled as a ref never reaches the delegate`() = runBlocking {
        val host = CountingGrantService().apply { seed("content://docs/123", "hello".toByteArray()) }
        val scoped = PluginScopedUserFileGrants(host, "some.plugin", UserGrantRegistry())

        // The obvious smuggling attempt: hand the host's raw ref straight
        // back. Tokens are the only currency — it must land in not-found.
        assertNull(scoped.readGranted("content://docs/123"))
        assertEquals(0, host.readTouches)
    }

    @Test
    fun `SF20-cross-plugin redemption is a hard PERMISSION_DENIED`() = runBlocking {
        val host = CountingGrantService().apply { seed("content://docs/123", "secret".toByteArray()) }
        val shared = UserGrantRegistry()
        val owner = PluginScopedUserFileGrants(host, "plugin.a", shared)
        val attacker = PluginScopedUserFileGrants(host, "plugin.b", shared)

        val token = owner.pickForRead()!!.ref
        val e = assertFailsWith<McosException> { attacker.readGranted(token) }
        assertEquals("PERMISSION_DENIED", e.code)
        assertEquals("grant_not_authorized", e.details["reason"]?.jsonPrimitive?.content)
        assertEquals(0, host.readTouches, "the delegate must not be consulted on a foreign token")
        assertEquals("secret".toByteArray().toList(), owner.readGranted(token)!!.toList())
    }

    @Test
    fun `SF21-release drops the token and a second release is false`() = runBlocking {
        val host = CountingGrantService().apply { seed("content://docs/123", "hello".toByteArray()) }
        val scoped = PluginScopedUserFileGrants(host, "some.plugin", UserGrantRegistry())

        val token = scoped.pickForRead()!!.ref
        assertTrue(scoped.releaseGranted(token))
        assertFalse(scoped.releaseGranted(token))
        assertNull(scoped.readGranted(token))
    }

    @Test
    fun `SF22-a user cancel mints nothing`() = runBlocking {
        val host = CountingGrantService()
        val registry = UserGrantRegistry()
        val scoped = PluginScopedUserFileGrants(host, "some.plugin", registry)

        assertNull(scoped.pickForRead())
        assertNull(scoped.readGranted("ug-anything"))
    }

    @Test
    fun `SF23-a host that cannot pick propagates its UNAVAILABLE`() = runBlocking {
        val host = object : com.morainet.mcos.sdk.UserFileGrantService {
            override suspend fun pickForRead(mimeTypes: List<String>): com.morainet.mcos.sdk.UserFileGrant? =
                throw McosException("UNAVAILABLE", "no picker")
            override suspend fun statGranted(ref: String) = null
            override suspend fun readGranted(ref: String): ByteArray? = null
            override suspend fun releaseGranted(ref: String) = false
        }
        val scoped = PluginScopedUserFileGrants(host, "some.plugin", UserGrantRegistry())

        val e = assertFailsWith<McosException> { scoped.pickForRead() }
        assertEquals("UNAVAILABLE", e.code)
    }

    @Test
    fun `SF24-the registry evicts a plugin's oldest grant at the per-plugin cap`() = runBlocking {
        val host = CountingGrantService()
        val cap = UserGrantRegistry.MAX_GRANTS_PER_PLUGIN
        for (i in 0 until cap + 1) {
            host.seed("content://docs/$i", i.toString().toByteArray())
        }
        val scoped = PluginScopedUserFileGrants(host, "some.plugin", UserGrantRegistry())

        val tokens = (0 until cap + 1).map { scoped.pickForRead()!!.ref }
        assertNull(scoped.readGranted(tokens.first()), "oldest grant evicted at cap")
        assertEquals(
            cap.toString().toByteArray().toList(),
            scoped.readGranted(tokens.last())!!.toList(),
            "newest grant still redeemable",
        )
    }
}
