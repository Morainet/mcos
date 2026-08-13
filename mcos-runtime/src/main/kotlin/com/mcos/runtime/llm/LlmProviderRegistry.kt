package com.mcos.runtime.llm

/**
 * Registry of [LlmProvider]s with capability-based routing support
 * (06 §17 V1 multi-provider).
 *
 * Providers are stored in registration order, which defines priority:
 * earlier registrations are preferred by the fallback chain.
 */
class LlmProviderRegistry {

    private val providers: LinkedHashMap<String, LlmProvider> = LinkedHashMap()

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
     * Providers whose [LlmProvider.probe] returns [LlmProbeResult.Ok],
     * in priority order. Unhealthy providers are excluded from routing
     * (06 §18.1 "On-device fallback" — a failed on-device probe routes to cloud).
     */
    suspend fun healthyProviders(): List<LlmProvider> {
        val healthy = mutableListOf<LlmProvider>()
        for (p in providers.values) {
            if (p.probe() is LlmProbeResult.Ok) healthy += p
        }
        return healthy
    }

    /** Highest-priority healthy provider, or `null` if none is healthy. */
    suspend fun primaryHealthy(): LlmProvider? = healthyProviders().firstOrNull()

    /** Number of registered providers. */
    val size: Int get() = providers.size

    /** True if a provider with [id] is registered. */
    fun isRegistered(id: String): Boolean = providers.containsKey(id)
}
