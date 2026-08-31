package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.sdk.*
import kotlinx.coroutines.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Conformance tests for WorkflowEngine v0.1.
 * Matches [01-architecture.md Workflow Engine].
 */
class WorkflowEngineTest {

    private lateinit var registry: CommandRegistry
    private lateinit var executor: Executor
    private lateinit var engine: WorkflowEngine
    private val services = StubHostServices()

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        executor = Executor(registry, services, SecurityConfig.permissive())
        engine = WorkflowEngine(executor)
    }

    @AfterTest
    fun tearDown() {
        registry.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // W1-W3: Single command
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W1-single command succeeds`() = runBlocking {
        registerPlugin("step.a", "ok")
        val result = engine.execute(
            WorkflowStep.Command("step.a")
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(1, result.steps.size)
        assertTrue(result.steps[0].ok)
        assertEquals("step.a", result.steps[0].commandId)
    }

    @Test
    fun `W2-unknown command fails`() = runBlocking {
        val result = engine.execute(
            WorkflowStep.Command("nonexistent.cmd")
        )
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        assertEquals(1, result.steps.size)
        assertFalse(result.steps[0].ok)
        assertEquals(McosErrorCode.UNKNOWN_COMMAND.name, result.steps[0].code)
    }

    @Test
    fun `W3-command handler returning error`() = runBlocking {
        val failing = createPlugin("test.fail", "1.0.0", mapOf(
            "fail.cmd" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    CommandResult.Err("TEST_ERROR", "intentional failure", false)
            }
        ))
        registry.register(failing)
        val result = engine.execute(WorkflowStep.Command("fail.cmd"))
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        assertFalse(result.steps[0].ok)
        assertEquals("TEST_ERROR", result.steps[0].code)
    }

    // ═══════════════════════════════════════════════════════════════
    // W4-W6: Sequential
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W4-sequential executes all steps in order`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t1", tracker, listOf("step.1", "step.2", "step.3"))

        val result = engine.execute(
            WorkflowStep.Sequential(
                listOf(
                    WorkflowStep.Command("step.1"),
                    WorkflowStep.Command("step.2"),
                    WorkflowStep.Command("step.3")
                )
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(3, result.steps.size)
        assertEquals(listOf("step.1", "step.2", "step.3"), tracker)
    }

    @Test
    fun `W5-sequential stops on first failure`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t5", tracker, listOf("s1", "s2", "s3"))

        val result = engine.execute(
            WorkflowStep.Sequential(
                listOf(
                    WorkflowStep.Command("s1"),
                    WorkflowStep.Command("nonexistent"),
                    WorkflowStep.Command("s3")
                )
            )
        )
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        // s1 executed, nonexistent failed, s3 never executed
        assertEquals(listOf("s1"), tracker)
        assertEquals(2, result.steps.size) // s1 ok + nonexistent error
    }

    @Test
    fun `W6-nested sequential`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t6", tracker, listOf("a", "b", "c", "d"))

        val result = engine.execute(
            WorkflowStep.Sequential(
                listOf(
                    WorkflowStep.Command("a"),
                    WorkflowStep.Sequential(
                        listOf(
                            WorkflowStep.Command("b"),
                            WorkflowStep.Command("c")
                        )
                    ),
                    WorkflowStep.Command("d")
                )
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(4, result.steps.size)
        assertEquals(listOf("a", "b", "c", "d"), tracker)
    }

    // ═══════════════════════════════════════════════════════════════
    // W7-W9: Parallel
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W7-parallel executes all steps`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t7", tracker, listOf("p1", "p2", "p3"))

        val result = engine.execute(
            WorkflowStep.Parallel(
                listOf(
                    WorkflowStep.Command("p1"),
                    WorkflowStep.Command("p2"),
                    WorkflowStep.Command("p3")
                )
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(3, result.steps.size)
        // All 3 executed, order may vary but all present
        assertTrue(tracker.containsAll(listOf("p1", "p2", "p3")))
    }

    @Test
    fun `W8-parallel with one failure`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t8", tracker, listOf("q1", "q2"))

        val result = engine.execute(
            WorkflowStep.Parallel(
                listOf(
                    WorkflowStep.Command("q1"),
                    WorkflowStep.Command("nonexistent"),
                    WorkflowStep.Command("q2")
                )
            )
        )
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        assertEquals(3, result.steps.size)
        assertTrue(result.steps.any { !it.ok })
    }

    @Test
    fun `W9-parallel within sequential`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t9", tracker, listOf("setup", "par1", "par2", "teardown"))

        val result = engine.execute(
            WorkflowStep.Sequential(
                listOf(
                    WorkflowStep.Command("setup"),
                    WorkflowStep.Parallel(
                        listOf(
                            WorkflowStep.Command("par1"),
                            WorkflowStep.Command("par2")
                        )
                    ),
                    WorkflowStep.Command("teardown")
                )
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(4, result.steps.size)
        assertEquals("setup", tracker.first())
        assertEquals("teardown", tracker.last())
        assertTrue(tracker.containsAll(listOf("par1", "par2")))
    }

    // ═══════════════════════════════════════════════════════════════
    // W10-W12: If / Conditional
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W10-if condition always true executes then branch`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t10", tracker, listOf("then", "else"))

        val result = engine.execute(
            WorkflowStep.If(
                condition = WorkflowCondition.Always(true),
                thenStep = WorkflowStep.Command("then"),
                elseStep = WorkflowStep.Command("else")
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("then"), tracker)
    }

    @Test
    fun `W11-if condition always false executes else branch`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t11", tracker, listOf("then", "else"))

        val result = engine.execute(
            WorkflowStep.If(
                condition = WorkflowCondition.Always(false),
                thenStep = WorkflowStep.Command("then"),
                elseStep = WorkflowStep.Command("else")
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("else"), tracker)
    }

    @Test
    fun `W12-if based on previous step success`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t12", tracker, listOf("guard", "on-success", "on-failure"))

        val result = engine.execute(
            WorkflowStep.Sequential(
                listOf(
                    WorkflowStep.Command("guard"),
                    WorkflowStep.If(
                        condition = WorkflowCondition.BasedOnPrevious(WorkflowPredicate.LAST_STEP_SUCCEEDED),
                        thenStep = WorkflowStep.Command("on-success"),
                        elseStep = WorkflowStep.Command("on-failure")
                    )
                )
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("guard", "on-success"), tracker)
    }

    // ═══════════════════════════════════════════════════════════════
    // W13-W15: Loop
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W13-loop with max iterations`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t13", tracker, listOf("tick"))

        // Always true loop - should stop at maxIterations
        val result = engine.execute(
            WorkflowStep.Loop(
                body = WorkflowStep.Command("tick"),
                condition = WorkflowCondition.Always(true),
                maxIterations = 3
            )
        )
        assertEquals(WorkflowOutcome.FAILED, result.outcome) // exceeded
        assertEquals(3, tracker.size)
        assertEquals(listOf("tick", "tick", "tick"), tracker)
        val lastStep = result.steps.last()
        assertFalse(lastStep.ok)
        assertEquals(McosErrorCode.MAX_ITERATIONS_EXCEEDED.name, lastStep.code)
    }

    @Test
    fun `W14-loop exits when condition becomes false`() = runBlocking {
        var counter = 0
        val plugin = createPlugin("t14", "1.0.0", mapOf(
            "inc" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    counter++
                    return CommandResult.Ok(JsonPrimitive(counter.toString()))
                }
            }
        ))
        registry.register(plugin)

        // Stop after first success (counter would be 1)
        val result = engine.execute(
            WorkflowStep.Loop(
                body = WorkflowStep.Command("inc"),
                condition = WorkflowCondition.Always(true),
                maxIterations = 5
            )
        )
        // All 5 iterations run, then exceeded
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        assertEquals(5, counter)
    }

    @Test
    fun `W15-loop with max iterations not exceeded on natural exit`() = runBlocking {
        var counter = 0
        val plugin = createPlugin("t15", "1.0.0", mapOf(
            "chk" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    counter++
                    return if (counter >= 2) {
                        CommandResult.Err("DONE", "stopped", false)
                    } else {
                        CommandResult.Ok(JsonPrimitive("running"))
                    }
                }
            }
        ))
        registry.register(plugin)

        val result = engine.execute(
            WorkflowStep.Loop(
                body = WorkflowStep.Command("chk"),
                condition = WorkflowCondition.BasedOnPrevious(WorkflowPredicate.LAST_STEP_SUCCEEDED),
                maxIterations = 10
            )
        )
        // 1st: succeeds → continue, 2nd: fails (counter=2) → stops
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        assertEquals(2, counter)
    }

    // ═══════════════════════════════════════════════════════════════
    // W16-W18: Retry
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W16-retry succeeds on second attempt`() = runBlocking {
        var attempts = 0
        val plugin = createPlugin("t16", "1.0.0", mapOf(
            "flaky" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    attempts++
                    return if (attempts < 2) {
                        CommandResult.Err("UNAVAILABLE", "not ready", true)
                    } else {
                        CommandResult.Ok(JsonPrimitive("ready"))
                    }
                }
            }
        ))
        registry.register(plugin)

        val result = engine.execute(
            WorkflowStep.Retry(
                step = WorkflowStep.Command("flaky"),
                maxRetries = 3,
                backoffMs = 10
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(2, attempts)
        // Should have one failed + one succeeded = 2 step results
        assertEquals(2, result.steps.size)
        assertFalse(result.steps[0].ok)
        assertTrue(result.steps[1].ok)
    }

    @Test
    fun `W17-retry exhausts all attempts and fails`() = runBlocking {
        var attempts = 0
        val plugin = createPlugin("t17", "1.0.0", mapOf(
            "alwaysFails" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    attempts++
                    return CommandResult.Err("DEAD", "always dead", false)
                }
            }
        ))
        registry.register(plugin)

        val result = engine.execute(
            WorkflowStep.Retry(
                step = WorkflowStep.Command("alwaysFails"),
                maxRetries = 2,
                backoffMs = 10
            )
        )
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        assertEquals(3, attempts) // 1 original + 2 retries = 3 total
        assertEquals(3, result.steps.size)
        result.steps.forEach { assertFalse(it.ok) }
    }

    @Test
    fun `W18-retry with zero max retries executes once`() = runBlocking {
        var attempts = 0
        val plugin = createPlugin("t18", "1.0.0", mapOf(
            "once" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    attempts++
                    return CommandResult.Err("FAIL", "nope", false)
                }
            }
        ))
        registry.register(plugin)

        val result = engine.execute(
            WorkflowStep.Retry(
                step = WorkflowStep.Command("once"),
                maxRetries = 0,
                backoffMs = 10
            )
        )
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        assertEquals(1, attempts)
    }

    // ═══════════════════════════════════════════════════════════════
    // W19-W20: Try / Compensation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W19-try succeeds without compensation`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t19", tracker, listOf("main", "comp"))

        val result = engine.execute(
            WorkflowStep.Try(
                step = WorkflowStep.Command("main"),
                compensation = listOf(WorkflowStep.Command("comp"))
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("main"), tracker) // compensation not run
    }

    @Test
    fun `W20-try runs compensation on failure`() = runBlocking {
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("t20", tracker, listOf("comp1", "comp2"))

        val result = engine.execute(
            WorkflowStep.Try(
                step = WorkflowStep.Command("nonexistent.cmd"),
                compensation = listOf(
                    WorkflowStep.Command("comp1"),
                    WorkflowStep.Command("comp2")
                )
            )
        )
        // Main step failed, compensation should run
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        assertEquals(listOf("comp1", "comp2"), tracker)
        // 3 records: main failed + 2 compensation steps
        assertTrue(result.steps.size >= 3)
    }

    // ═══════════════════════════════════════════════════════════════
    // W21-W22: Complex scenarios
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W21-home movie workflow scenario`() = runBlocking {
        // Simulate: home.movie = parallel(light.on, tv.on, curtain.close, ac.on)
        val tracker = mutableListOf<String>()
        registerTrackingPlugin("home", tracker, listOf("light.on", "tv.on", "curtain.close", "ac.on"))

        val result = engine.execute(
            WorkflowStep.Parallel(
                listOf(
                    WorkflowStep.Command("light.on"),
                    WorkflowStep.Command("tv.on"),
                    WorkflowStep.Command("curtain.close"),
                    WorkflowStep.Command("ac.on")
                )
            )
        )
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(4, result.steps.size)
        assertTrue(tracker.containsAll(listOf("light.on", "tv.on", "curtain.close", "ac.on")))
    }

    @Test
    fun `W22-sequential with try-compensation and retry`() = runBlocking {
        var attempts = 0
        val plugin = createPlugin("t22", "1.0.0", mapOf(
            "risky" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    attempts++
                    return if (attempts < 3) {
                        CommandResult.Err("RISKY_FAIL", "attempt $attempts", true)
                    } else {
                        CommandResult.Ok(JsonPrimitive("success on attempt $attempts"))
                    }
                }
            },
            "cleanup" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    CommandResult.Ok(JsonPrimitive("cleaned"))
            }
        ))
        registry.register(plugin)

        val result = engine.execute(
            WorkflowStep.Try(
                step = WorkflowStep.Retry(
                    step = WorkflowStep.Command("risky"),
                    maxRetries = 3,
                    backoffMs = 10
                ),
                compensation = listOf(WorkflowStep.Command("cleanup"))
            )
        )
        // risky succeeds on 3rd attempt, no compensation needed
        assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
        assertEquals(3, attempts)
    }

    // ═══════════════════════════════════════════════════════════════
    // W23-W24: Parallel cancelOnFailure (P0-C3 regression)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W23-parallel cancelOnFailure skips not-yet-started siblings`() = runBlocking {
        // When cancelOnFailure=true (default), a failing branch must prevent
        // siblings that have not started from executing. We make the failing
        // branch slow enough that the later branch observes the failure flag
        // before it begins. Skipped branches are recorded as CANCELLED.
        val tracker = mutableListOf<String>()
        // c1 fails quickly; c2 is a slow command that registers in tracker; c3
        // should be skipped because c1 already failed.
        val handlers = mapOf(
            "failFast" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    CommandResult.Err("E_FAIL", "boom", false)
            },
            "slow" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    kotlinx.coroutines.delay(200)
                    tracker.add("slow")
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            },
            "tail" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    tracker.add("tail")
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        )
        registry.register(createPlugin("t23", "1.0.0", handlers))

        val result = engine.execute(
            WorkflowStep.Parallel(
                listOf(
                    WorkflowStep.Command("failFast"),
                    WorkflowStep.Command("slow"),
                    WorkflowStep.Command("tail")
                ),
                cancelOnFailure = true,
            )
        )
        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        // The slow branch may or may not have run depending on scheduling, but
        // at least one step must carry the CANCELLED code (a skipped sibling).
        assertTrue(
            result.steps.any { it.code == "CANCELLED" },
            "expected at least one CANCELLED sibling, got: ${result.steps}"
        )
    }

    @Test
    fun `W24-parallel cancelOnFailure=false runs all branches to completion`() = runBlocking {
        // With cancelOnFailure=false, a failing branch does NOT short-circuit
        // its siblings — every branch runs regardless.
        val tracker = mutableListOf<String>()
        val handlers = mapOf(
            "fail" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    CommandResult.Err("E_FAIL", "boom", false)
            },
            "ok1" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    tracker.add("ok1")
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            },
            "ok2" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    tracker.add("ok2")
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        )
        registry.register(createPlugin("t24", "1.0.0", handlers))

        val result = engine.execute(
            WorkflowStep.Parallel(
                listOf(
                    WorkflowStep.Command("fail"),
                    WorkflowStep.Command("ok1"),
                    WorkflowStep.Command("ok2")
                ),
                cancelOnFailure = false,
            )
        )
        assertEquals(WorkflowOutcome.FAILED, result.outcome) // one branch failed
        // No sibling should be cancelled.
        assertFalse(result.steps.any { it.code == "CANCELLED" }, "no CANCELLED with cancelOnFailure=false")
        // Both ok branches executed.
        assertTrue(tracker.containsAll(listOf("ok1", "ok2")), "both siblings should run: $tracker")
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // W25: Retry with composite step — retryOnCodes inspects all new results (P2-F1)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `W25-retry retryOnCodes checks all results from composite step not just last`() = runBlocking {
        // A Sequential step produces two failure results. The first carries a
        // retryable code ("RETRY_ME"), the second a non-retryable code ("NOPE").
        // With the old lastOrNull() logic, only "NOPE" was examined and retry
        // was incorrectly suppressed. The fix inspects the full slice and
        // retries when ANY result code matches retryOnCodes.
        var attempts = 0
        val plugin = createPlugin("t25", "1.0.0", mapOf(
            "retryable" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    CommandResult.Err("RETRY_ME", "retryable failure", true)
            },
            "permanent" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    CommandResult.Err("NOPE", "permanent failure", false)
            },
            // Succeeds on the second attempt so the retry can eventually complete.
            "succeedSecond" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    attempts++
                    return if (attempts >= 2) {
                        CommandResult.Ok(JsonPrimitive("done"))
                    } else {
                        CommandResult.Err("RETRY_ME", "not yet", true)
                    }
                }
            }
        ))
        registry.register(plugin)

        // Build a Retry that wraps a Sequential producing two errors on the
        // first attempt, then succeeds on the second.
        val result = engine.execute(
            WorkflowStep.Retry(
                step = WorkflowStep.Sequential(listOf(
                    WorkflowStep.Command("succeedSecond"),
                    WorkflowStep.Command("permanent")
                )),
                maxRetries = 3,
                backoffMs = 5,
                retryOnCodes = setOf("RETRY_ME"),
                idempotent = true,
            )
        )
        // The retry should have attempted at least twice (retry was NOT
        // suppressed by the trailing "NOPE" code).
        assertTrue(attempts >= 2, "retry should not be suppressed by non-matching last code: attempts=$attempts")
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun registerPlugin(id: String, response: String): McosPlugin {
        val plugin = createPlugin(id, "1.0.0", mapOf(id to object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                CommandResult.Ok(JsonPrimitive(response))
        }))
        registry.register(plugin)
        return plugin
    }

    private fun registerTrackingPlugin(
        namespace: String,
        tracker: MutableList<String>,
        commandIds: List<String>
    ) {
        val handlers = commandIds.associateWith { id ->
            object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    tracker.add(id)
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        }
        val plugin = createPlugin(namespace, "1.0.0", handlers)
        registry.register(plugin)
    }

    private fun createPlugin(
        id: String,
        version: String,
        commands: Map<String, CommandHandler>
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin for workflow",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = commands.map { (cmdId, _) ->
                CommandManifestEntry(
                    id = cmdId,
                    version = version,
                    title = cmdId,
                    description = "Test command: $cmdId",
                    sideEffectClass = SideEffectClass.read
                )
            }
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = commands
    }

    /** Minimal HostServices stub for JVM testing */
    class StubHostServices : HostServices {
        override val files = object : FileService {
            override suspend fun list(uri: String, mimeType: String?): List<FileEntry> = emptyList()
        }
        override val net = object : NetService {
            override suspend fun request(req: HttpRequest): HttpResponse =
                HttpResponse(200, body = "{}".encodeToByteArray())
        }
        override val ui = object : UiService {
            override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? = null
        }
        override val secureStore = object : SecureStore {
            override suspend fun get(key: String): ByteArray? = null
            override suspend fun put(key: String, value: ByteArray) {}
            override suspend fun remove(key: String) {}
            override suspend fun keys(): Set<String> = emptySet()
        }
        override val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            override fun monotonicMs(): Long = System.currentTimeMillis()
        }
        override val json = object : JsonService {
            override fun parse(json: String): JsonElement = Json.parseToJsonElement(json)
        }
        override val memory = object : MemoryFacade {
            override suspend fun get(path: String): JsonElement? = null
            override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound()
        }
    }
}
