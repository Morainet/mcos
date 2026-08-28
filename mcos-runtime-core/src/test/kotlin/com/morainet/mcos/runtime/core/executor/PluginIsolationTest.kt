package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Process-isolation dispatch tests (08-security.md §7.2/§8.1). Slice 1 covers
 * the pure-JVM seam: the [IsolationPolicy] decision, trust-level routing to an
 * injected [IsolationHost], and the best-effort in-process fallback (audited)
 * when no host is wired. The Android bound-service impl is a later slice.
 */
class PluginIsolationTest {

    private val registry = CommandRegistry()
    private val services: HostServices = StubHostServices(MemoryStore())

    @AfterTest
    fun tearDown() = registry.clear().let {}

    /** Records the last [IsolatedInvocation] and returns a canned result. */
    private class RecordingIsolationHost(
        private val result: CommandResult = CommandResult.Ok(JsonPrimitive("isolated")),
        private val throwOnInvoke: Boolean = false,
    ) : IsolationHost {
        var lastRequest: IsolatedInvocation? = null
        var invokeCount = 0
        override suspend fun invoke(request: IsolatedInvocation): CommandResult {
            invokeCount++
            lastRequest = request
            if (throwOnInvoke) throw RuntimeException("remote plugin crashed")
            return result
        }
    }

    private fun executor(
        host: IsolationHost? = null,
        auditLog: InMemoryAuditLog = InMemoryAuditLog(),
    ): Executor = Executor(
        registry,
        services,
        SecurityConfig.permissive().copy(auditLog = auditLog),
        isolationHost = host,
    )

    private fun registerPlugin(
        id: String,
        commandId: String,
        trustLevel: TrustLevel,
        handler: CommandHandler,
    ) {
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = id, name = id, version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "Isolation test plugin",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.plugin.test.TestPlugin",
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
            override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
        }
        registry.register(plugin, trustLevel)
    }

    private fun handler(onRun: () -> Unit = {}, response: String = "in-process") =
        object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                onRun()
                return CommandResult.Ok(JsonPrimitive(response))
            }
        }

    // ─── IP1: policy decision ───────────────────────────────────────────

    @Test
    fun `IP1-policy maps only BUILTIN to in-process`() {
        assertEquals(IsolationMode.IN_PROCESS, IsolationPolicy.modeFor(TrustLevel.BUILTIN))
        assertEquals(IsolationMode.ISOLATED, IsolationPolicy.modeFor(TrustLevel.MARKETPLACE_VERIFIED))
        assertEquals(IsolationMode.ISOLATED, IsolationPolicy.modeFor(TrustLevel.SIDELOAD_DEBUG))
        assertEquals(IsolationMode.ISOLATED, IsolationPolicy.modeFor(TrustLevel.UNTRUSTED))
    }

    // ─── IP2: BUILTIN always in-process, never routed to a host ──────────

    @Test
    fun `IP2-BUILTIN runs in-process even when an isolation host is wired`() = runBlocking {
        val host = RecordingIsolationHost()
        var ran = false
        registerPlugin("test.builtin", "b.cmd", TrustLevel.BUILTIN, handler(onRun = { ran = true }))

        val result = executor(host = host).execute("b.cmd")

        assertIs<CommandResult.Ok>(result)
        assertEquals("in-process", result.value.let { (it as JsonPrimitive).content })
        assertTrue(ran, "BUILTIN handler must run in-process")
        assertEquals(0, host.invokeCount, "BUILTIN must never route to the isolation host")
    }

    // ─── IP3: non-BUILTIN routes to the isolation host ───────────────────

    @Test
    fun `IP3-MARKETPLACE_VERIFIED routes to the isolation host with a marshalable request`() = runBlocking {
        val host = RecordingIsolationHost()
        var ran = false
        registerPlugin("test.market", "m.cmd", TrustLevel.MARKETPLACE_VERIFIED, handler(onRun = { ran = true }))

        val args = buildJsonObject { put("q", JsonPrimitive("hello")) }
        val result = executor(host = host).execute("m.cmd", args, source = "CHAT")

        assertIs<CommandResult.Ok>(result)
        assertEquals("isolated", (result.value as JsonPrimitive).content)
        assertFalse(ran, "isolated dispatch must not run the in-process handler")
        assertEquals(1, host.invokeCount)
        val req = assertNotNull(host.lastRequest)
        assertEquals("test.market", req.pluginId)
        assertEquals("1.0.0", req.pluginVersion)
        assertEquals("m.cmd", req.commandId)
        assertEquals(args, req.args)
        assertEquals("CHAT", req.source)
        assertTrue(req.runId.isNotBlank())
    }

    @Test
    fun `IP4-SIDELOAD_DEBUG also routes to the isolation host`() = runBlocking {
        val host = RecordingIsolationHost()
        registerPlugin("test.side", "s.cmd", TrustLevel.SIDELOAD_DEBUG, handler())

        executor(host = host).execute("s.cmd")

        assertEquals(1, host.invokeCount)
        assertEquals("test.side", host.lastRequest?.pluginId)
    }

    // ─── IP5: isolated host result propagation ───────────────────────────

    @Test
    fun `IP5-isolation host error result propagates unchanged`() = runBlocking {
        val err = CommandResult.Err(code = McosErrorCode.UNAVAILABLE.name, message = "server down", retryable = true)
        val host = RecordingIsolationHost(result = err)
        registerPlugin("test.market", "m.err", TrustLevel.MARKETPLACE_VERIFIED, handler())

        val result = executor(host = host).execute("m.err")

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.UNAVAILABLE.name, result.code)
        assertTrue(result.retryable)
    }

    @Test
    fun `IP6-isolation host that throws maps to PLUGIN_ERROR`() = runBlocking {
        val host = RecordingIsolationHost(throwOnInvoke = true)
        registerPlugin("test.market", "m.boom", TrustLevel.MARKETPLACE_VERIFIED, handler())

        val result = executor(host = host).execute("m.boom")

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PLUGIN_ERROR.name, result.code)
    }

    // ─── IP7: best-effort in-process fallback (audited) when no host ─────

    @Test
    fun `IP7-non-BUILTIN without a host runs in-process and audits the fallback once`() = runBlocking {
        val auditLog = InMemoryAuditLog()
        auditLog.start()
        var runCount = 0
        registerPlugin("test.market", "m.fb", TrustLevel.MARKETPLACE_VERIFIED, handler(onRun = { runCount++ }))
        val exec = executor(host = null, auditLog = auditLog)

        val r1 = exec.execute("m.fb")
        val r2 = exec.execute("m.fb")

        assertIs<CommandResult.Ok>(r1)
        assertIs<CommandResult.Ok>(r2)
        assertEquals(2, runCount, "fallback must still run the handler in-process")

        auditLog.flush()
        val fallbacks = auditLog.getRuns().filter { run ->
            run.steps.any { it.code == "plugin.isolation_fallback" }
        }
        assertEquals(1, fallbacks.size, "fallback audit must fire once per plugin, not per invocation")
        assertEquals("test.market", fallbacks.single().steps.single().pluginId)
    }

    @Test
    fun `IP8-BUILTIN without a host never audits an isolation fallback`() = runBlocking {
        val auditLog = InMemoryAuditLog()
        auditLog.start()
        registerPlugin("test.builtin", "b.fb", TrustLevel.BUILTIN, handler())
        executor(host = null, auditLog = auditLog).execute("b.fb")

        auditLog.flush()
        assertTrue(auditLog.getRuns().none { run -> run.steps.any { it.code == "plugin.isolation_fallback" } })
    }
}
