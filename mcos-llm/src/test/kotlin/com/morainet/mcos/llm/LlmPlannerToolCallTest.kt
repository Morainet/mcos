package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tests for [LlmPlanner]'s NATIVE_TOOL_CALL planning mode
 * (06 §3.2 mode selection, §17 V1 `FREEFORM_JSON` + `NATIVE_TOOL_CALL`).
 */
class LlmPlannerToolCallTest {

    private lateinit var registry: CommandRegistry

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        registerEchoCommand()
    }

    // ---- T1: NATIVE_TOOL_CALL path ----------------------------------------

    @Test
    fun `T1-TOOL_CALL provider plans via native tool calling and records mode`() = runBlocking {
        val provider = FakeToolCallProvider(
            id = "tool-p",
            responses = listOf(
                ToolCallResponse.Ok(
                    listOf(
                        ToolCall("call_1", "test.hello", buildJsonObject { put("greeting", JsonPrimitive("hi")) })
                    )
                )
            )
        )

        val planner = LlmPlanner(provider, registry)
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(1, plan.commands.size)
        assertEquals("test.hello", plan.commands[0].id)
        assertEquals("hi", plan.commands[0].args["greeting"]?.jsonPrimitive?.content)
        assertEquals(PlanMode.NATIVE_TOOL_CALL, plan.planMode)
        assertEquals("tool-p", plan.providerId)
        assertEquals(1, provider.toolCallCalls)
        assertEquals(0, provider.chatCalls)
    }

    // ---- T2: FREEFORM_JSON for chat-only provider -------------------------

    @Test
    fun `T2-chat-only provider plans via freeform json`() = runBlocking {
        val provider = FakeLlmProvider(listOf(LlmResponse.Ok("test.hello(greeting=\"hi\")")))

        val planner = LlmPlanner(provider, registry)
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(PlanMode.FREEFORM_JSON, plan.planMode)
        assertEquals("test.hello", plan.commands[0].id)
    }

    // ---- T3: retryable toolCall failure falls back ------------------------

    @Test
    fun `T3-retryable tool call failure falls back to chat provider`() = runBlocking {
        val primary = FakeToolCallProvider(
            id = "tool-p",
            responses = listOf(ToolCallResponse.Err("LLM_TIMEOUT", "tool call timeout", true))
        )
        val backup = FakeLlmProvider(listOf(LlmResponse.Ok("test.hello(greeting=\"via-chat\")")))

        val planner = LlmPlanner(primary, registry, fallbacks = listOf(backup))
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals("test.hello", plan.commands[0].id)
        assertEquals("via-chat", plan.commands[0].args["greeting"]?.jsonPrimitive?.content)
        assertEquals(PlanMode.FREEFORM_JSON, plan.planMode)
        assertEquals("FakeLlmProvider", plan.providerId)
    }

    // ---- T4: non-retryable toolCall failure stops the chain ----------------

    @Test
    fun `T4-non-retryable tool call error does not fall back`() = runBlocking {
        val primary = FakeToolCallProvider(
            id = "tool-p",
            responses = listOf(ToolCallResponse.Err("AUTH_ERROR", "bad key", false))
        )
        val backup = SequenceFakeProvider("backup", responses = listOf(LlmResponse.Ok("test.hello(greeting=\"hi\")")))

        val planner = LlmPlanner(primary, registry, fallbacks = listOf(backup))
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("AUTH_ERROR", plan.error?.code)
        assertEquals(0, backup.chatCalls) // never reached
        assertEquals("tool-p", plan.providerId)
    }

    // ---- T5: multiple tool calls map to multiple commands ------------------

    @Test
    fun `T5-multiple tool calls map to multiple commands`() = runBlocking {
        val provider = FakeToolCallProvider(
            id = "tool-p",
            responses = listOf(
                ToolCallResponse.Ok(
                    listOf(
                        ToolCall("call_1", "test.hello", buildJsonObject { put("greeting", JsonPrimitive("a")) }),
                        ToolCall("call_2", "test.hello", buildJsonObject { put("greeting", JsonPrimitive("b")) })
                    )
                )
            )
        )

        val planner = LlmPlanner(provider, registry)
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(2, plan.commands.size)
        assertEquals("a", plan.commands[0].args["greeting"]?.jsonPrimitive?.content)
        assertEquals("b", plan.commands[1].args["greeting"]?.jsonPrimitive?.content)
        assertTrue(plan.thoughts.orEmpty().contains("2"), "thoughts should mention the count")
    }

    // ---- T6: ToolDescriptor projection -------------------------------------

    @Test
    fun `T6-tool descriptors are projected from registry commands`() = runBlocking {
        val provider = FakeToolCallProvider(id = "tool-p", responses = listOf(ToolCallResponse.Ok(emptyList())))

        val planner = LlmPlanner(provider, registry)
        planner.plan("say hi")

        val tools = provider.lastTools ?: emptyList()
        assertEquals(1, tools.size)
        val tool = tools[0]
        assertEquals("test.hello", tool.command)
        assertEquals("Say hello", tool.description)
        // inputSchema passes through untouched
        assertEquals("object", tool.inputSchema["type"]?.jsonPrimitive?.content)
        // example DSL is best-effort parsed into its args object
        assertEquals(1, tool.examples.size)
        assertEquals("hi", tool.examples[0]["greeting"]?.jsonPrimitive?.content)
    }

    // ---- T7: empty tool calls produce failed plan --------------------------

    @Test
    fun `T7-empty tool calls produce failed plan with explanation`() = runBlocking {
        val provider = FakeToolCallProvider(id = "tool-p", responses = listOf(ToolCallResponse.Ok(emptyList())))

        val planner = LlmPlanner(provider, registry)
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertNull(plan.error)
        assertTrue(plan.thoughts.orEmpty().contains("no tool calls"), "should explain the empty result")
        assertEquals("tool-p", plan.providerId)
        assertEquals(PlanMode.NATIVE_TOOL_CALL, plan.planMode)
    }

    // ---- T8: toolCall fallback walks multiple tool providers ---------------

    @Test
    fun `T8-fallback walks tool call providers in order`() = runBlocking {
        val p1 = FakeToolCallProvider("p1", responses = listOf(ToolCallResponse.Err("LLM_TIMEOUT", "t", true)))
        val p2 = FakeToolCallProvider(
            "p2",
            responses = listOf(
                ToolCallResponse.Ok(
                    listOf(ToolCall("c1", "test.hello", buildJsonObject { put("greeting", JsonPrimitive("ok")) }))
                )
            )
        )

        val planner = LlmPlanner(p1, registry, fallbacks = listOf(p2))
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(1, p1.toolCallCalls)
        assertEquals(1, p2.toolCallCalls)
        assertEquals("p2", plan.providerId)
        assertEquals(PlanMode.NATIVE_TOOL_CALL, plan.planMode)
    }

    // ---- Helpers ----------------------------------------------------------

    private fun registerEchoCommand() {
        val plugin = object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "echo-plugin",
                name = "Echo",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Echo plugin",
                provider = ProviderInfo("TestOrg", "https://example.com"),
                entry = "com.morainet.mcos.plugin.echo.EchoPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = "test.hello",
                        version = "1.0",
                        title = "Hello",
                        description = "Say hello",
                        sideEffectClass = SideEffectClass.read,
                        examples = listOf("test.hello(greeting=\"hi\")"),
                        inputSchema = buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", buildJsonObject {
                                put("greeting", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                })
                            })
                        }
                    )
                )
            )
            override fun handlers(): Map<String, CommandHandler> = emptyMap()
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }
}

/**
 * Fake provider that returns pre-configured tool-call responses in order,
 * counts calls, and records the projected [ToolDescriptor]s.
 */
class FakeToolCallProvider(
    override val id: String,
    private val responses: List<ToolCallResponse>,
    override val capabilities: Set<Capability> = setOf(Capability.CHAT, Capability.TOOL_CALL),
) : LlmProvider {

    var chatCalls: Int = 0
        private set
    var toolCallCalls: Int = 0
        private set
    var lastTools: List<ToolDescriptor>? = null
        private set

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse =
        LlmResponse.Err("UNUSED", "chat not used", false)

    override suspend fun toolCall(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
    ): ToolCallResponse {
        toolCallCalls++
        lastTools = tools
        return responses[minOf(toolCallCalls - 1, responses.lastIndex)]
    }
}
