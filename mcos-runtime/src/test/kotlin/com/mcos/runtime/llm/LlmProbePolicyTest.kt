package com.mcos.runtime.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for probing policy on [LlmProviderRegistry] (06 §17 V1):
 * result caching, failure cooldown, probe timeout, concurrent probing,
 * health snapshots and forced refresh.
 *
 * Named Q1-Q8 to avoid clashing with the existing P1-P10 GrammarLlmProvider
 * suite.
 */
class LlmProbePolicyTest {

    // ---- Q1-Q4: caching & cooldown ----------------------------------------

    @Test
    fun `Q1-healthy result is cached within TTL - no re-probe`() = runBlocking {
        val registry = LlmProviderRegistry()
        val counting = CountingProbeProvider("p1")
        registry.register(counting)

        // cold cache -> probes once
        val first = registry.healthyProviders()
        assertEquals(listOf("p1"), first.map { it.id })
        assertEquals(1, counting.probeCount)

        // warm cache -> served from cache, no second probe
        val second = registry.healthyProviders()
        assertEquals(listOf("p1"), second.map { it.id })
        assertEquals(1, counting.probeCount)
    }

    @Test
    fun `Q2-failed provider is not re-probed during cooldown`() = runBlocking {
        val registry = LlmProviderRegistry()
        val down = CountingProbeProvider("down", result = LlmProbeResult.Err("DOWN", "boom"))
        registry.register(down)
        val policy = LlmProbePolicy(failureCooldownMs = 60_000)

        assertTrue(registry.healthyProviders(policy).isEmpty())
        assertEquals(1, down.probeCount)

        // still in cooldown -> stays unhealthy, no re-probe
        assertTrue(registry.healthyProviders(policy).isEmpty())
        assertEquals(1, down.probeCount)
    }

    @Test
    fun `Q3-cache expires after TTL - provider is re-probed`() = runBlocking {
        val registry = LlmProviderRegistry()
        val counting = CountingProbeProvider("p1")
        registry.register(counting)
        val policy = LlmProbePolicy(cacheTtlMs = 50)

        registry.healthyProviders(policy)
        assertEquals(1, counting.probeCount)

        delay(80) // exceed cache TTL
        registry.healthyProviders(policy)
        assertEquals(2, counting.probeCount)
    }

    @Test
    fun `Q4-cooldown expires - failed provider is probed again`() = runBlocking {
        val registry = LlmProviderRegistry()
        val down = CountingProbeProvider(
            "down",
            result = LlmProbeResult.Err("DOWN", "temporary")
        )
        registry.register(down)
        val policy = LlmProbePolicy(failureCooldownMs = 50)

        assertTrue(registry.healthyProviders(policy).isEmpty())
        assertEquals(1, down.probeCount)

        delay(80) // cooldown expires
        assertTrue(registry.healthyProviders(policy).isEmpty()) // still Err
        assertEquals(2, down.probeCount)
    }

    // ---- Q5: probe timeout ------------------------------------------------

    @Test
    fun `Q5-slow probe is bounded by timeout and treated as unhealthy`() = runBlocking {
        val registry = LlmProviderRegistry()
        val slow = CountingProbeProvider("slow", delayMs = 5_000)
        registry.register(slow)
        val policy = LlmProbePolicy(probeTimeoutMs = 50)

        val start = System.currentTimeMillis()
        val healthy = registry.healthyProviders(policy)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(healthy.isEmpty(), "timed-out probe must not be healthy")
        assertTrue(elapsed < 1_000, "probe should be cancelled by timeout, took ${elapsed}ms")
    }

    // ---- Q6: concurrent probing -------------------------------------------

    @Test
    fun `Q6-concurrent probing is faster than sequential`() = runBlocking {
        val registry = LlmProviderRegistry()
        registry.register(CountingProbeProvider("a", delayMs = 200))
        registry.register(CountingProbeProvider("b", delayMs = 200))
        registry.register(CountingProbeProvider("c", delayMs = 200))

        val sequential = LlmProbePolicy(concurrent = false, probeTimeoutMs = 60_000)
        val concurrent = LlmProbePolicy(concurrent = true, probeTimeoutMs = 60_000)

        val seqMs = measure { registry.healthyProviders(sequential) }
        val concMs = measure { registry.healthyProviders(concurrent) }

        // 3 x 200ms sequential ≈ 600ms; concurrent ≈ 200ms + overhead
        assertTrue(seqMs >= 550, "sequential should take ~600ms, took ${seqMs}ms")
        assertTrue(concMs < 550, "concurrent should take ~200ms, took ${concMs}ms")
        assertTrue(concMs < seqMs, "concurrent (${concMs}ms) must beat sequential (${seqMs}ms)")
    }

    // ---- Q7-Q8: health snapshot & forced refresh ---------------------------

    @Test
    fun `Q7-healthSnapshot reflects last probe without network calls`() = runBlocking {
        val registry = LlmProviderRegistry()
        registry.register(CountingProbeProvider("ok"))
        registry.register(
            CountingProbeProvider("bad", result = LlmProbeResult.Err("AUTH", "401"))
        )

        // never probed -> unhealthy, lastProbeAtMs null
        val before = registry.healthSnapshot()
        assertEquals(listOf("ok", "bad"), before.map { it.providerId })
        assertTrue(before.all { !it.healthy && it.lastProbeAtMs == null })

        registry.healthyProviders()

        val after = registry.healthSnapshot()
        assertEquals("ok", after[0].providerId)
        assertTrue(after[0].healthy)
        assertNotNull(after[0].lastProbeAtMs)
        assertEquals("bad", after[1].providerId)
        assertFalse(after[1].healthy)
        assertEquals("AUTH", after[1].errorCode)
        assertEquals(1, after[1].consecutiveFailures)
    }

    @Test
    fun `Q8-probeAll forces refresh bypassing cache`() = runBlocking {
        val registry = LlmProviderRegistry()
        val counting = CountingProbeProvider("ok")
        registry.register(counting)

        registry.healthyProviders() // probe once (cached)
        assertEquals(1, counting.probeCount)

        registry.probeAll() // cache cleared -> probes again
        assertEquals(2, counting.probeCount)
        val snapshot = registry.healthSnapshot()
        assertTrue(snapshot.single().healthy)
        assertTrue(snapshot.single().lastProbeAtMs != null)
    }
}

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

/**
 * Fake provider whose probe returns a fixed result and can count invocations
 * and simulate latency.
 */
class CountingProbeProvider(
    override val id: String,
    private val result: LlmProbeResult = LlmProbeResult.Ok,
    private val delayMs: Long = 0,
) : LlmProvider {

    var probeCount: Int = 0
        private set

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse =
        LlmResponse.Err("NO_RESPONSE", "not used", false)

    override suspend fun probe(): LlmProbeResult {
        probeCount++
        if (delayMs > 0) delay(delayMs)
        return result
    }
}

/** Runs [block], returning elapsed wall-clock milliseconds. */
private suspend fun measure(block: suspend () -> Unit): Long {
    val start = System.currentTimeMillis()
    block()
    return System.currentTimeMillis() - start
}
