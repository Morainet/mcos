package com.morainet.mcos.runtime.llm

import com.morainet.mcos.runtime.registry.CommandRegistry
import com.morainet.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tests for [LlmPlanner]'s multi-provider fallback chain
 * (06 §17 V1, §18.1 "On-device fallback": on-device failure routes to cloud).
 */
class LlmPlannerFallbackTest {

    private lateinit var registry: CommandRegistry

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        registerEchoCommand("test.hello")
    }

    // ---- F1: primary succeeds ---------------------------------------------

    @Test
    fun `F1-primary provider success is used and recorded`() = runBlocking {
        val primary = SequenceFakeProvider(
            "primary",
            responses = listOf(LlmResponse.Ok("test.hello(greeting=\"hi\")"))
        )
        val backup = SequenceFakeProvider("backup", responses = listOf(LlmResponse.Ok("test.hello(greeting=\"via-backup\")")))

        val planner = LlmPlanner(primary, registry, fallbacks = listOf(backup))
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(1, primary.chatCalls)
        assertEquals(0, backup.chatCalls)
        assertEquals("primary", plan.providerId)
    }

    // ---- F2: retryable primary failure falls back -------------------------

    @Test
    fun `F2-retryable primary failure falls back to backup provider`() = runBlocking {
        val primary = SequenceFakeProvider(
            "on-device",
            responses = listOf(LlmResponse.Err("LLM_TIMEOUT", "on-device timeout", true))
        )
        val backup = SequenceFakeProvider(
            "cloud",
            responses = listOf(LlmResponse.Ok("test.hello(greeting=\"hi\")"))
        )

        val planner = LlmPlanner(primary, registry, fallbacks = listOf(backup))
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(1, primary.chatCalls)
        assertEquals(1, backup.chatCalls)
        assertEquals("cloud", plan.providerId)
    }

    // ---- F3: non-retryable failure stops the chain ------------------------

    @Test
    fun `F3-non-retryable primary failure does not fall back`() = runBlocking {
        val primary = SequenceFakeProvider(
            "primary",
            responses = listOf(LlmResponse.Err("AUTH_ERROR", "bad key", false))
        )
        val backup = SequenceFakeProvider("backup", responses = listOf(LlmResponse.Ok("test.hello(greeting=\"hi\")")))

        val planner = LlmPlanner(primary, registry, fallbacks = listOf(backup))
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("AUTH_ERROR", plan.error?.code)
        assertEquals(0, backup.chatCalls) // never reached
        assertEquals("primary", plan.providerId)
    }

    // ---- F4: all providers fail -------------------------------------------

    @Test
    fun `F4-all providers failing returns last error with attempted ids`() = runBlocking {
        val primary = SequenceFakeProvider(
            "primary",
            responses = listOf(LlmResponse.Err("LLM_TIMEOUT", "t1", true))
        )
        val backup = SequenceFakeProvider(
            "backup",
            responses = listOf(LlmResponse.Err("LLM_TIMEOUT", "t2", true))
        )

        val planner = LlmPlanner(primary, registry, fallbacks = listOf(backup))
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("LLM_TIMEOUT", plan.error?.code)
        assertEquals("backup", plan.providerId)
        assertTrue(plan.thoughts.orEmpty().contains("primary"))
        assertTrue(plan.thoughts.orEmpty().contains("backup"))
    }

    // ---- F5: multi-hop fallback -------------------------------------------

    @Test
    fun `F5-fallback walks multiple providers in order`() = runBlocking {
        val p1 = SequenceFakeProvider("p1", responses = listOf(LlmResponse.Err("LLM_TIMEOUT", "t", true)))
        val p2 = SequenceFakeProvider("p2", responses = listOf(LlmResponse.Err("LLM_TIMEOUT", "t", true)))
        val p3 = SequenceFakeProvider("p3", responses = listOf(LlmResponse.Ok("test.hello(greeting=\"hi\")")))

        val planner = LlmPlanner(p1, registry, fallbacks = listOf(p2, p3))
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals(1, p1.chatCalls)
        assertEquals(1, p2.chatCalls)
        assertEquals(1, p3.chatCalls)
        assertEquals("p3", plan.providerId)
    }

    // ---- F6: no fallbacks (single provider) -------------------------------

    @Test
    fun `F6-no fallbacks configured behaves as before`() = runBlocking {
        val provider = SequenceFakeProvider(
            "solo",
            responses = listOf(LlmResponse.Err("LLM_TIMEOUT", "t", true))
        )

        val planner = LlmPlanner(provider, registry)
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("solo", plan.providerId)
    }

    // ---- Helpers ----------------------------------------------------------

    private fun registerEchoCommand(id: String) {
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
 * Fake provider that returns pre-configured responses in order and counts calls.
 */
class SequenceFakeProvider(
    override val id: String,
    private val responses: List<LlmResponse>,
    override val capabilities: Set<Capability> = setOf(Capability.CHAT)
) : LlmProvider {

    var chatCalls: Int = 0
        private set

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse {
        val response = responses[minOf(chatCalls, responses.lastIndex)]
        chatCalls++
        return response
    }
}
