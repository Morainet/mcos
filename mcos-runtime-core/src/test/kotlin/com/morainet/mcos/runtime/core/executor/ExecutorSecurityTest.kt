package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.CrashQuarantine
import com.morainet.mcos.security.NoopCrashQuarantine
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.SlidingWindowCrashQuarantine
import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.FileService
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.JsonService
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.NetResponse
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.SideEffectClass
import com.morainet.mcos.sdk.UiService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for the A-1 secret template resolution (§9.2) and
 * A-2 crash-loop quarantine (§15.3) wired through [Executor].
 */
class ExecutorSecurityTest {

    private fun newExecutor(
        auditLog: AuditLog = InMemoryAuditLog(),
        quarantine: CrashQuarantine = NoopCrashQuarantine,
    ): Triple<Executor, CommandRegistry, RecordingNetService> {
        val net = RecordingNetService()
        val store = MapSecureStore(mutableMapOf("token" to "top-secret"))
        val registry = CommandRegistry()
        val executor = Executor(
            registry,
            NetHostServices(net, store),
            security = SecurityConfig.permissive().copy(
                auditLog = auditLog,
                quarantine = quarantine,
            ),
        )
        return Triple(executor, registry, net)
    }

    // ─── A-1: Secret template resolution ────────────────────────────────

    @Test
    fun `secret template in outbound header is resolved from scoped store`() = runBlocking {
        val (executor, registry, net) = newExecutor()
        registry.register(createPlugin("sec.plugin", "net.auth") { ctx ->
            ctx.services.net.request(
                method = "GET",
                url = "https://api.example.com/v1",
                headers = mapOf("Authorization" to "Bearer {{secret.token}}"),
            )
            CommandResult.Ok(JsonPrimitive("done"))
        })

        val r = executor.execute("net.auth")
        assertTrue(r is CommandResult.Ok)
        assertEquals("Bearer top-secret", net.lastHeaders["Authorization"])
        assertEquals(1, net.requestCount)
    }

    @Test
    fun `secret template in request body is resolved`() = runBlocking {
        val (executor, registry, net) = newExecutor()
        registry.register(createPlugin("sec.plugin", "net.post") { ctx ->
            ctx.services.net.request(
                method = "POST",
                url = "https://api.example.com/v1",
                body = """{"token":"{{secret.token}}"}""",
            )
            CommandResult.Ok(JsonPrimitive("done"))
        })

        executor.execute("net.post")
        assertEquals("""{"token":"top-secret"}""", net.lastBody)
    }

    @Test
    fun `args keep the template form - resolved value is not written back`() = runBlocking {
        val (executor, registry, net) = newExecutor()
        var seenArgs: JsonElement? = null
        registry.register(createPlugin("sec.plugin", "echo.auth") { ctx ->
            seenArgs = ctx.args
            ctx.services.net.request(
                method = "GET",
                url = "https://api.example.com",
                headers = mapOf("Authorization" to "Bearer {{secret.token}}"),
            )
            CommandResult.Ok(JsonPrimitive("done"))
        })

        val args = buildJsonObject { put("token", JsonPrimitive("{{secret.token}}")) }
        executor.execute("echo.auth", args)
        // the value reached the outbound request...
        assertEquals("Bearer top-secret", net.lastHeaders["Authorization"])
        // ...but ExecutionContext.args were never rewritten.
        assertEquals("{{secret.token}}", seenArgs!!.jsonObject["token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown secret key leaves the template inert`() = runBlocking {
        val (executor, registry, net) = newExecutor()
        registry.register(createPlugin("sec.plugin", "net.missing") { ctx ->
            ctx.services.net.request(
                method = "GET",
                url = "https://api.example.com",
                headers = mapOf("Authorization" to "Bearer {{secret.missingKey}}"),
            )
            CommandResult.Ok(JsonPrimitive("done"))
        })

        executor.execute("net.missing")
        // store has no entry for this key — no value leaks, template stays inert.
        assertEquals("Bearer {{secret.missingKey}}", net.lastHeaders["Authorization"])
    }

    // ─── A-2: Crash-loop quarantine ─────────────────────────────────────

    private val crashingHandler = object : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult =
            throw IllegalStateException("boom")
    }

    @Test
    fun `plugin quarantined after three crashes in window`() = runBlocking {
        val auditLog = InMemoryAuditLog()
        auditLog.start()
        val quarantine = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 3)
        val (executor, registry, _) = newExecutor(auditLog, quarantine)
        registry.register(createPlugin("sec.plugin", "sec.crash", crashingHandler))

        repeat(2) {
            val r = executor.execute("sec.crash")
            assertIs<CommandResult.Err>(r)
            assertEquals(McosErrorCode.PLUGIN_ERROR.name, r.code)
        }
        val third = executor.execute("sec.crash")
        assertIs<CommandResult.Err>(third)
        assertEquals(McosErrorCode.PLUGIN_ERROR.name, third.code)

        assertTrue(quarantine.isQuarantined("sec.plugin"))
        assertTrue(quarantine.quarantinedPlugins().contains("sec.plugin"))
        // commands were unregistered from the registry — the plugin refuses to load
        val after = executor.execute("sec.crash")
        assertIs<CommandResult.Err>(after)
        assertEquals(McosErrorCode.UNKNOWN_COMMAND.name, after.code)
        // an audit event records the quarantine
        auditLog.flush()
        val runs = auditLog.getRuns()
        assertTrue(runs.any { run -> run.steps.any { it.code == "plugin.quarantined" } })
    }

