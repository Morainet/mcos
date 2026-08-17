package com.mcos.runtime.llm

import com.mcos.runtime.registry.CommandRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Tests for PlanMode.LATENCY_TIERED -- latency-tiered routing (06 §13.1
 * routing strategy, §15.1 performance budget, §13.2 privacy gate).
 *
 * Routing contract:
 * - EXACT_CLI utterances go parser-only (no LLM round-trip).
 * - KNOWN_RECIPE utterances hit the local [RecipeMatcher] (no LLM round-trip).
 * - Everything else walks the LLM chain ordered by latency tier:
 *   ON_DEVICE first, CLOUD last; COMPLEX inverts when cloud is opted in;
 *   PRIVACY_SENSITIVE always keeps on-device first.
 */
class LlmPlannerLatencyTieredTest {

    private val registry = CommandRegistry()

    private val recipes = listOf(
        Recipe(
            id = "morning",
            triggers = listOf("good morning", "早上好"),
            dsl = "hello.greet(name=\"MCOS\")\nweather.now(city=\"auto\")",
        )
    )

    private fun planner(
        primary: LlmProvider,
        fallbacks: List<LlmProvider> = emptyList(),
        cloudFallbackEnabled: Boolean = false,
    ) = LlmPlanner(
        primary, registry,
        fallbacks = fallbacks,
        cloudFallbackEnabled = cloudFallbackEnabled,
        recipes = recipes,
    )

    // ---- L1-L2: zero-latency paths (no LLM) ------------------------------

