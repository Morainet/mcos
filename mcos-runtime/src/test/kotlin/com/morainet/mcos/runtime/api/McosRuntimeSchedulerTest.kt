package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.ExecutionStatus
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.scheduler.SchedulerConfig
import com.morainet.mcos.runtime.core.scheduler.SchedulerLane
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/**
 * Facade E2E tests for the Stage-7 scheduler wiring (03-runtime.md §8):
 * lane routing is observable through [McosRuntime.schedulerMetrics], a full
 * lane surfaces a terminal `RunFailed(RATE_LIMITED)`, cancelling a queued run
 * surfaces a terminal `RunCancelled` (EventBus rule 9), and a shut-down
 * runtime rejects execution.
 */
class McosRuntimeSchedulerTest {

    private lateinit var runtime: McosRuntime
    private lateinit var registry: CommandRegistry

    /** Parks in the handler until the test opens [gate]; signals [entered]. */
    private class GatedCommand(
        val entered: CompletableDeferred<Unit>,
        val gate: CompletableDeferred<Unit>,
    ) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            entered.complete(Unit)
            gate.await()
            return CommandResult.Ok(JsonPrimitive("done"))
        }
    }

    private fun register(id: String, handler: CommandHandler) {
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = "test-plugin-$id", name = "Test Plugin $id", version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "Scheduler test plugin",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.plugin.test.TestPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = id, version = "1.0.0", title = id,
                        description = "Gated command", sideEffectClass = SideEffectClass.read,
                        inputSchema = JsonObject(emptyMap()),
                    )
                ),
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(id to handler)
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }

    private fun build(config: SchedulerConfig = SchedulerConfig()): McosRuntime =
        McosRuntime.Builder()
            .withRegistry(registry)
            .withSchedulerConfig(config)
            .build()

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
    }

    @AfterTest
    fun tearDown() {
        runtime.shutdown() // idempotent; releases the scheduler's workers
    }

    // ═══════════════════════════════════════════════════════════════
    // S1-S2: Lane routing (§8.1) observed through schedulerMetrics()
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S1-CLI source routes to the interactive lane`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        register("test.gated.cli", GatedCommand(entered, gate))
        runtime = build()

        val handle = runtime.execute(
            ExecuteRequest(source = Source.CLI, payload = Payload.DslText("test.gated.cli()"))
        )
        assertEquals(ExecutionStatus.RUNNING, handle.status)

        withTimeout(5_000) { entered.await() } // body started → in-flight on its lane
        val metrics = runtime.schedulerMetrics()
        assertEquals(1, metrics.laneInFlight[SchedulerLane.INTERACTIVE])
        assertEquals(0, metrics.laneInFlight[SchedulerLane.BACKGROUND])

        gate.complete(Unit)
    }

    @Test
    fun `S2-EVENT source routes to the background lane`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        register("test.gated.event", GatedCommand(entered, gate))
        runtime = build()

        val handle = runtime.execute(
            ExecuteRequest(source = Source.EVENT, payload = Payload.DslText("test.gated.event()"))
        )
        assertEquals(ExecutionStatus.RUNNING, handle.status)

        withTimeout(5_000) { entered.await() }
        val metrics = runtime.schedulerMetrics()
        assertEquals(1, metrics.laneInFlight[SchedulerLane.BACKGROUND])
        assertEquals(0, metrics.laneInFlight[SchedulerLane.INTERACTIVE])

        gate.complete(Unit)
    }

    // ═══════════════════════════════════════════════════════════════
    // S3-S4: Backpressure terminal events (§8.4 / rule 9)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S3-full lane surfaces terminal RunFailed RATE_LIMITED`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        register("test.gated.full", GatedCommand(entered, gate))
        // 1 worker permit + 1 queue slot: one run executes, one waits, the
        // rest are rejected — regardless of worker-pickup interleaving.
        runtime = build(SchedulerConfig(maxConcurrentInvokes = 1, laneCapacity = 1))

        val first = runtime.execute(
            ExecuteRequest(source = Source.CLI, payload = Payload.DslText("test.gated.full()"))
        )
        assertEquals(ExecutionStatus.RUNNING, first.status)
        withTimeout(5_000) { entered.await() } // worker busy → queue holds at most one more

        val handles = (1..3).map {
            runtime.execute(ExecuteRequest(source = Source.CLI, payload = Payload.DslText("test.gated.full()")))
        }
        val rejected = handles.filter { it.status == ExecutionStatus.FAILED }
        assertTrue(rejected.isNotEmpty(), "at most one of the three could be admitted; got ${handles.map { it.status }}")

        val observed = mutableListOf<RuntimeEvent>()
        val collector = launch { runtime.observe(rejected.first().runId).collect { observed.add(it) } }
        withTimeout(5_000) {
            while (observed.none { it is RuntimeEvent.RunFailed }) delay(20)
        }
        collector.cancel()

        val failed = observed.last()
        assertIs<RuntimeEvent.RunFailed>(failed)
        assertTrue(failed.error.contains("RATE_LIMITED"), "expected RATE_LIMITED in: ${failed.error}")

        gate.complete(Unit)
    }

    @Test
    fun `S4-cancel of queued run surfaces terminal RunCancelled`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        register("test.gated.cancel", GatedCommand(entered, gate))
        runtime = build(SchedulerConfig(maxConcurrentInvokes = 1, laneCapacity = 4))

        val running = runtime.execute(
            ExecuteRequest(source = Source.CLI, payload = Payload.DslText("test.gated.cancel()"))
        )
        withTimeout(5_000) { entered.await() } // holds the only permit for the whole test

        val queued = runtime.execute(
            ExecuteRequest(source = Source.CLI, payload = Payload.DslText("test.gated.cancel()"))
        )
        assertEquals(ExecutionStatus.RUNNING, queued.status) // admitted, still queued behind the gate
        runtime.cancel(queued.runId)

        val observed = mutableListOf<RuntimeEvent>()
        val collector = launch { runtime.observe(queued.runId).collect { observed.add(it) } }
        withTimeout(5_000) {
            while (observed.none { it is RuntimeEvent.RunCancelled }) delay(20)
        }
        collector.cancel()

        assertIs<RuntimeEvent.RunCancelled>(observed.last()) // rule 9: terminal event observed

        gate.complete(Unit)
    }

    // ═══════════════════════════════════════════════════════════════
    // S5: Post-shutdown admission (03 §"shutdown")
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S5-execute after shutdown fails with UNAVAILABLE`() = runBlocking<Unit> {
        register("test.echo", object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult = CommandResult.Ok(JsonPrimitive("hi"))
        })
        runtime = build()
        runtime.shutdown()

        val handle = runtime.execute(
            ExecuteRequest(source = Source.CLI, payload = Payload.DslText("test.echo()"))
        )
        assertEquals(ExecutionStatus.FAILED, handle.status)

        val observed = mutableListOf<RuntimeEvent>()
        val collector = launch { runtime.observe(handle.runId).collect { observed.add(it) } }
        withTimeout(5_000) {
            while (observed.none { it is RuntimeEvent.RunFailed }) delay(20)
        }
        collector.cancel()

        val failed = observed.lastOrNull()
        assertIs<RuntimeEvent.RunFailed>(failed)
        assertTrue(failed.error.contains("UNAVAILABLE"), "expected UNAVAILABLE in: ${failed.error}")
    }
}
