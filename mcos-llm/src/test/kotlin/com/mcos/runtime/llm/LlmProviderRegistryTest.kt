package com.mcos.runtime.llm

import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for [LlmProviderRegistry] -- multi-provider registration,
 * capability routing, and health probing (06 §17 V1).
 */
class LlmProviderRegistryTest {

    // ---- R1-R3: registration & query -------------------------------------

    @Test
    fun `R1-register stores providers in priority order`() {
        val registry = LlmProviderRegistry()
        val primary = FakeRegistryProvider("primary", capabilities = setOf(Capability.CHAT))
        val backup = FakeRegistryProvider("backup", capabilities = setOf(Capability.CHAT))

        registry.register(primary)
        registry.register(backup)

        assertEquals(2, registry.size)
        assertEquals(listOf("primary", "backup"), registry.all().map { it.id })
        assertEquals(primary, registry.all().first())
    }

    @Test
    fun `R2-duplicate id registration is ignored`() {
        val registry = LlmProviderRegistry()
        val first = FakeRegistryProvider("dup", capabilities = setOf(Capability.CHAT))
        val second = FakeRegistryProvider("dup", capabilities = setOf(Capability.CHAT))

        assertTrue(registry.register(first))
        assertFalse(registry.register(second)) // duplicate id rejected
        assertEquals(1, registry.size)
    }

    @Test
    fun `R3-unregister removes provider`() {
        val registry = LlmProviderRegistry()
        registry.register(FakeRegistryProvider("a", capabilities = setOf(Capability.CHAT)))

        assertTrue(registry.unregister("a"))
        assertFalse(registry.unregister("a")) // already gone
        assertEquals(0, registry.size)
        assertFalse(registry.isRegistered("a"))
    }

    // ---- R4-R5: capability filtering --------------------------------------

    @Test
    fun `R4-withCapability filters by advertised capability`() {
        val registry = LlmProviderRegistry()
        registry.register(FakeRegistryProvider("chat-only", capabilities = setOf(Capability.CHAT)))
        registry.register(FakeRegistryProvider("tool", capabilities = setOf(Capability.CHAT, Capability.TOOL_CALL)))
        registry.register(FakeRegistryProvider("embed", capabilities = setOf(Capability.EMBED)))

        assertEquals(listOf("chat-only", "tool"), registry.withCapability(Capability.CHAT).map { it.id })
        assertEquals(listOf("tool"), registry.withCapability(Capability.TOOL_CALL).map { it.id })
        assertEquals(listOf("embed"), registry.withCapability(Capability.EMBED).map { it.id })
    }

    @Test
    fun `R5-chatProviders returns only chat-capable providers in order`() {
        val registry = LlmProviderRegistry()
        registry.register(FakeRegistryProvider("embed", capabilities = setOf(Capability.EMBED)))
        registry.register(FakeRegistryProvider("chat1", capabilities = setOf(Capability.CHAT)))
        registry.register(FakeRegistryProvider("chat2", capabilities = setOf(Capability.CHAT, Capability.TOOL_CALL)))

        assertEquals(listOf("chat1", "chat2"), registry.chatProviders().map { it.id })
    }

    // ---- R6-R8: health probing --------------------------------------------

    @Test
    fun `R6-healthyProviders excludes unhealthy providers`() = runBlocking {
        val registry = LlmProviderRegistry()
        registry.register(FakeRegistryProvider("healthy", capabilities = setOf(Capability.CHAT)))
        registry.register(
            FakeRegistryProvider("down", capabilities = setOf(Capability.CHAT), probe = LlmProbeResult.Err("DOWN", "timeout"))
        )

        assertEquals(listOf("healthy"), registry.healthyProviders().map { it.id })
    }

    @Test
    fun `R7-primaryHealthy returns highest-priority healthy provider`() = runBlocking {
        val registry = LlmProviderRegistry()
        registry.register(
            FakeRegistryProvider("down", capabilities = setOf(Capability.CHAT), probe = LlmProbeResult.Err("DOWN", "timeout"))
        )
        registry.register(FakeRegistryProvider("up", capabilities = setOf(Capability.CHAT)))

        assertEquals("up", registry.primaryHealthy()?.id)
    }

    @Test
    fun `R8-primaryHealthy null when all providers unhealthy`() = runBlocking {
        val registry = LlmProviderRegistry()
        registry.register(
            FakeRegistryProvider("a", capabilities = setOf(Capability.CHAT), probe = LlmProbeResult.Err("DOWN", "x"))
        )

        assertNull(registry.primaryHealthy())
    }
}

/**
 * Configurable fake provider for registry tests.
 */
class FakeRegistryProvider(
    override val id: String,
    override val capabilities: Set<Capability>,
    private val probe: LlmProbeResult = LlmProbeResult.Ok,
    private val chatResponse: LlmResponse = LlmResponse.Err("NO_RESPONSE", "not used", false)
) : LlmProvider {

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse = chatResponse

    override suspend fun probe(): LlmProbeResult = probe
}
