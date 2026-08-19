package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tests for PlanMode CONSTRAINED (06 §3.2 V2 / §17 V2): grammar-constrained
 * decoding where the model replies with a single IR JSON object
 * (`invoke` / `sequence` / `clarify` / `refuse`).
 */
class LlmPlannerConstrainedTest {

    private lateinit var registry: CommandRegistry

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        registerEchoCommand("test.hello")
    }

    // ---- C1: mode selection ------------------------------------------------

    @Test
    fun `C1-CONSTRAINED provider is routed to constrainedChat`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "constrained",
            responses = listOf(LlmResponse.Ok("""{"type":"invoke","command":"test.hello","args":{"greeting":"hi"}}"""))
        )

        val planner = LlmPlanner(provider, registry)
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(1, provider.constrainedCalls)
        assertEquals(0, provider.chatCalls)
        assertEquals(PlanMode.CONSTRAINED, plan.planMode)
        assertEquals("constrained", plan.providerId)
    }

    @Test
    fun `C1b-TOOL_CALL wins over CONSTRAINED`() = runBlocking {
        val provider = object : LlmProvider {
            override val id = "both"
            override val capabilities = setOf(Capability.CHAT, Capability.TOOL_CALL, Capability.CONSTRAINED)
            var toolCalls = 0
            override suspend fun chat(messages: List<ChatMessage>): LlmResponse =
                LlmResponse.Err("LLM_NETWORK_ERROR", "unexpected chat", false)

            override suspend fun toolCall(messages: List<ChatMessage>, tools: List<ToolDescriptor>): ToolCallResponse {
                toolCalls++
                return ToolCallResponse.Ok(listOf(ToolCall("tc-1", "test.hello", JsonObject(emptyMap()))))
            }
        }

        val planner = LlmPlanner(provider, registry)
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(PlanMode.NATIVE_TOOL_CALL, plan.planMode)
        assertEquals(1, provider.toolCalls)
    }

    // ---- C2/C3: IR parsing -------------------------------------------------

    @Test
    fun `C2-CONSTRAINED invoke is parsed into one command`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c2",
            responses = listOf(LlmResponse.Ok("""{"type":"invoke","command":"test.hello","args":{"greeting":"hi"}}"""))
        )

        val plan = LlmPlanner(provider, registry).plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(1, plan.commands.size)
        assertEquals("test.hello", plan.commands[0].id)
        assertEquals(JsonPrimitive("hi"), plan.commands[0].args["greeting"])
    }

    @Test
    fun `C3-CONSTRAINED sequence is parsed in order`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c3",
            responses = listOf(
                LlmResponse.Ok(
                    """{"type":"sequence","steps":[
                        {"command":"test.hello","args":{"greeting":"a"}},
                        {"command":"test.hello","args":{"greeting":"b"}}
                    ]}"""
                )
            )
        )

        val plan = LlmPlanner(provider, registry).plan("do both")

        assertTrue(plan.isSuccess)
        assertEquals(2, plan.commands.size)
        assertEquals("a", (plan.commands[0].args["greeting"] as JsonPrimitive).content)
        assertEquals("b", (plan.commands[1].args["greeting"] as JsonPrimitive).content)
    }

    // ---- C4/C5: terminal states --------------------------------------------

    @Test
    fun `C4-CONSTRAINED clarify is an empty successful plan`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c4",
            responses = listOf(LlmResponse.Ok("""{"type":"clarify","question":"Front or back camera?"}"""))
        )

        val plan = LlmPlanner(provider, registry).plan("take a photo")

        assertNull(plan.error) // valid terminal state, empty plan
        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.thoughts.orEmpty().contains("Front or back camera?"))
    }

    @Test
    fun `C5-CONSTRAINED refuse is an empty successful plan`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c5",
            responses = listOf(LlmResponse.Ok("""{"type":"refuse","reason":"Not possible"}"""))
        )

        val plan = LlmPlanner(provider, registry).plan("do the impossible")

        assertNull(plan.error) // valid terminal state, empty plan
        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.thoughts.orEmpty().contains("Not possible"))
    }

    // ---- C6: malformed output ----------------------------------------------

    @Test
    fun `C6-CONSTRAINED malformed JSON yields retryable parse error`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c6",
            responses = listOf(LlmResponse.Ok("not json at all"))
        )

        val plan = LlmPlanner(provider, registry).plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("LLM_PARSE_ERROR", plan.error?.code)
        assertTrue(plan.error?.retryable == true)
    }

    @Test
    fun `C6b-CONSTRAINED unknown type yields parse error`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c6b",
            responses = listOf(LlmResponse.Ok("""{"type":"teleport","command":"test.hello"}"""))
        )

        val plan = LlmPlanner(provider, registry).plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("LLM_PARSE_ERROR", plan.error?.code)
    }

    // ---- C7: fallback chain ------------------------------------------------

    @Test
    fun `C7-CONSTRAINED retryable failure falls back to next provider`() = runBlocking {
        val primary = ConstrainedFakeProvider(
            "constrained",
            responses = listOf(LlmResponse.Err("LLM_TIMEOUT", "timeout", true))
        )
        val backup = SequenceFakeProvider(
            "freeform",
            responses = listOf(LlmResponse.Ok("test.hello(greeting=\"via-fallback\")"))
        )

        val planner = LlmPlanner(primary, registry, fallbacks = listOf(backup))
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(1, primary.constrainedCalls)
        assertEquals(1, backup.chatCalls)
        assertEquals("freeform", plan.providerId)
        assertEquals(PlanMode.FREEFORM_JSON, plan.planMode)
    }

    @Test
    fun `C7b-CONSTRAINED non-retryable failure stops the chain`() = runBlocking {
        val primary = ConstrainedFakeProvider(
            "constrained",
            responses = listOf(LlmResponse.Err("AUTH_ERROR", "bad key", false))
        )
        val backup = SequenceFakeProvider("backup", responses = listOf(LlmResponse.Ok("test.hello(greeting=\"hi\")")))

        val plan = LlmPlanner(primary, registry, fallbacks = listOf(backup)).plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("AUTH_ERROR", plan.error?.code)
        assertEquals(0, backup.chatCalls)
    }

    // ---- C8: grammar injection ---------------------------------------------

    @Test
    fun `C8-grammar passed to provider contains IR terminal types`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c8",
            responses = listOf(LlmResponse.Ok("""{"type":"refuse","reason":"n/a"}"""))
        )

        LlmPlanner(provider, registry).plan("say hi")

        val grammar = provider.lastGrammar?.content.orEmpty()
        assertTrue(grammar.contains("\"invoke\""))
        assertTrue(grammar.contains("\"sequence\""))
        assertTrue(grammar.contains("\"clarify\""))
        assertTrue(grammar.contains("\"refuse\""))
    }

    @Test
    fun `C9-default JSON Schema grammar is injected for JSON_SCHEMA providers`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c9",
            responses = listOf(LlmResponse.Ok("""{"type":"refuse","reason":"n/a"}"""))
        )

        LlmPlanner(provider, registry).plan("say hi")

        val grammar = provider.lastGrammar
        assertNotNull(grammar)
        assertEquals(GrammarFormat.JSON_SCHEMA, grammar.format)
        assertTrue(grammar.content.contains("\"invoke\""))
    }

    @Test
    fun `C10-GBNF grammar is injected for GBNF-capable providers`() = runBlocking {
        val provider = ConstrainedFakeProvider(
            "c10",
            responses = listOf(LlmResponse.Ok("""{"type":"refuse","reason":"n/a"}""")),
            grammarFormats = setOf(GrammarFormat.GBNF)
        )

        LlmPlanner(provider, registry).plan("say hi")

        val grammar = provider.lastGrammar
        assertNotNull(grammar)
        assertEquals(GrammarFormat.GBNF, grammar.format)
        // Real llama.cpp GBNF: root rule enumerates cataloged commands.
        assertTrue(grammar.content.contains("root ::= ws"))
        assertTrue(grammar.content.contains("args-test_hello"))
        assertTrue(grammar.content.contains("\"test.hello\""))
    }

    // ---- parseIrJson unit tests --------------------------------------------

    @Test
    fun `parseIrJson strips json fences`() {
        val plan = LlmPlanner(NoopProvider(), CommandRegistry()).parseIrJson(
            "```json\n{\"type\":\"invoke\",\"command\":\"test.hello\"}\n```"
        )
        assertTrue(plan.isSuccess)
        assertEquals(1, plan.commands.size)
        assertEquals("test.hello", plan.commands[0].id)
    }

    @Test
    fun `parseIrJson missing command is a parse error`() {
        val plan = LlmPlanner(NoopProvider(), CommandRegistry()).parseIrJson("""{"type":"invoke","args":{}}""")
        assertFalse(plan.isSuccess)
        assertEquals("LLM_PARSE_ERROR", plan.error?.code)
    }

    @Test
    fun `parseIrJson empty sequence is a parse error`() {
        val plan = LlmPlanner(NoopProvider(), CommandRegistry()).parseIrJson("""{"type":"sequence","steps":[]}""")
        assertFalse(plan.isSuccess)
        assertEquals("LLM_PARSE_ERROR", plan.error?.code)
    }

    // ---- Helpers ----------------------------------------------------------

    private fun registerEchoCommand(id: String) {
        val plugin = object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "echo-plugin-constrained",
                name = "Echo",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Echo plugin",
                provider = ProviderInfo("TestOrg", "https://example.com"),
                entry = "com.morainet.mcos.plugin.echo.EchoPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = id,
                        version = "1.0",
                        title = "Hello",
                        description = "Say hello",
                        sideEffectClass = SideEffectClass.read,
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
 * Fake provider advertising CONSTRAINED (grammar-constrained decoding).
 */
class ConstrainedFakeProvider(
    override val id: String,
    private val responses: List<LlmResponse>,
    override val grammarFormats: Set<GrammarFormat> = setOf(GrammarFormat.JSON_SCHEMA),
) : LlmProvider {

    override val capabilities: Set<Capability> =
        setOf(Capability.CHAT, Capability.CONSTRAINED)

    var constrainedCalls: Int = 0
        private set

    var chatCalls: Int = 0
        private set

    var lastGrammar: LlmGrammar? = null
        private set

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse {
        chatCalls++
        return responses[minOf(chatCalls - 1, responses.lastIndex)]
    }

    override suspend fun constrainedChat(messages: List<ChatMessage>, grammar: LlmGrammar): LlmResponse {
        constrainedCalls++
        lastGrammar = grammar
        return responses[minOf(constrainedCalls - 1, responses.lastIndex)]
    }
}

/**
 * Provider that always fails; used for exercising internal helpers that do
 * not need a working provider (e.g. [LlmPlanner.parseIrJson]).
 */
private class NoopProvider : LlmProvider {
    override val id = "noop"
    override val capabilities = setOf(Capability.CHAT)
    override suspend fun chat(messages: List<ChatMessage>): LlmResponse =
        LlmResponse.Err("LLM_NETWORK_ERROR", "noop provider", false)
}
