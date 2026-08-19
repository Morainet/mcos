package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Tests for the on-device -> cloud fallback privacy gate
 * (06 §13.0 tiers, §13.2 "Allow cloud planner" opt-in, §17 V2 on-device chain).
 *
 * Core rule: once an ON_DEVICE provider has been attempted, escalation to a
 * CLOUD provider requires `cloudFallbackEnabled = true`. Without the opt-in
 * the failure surfaces as a refusal ([LlmErrorCode.CLOUD_FALLBACK_DISABLED])
 * and no cloud provider is touched -- privacy-first default.
 */
class LlmPlannerOnDeviceFallbackTest {

    private val registry = CommandRegistry() // empty: no commands needed for these paths

    // ---- O1: on-device success never touches cloud -------------------------

    @Test
    fun `O1-on-device success plans locally and records provider`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"local\")")))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        val planner = LlmPlanner(onDevice, registry, fallbacks = listOf(cloud))
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals("llama3", plan.providerId)
        assertEquals("local", plan.commands[0].args["greeting"]?.jsonPrimitive?.content)
        assertEquals(1, onDevice.chatCalls)
        assertEquals(0, cloud.chatCalls)
    }

    // ---- O2: CAPABILITY_EXCEEDED + no opt-in -> refusal, cloud untouched ----

    @Test
    fun `O2-no opt-in refuses escalation and never calls cloud`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Err(LlmErrorCode.CAPABILITY_EXCEEDED, "too complex for on-device", true)))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        val planner = LlmPlanner(onDevice, registry, fallbacks = listOf(cloud)) // opt-in disabled
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals(LlmErrorCode.CLOUD_FALLBACK_DISABLED, plan.error?.code)
        assertFalse(plan.error!!.retryable)
        assertTrue(plan.thoughts.orEmpty().contains("privacy gate"))
        assertEquals(1, onDevice.chatCalls)
        assertEquals(0, cloud.chatCalls)
    }

    // ---- O3: CAPABILITY_EXCEEDED + opt-in -> escalates to cloud -------------

    @Test
    fun `O3-opt-in escalates capability failure to cloud`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Err(LlmErrorCode.CAPABILITY_EXCEEDED, "too complex", true)))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        val planner = LlmPlanner(onDevice, registry, fallbacks = listOf(cloud), cloudFallbackEnabled = true)
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals("openai", plan.providerId)
        assertEquals("cloud", plan.commands[0].args["greeting"]?.jsonPrimitive?.content)
        assertEquals(1, onDevice.chatCalls)
        assertEquals(1, cloud.chatCalls)
    }

    // ---- O4: gate guards ALL on-device failures, not just capability ones --

    @Test
    fun `O4-no opt-in blocks escalation for any on-device failure`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Err("LLM_TIMEOUT", "model crashed", true)))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        val planner = LlmPlanner(onDevice, registry, fallbacks = listOf(cloud))
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals(LlmErrorCode.CLOUD_FALLBACK_DISABLED, plan.error?.code)
        assertEquals(0, cloud.chatCalls)
    }

    // ---- O5: non-retryable on-device error stops even with opt-in -----------

    @Test
    fun `O5-non-retryable on-device error never escalates even with opt-in`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Err("ON_DEVICE_CRASH", "out of memory", false)))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        val planner = LlmPlanner(onDevice, registry, fallbacks = listOf(cloud), cloudFallbackEnabled = true)
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("ON_DEVICE_CRASH", plan.error?.code)
        assertEquals(0, cloud.chatCalls)
    }

    // ---- O6: opt-in + cloud also fails -> error with attempted ids ----------

    @Test
    fun `O6-opt-in with failing cloud reports both attempted providers`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Err(LlmErrorCode.CAPABILITY_EXCEEDED, "too complex", true)))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Err("LLM_API_ERROR", "quota exceeded", true)))

        val planner = LlmPlanner(onDevice, registry, fallbacks = listOf(cloud), cloudFallbackEnabled = true)
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("LLM_API_ERROR", plan.error?.code)
        assertEquals("openai", plan.providerId)
        assertTrue(plan.thoughts.orEmpty().contains("llama3") && plan.thoughts.orEmpty().contains("openai"))
    }

    // ---- O7: cloud-only chain is unaffected by the gate (regression) --------

    @Test
    fun `O7-cloud only chain never hits privacy gate`() = runBlocking {
        val p1 = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Err("LLM_TIMEOUT", "t", true)))
        val p2 = TieredFakeProvider("gemini", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"fallback\")")))

        val planner = LlmPlanner(p1, registry, fallbacks = listOf(p2)) // opt-in irrelevant
        val plan = planner.plan("say hi")

        assertTrue(plan.isSuccess)
        assertEquals("gemini", plan.providerId)
        assertEquals(1, p1.chatCalls)
        assertEquals(1, p2.chatCalls)
    }

    // ---- O8: no cloud in chain -> gate is not triggered ---------------------

    @Test
    fun `O8-on-device only chain reports its own error without gate`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            chatResponses = listOf(LlmResponse.Err("LLM_TIMEOUT", "model crashed", true)))

        val planner = LlmPlanner(onDevice, registry)
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals("LLM_TIMEOUT", plan.error?.code) // own error, not the gate refusal
    }

    // ---- O9: registry tier filtering ----------------------------------------

    @Test
    fun `O9-registry filters providers by tier`() = runBlocking {
        val registry = LlmProviderRegistry()
        registry.register(TieredFakeProvider("openai", tier = ProviderTier.CLOUD, chatResponses = emptyList()))
        registry.register(TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE, chatResponses = emptyList()))
        registry.register(TieredFakeProvider("gemini", tier = ProviderTier.CLOUD, chatResponses = emptyList()))

        assertEquals(listOf("llama3"), registry.onDeviceProviders().map { it.id })
        assertEquals(listOf("openai", "gemini"), registry.cloudProviders().map { it.id })
    }

    // ---- O10: on-device TOOL_CALL provider keeps native mode + gate ---------

    @Test
    fun `O10-on-device native tool calling failure respects gate`() = runBlocking {
        val onDevice = TieredFakeProvider("llama3", tier = ProviderTier.ON_DEVICE,
            capabilities = setOf(Capability.CHAT, Capability.TOOL_CALL),
            chatResponses = emptyList(),
            toolResponses = listOf(ToolCallResponse.Err(LlmErrorCode.CAPABILITY_EXCEEDED, "no tool support", true)))
        val cloud = TieredFakeProvider("openai", tier = ProviderTier.CLOUD,
            chatResponses = listOf(LlmResponse.Ok("test.hello(greeting=\"cloud\")")))

        // opt-in disabled -> gate refuses, cloud untouched
        val planner = LlmPlanner(onDevice, registry, fallbacks = listOf(cloud))
        val plan = planner.plan("say hi")

        assertFalse(plan.isSuccess)
        assertEquals(LlmErrorCode.CLOUD_FALLBACK_DISABLED, plan.error?.code)
        assertEquals(0, cloud.chatCalls)
    }
}

/**
 * Fake provider with configurable tier, chat and tool-call response sequences,
 * and call counters.
 */
class TieredFakeProvider(
    override val id: String,
    override val tier: ProviderTier = ProviderTier.CLOUD,
    override val capabilities: Set<Capability> = setOf(Capability.CHAT),
    private val chatResponses: List<LlmResponse>,
    private val toolResponses: List<ToolCallResponse> = emptyList(),
) : LlmProvider {

    var chatCalls: Int = 0
        private set
    var toolCallCalls: Int = 0
        private set

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse {
        val response = if (chatResponses.isEmpty()) {
            LlmResponse.Err("NO_RESPONSE", "no chat responses", true)
        } else {
            chatResponses[minOf(chatCalls, chatResponses.lastIndex)]
        }
        chatCalls++
        return response
    }

    override suspend fun toolCall(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
    ): ToolCallResponse {
        val response = if (toolResponses.isEmpty()) {
            ToolCallResponse.Err("NO_RESPONSE", "no tool responses", true)
        } else {
            toolResponses[minOf(toolCallCalls, toolResponses.lastIndex)]
        }
        toolCallCalls++
        return response
    }
}
