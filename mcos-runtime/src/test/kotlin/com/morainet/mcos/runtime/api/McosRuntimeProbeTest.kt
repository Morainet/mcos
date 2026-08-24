package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.AGENT_PROBE_AUDIT_SOURCE
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.security.permission.PermissionKernel
import com.morainet.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tests for [McosRuntime.executeProbe] — the read-only Agent probe port
 * (06-agent.md §11.3, RuntimeGateway.executeProbe contract).
 *
 * P1 fail-closed on non-read steps · P2 read batch returns full values ·
 * P3 audit source AGENT_PROBE · P4 unknown command fails the whole batch ·
 * P5 regression: regular execute records the truthful request source.
 */
class McosRuntimeProbeTest {

    private lateinit var runtime: McosRuntime
    private lateinit var registry: CommandRegistry
    private lateinit var permissions: PermissionKernel
    private val auditLog = InMemoryAuditLog()

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        permissions = DefaultPermissionKernel()
        auditLog.clear()
        // P0-C5 semantics: the writer coroutine must be started before any
        // append — hosts own start/stop of the audit log.
        auditLog.start()

        runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(permissions)
            .withMemory(MemoryStore())
            .withAuditLog(auditLog)
            .build()
    }

    // ═══════════════════════════════════════════════════════════════
    // P1: fail-closed on non-read sideEffectClass
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P1-probe rejects non-read sideEffectClass without executing`() = runBlocking {
        val executed = mutableListOf<String>()
        registerCommand("probe.write.cmd", SideEffectClass.write) { id ->
            CountingHandler(id, executed, value = buildJsonObject { put("done", true) })
        }

        val results = runtime.executeProbe(listOf(Command("probe.write.cmd")))

        assertEquals(1, results.size, "non-read batch returns a single rejection result")
        val err = results.first() as CommandResult.Err
        assertEquals("PERMISSION_DENIED", err.code, "write-class probe is denied: ${err.message}")
        assertTrue(err.message.contains("only read steps"), "message should explain the read-only rule: ${err.message}")
        assertTrue(executed.isEmpty(), "no handler may run when the batch is rejected")
    }

    @Test
    fun `P1b-mixed batch with any non-read step is rejected wholesale`() = runBlocking {
        val executed = mutableListOf<String>()
        registerCommand("probe.read.ok", SideEffectClass.read) { id ->
            CountingHandler(id, executed, value = buildJsonObject { put("count", 1) })
        }
        registerCommand("probe.destructive.cmd", SideEffectClass.destructive) { id ->
            CountingHandler(id, executed, value = buildJsonObject { put("deleted", true) })
        }

        val results = runtime.executeProbe(
            listOf(Command("probe.read.ok"), Command("probe.destructive.cmd"))
        )

        assertEquals(1, results.size, "batch is rejected as a unit, not partially executed")
        val err = results.first() as CommandResult.Err
        assertEquals("PERMISSION_DENIED", err.code)
        assertTrue(err.message.contains("probe.destructive.cmd"), "rejection names the offending step")
        assertTrue(executed.isEmpty(), "even the valid read step must not run (pre-flight, fail-closed)")
    }

    // ═══════════════════════════════════════════════════════════════
    // P2: read batch executes and returns full observation values
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P2-read batch executes sequentially and returns full Ok values`() = runBlocking {
        registerCommand("photo.search", SideEffectClass.read) { id ->
            CountingHandler(id, mutableListOf(), value = buildJsonObject { put("count", 47) })
        }
        registerCommand("contacts.find", SideEffectClass.read) { id ->
            CountingHandler(id, mutableListOf(), value = buildJsonObject { put("name", "Tom") })
        }

        val results = runtime.executeProbe(
            listOf(
                Command("photo.search", buildJsonObject { put("query", "today") }),
                Command("contacts.find", buildJsonObject { put("name", "Tom") })
            )
        )

        assertEquals(2, results.size, "both read steps should run: $results")
        val first = results[0] as CommandResult.Ok
        assertEquals(47, first.value.jsonObject["count"]?.jsonPrimitive?.int, "probe must return the full Ok.value payload (observation)")
        val second = results[1] as CommandResult.Ok
        assertEquals("Tom", second.value.jsonObject["name"]?.jsonPrimitive?.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // P3: probe invocations audit with source AGENT_PROBE
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P3-probe audit records carry source AGENT_PROBE`() = runBlocking {
        registerCommand("probe.read.audit", SideEffectClass.read) { id ->
            CountingHandler(id, mutableListOf(), value = buildJsonObject { put("ok", true) })
        }

        runtime.executeProbe(listOf(Command("probe.read.audit")))
        auditLog.flush()

        val records = auditLog.getRecent(10)
        assertTrue(records.isNotEmpty(), "probe invocation must be audited (Stage 10)")
        assertEquals(AGENT_PROBE_AUDIT_SOURCE, records.first().source,
            "probe audit source must be AGENT_PROBE, got '${records.first().source}'")
    }

    // ═══════════════════════════════════════════════════════════════
    // P4: unknown command fails the whole batch
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P4-unknown command in batch is rejected without executing anything`() = runBlocking {
        val executed = mutableListOf<String>()
        registerCommand("probe.read.valid", SideEffectClass.read) { id ->
            CountingHandler(id, executed, value = buildJsonObject { put("ok", true) })
        }

        val results = runtime.executeProbe(
            listOf(Command("probe.read.valid"), Command("no.such.command"))
        )

        assertEquals(1, results.size)
        val err = results.first() as CommandResult.Err
        assertEquals("UNKNOWN_COMMAND", err.code)
        assertTrue(err.message.contains("no.such.command"), "rejection names the unknown step")
        assertTrue(executed.isEmpty(), "fail-closed pre-flight: nothing runs")
    }

    // ═══════════════════════════════════════════════════════════════
    // P5: regression — regular execute records the truthful request source
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P5-DSL execute via Source-CHAT audits with source CHAT`() = runBlocking {
        registerCommand("probe.read.chat", SideEffectClass.read)

        val handle = runtime.execute(
            ExecuteRequest(
                source = Source.CHAT,
                payload = Payload.DslText("probe.read.chat()")
            )
        )

        // Wait for the async run to reach Stage 10.
        withTimeout(5_000) {
            while (true) {
                val terminal = runtime.observe(handle.runId)
                var done = false
                terminal.collect { ev ->
                    if (ev is com.morainet.mcos.runtime.core.api.RuntimeEvent.RunSucceeded ||
                        ev is com.morainet.mcos.runtime.core.api.RuntimeEvent.RunFailed
                    ) done = true
                }
                if (done) break
            }
        }
        auditLog.flush()

        val record = auditLog.getRecent(5).firstOrNull()
        assertNotNull(record, "DSL execution must be audited")
        assertEquals("CHAT", record.source,
            "audit source should mirror the request source, got '${record.source}'")
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun registerCommand(
        id: String,
        sideEffectClass: SideEffectClass,
        handlerFactory: (String) -> CommandHandler = { cid -> EchoCommandHandler(cid) },
    ) {
        val plugin = object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "test-plugin-$id",
                name = "Test Plugin",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Test plugin for $id",
                provider = ProviderInfo("TestOrg", "https://example.com"),
                entry = "com.morainet.mcos.plugin.test.TestPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = id,
                        version = "1.0",
                        title = id,
                        description = "Test command $id",
                        sideEffectClass = sideEffectClass,
                        inputSchema = JsonObject(emptyMap())
                    )
                )
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(id to handlerFactory(id))
            override suspend fun onLoad(services: HostServices) {
                permissions.grant(manifest.id, "mcos:all")
            }
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }
}

/** Read probe handler that records invocations and returns a fixed value payload. */
private class CountingHandler(
    private val id: String,
    private val executed: MutableList<String>,
    private val value: JsonObject,
) : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        executed.add(id)
        return CommandResult.Ok(value = value)
    }
}
