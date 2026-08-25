package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunRecord
import com.morainet.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * `__input` binding (05 §6.2) and per-run step source / auth threading —
 * slice 2 of the event-triggered recipes feature.
 */
class WorkflowInputTest {

    private lateinit var registry: CommandRegistry
    private lateinit var executor: Executor
    private lateinit var engine: WorkflowEngine
    private lateinit var audit: RecordingAuditLog

    /** Args captured per command invocation (last wins per id). */
    private val capturedArgs = mutableMapOf<String, JsonObject>()
    private val capturedAuths = mutableMapOf<String, AuthStamp?>()

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        audit = RecordingAuditLog()
        executor = Executor(registry, WorkflowEngineTest.StubHostServices(), SecurityConfig.permissive().copy(auditLog = audit))
        engine = WorkflowEngine(executor, audit)
    }

    @AfterTest
    fun tearDown() {
        registry.clear()
    }

    /** Registers a command whose handler records its args/auth then succeeds. */
    private fun registerArgCapturePlugin(vararg commandIds: String) {
        val handlers = commandIds.associateWith { id ->
            object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    capturedArgs[id] = ctx.args as? JsonObject ?: JsonObject(emptyMap())
                    capturedAuths[id] = ctx.auth
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        }
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = "capture", name = "capture", version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "arg capture",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.plugin.test.CapturePlugin",
                commands = commandIds.map { cmdId ->
                    CommandManifestEntry(
                        id = cmdId,
                        version = "1.0.0",
                        title = cmdId,
                        description = "capture: $cmdId",
                        sideEffectClass = SideEffectClass.read
                    )
                }
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
            override fun handlers(): Map<String, CommandHandler> = handlers
        }
        registry.register(plugin)
    }

    private fun obj(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject

    // ─── __input substitution (05 §6.2) ──────────────────────────────────

    @Test
    fun `W26-top-level dollar-ref input resolves into args`() = runBlocking {
        registerArgCapturePlugin("chat.send")

        engine.execute(
            WorkflowStep.Command(
                "chat.send",
                obj("""{"to":{"${'$'}ref":"__input.recipient"},"body":{"${'$'}ref":"__input.msg"}}""")
            ),
            inputs = obj("""{"recipient":"tom","msg":"hello"}""")
        )

        assertEquals(
            """{"to":"tom","body":"hello"}""",
            capturedArgs["chat.send"].toString(),
            "both \$ref values should be substituted from the run inputs"
        )
    }

    @Test
    fun `W27-nested dotted input path resolves`() = runBlocking {
        registerArgCapturePlugin("sys.notify")

        engine.execute(
            WorkflowStep.Command(
                "sys.notify",
                obj("""{"text":{"${'$'}ref":"__input.event.ssid"}}""")
            ),
            inputs = obj("""{"event":{"ssid":"Office","bssid":"aa:bb"}}""")
        )

        assertEquals(
            """{"text":"Office"}""",
            capturedArgs["sys.notify"].toString()
        )
    }

    @Test
    fun `W28-unresolvable input ref fails the step with SCHEMA_VIOLATION`() = runBlocking {
        registerArgCapturePlugin("chat.send")

        val result = engine.execute(
            WorkflowStep.Command(
                "chat.send",
                obj("""{"to":{"${'$'}ref":"__input.missing"}}""")
            ),
            inputs = obj("""{"recipient":"tom"}""")
        )

        assertEquals(WorkflowOutcome.FAILED, result.outcome)
        val step = result.steps.single()
        assertFalse(step.ok)
        assertEquals("SCHEMA_VIOLATION", step.code)
        assertTrue(
            step.message!!.contains("input_ref_unresolvable"),
            "message should name the failed ref: ${step.message}"
        )
        assertNull(capturedArgs["chat.send"], "the command must not execute with a dangling ref")
    }

    @Test
    fun `W29-args without refs pass through verbatim (regression)`() = runBlocking {
        registerArgCapturePlugin("step.a")

        engine.execute(
            WorkflowStep.Command("step.a", obj("""{"plain":{"nested":1},"list":[1,2]}"""))
        )

        assertEquals(
            """{"plain":{"nested":1},"list":[1,2]}""",
            capturedArgs["step.a"].toString()
        )
    }

    // ─── stepSource / authFor threading ─────────────────────────────────

    @Test
    fun `W30-stepSource lands in the executor audit record`() = runBlocking {
        registerArgCapturePlugin("vpn.connect")

        engine.execute(
            WorkflowStep.Command("vpn.connect"),
            stepSource = "EVENT"
        )

        val stepRecord = audit.records.filter { it.source == "EVENT" }.singleOrNull()
        assertNotNull(
            stepRecord,
            "the per-step executor record should carry source EVENT (sources seen: ${audit.records.map { it.source }})"
        )
        assertEquals("vpn.connect", stepRecord.steps.single().commandId)
        assertTrue(
            audit.records.any { it.source == "WORKFLOW" },
            "the engine's own run record keeps its WORKFLOW label"
        )
    }

    @Test
    fun `W31-authFor supplies a stamp only to the matching command`() = runBlocking {
        registerArgCapturePlugin("step.a", "step.b")
        val stampForA = AuthStamp(
            runId = "run-1",
            commandId = "step.a",
            pluginId = "capture",
            grantsUsed = setOf("capture.run"),
            issuedAt = 0L,
            expiresAt = Long.MAX_VALUE,
            signature = ""
        )

        engine.execute(
            WorkflowStep.Sequential(listOf(
                WorkflowStep.Command("step.a"),
                WorkflowStep.Command("step.b")
            )),
            authFor = { cmd -> if (cmd == "step.a") stampForA else null }
        )

        // The executor adopts the supplied stamp's grants but rebinds it to
        // the actual run id (Stage 6 re-minting) — assert on the carried
        // grants, not reference identity.
        assertEquals(setOf("capture.run"), capturedAuths["step.a"]?.grantsUsed)
        assertEquals("step.a", capturedAuths["step.a"]?.commandId)
        // step.b got no supplied stamp — the kernel minted its own (no grants
        // carried), proving authFor only reached the matching command.
        assertTrue(
            capturedAuths["step.b"]?.grantsUsed?.isEmpty() == true,
            "step.b goes through the kernel path with no supplied grants: ${capturedAuths["step.b"]}"
        )
    }

    // ─── Recording audit sink ────────────────────────────────────────────

    private class RecordingAuditLog : AuditLog {
        val records = CopyOnWriteArrayList<RunRecord>()
        override fun append(record: RunRecord) { records.add(record) }
        override suspend fun flush() {}
        override fun start() {}
        override fun stop() {}
        override fun getRuns(): List<RunRecord> = records.reversed()
        override fun getRun(runId: String): RunRecord? = records.lastOrNull { it.runId == runId }
        override fun getRecent(limit: Int): List<RunRecord> = records.takeLast(limit).reversed()
        override fun count(): Int = records.size
        override fun export(): String = records.joinToString("\n") { it.toString() }
        override fun clear() { records.clear() }
    }
}
