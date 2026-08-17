package com.morainet.mcos.runtime.llm

/**
 * Probing policy for [LlmProviderRegistry] health checks (06 §17 V1).
 *
 * Probing is expensive (a network round-trip per provider), so results are
 * cached: a healthy provider is not re-probed within [cacheTtlMs], and a
 * failed provider is not re-probed within [failureCooldownMs]. A single probe
 * is bounded by [probeTimeoutMs]; providers that exceed it are treated as
 * unhealthy. When [concurrent] is true all providers are probed in parallel.
 */
data class LlmProbePolicy(
    /** Healthy probe results are reused for routing for this long. */
    val cacheTtlMs: Long = 30_000,
    /** Failed providers are not re-probed within this window. */
    val failureCooldownMs: Long = 10_000,
    /** Upper bound for a single probe call; timeout maps to unhealthy. */
    val probeTimeoutMs: Long = 5_000,
    /** Probe all providers in parallel instead of sequentially. */
    val concurrent: Boolean = true,
)

/**
 * Immutable snapshot of one provider's health, as last seen by
 * [LlmProviderRegistry]. Exposed via [LlmProviderRegistry.healthSnapshot]
 * for diagnostics and UI surfacing (06 §17 V1).
 */
data class ProviderHealth(
    val providerId: String,
    val tier: ProviderTier,
    val capabilities: Set<Capability>,
    val healthy: Boolean,
    /** Wall-clock time of the last probe, or `null` if never probed. */
    val lastProbeAtMs: Long? = null,
    /** Consecutive failed probes since the last success. */
    val consecutiveFailures: Int = 0,
    /** Error code from the most recent [LlmProbeResult.Err], if any. */
    val errorCode: String? = null,
    /** Error message from the most recent [LlmProbeResult.Err], if any. */
    val errorMessage: String? = null,
)
