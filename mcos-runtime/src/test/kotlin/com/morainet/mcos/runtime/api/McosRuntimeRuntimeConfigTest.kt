package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.config.RuntimeConfig
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.runtime.core.scheduler.SchedulerConfig
import com.morainet.mcos.security.RedactionLevel
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.security.permission.PermissionKernel
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RCF1-RCF4 — end-to-end facade wiring of the §19 [RuntimeConfig] manager
 * (03-runtime.md §19). Applying a config through [McosRuntime.applyRuntimeConfig]
 * must retune the live scheduler, emit the always-audited ConfigChanged record,
 * flip the read-through suppliers (redaction), and gate a command via the
 * Stage-3 allow-list on the very next invoke — no rebuild.
 */
class McosRuntimeRuntimeConfigTest {

    private lateinit var runtime: McosRuntime
    private lateinit var registry: CommandRegistry
    private lateinit var permissions: PermissionKernel
    private val auditLog = InMemoryAuditLog()

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        permissions = DefaultPermissionKernel()
        auditLog.clear()
        auditLog.start()
        runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(permissions)
            .withMemory(MemoryStore())
            .withAuditLog(auditLog)
            .build()
    }

    @AfterTest fun tearDown() { runtime.shutdown() }

    @Test
    fun `RCF1-applyRuntimeConfig retunes the live scheduler`() {
        val mgr = runtime.runtimeConfig()
        val applied = runtime.applyRuntimeConfig(
            RuntimeConfig(maxParallel = 8, scheduler = SchedulerConfig(maxConcurrentInvokes = 8))
        )
        assertEquals(8, applied.maxParallel)
        assertEquals(8, mgr.current().maxParallel)
        // The scheduler took the retune (its next reconfigure returns 8 as prior).
        val prev = runtime.reconfigureScheduler(SchedulerConfig(maxConcurrentInvokes = 6))
        assertEquals(8, prev.maxConcurrentInvokes)
    }

    @Test
    fun `RCF2-applyRuntimeConfig emits an always-audited ConfigChanged record`() = runBlocking {
        runtime.applyRuntimeConfig(RuntimeConfig(auditRedaction = RedactionLevel.STRICT))
        auditLog.flush() // drain the single-writer queue before reading
        val rec = auditLog.getRuns().single { it.commandId == "config.changed" }
        assertEquals("config", rec.runId)
        assertEquals("SYSTEM", rec.source)
        assertTrue(rec.ir!!.contains("STRICT"))
        Unit
    }

    @Test
    fun `RCF3-redaction level flips through the read-through supplier`() {
        val mgr = runtime.runtimeConfig()
        assertEquals(RedactionLevel.DEFAULT, mgr.redactionLevel())
        runtime.applyRuntimeConfig(RuntimeConfig(auditRedaction = RedactionLevel.STRICT))
        assertEquals(RedactionLevel.STRICT, mgr.redactionLevel())
    }

    @Test
    fun `RCF4-an allow-list flips a command to UNKNOWN_COMMAND on the next probe`() = runBlocking {
        registry.register(plugin("p.cam", "camera.scan"))
        registry.register(plugin("p.vpn", "vpn.connect"))

        // Before any allow-list: both read commands run under a probe.
        val before = runtime.executeProbe(listOf(Command("vpn.connect")))
        assertIs<CommandResult.Ok>(before.single())

        // Apply an allow-list that matches camera but not vpn (camera.scan is
        // registered, so the list is NOT deferred).
        runtime.applyRuntimeConfig(RuntimeConfig(enterpriseAllowlist = listOf("camera.*")))

        val after = runtime.executeProbe(listOf(Command("vpn.connect")))
        val err = after.single()
        assertIs<CommandResult.Err>(err)
        assertEquals("UNKNOWN_COMMAND", err.code)
        Unit
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun plugin(pluginId: String, vararg commandIds: String): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = pluginId, name = pluginId, version = "1.0.0",
            minRuntimeVersion = "0.1.0", description = "test",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = commandIds.map {
                CommandManifestEntry(
                    id = it, version = "1.0.0", title = it, description = it,
                    sideEffectClass = SideEffectClass.read, inputSchema = JsonObject(emptyMap()),
                )
            },
        )
        override suspend fun onLoad(services: HostServices) { permissions.grant(manifest.id, "mcos:all") }
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = commandIds.associateWith {
            object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext) = CommandResult.Ok(JsonPrimitive("ok"))
            }
        }
    }
}
