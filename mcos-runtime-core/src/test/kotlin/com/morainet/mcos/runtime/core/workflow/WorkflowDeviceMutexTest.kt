package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.SecurityConfig
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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §8.5 wiring E2E (03 §8.5 / 05 §5.0): a command step's device set — its
 * literal `requiresDevices` declaration plus ids resolved from the command's
 * `x-mcos-semantic: "device"` schema fields — drives
 * [com.morainet.mcos.runtime.core.scheduler.DeviceMutexMap] around the
 * step's dispatch, released at step end, with same-run concurrent
 * (parallel-branch) acquisition rejected as `CONFLICT`/`device_locked`.
 */
class WorkflowDeviceMutexTest {

    private lateinit var registry: CommandRegistry
    private lateinit var engine: WorkflowEngine

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        val executor = Executor(
            registry,
            WorkflowEngineTest.StubHostServices(),
            SecurityConfig.permissive()
        )
        engine = WorkflowEngine(executor)
    }

    @AfterTest
    fun tearDown() {
        registry.clear()
    }

    /** A handler that signals entry then parks on [gate]. */
    private class GatedHandler(
        private val entered: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            entered.complete(Unit)
            gate.await()
            return CommandResult.Ok(JsonPrimitive("ok"))
        }
    }

    private fun registerPlugin(
        namespace: String,
        handlers: Map<String, CommandHandler>,
        inputSchemas: Map<String, JsonObject> = emptyMap(),
    ) {
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = namespace, name = namespace, version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "Test plugin",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.plugin.test.TestPlugin",
                commands = handlers.keys.map { cmdId ->
                    CommandManifestEntry(
                        id = cmdId, version = "1.0.0", title = cmdId,
                        description = "Test command: $cmdId",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = inputSchemas[cmdId] ?: JsonObject(emptyMap())
                    )
                }
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
            override fun handlers(): Map<String, CommandHandler> = handlers
        }
        registry.register(plugin)
    }

    private fun deviceSchema(vararg fields: String): JsonObject = buildJsonObject {
        putJsonObject("properties") {
            for (f in fields) {
                put(f, buildJsonObject {
                    put("type", "string")
                    put("x-mcos-semantic", "device")
                })
            }
        }
    }

    // ─── Declared requiresDevices ────────────────────────────────────────

    @Test
    fun `WD1-two runs declaring the same device serialize`() = runBlocking<Unit> {
        val enteredA = CompletableDeferred<Unit>()
        val gateA = CompletableDeferred<Unit>()
        val enteredB = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        registerPlugin(
            "dev",
            mapOf(
                "dev.a" to GatedHandler(enteredA, gateA),
                "dev.b" to GatedHandler(enteredB, gateB),
            )
        )

        val runA = async {
            engine.execute(WorkflowStep.Command("dev.a", requiresDevices = listOf("living-room")))
        }
        withTimeout(5_000) { enteredA.await() } // run A holds living-room

        val runB = async {
            engine.execute(WorkflowStep.Command("dev.b", requiresDevices = listOf("living-room")))
        }
        assertNull(withTimeoutOrNull(200) { enteredB.await() }) // parked on the device mutex

        gateA.complete(Unit)
        assertEquals(WorkflowOutcome.COMPLETED, runA.await().outcome)
        withTimeout(5_000) { enteredB.await() } // acquired after A's release
        gateB.complete(Unit)
        assertEquals(WorkflowOutcome.COMPLETED, runB.await().outcome)
    }

    @Test
    fun `WD2-distinct declared devices do not block each other`() = runBlocking<Unit> {
        val enteredA = CompletableDeferred<Unit>()
        val gateA = CompletableDeferred<Unit>()
        val enteredB = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        registerPlugin(
            "dev",
            mapOf(
                "dev.a" to GatedHandler(enteredA, gateA),
                "dev.b" to GatedHandler(enteredB, gateB),
            )
        )

        val runA = async {
            engine.execute(WorkflowStep.Command("dev.a", requiresDevices = listOf("d1")))
        }
        withTimeout(5_000) { enteredA.await() }

        val runB = async {
            engine.execute(WorkflowStep.Command("dev.b", requiresDevices = listOf("d2")))
        }
        withTimeout(2_000) { enteredB.await() } // disjoint device set → no contention

        gateA.complete(Unit)
        gateB.complete(Unit)
        assertEquals(WorkflowOutcome.COMPLETED, runA.await().outcome)
        assertEquals(WorkflowOutcome.COMPLETED, runB.await().outcome)
    }

    // ─── Declared ∪ semantic resolution ──────────────────────────────────

    @Test
    fun `WD3-union of declared and schema-semantic devices both gate the step`() =
        runBlocking<Unit> {
            val deviceArgs = JsonObject(mapOf("id" to JsonPrimitive("d2")))
            registerPlugin(
                "home",
                mapOf(
                    "home.light" to object : CommandHandler {
                        override suspend fun invoke(ctx: ExecutionContext) =
                            CommandResult.Ok(JsonPrimitive("ok"))
                    },
                ),
                inputSchemas = mapOf("home.light" to deviceSchema("id")),
            )
            // The union step: declared d1 + schema-semantic id=d2.

            // Phase 1 — a gated holder on the DECLARED d1 blocks the union step.
            val held1 = CompletableDeferred<Unit>()
            val release1 = CompletableDeferred<Unit>()
            registerPlugin("hold1", mapOf("hold1.g" to GatedHandler(held1, release1)))
            val holder1 = async {
                engine.execute(WorkflowStep.Command("hold1.g", requiresDevices = listOf("d1")))
            }
            withTimeout(5_000) { held1.await() }
            val union1 = async {
                engine.execute(
                    WorkflowStep.Command("home.light", deviceArgs, requiresDevices = listOf("d1"))
                )
            }
            assertNull(withTimeoutOrNull(200) { union1.await() }) // blocked on d1
            release1.complete(Unit)
            assertEquals(WorkflowOutcome.COMPLETED, withTimeout(5_000) { union1.await() }.outcome)
            assertEquals(WorkflowOutcome.COMPLETED, holder1.await().outcome)

            // Phase 2 — a gated holder on the SEMANTIC d2 blocks it just the
            // same (d1 is free by now, so only the schema-resolved id gates).
            val held2 = CompletableDeferred<Unit>()
            val release2 = CompletableDeferred<Unit>()
            registerPlugin("hold2", mapOf("hold2.g" to GatedHandler(held2, release2)))
            val holder2 = async {
                engine.execute(WorkflowStep.Command("hold2.g", requiresDevices = listOf("d2")))
            }
            withTimeout(5_000) { held2.await() }
            val union2 = async {
                engine.execute(
                    WorkflowStep.Command("home.light", deviceArgs, requiresDevices = listOf("d1"))
                )
            }
            assertNull(withTimeoutOrNull(200) { union2.await() }) // blocked on d2
            release2.complete(Unit)
            assertEquals(WorkflowOutcome.COMPLETED, withTimeout(5_000) { union2.await() }.outcome)
            assertEquals(WorkflowOutcome.COMPLETED, holder2.await().outcome)
        }

    @Test
    fun `WD4-schema-semantic device alone gates a step with no declaration`() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registerPlugin(
            "home",
            mapOf(
                "home.ac" to object : CommandHandler {
                    override suspend fun invoke(ctx: ExecutionContext) =
                        CommandResult.Ok(JsonPrimitive("ok"))
                },
                "home.hold" to GatedHandler(held, release),
            ),
            inputSchemas = mapOf("home.ac" to deviceSchema("id")),
        )

        val holder = async {
            engine.execute(WorkflowStep.Command("home.hold", requiresDevices = listOf("ac-1")))
        }
        withTimeout(5_000) { held.await() }

        val blocked = async {
            engine.execute(
                WorkflowStep.Command("home.ac", JsonObject(mapOf("id" to JsonPrimitive("ac-1"))))
            )
        }
        assertNull(withTimeoutOrNull(200) { blocked.await() }) // semantic id=ac-1 contended
        release.complete(Unit)
        assertEquals(WorkflowOutcome.COMPLETED, withTimeout(5_000) { blocked.await() }.outcome)
        assertEquals(WorkflowOutcome.COMPLETED, holder.await().outcome)
    }

    // ─── Same-run rules ──────────────────────────────────────────────────

    @Test
    fun `WD5-parallel branches declaring the same device conflict deterministically`() =
        runBlocking<Unit> {
            // Both handlers signal the SHARED entered: exactly one branch
            // registers its device intent first and parks inside its handler;
            // the other is rejected while the winner holds.
            val anyEntered = CompletableDeferred<Unit>()
            val gateX = CompletableDeferred<Unit>()
            val gateY = CompletableDeferred<Unit>()
            registerPlugin(
                "dev",
                mapOf(
                    "dev.x" to GatedHandler(anyEntered, gateX),
                    "dev.y" to GatedHandler(anyEntered, gateY),
                )
            )

            val run = async {
                engine.execute(
                    WorkflowStep.Parallel(
                        listOf(
                            WorkflowStep.Command("dev.x", requiresDevices = listOf("same")),
                            WorkflowStep.Command("dev.y", requiresDevices = listOf("same")),
                        )
                    )
                )
            }
            withTimeout(5_000) { anyEntered.await() } // the winner parks, holding "same"
            // Open BOTH gates — the loser's handler never ran, its gate is unused.
            gateX.complete(Unit)
            gateY.complete(Unit)

            val result = withTimeout(5_000) { run.await() }
            assertEquals(WorkflowOutcome.FAILED, result.outcome)
            assertEquals(1, result.steps.count { it.ok })
            val conflicts = result.steps.filter { !it.ok }
            assertEquals(1, conflicts.size)
            assertEquals(McosErrorCode.CONFLICT.name, conflicts[0].code)
            assertTrue(conflicts[0].message!!.contains("nested acquisition"))
        }

    @Test
    fun `WD6-sequential steps on the same device complete - release between steps`() =
        runBlocking<Unit> {
            registerPlugin(
                "dev",
                mapOf(
                    "dev.p" to object : CommandHandler {
                        override suspend fun invoke(ctx: ExecutionContext) =
                            CommandResult.Ok(JsonPrimitive("1"))
                    },
                    "dev.q" to object : CommandHandler {
                        override suspend fun invoke(ctx: ExecutionContext) =
                            CommandResult.Ok(JsonPrimitive("2"))
                    },
                )
            )

            val result = engine.execute(
                WorkflowStep.Sequential(
                    listOf(
                        WorkflowStep.Command("dev.p", requiresDevices = listOf("d")),
                        WorkflowStep.Command("dev.q", requiresDevices = listOf("d")),
                    )
                )
            )

            assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
            assertEquals(2, result.steps.size)
            assertTrue(result.steps.all { it.ok })
        }
}