    @Test
    fun `L1-exact CLI utterance is parsed directly without any LLM call`() = runBlocking {
        val llm = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"local\")")))

        val plan = planner(llm).plan("""camera.capture(flash="on")""", PlanMode.LATENCY_TIERED)

        assertTrue(plan.isSuccess)
        assertEquals("camera.capture", plan.commands[0].id)
        assertEquals("on", plan.commands[0].args["flash"]?.jsonPrimitive?.content)
        assertEquals(0, llm.chatCalls)
        assertEquals("direct-parser", plan.route)
        assertEquals(UtteranceClass.EXACT_CLI, plan.utteranceClass)
    }

    @Test
    fun `L2-known recipe is served locally without any LLM call`() = runBlocking {
        val llm = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"local\")")))

        val plan = planner(llm).plan("good morning", PlanMode.LATENCY_TIERED)

        assertTrue(plan.isSuccess)
        assertEquals(2, plan.commands.size)
        assertEquals("hello.greet", plan.commands[0].id)
        assertEquals(0, llm.chatCalls)
        assertEquals("recipe:morning", plan.route)
        assertEquals(UtteranceClass.KNOWN_RECIPE, plan.utteranceClass)
    }

    // ---- L3-L5: tier ordering ---------------------------------------------

    @Test
    fun `L3-simple intent prefers on-device even when cloud is primary`() = runBlocking {
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"local\")")))

        // cloud passed as primary, on-device as fallback -> tiering must invert
        val plan = planner(cloud, fallbacks = listOf(onDevice)).plan("open the light", PlanMode.LATENCY_TIERED)

        assertTrue(plan.isSuccess)
        assertEquals("llama3", plan.providerId)
        assertEquals("local", plan.commands[0].args["greeting"]?.jsonPrimitive?.content)
        assertEquals(0, cloud.chatCalls)
        assertEquals(1, onDevice.chatCalls)
        assertEquals("llm:llama3", plan.route)
    }

    @Test
    fun `L4-complex intent goes cloud-first when opted in`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"local\")")))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        // "take a photo and share it" is COMPLEX (multi-step connector)
        val plan = planner(onDevice, fallbacks = listOf(cloud), cloudFallbackEnabled = true)
            .plan("take a photo and share it", PlanMode.LATENCY_TIERED)

        assertTrue(plan.isSuccess)
        assertEquals("openai", plan.providerId)
        assertEquals("cloud", plan.commands[0].args["greeting"]?.jsonPrimitive?.content)
        assertEquals(0, onDevice.chatCalls)
        assertEquals(1, cloud.chatCalls)
    }

    @Test
    fun `L5-privacy-sensitive always prefers on-device even with opt-in`() = runBlocking {
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"local\")")))

        // privacy-sensitive + opt-in: still on-device first (no cloud bypass)
        val plan = planner(cloud, fallbacks = listOf(onDevice), cloudFallbackEnabled = true)
            .plan("tell me my password", PlanMode.LATENCY_TIERED)

        assertTrue(plan.isSuccess)
        assertEquals("llama3", plan.providerId)
        assertEquals(0, cloud.chatCalls)
        assertEquals(1, onDevice.chatCalls)
    }

    // ---- L6-L7: fast-path misses fall through to the LLM chain ------------

    @Test
    fun `L6-malformed DSL utterance falls through to LLM chain`() = runBlocking {
        val llm = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"fallback\")")))

        // looks like DSL (EXACT_CLI) but fails to parse -> LLM fallback
        val plan = planner(llm).plan("camera.capture(flash=)", PlanMode.LATENCY_TIERED)

        assertTrue(plan.isSuccess)
        assertEquals(1, llm.chatCalls)
        assertEquals("llm:llama3", plan.route)
    }

    @Test
    fun `L7-broken recipe DSL falls through to LLM chain`() = runBlocking {
        val llm = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"fallback\")")))
        val broken = listOf(
            Recipe("broken", listOf("do the bad thing"), "hello.greet(name=)")
        )
        val p = LlmPlanner(llm, registry, recipes = broken)

        val plan = p.plan("do the bad thing", PlanMode.LATENCY_TIERED)

        assertTrue(plan.isSuccess)
        assertEquals(1, llm.chatCalls)
        assertEquals("llm:llama3", plan.route)
    }

    // ---- L8: telemetry ------------------------------------------------------

    @Test
    fun `L8-llm path records latency and route telemetry`() = runBlocking {
        val llm = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"x\")")))

        val plan = planner(llm).plan("open the light", PlanMode.LATENCY_TIERED)

        assertTrue(plan.isSuccess)
        assertNotNull(plan.latencyMs)
        assertTrue(plan.latencyMs!! >= 0)
        assertEquals("llm:llama3", plan.route)
        assertEquals(UtteranceClass.SIMPLE, plan.utteranceClass)
    }

    // ---- L9-L10: privacy gate preserved in tiered mode ----------------------

    @Test
    fun `L9-simple on-device failure without opt-in still hits the gate`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Err(LlmErrorCode.CAPABILITY_EXCEEDED, "too complex", true)))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        val plan = planner(onDevice, fallbacks = listOf(cloud))
            .plan("open the light", PlanMode.LATENCY_TIERED)

        assertFalse(plan.isSuccess)
        assertEquals(LlmErrorCode.CLOUD_FALLBACK_DISABLED, plan.error?.code)
        assertEquals(0, cloud.chatCalls)
        assertFalse(plan.error!!.retryable)
    }

    @Test
    fun `L10-complex without opt-in stays on-device first and respects gate`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Err(LlmErrorCode.CAPABILITY_EXCEEDED, "too complex", true)))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        val plan = planner(onDevice, fallbacks = listOf(cloud)) // no opt-in
            .plan("take a photo and share it", PlanMode.LATENCY_TIERED)

        assertFalse(plan.isSuccess)
        assertEquals(LlmErrorCode.CLOUD_FALLBACK_DISABLED, plan.error?.code)
        assertEquals(1, onDevice.chatCalls)
        assertEquals(0, cloud.chatCalls)
    }

    // ---- L11: default mode keeps legacy chain order (regression) ------------

    @Test
    fun `L11-default plan mode keeps provider chain order untouched`() = runBlocking {
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"local\")")))

        // no mode arg: legacy behavior, primary (cloud) is tried first
        val plan = planner(cloud, fallbacks = listOf(onDevice)).plan("open the light")

        assertTrue(plan.isSuccess)
        assertEquals("openai", plan.providerId)
        assertEquals(1, cloud.chatCalls)
        assertEquals(0, onDevice.chatCalls)
        assertNull(plan.utteranceClass) // telemetry only populated in tiered mode
    }
}
