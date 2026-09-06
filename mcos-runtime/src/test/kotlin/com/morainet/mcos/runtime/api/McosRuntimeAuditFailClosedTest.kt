package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Facade wiring of the fail-closed Stage-10 audit knob ([03-runtime.md
 * §13.3]): [McosRuntime.Builder.withAuditFailClosed] must reach the default
 * executor's per-command Stage-10 write. A run whose audit record cannot be
 * durably written (an audit sink that is not draining) fails with `INTERNAL`;
 * the default best-effort posture leaves the same run a success.
 */
class McosRuntimeAuditFailClosedTest {

    private lateinit var runtime: McosRuntime
    private lateinit var registry: CommandRegistry

    private fun registerEcho() {
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = "audit-fc-plugin",
                name = "Audit Fail-Closed Plugin",
                version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "Echoes Ok",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.plugin.test.AuditFc",
                commands = listOf(
                    CommandManifestEntry(
                        id = "auditfc.echo",
                        version = "1.0.0",
                        title = "echo",
                        description = "Returns Ok",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = JsonObject(emptyMap()),
                    )
                ),
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(
                "auditfc.echo" to object : CommandHandler {
                    override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                        CommandResult.Ok(JsonPrimitive("ok"))
                }
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }

    /**
     * An audit log that was never [AuditLog.start]ed: records it "accepts"
     * would be silently lost, so its fail-closed answer is false.
     */
    private fun build(auditFailClosed: Boolean = false): McosRuntime =
        McosRuntime.Builder()
            .withRegistry(registry)
            .withAuditLog(InMemoryAuditLog())
            .withAuditFailClosed(auditFailClosed)
            .build()

    private suspend fun CoroutineScope.awaitTerminal(runId: String): RuntimeEvent {
        val observed = mutableListOf<RuntimeEvent>()
        val collector = launch { runtime.observe(runId).collect { observed.add(it) } }
        try {
            withTimeout(5_000) {
                while (observed.none { it is RuntimeEvent.RunFailed || it is RuntimeEvent.RunSucceeded || it is RuntimeEvent.RunCancelled }) delay(20)
            }
        } finally {
            collector.cancel()
        }
        return observed.last()
    }

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
    }

    @AfterTest
    fun tearDown() {
        runtime.shutdown() // idempotent; releases the scheduler's workers
    }

    @Test
    fun `auditFailClosed fails a run whose audit record cannot be written`() = runBlocking<Unit> {
        registerEcho()
        runtime = build(auditFailClosed = true)

        val handle = runtime.execute(
            ExecuteRequest(source = Source.CLI, payload = Payload.DslText("auditfc.echo()"))
        )
        val terminal = awaitTerminal(handle.runId)
        assertIs<RuntimeEvent.RunFailed>(terminal)
        assertTrue(terminal.error.contains("auditFailClosed"), "error: ${terminal.error}")
    }

    @Test
    fun `default best-effort audit lets the same run succeed`() = runBlocking<Unit> {
        registerEcho()
        runtime = build(auditFailClosed = false)

        val handle = runtime.execute(
            ExecuteRequest(source = Source.CLI, payload = Payload.DslText("auditfc.echo()"))
        )
        val terminal = awaitTerminal(handle.runId)
        assertIs<RuntimeEvent.RunSucceeded>(terminal)
    }
}
