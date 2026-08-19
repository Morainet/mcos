package com.morainet.mcos.llm

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registry of [LlmProvider]s with capability-based routing support
 * (06 §17 V1 multi-provider).
 *
 * Providers are stored in registration order, which defines priority:
 * earlier registrations are preferred by the fallback chain.
 *
 * Health probing follows [LlmProbePolicy]: results are cached (a healthy
 * provider is not re-probed within `cacheTtlMs`, a failed provider not
 * within `failureCooldownMs`), single probes are bounded by
 * `probeTimeoutMs`, and all providers are probed in parallel by default.
 */
class LlmProviderRegistry {

    private val providers: LinkedHashMap<String, LlmProvider> = LinkedHashMap()

    /** Last known health per provider id; written by probes, read by snapshots. */
    private val healthCache: ConcurrentHashMap<String, ProviderHealth> = ConcurrentHashMap()

    /**
     * Register a provider. Returns `true` if this id was not already
     * registered (a duplicate id is ignored).
     */
    fun register(provider: LlmProvider): Boolean =
        if (providers.containsKey(provider.id)) {
            false
        } else {
            providers[provider.id] = provider
            true
        }

    /** Unregister a provider by id. Returns `true` if it was present. */
    fun unregister(id: String): Boolean = providers.remove(id) != null

    /** All registered providers, in priority order (registration order). */
    fun all(): List<LlmProvider> = providers.values.toList()

    /** Providers that advertise [Capability.CHAT], in priority order. */
    fun chatProviders(): List<LlmProvider> = providers.values.filter { Capability.CHAT in it.capabilities }

    /** Providers advertising a given capability, in priority order. */
    fun withCapability(capability: Capability): List<LlmProvider> =
        providers.values.filter { capability in it.capabilities }

    /**
     * On-device providers (06 §13.0), in priority order. Used to build an
     * on-device-first fallback chain; escalation beyond these requires the
     * "Allow cloud planner" opt-in (06 §13.2).
     */
    fun onDeviceProviders(): List<LlmProvider> =
        providers.values.filter { it.tier == ProviderTier.ON_DEVICE }

    /** Cloud providers, in priority order. */
    fun cloudProviders(): List<LlmProvider> =
        providers.values.filter { it.tier == ProviderTier.CLOUD }

    /**
     * Providers whose most recent probe was healthy, in priority order.
     * Unhealthy providers are excluded from routing
     * (06 §18.1 "On-device fallback" — a failed on-device probe routes to cloud).
     *
     * Probing follows [policy]: healthy results are cached for
     * [LlmProbePolicy.cacheTtlMs], failed providers are not re-probed within
     * [LlmProbePolicy.failureCooldownMs], a single probe is bounded by
     * [LlmProbePolicy.probeTimeoutMs], and providers are probed in parallel
     * when [LlmProbePolicy.concurrent] is true.
     */
    suspend fun healthyProviders(policy: LlmProbePolicy = LlmProbePolicy()): List<LlmProvider> =
        if (policy.concurrent) {
            coroutineScope {
                providers.values.map { p ->
                    async { p.id to probe(p, policy) }
                }.awaitAll()
            }
                .filter { (_, healthy) -> healthy }
                .mapNotNull { (id, _) -> providers[id] }
        } else {
            providers.values.filter { probe(it, policy) }
        }

    /** Highest-priority healthy provider, or `null` if none is healthy. */
    suspend fun primaryHealthy(policy: LlmProbePolicy = LlmProbePolicy()): LlmProvider? =
        healthyProviders(policy).firstOrNull()

    /**
     * Probe a single provider, honoring the cache. Returns `true` when the
     * provider is healthy. Never throws: a probe timeout is reported as
     * unhealthy ([LlmProbePolicy.probeTimeoutMs]).
     */
    private suspend fun probe(p: LlmProvider, policy: LlmProbePolicy): Boolean {
        val now = System.currentTimeMillis()
        val cached = healthCache[p.id]
        if (cached != null) {
            val last = cached.lastProbeAtMs ?: 0L
            if (cached.healthy && now - last < policy.cacheTtlMs) return true
            if (!cached.healthy && now - last < policy.failureCooldownMs) return false
        }

        val result = withTimeoutOrNull(policy.probeTimeoutMs) { p.probe() }
            ?: LlmProbeResult.Err(
                "LLM_PROBE_TIMEOUT",
                "Probe exceeded ${policy.probeTimeoutMs}ms"
            )

        val healthy = result is LlmProbeResult.Ok
        healthCache[p.id] = when (result) {
            is LlmProbeResult.Ok -> ProviderHealth(
                providerId = p.id,
                tier = p.tier,
                capabilities = p.capabilities,
                healthy = true,
                lastProbeAtMs = now,
                consecutiveFailures = 0,
            )
            is LlmProbeResult.Err -> ProviderHealth(
                providerId = p.id,
                tier = p.tier,
                capabilities = p.capabilities,
                healthy = false,
                lastProbeAtMs = now,
                consecutiveFailures = (cached?.consecutiveFailures ?: 0) + 1,
                errorCode = result.code,
                errorMessage = result.message,
            )
        }
        return healthy
    }

    /**
     * Last known health of every registered provider, without issuing new
     * network probes. Providers that have never been probed report
     * `healthy=false` with `lastProbeAtMs=null`. Used for diagnostics and
     * UI surfacing (06 §17 V1).
     */
    fun healthSnapshot(): List<ProviderHealth> =
        providers.values.map { p ->
            healthCache[p.id] ?: ProviderHealth(
                providerId = p.id,
                tier = p.tier,
                capabilities = p.capabilities,
                healthy = false,
                lastProbeAtMs = null,
            )
        }

    /**
     * Force a fresh probe of every provider (clearing the cache first) and
     * return the resulting snapshot. Used by UI "re-check" actions.
     */
    suspend fun probeAll(policy: LlmProbePolicy = LlmProbePolicy()): List<ProviderHealth> {
        healthCache.clear()
        healthyProviders(policy)
        return healthSnapshot()
    }

    /** Number of registered providers. */
    val size: Int get() = providers.size

    /** True if a provider with [id] is registered. */
    fun isRegistered(id: String): Boolean = providers.containsKey(id)
}