    @Test
    fun `successful invoke resets the crash counter`() = runBlocking {
        val quarantine = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 3)
        val (executor, registry, _) = newExecutor(InMemoryAuditLog(), quarantine)
        var calls = 0
        registry.register(createPlugin("sec.plugin", "sec.flaky") {
            calls++
            if (calls < 3) throw IllegalStateException("boom")
            CommandResult.Ok(JsonPrimitive("ok"))
        })

        // crash, crash, ok (resets), crash, crash → still under threshold
        executor.execute("sec.flaky")
        executor.execute("sec.flaky")
        assertTrue(executor.execute("sec.flaky") is CommandResult.Ok)
        executor.execute("sec.flaky")
        executor.execute("sec.flaky")
        assertFalse(quarantine.isQuarantined("sec.plugin"))
    }

    @Test
    fun `re-registered quarantined plugin is refused with UNAVAILABLE`() = runBlocking {
        val quarantine = SlidingWindowCrashQuarantine(windowMs = 60_000, threshold = 2)
        val (executor, registry, _) = newExecutor(InMemoryAuditLog(), quarantine)
        registry.register(createPlugin("sec.plugin", "sec.crash", crashingHandler))

        executor.execute("sec.crash")
        val second = executor.execute("sec.crash")
        assertIs<CommandResult.Err>(second)
        assertTrue(quarantine.isQuarantined("sec.plugin"))

        // quarantine is lifted only by explicit re-enable: even after the
        // commands are re-registered, execution is refused (§15.3).
        registry.register(createPlugin("sec.plugin", "sec.crash", crashingHandler))
        val re = executor.execute("sec.crash")
        assertIs<CommandResult.Err>(re)
        assertEquals(McosErrorCode.UNAVAILABLE.name, re.code)
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun createPlugin(
        id: String,
        commandId: String,
        handler: CommandHandler,
    ): McosPlugin = createPlugin(id, commandId, handler::invoke)

    private fun createPlugin(
        id: String,
        commandId: String,
        handler: suspend (ExecutionContext) -> CommandResult,
    ): McosPlugin {
        val h = object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult = handler(ctx)
        }
        return object : McosPlugin {
            override val manifest = PluginManifest(
                id = id,
                name = id,
                version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "Security test plugin",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.test.SecurityTest",
                commands = listOf(
                    CommandManifestEntry(
                        id = commandId,
                        version = "1.0.0",
                        title = commandId,
                        description = "Security test command",
                        sideEffectClass = SideEffectClass.read,
                    )
                ),
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to h)
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
    }

    /** Records the last outbound request instead of performing it. */
    private class RecordingNetService : NetService {
        var lastMethod: String = ""
        var lastUrl: String = ""
        var lastBody: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var requestCount: Int = 0

        override suspend fun request(
            method: String,
            url: String,
            body: String?,
            headers: Map<String, String>,
        ): NetResponse {
            lastMethod = method
            lastUrl = url
            lastBody = body
            lastHeaders = headers
            requestCount++
            return NetResponse(status = 200, body = "{}")
        }
    }

    /** In-memory SecureStore backed by a mutable map. */
    private class MapSecureStore(
        private val entries: MutableMap<String, String> = mutableMapOf(),
    ) : SecureStore {
        override suspend fun get(key: String): String? = entries[key]
        override suspend fun put(key: String, value: String) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
    }

    /** Minimal HostServices stub backed by the injected net/secureStore. */
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
}
