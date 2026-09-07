package com.morainet.mcos.runtime.core.config

import com.morainet.mcos.runtime.core.scheduler.RunScheduler
import com.morainet.mcos.runtime.core.scheduler.SchedulerConfig
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.security.RateLimits
import com.morainet.mcos.security.RedactionLevel
import com.morainet.mcos.security.UserPolicy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The runtime's live-tunable configuration surface — [03-runtime.md §19].
 *
 * This is the aggregate a host submits (via `RuntimeConfigManager.apply`) to
 * retune the whole runtime at once. Every field is validated ([validate]) and
 * fanned out to its consumer; the security-relevant fields also participate in
 * the most-restrictive-wins merge across the MDM / user / defaults sources.
 *
 * The defaults are the pre-§19 behaviour exactly, so `RuntimeConfig()` is a
 * no-op apply.
 *
 * @property maxParallel Global run-body concurrency (§19.1 range `[1, 16]`).
 *   Mapped onto [SchedulerConfig.maxConcurrentInvokes]; must equal
 *   `scheduler.maxConcurrentInvokes` — a drift between the two is rejected by
 *   [validate] so the aggregate can never disagree with the scheduler it feeds.
 * @property defaultTimeoutMs Global default command timeout (§19.1 range
 *   `[1000, 600000]`), applied to descriptors that kept the manifest default.
 * @property strictSchemaOutput When true, a command's successful result is
 *   validated against its declared `outputSchema` (a violation → SCHEMA_VIOLATION).
 * @property auditRedaction Audit `ir` redaction level. Security-relevant:
 *   the merge takes the maximum across sources (a user may raise, never lower).
 * @property eventTriggersEnabled Master switch for event triggers. Security-
 *   relevant: the merge ANDs across sources (any source may disable).
 * @property enterpriseAllowlist Command-id globs composed into the enterprise
 *   policy's `allowCommands` **and** enforced as a Stage-3 visibility gate.
 *   `null` = no allow-list (unconstrained). Non-null forces [auditRedaction]
 *   to at least STRICT (an enterprise deployment tight enough to allow-list
 *   commands should not ship raw audit).
 * @property networkAllowList Host globs composed into the enterprise policy's
 *   `networkAllow`.
 * @property rateLimits The three rate-limit knobs (invoke/min, destructive/hr,
 *   background fires/hr).
 * @property scheduler The full scheduler config (its live subset is hot-applied
 *   via [RunScheduler.reconfigure]).
 * @property userPolicy User confirmation-tightening flags (08 §4.2).
 */
data class RuntimeConfig(
    val maxParallel: Int = 4,
    val defaultTimeoutMs: Long = 60_000L,
    val strictSchemaOutput: Boolean = false,
    val auditRedaction: RedactionLevel = RedactionLevel.DEFAULT,
    val eventTriggersEnabled: Boolean = true,
    val enterpriseAllowlist: List<String>? = null,
    val networkAllowList: List<String> = emptyList(),
    val rateLimits: RateLimits = RateLimits(),
    val scheduler: SchedulerConfig = SchedulerConfig(),
    val userPolicy: UserPolicy = UserPolicy(),
) {

    /**
     * Validate all §19 constraints. Throws [McosException] with code `INTERNAL`
     * and `details.component = "config"` on any violation — a rejected config
     * is NEVER silently coerced (§19.1). Returns `this` for chaining.
     */
    fun validate(): RuntimeConfig {
        fun fail(reason: String): Nothing = throw McosException(
            code = "INTERNAL",
            message = "Invalid RuntimeConfig: $reason",
            details = buildJsonObject { put("component", "config") },
        )

        if (maxParallel !in 1..RunScheduler.MAX_CONCURRENT_INVOKES) {
            fail("maxParallel must be in 1..${RunScheduler.MAX_CONCURRENT_INVOKES} (§19.1); got $maxParallel")
        }
        if (defaultTimeoutMs !in 1_000L..600_000L) {
            fail("defaultTimeoutMs must be in 1000..600000 (§19.1); got $defaultTimeoutMs")
        }
        if (maxParallel != scheduler.maxConcurrentInvokes) {
            fail(
                "maxParallel ($maxParallel) must equal scheduler.maxConcurrentInvokes " +
                    "(${scheduler.maxConcurrentInvokes}); the two map to the same knob"
            )
        }
        if (rateLimits.maxInvokesPerMinute < 1) {
            fail("rateLimits.maxInvokesPerMinute must be >= 1; got ${rateLimits.maxInvokesPerMinute}")
        }
        if (rateLimits.maxDestructivePerHour < 1) {
            fail("rateLimits.maxDestructivePerHour must be >= 1; got ${rateLimits.maxDestructivePerHour}")
        }
        if (rateLimits.maxBackgroundFiresPerHour < 1) {
            fail("rateLimits.maxBackgroundFiresPerHour must be >= 1; got ${rateLimits.maxBackgroundFiresPerHour}")
        }
        if (enterpriseAllowlist != null && enterpriseAllowlist.isEmpty()) {
            fail("enterpriseAllowlist, when present, must be non-empty (use null for no allow-list)")
        }
        return this
    }

    /**
     * Return the config normalized for §19 security invariants that are
     * *forced* rather than rejected:
     * - a non-null [enterpriseAllowlist] forces [auditRedaction] to at least
     *   STRICT (never lower). Validate first, so callers get the same forcing
     *   whether they call [validate] or [normalized].
     */
    fun normalized(): RuntimeConfig {
        validate()
        val forcedRedaction = if (enterpriseAllowlist != null && auditRedaction < RedactionLevel.STRICT) {
            RedactionLevel.STRICT
        } else {
            auditRedaction
        }
        return if (forcedRedaction == auditRedaction) this else copy(auditRedaction = forcedRedaction)
    }
}
