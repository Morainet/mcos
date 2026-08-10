package com.mcos.runtime.llm

import com.mcos.runtime.api.*
import com.mcos.runtime.memory.MemoryStore
import com.mcos.runtime.permission.PermissionKernel
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/**
 * Tests for [ChatOrchestrator] — the NL → Plan → Execute pipeline.
 *
 * Covers:
 * - LLM failure / empty response → planning failure
 * - Valid single command → successful execution
 * - Valid sequence → multi-step execution
 * - Unknown command → execution failure
 * - ChatResult metadata (dsl, summary, events)
 */
class ChatOrchestratorTest {

    private lateinit var registry: CommandRegistry
    private lateinit var runtime: McosRuntime

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(PermissionKernel())
            .withMemory(MemoryStore())
            .build()
    }

    // ═══════════════════════════════════════════════════════════════
    // O1-O2: Planning failures
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `O1-chat with LLM error returns failure`() = runBlocking {
        val provider = FakeLlmProvider(
            listOf(LlmResponse.Err("API_ERROR", "Service unavailable", true))
        )
        val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)

        val result = orchestrator.chat("do something")

        assertFalse(result.success, "chat should fail when LLM returns error")
        assertTrue(result.summary.contains("Planning failed"), "summary should mention planning failure")
        assertEquals("", result.dsl, "dsl should be empty")
        assertTrue(result.events.isEmpty(), "no execution events when planning fails")
    }

    @Test
    fun `O2-chat with empty LLM response returns failure`() = runBlocking {
        val provider = FakeLlmProvider(listOf(LlmResponse.Ok("  ")))
        val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)

        val result = orchestrator.chat("do something")

        assertFalse(result.success, "chat should fail when LLM returns blank")
        assertTrue(result.summary.contains("Planning failed"))
    }

    // ═══════════════════════════════════════════════════════════════
    // O3-O4: Successful execution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `O3-chat with valid single command executes successfully`() = runBlocking {
        registerEchoCommand("test.hello")
        val provider = FakeLlmProvider(
            listOf(LlmResponse.Ok("test.hello(greeting=\"hi\")"))
        )
        val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)

        val result = orchestrator.chat("say hello")

        assertTrue(result.success, "chat should succeed: ${result.summary}")
        assertEquals(1, result.plan.commands.size, "plan should have 1 command")
        assertEquals("test.hello", result.plan.commands[0].id)
        assertTrue(result.summary.contains("Executed 1 command"))
        assertTrue(result.summary.contains("successfully"))
        assertTrue(result.events.any { it is RuntimeEvent.RunStarted }, "should have RunStarted")
        assertTrue(result.events.any { it is RuntimeEvent.StepStarted }, "should have StepStarted")
        assertTrue(result.events.any { it is RuntimeEvent.StepSucceeded }, "should have StepSucceeded")
        assertTrue(result.events.any { it is RuntimeEvent.RunSucceeded }, "should have RunSucceeded")
        assertFalse(result.events.any { it is RuntimeEvent.RunFailed }, "should not have RunFailed")
    }

    @Test
    fun `O4-chat with valid command sequence executes all steps`() = runBlocking {
        registerEchoCommand("test.a")
        registerEchoCommand("test.b")
        registerEchoCommand("test.c")
        val provider = FakeLlmProvider(
            listOf(LlmResponse.Ok("""
                test.a(x="1")
                test.b(y="2")
                test.c(z="3")
            """.trimIndent()))
        )
        val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)

        val result = orchestrator.chat("run three steps")

        assertTrue(result.success, "chat should succeed: ${result.summary}")
        assertEquals(3, result.plan.commands.size)
        assertTrue(result.summary.contains("Executed 3 command"))
        val stepSucceeded = result.events.filterIsInstance<RuntimeEvent.StepSucceeded>()
        assertEquals(3, stepSucceeded.size, "should have 3 StepSucceeded events")
    }

    // ═══════════════════════════════════════════════════════════════
    // O5: Unknown command → execution failure
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `O5-chat with unknown command fails at execution`() = runBlocking {
        // No commands registered — LLM generates DSL for an unregistered command
        val provider = FakeLlmProvider(
            listOf(LlmResponse.Ok("unknown.cmd(param=\"x\")"))
        )
        val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)

        val result = orchestrator.chat("run unknown command")

        assertFalse(result.success, "should fail for unknown command")
        assertTrue(result.summary.contains("Execution failed") || result.summary.contains("UNKNOWN_COMMAND"),
            "summary should indicate failure: ${result.summary}")
        assertTrue(result.events.any { it is RuntimeEvent.RunFailed }, "should have RunFailed event")
    }

    // ═══════════════════════════════════════════════════════════════
    // O6-O7: ChatResult metadata
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `O6-ChatResult dsl returns raw DSL from plan`() = runBlocking {
        registerEchoCommand("test.hello")
        val rawDsl = "test.hello(greeting=\"hi\")"
        val provider = FakeLlmProvider(listOf(LlmResponse.Ok(rawDsl)))
        val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)

        val result = orchestrator.chat("say hello")

        assertEquals(rawDsl, result.dsl, "dsl should match raw LLM output")
    }

    @Test
    fun `O7-ChatResult with failure contains appropriate summary`() = runBlocking {
        val provider = FakeLlmProvider(
            listOf(LlmResponse.Err("TIMEOUT", "Request timed out", false))
        )
        val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)

        val result = orchestrator.chat("anything")

        assertFalse(result.success)
        assertTrue(result.summary.contains("timed out") || result.summary.contains("TIMEOUT"),
            "summary should contain error info: ${result.summary}")
        assertNotNull(result.plan.error)
        assertEquals("TIMEOUT", result.plan.error!!.code)
    }

    @Test
    fun `O8-ChatResult from successful run contains no failure events`() = runBlocking {
        registerEchoCommand("test.ok")
        val provider = FakeLlmProvider(
            listOf(LlmResponse.Ok("test.ok()"))
        )
        val orchestrator = ChatOrchestrator(LlmPlanner(provider, registry), runtime)

        val result = orchestrator.chat("do ok task")

        assertTrue(result.success)
        assertFalse(result.events.any { it is RuntimeEvent.RunFailed })
        assertFalse(result.events.any { it is RuntimeEvent.StepFailed })
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun registerEchoCommand(id: String) {
        val provider = ProviderInfo("TestOrg", "https://example.com")
        val handler = EchoCommandHandler(id)
        val plugin = object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "test-plugin-$id",
                name = "Test Echo",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Echo plugin for $id",
                provider = provider,
                entry = "com.mcos.plugin.test.TestPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = id,
                        version = "1.0",
                        title = id,
                        description = "Echo command $id",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = JsonObject(emptyMap())
                    )
                )
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(id to handler)
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }
}

/**
 * Echo handler: succeeds and returns the command id as a text artifact.
 */
class EchoCommandHandler(
    private val commandId: String,
) : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(
            artifacts = listOf(
                Artifact("text", "echo:$commandId", "text/plain")
            )
        )
    }
}
