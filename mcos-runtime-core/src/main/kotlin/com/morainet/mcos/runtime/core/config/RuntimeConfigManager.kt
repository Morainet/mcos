package com.morainet.mcos.runtime.core.config

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.scheduler.RunScheduler
import com.morainet.mcos.runtime.core.workflow.EventTriggerManager
import com.morainet.mcos.runtime.core.workflow.ScheduleTriggerManager
import com.morainet.mcos.runtime.core.workflow.TriggerLimits
import com.morainet.mcos.security.EnterprisePolicy
import com.morainet.mcos.security.RedactionLevel
import com.morainet.mcos.security.TokenBucketRateLimiter
import com.morainet.mcos.security.UserPolicy
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The §19 source-precedence aggregator and hot-reload fan-out — [03-runtime.md
 * §19]. Owns the single live [RuntimeConfig] and is the one place that:
 *
 * 1. **Merges** an incoming config with the two optional pull sources — MDM /
 *    enterprise ([mdm]) and user ([user]) — under the §19.1 precedence rules
 *    (see [merge]).
 * 2. **Validates** the merged result (never silently coerces — an invalid
 *    config throws).
 * 3. **Fans out** each field to its live consumer: the scheduler
 *    ([RunScheduler.reconfigure]), the rate limiter
 *    ([TokenBucketRateLimiter.reconfigure]), both trigger managers'
 *    background-fire budget, and the read-through suppliers wired into the
 *    audit sink, the permission kernel and the Executor.
 * 4. Emits an always-audited **ConfigChanged** record (§19 "ConfigChanged
 *    event", never silent) capturing the before/after of the security-relevant
 *    fields.
 *
 * The read-through fields (`auditRedaction`, `userPolicy`,
 * `defaultTimeoutMs`, `strictSchemaOutput`, `enterpriseAllowlist`,
 * `networkAllowList`) need no push: their consumers read the manager's live
 * [current] value via the suppliers the host wired at construction. Only the
 * push-driven consumers (scheduler / limiter / trigger managers) are retuned in
 * [apply].
 *
 * Thread-safe: [apply] is `@Synchronized` and [current] is a volatile read, so
 * concurrent invocations observe a consistent config.
 *
 * @param registry Used to resolve the [RuntimeConfig.enterpriseAllowlist]
 *   deferral rule (§19.1: an allow-list whose entries reference no
 *   currently-registered command is deferred, the prior value kept).
 * @param scheduler The live scheduler to retune (its `currentConfig()` is also
 *   the source of truth the merged config's `maxParallel` is validated against).
 * @param rateLimiter The token-bucket limiter to retune.
 * @param eventTriggers Event-trigger manager whose background-fire budget is retuned.
 * @param scheduleTriggers Schedule-trigger manager whose budget is retuned.
 * @param auditLog Sink for the always-appended ConfigChanged record.
 * @param mdm Optional pull source for the enterprise/MDM config layer (highest
 *   precedence). Returns null when no MDM config is present.
 * @param user Optional pull source for the user config layer (middle precedence).
 * @param initial The starting config (defaults to `RuntimeConfig()` — the
 *   pre-§19 behaviour). Validated + normalized on construction.
 */
class RuntimeConfigManager(
    private val registry: CommandRegistry,
    private val scheduler: RunScheduler,
    private val rateLimiter: TokenBucketRateLimiter,
    private val eventTriggers: EventTriggerManager,
    private val scheduleTriggers: ScheduleTriggerManager,
    private val auditLog: AuditLog,
    private val mdm: () -> RuntimeConfig? = { null },
    private val user: () -> RuntimeConfig? = { null },
    initial: RuntimeConfig = RuntimeConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var live: RuntimeConfig = initial.normalized()

    /** The currently-active, merged-and-validated config. Never throws. */
    fun current(): RuntimeConfig = live

    // ─── Read-through suppliers (wired into consumers at host build time) ──

    fun redactionLevel(): RedactionLevel = live.auditRedaction
    fun userPolicy(): UserPolicy = live.userPolicy
    fun defaultTimeout(): Long = live.defaultTimeoutMs
    fun strictSchemaOutput(): Boolean = live.strictSchemaOutput

    /**
     * The Stage-3 command allow-list the Executor consults (null = no gate).
     * Independent of the enterprise-policy composition (which enforces the same
     * list at Stage 6); this drives the visibility gate.
     */
    fun commandAllowlist(): List<String>? = live.enterpriseAllowlist

    /**
     * The overlay [EnterprisePolicy] projected from the live config's
     * allow-lists, for composition into a `UnionEnterprisePolicySource`. Null
     * when neither allow-list constrains — the base policy passes through
     * unchanged.
     */
    fun enterpriseOverlay(): EnterprisePolicy? {
        val cmds = live.enterpriseAllowlist ?: emptyList()
        val net = live.networkAllowList
        if (cmds.isEmpty() && net.isEmpty()) return null
        return EnterprisePolicy(allowCommands = cmds, networkAllow = net)
    }

    // ─── Merge (§19.1 precedence) ──────────────────────────────────────────

    /**
     * Merge [submitted] with the [mdm] and [user] pull sources under §19.1:
     *
     * - **Non-security fields** (`maxParallel`/`scheduler`, `defaultTimeoutMs`,
     *   `strictSchemaOutput`, `rateLimits`) — highest-precedence source wins:
     *   MDM, then user, then the submitted base.
     * - **Security fields** — most-restrictive-wins:
     *   - `auditRedaction` = maximum across all present sources (a user may
     *     raise redaction, never lower it below MDM).
     *   - `eventTriggersEnabled` = AND across all present sources (any source
     *     may disable).
     *   - `enterpriseAllowlist` / `networkAllowList` = union of all non-null
     *     present sources (a tightening on either side applies); null only when
     *     no source constrains.
     *   - `userPolicy` = OR-combined flags across all present sources.
     *
     * A source that is null contributes nothing (its fields are skipped).
     */
    internal fun merge(submitted: RuntimeConfig): RuntimeConfig {
        val m = mdm()
        val u = user()

        // Highest-precedence non-null wins for the non-security fields.
        val top = m ?: u ?: submitted
        val maxParallel = top.maxParallel
        val scheduler = top.scheduler
        val defaultTimeoutMs = top.defaultTimeoutMs
        val strictSchemaOutput = top.strictSchemaOutput
        val rateLimits = top.rateLimits

        val present = listOfNotNull(submitted, u, m)

        // auditRedaction: maximum (most restrictive) across present sources.
        val auditRedaction = present.map { it.auditRedaction }.maxByOrNull { it.ordinal }
            ?: RedactionLevel.DEFAULT

        // eventTriggersEnabled: AND across present sources.
        val eventTriggersEnabled = present.all { it.eventTriggersEnabled }

        // Allow-lists: union of all non-null present sources; null when none.
        val cmdUnion = present.mapNotNull { it.enterpriseAllowlist }.flatten().distinct()
        val enterpriseAllowlist = if (present.any { it.enterpriseAllowlist != null }) cmdUnion else null
        val networkAllowList = present.flatMap { it.networkAllowList }.distinct()

        // userPolicy: OR-combined flags across present sources.
        val userPolicy = present.map { it.userPolicy }
            .fold(UserPolicy()) { acc, p -> acc.or(p) }

        return RuntimeConfig(
            maxParallel = maxParallel,
            defaultTimeoutMs = defaultTimeoutMs,
            strictSchemaOutput = strictSchemaOutput,
            auditRedaction = auditRedaction,
            eventTriggersEnabled = eventTriggersEnabled,
            enterpriseAllowlist = enterpriseAllowlist,
            networkAllowList = networkAllowList,
            rateLimits = rateLimits,
            scheduler = scheduler,
            userPolicy = userPolicy,
        )
    }

    // ─── Apply (validate → merge → fan out → audit) ────────────────────────

    /**
     * Apply [submitted]: merge it over the pull sources, validate + normalize,
     * apply the §19.1 allow-list deferral, fan the push-driven fields out to
     * their live consumers, swap [current], and append the always-audited
     * ConfigChanged record.
     *
     * @return The config that is now live (post-merge, post-normalize,
     *   post-deferral) — NOT necessarily [submitted]. An invalid merged config
     *   throws (never silently coerced, §19.1) and nothing is applied.
     */
    @Synchronized
    fun apply(submitted: RuntimeConfig): RuntimeConfig {
        val previous = live
        val merged = merge(submitted).normalized()

        // §19.1 allow-list deferral: an allow-list whose entries reference NO
        // currently-registered command is deferred — the prior value is kept
        // and the deferral is noted in the audit record. An allow-list that
        // matches at least one registered command is applied as-is (unknown
        // entries within it are harmless — they simply match nothing).
        val registeredIds = registry.allCommands().map { it.id }
        val deferAllowlist = merged.enterpriseAllowlist != null &&
            merged.enterpriseAllowlist.none { pattern ->
                registeredIds.any { commandGlobMatches(pattern, it) }
            }
        val applied = if (deferAllowlist) {
            merged.copy(enterpriseAllowlist = previous.enterpriseAllowlist).normalized()
        } else {
            merged
        }

        // Fan-out — push-driven consumers only; read-through suppliers observe
        // `live` on their next read.
        scheduler.reconfigure(applied.scheduler)
        rateLimiter.reconfigure(
            maxInvokesPerMinute = applied.rateLimits.maxInvokesPerMinute,
            maxDestructivePerHour = applied.rateLimits.maxDestructivePerHour,
        )
        val triggerLimits = TriggerLimits(maxBackgroundFiresPerHour = applied.rateLimits.maxBackgroundFiresPerHour)
        eventTriggers.reconfigure(triggerLimits)
        scheduleTriggers.reconfigure(triggerLimits)

        live = applied

        auditLog.append(configChangedRecord(previous, applied, deferAllowlist))
        return applied
    }

    // ─── ConfigChanged audit (§19, always appended) ────────────────────────

    private fun configChangedRecord(
        before: RuntimeConfig,
        after: RuntimeConfig,
        allowlistDeferred: Boolean,
    ): RunRecord {
        val diff = buildJsonObject {
            put("auditRedaction", diffScalar(before.auditRedaction.name, after.auditRedaction.name))
            put("eventTriggersEnabled", diffScalar(before.eventTriggersEnabled.toString(), after.eventTriggersEnabled.toString()))
            put("enterpriseAllowlist", diffList(before.enterpriseAllowlist, after.enterpriseAllowlist))
            put("networkAllowList", diffList(before.networkAllowList, after.networkAllowList))
            put("maxParallel", diffScalar(before.maxParallel.toString(), after.maxParallel.toString()))
            put("defaultTimeoutMs", diffScalar(before.defaultTimeoutMs.toString(), after.defaultTimeoutMs.toString()))
            put("strictSchemaOutput", diffScalar(before.strictSchemaOutput.toString(), after.strictSchemaOutput.toString()))
            put(
                "userPolicy",
                buildJsonObject {
                    put("confirmEveryNetwork", diffScalar(before.userPolicy.confirmEveryNetwork.toString(), after.userPolicy.confirmEveryNetwork.toString()))
                    put("backgroundEventsRequireForeground", diffScalar(before.userPolicy.backgroundEventsRequireForeground.toString(), after.userPolicy.backgroundEventsRequireForeground.toString()))
                    put("disableSessionGrants", diffScalar(before.userPolicy.disableSessionGrants.toString(), after.userPolicy.disableSessionGrants.toString()))
                },
            )
            put(
                "rateLimits",
                buildJsonObject {
                    put("maxInvokesPerMinute", diffScalar(before.rateLimits.maxInvokesPerMinute.toString(), after.rateLimits.maxInvokesPerMinute.toString()))
                    put("maxDestructivePerHour", diffScalar(before.rateLimits.maxDestructivePerHour.toString(), after.rateLimits.maxDestructivePerHour.toString()))
                    put("maxBackgroundFiresPerHour", diffScalar(before.rateLimits.maxBackgroundFiresPerHour.toString(), after.rateLimits.maxBackgroundFiresPerHour.toString()))
                },
            )
            put("allowlistDeferred", JsonPrimitive(allowlistDeferred))
        }
        return RunRecord(
            runId = "config",
            timestamp = clock(),
            source = "SYSTEM",
            commandId = "config.changed",
            ir = diff.toString(),
        )
    }

    private fun diffScalar(before: String, after: String): JsonObject = buildJsonObject {
        put("before", before)
        put("after", after)
    }

    private fun diffList(before: List<String>?, after: List<String>?): JsonObject = buildJsonObject {
        put("before", before?.let { JsonArray(it.map { s -> JsonPrimitive(s) }) } ?: JsonPrimitive("null"))
        put("after", after?.let { JsonArray(it.map { s -> JsonPrimitive(s) }) } ?: JsonPrimitive("null"))
    }

    private fun commandGlobMatches(pattern: String, commandId: String): Boolean {
        if (pattern == "*") return true
        if (pattern.endsWith(".*")) {
            val prefix = pattern.dropLast(2)
            return commandId.startsWith("$prefix.")
        }
        return commandId == pattern
    }
}
