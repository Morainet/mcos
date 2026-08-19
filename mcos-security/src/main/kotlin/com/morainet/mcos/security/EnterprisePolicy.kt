package com.morainet.mcos.security

import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Enterprise / OEM policy bundle — [08-security.md 13].
 *
 * Delivered by `mcos-server` (MCOS's own management channel) or MDM
 * (Android Enterprise). Every field is a *restriction*: the merge rule with
 * user settings is `result = max(enterprise_restrictiveness, user_restrictiveness)`
 * (spec §13.4) — neither side may loosen the other's tightening.
 *
 * Parsing is **fail-closed** (spec §13.3): a malformed document, missing
 * required field or unsupported schema version produces [FAIL_CLOSED], the
 * most restrictive policy the runtime knows, never the most permissive.
 *
 * @param allowCommands Command ID glob allow-list. When non-empty, any
 *        command whose ID matches no pattern is rejected.
 * @param denyCommands Command ID glob deny-list. Takes precedence over
 *        [allowCommands] and over user grants — unconditional.
 * @param forceConfirm Side-effect classes that always require confirmation
 *        ([08-security.md 4.3]) — upgrades ALLOW → CONFIRM_ONCE, never downgrades.
 * @param disableSideload Refuse loading plugins with `TrustLevel == SIDELOAD_DEBUG`.
 * @param disableCloudMemorySync Prevent Memory from syncing to the cloud.
 * @param auditFailClosed If Stage-10 audit write fails, fail the run instead of
 *        silently dropping the record.
 * @param networkAllow Domain glob allow-list applied in `decideEgress`.
 *        When non-empty, hosts matching no pattern are denied egress.
 * @param networkDeny Domain glob deny-list applied in `decideEgress`.
 *        Takes precedence over [networkAllow].
 * @param disableAllPluginNetwork Global egress kill switch — denies ALL plugin
 *        network egress regardless of granted scopes.
 * @param secretTtlDays Force secret rotation after N days.
 * @param version Policy schema version for compatibility checks.
 * @param issuedAt ISO-8601 timestamp of issuance (kept as [String] to avoid a
 *        kotlinx-datetime dependency; wire format is unchanged from the spec).
 * @param issuedBy MDM server identity for audit.
 */
@Serializable
data class EnterprisePolicy(
    val allowCommands: List<String> = emptyList(),
    val denyCommands: List<String> = emptyList(),
    val forceConfirm: List<SideEffectClass> = emptyList(),
    val disableSideload: Boolean = false,
    val disableCloudMemorySync: Boolean = false,
    val auditFailClosed: Boolean = false,
    val networkAllow: List<String> = emptyList(),
    val networkDeny: List<String> = emptyList(),
    val disableAllPluginNetwork: Boolean = false,
    val secretTtlDays: Int? = null,
    val version: String = SUPPORTED_VERSIONS.first(),
    val issuedAt: String = "",
    val issuedBy: String = "",
) {

    // ─── Command allow/deny evaluation ────────────────────────────────────

    /**
     * Whether a command with ID [commandId] is **not** rejected by the
     * command lists. Implements §13.2 semantics:
     * - [denyCommands] wins unconditionally.
     * - A non-empty [allowCommands] is an upper bound: non-matching commands
     *   are rejected.
     */
    fun commandAllowed(commandId: String): Boolean {
        if (denyCommands.any { commandGlobMatches(it, commandId) }) return false
        if (allowCommands.isNotEmpty() && allowCommands.none { commandGlobMatches(it, commandId) }) return false
        return true
    }

    /**
     * Whether a side-effect class must always be confirmed (spec §4.3).
     */
    fun requiresForceConfirm(sideEffectClass: SideEffectClass): Boolean =
        sideEffectClass in forceConfirm

    // ─── Network evaluation ───────────────────────────────────────────────

    /**
     * Whether egress to [host] is allowed under the network lists.
     * Implements §13.2 / §12.0 step 4 semantics: [networkDeny] wins, then a
     * non-empty [networkAllow] is an upper bound.
     */
    fun networkAllowed(host: String): Boolean {
        if (networkDeny.any { domainGlobMatches(it, host) }) return false
        if (networkAllow.isNotEmpty() && networkAllow.none { domainGlobMatches(it, host) }) return false
        return true
    }

    // ─── Glob matching (command IDs) ──────────────────────────────────────

    /**
     * Glob match for a command ID pattern.
     *
     * Command IDs are dot-separated (`camera.scan`, `sys.notify`,
     * `vpn.connect`). Supported patterns per §13.1:
     * - `*` matches any command ID.
     * - `prefix.*` matches any command whose ID starts with `prefix.`.
     * - any other pattern matches exactly.
     *
     * Wildcard-in-the-middle is not defined by the spec and returns false to
     * avoid over-matching.
     */
    internal fun commandGlobMatches(pattern: String, commandId: String): Boolean {
        if (pattern == "*") return true
        if (pattern.endsWith(".*")) {
            val prefix = pattern.dropLast(2) // drop ".*"
            // `prefix.*` requires at least one segment after the dot —
            // the bare `prefix` ID itself is NOT matched (dot boundary).
            return commandId.startsWith("$prefix.")
        }
        return commandId == pattern
    }

    /**
     * Glob match for a domain pattern — identical semantics to
     * [NetworkEgressPolicy.globMatch] (§12.1): `*` catch-all,
     * `*.example.com` matches one-or-more subdomain labels, exact otherwise.
     */
    internal fun domainGlobMatches(pattern: String, host: String): Boolean {
        val h = host.lowercase()
        val p = pattern.lowercase()
        if (p == "*") return true
        if (p.startsWith("*.")) {
            val suffix = p.substring(2)
            return h.endsWith(".$suffix")
        }
        return h == p
    }

    companion object {
        /** Policy schema versions this runtime understands. */
        val SUPPORTED_VERSIONS = setOf("1.0")

        /**
         * The most restrictive policy (spec §13.3 step 3): hardcoded safe-set
         * of commands, every class force-confirmed, sideload and all plugin
         * network disabled, audit fail-closed.
         */
        val FAIL_CLOSED = EnterprisePolicy(
            allowCommands = listOf("sys.notify", "sys.share"),
            forceConfirm = SideEffectClass.entries.toList(),
            disableSideload = true,
            disableCloudMemorySync = true,
            auditFailClosed = true,
            disableAllPluginNetwork = true,
            version = "FAIL_CLOSED",
            issuedBy = "mcos-runtime",
        )

        private val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * Parse a policy document. Throws on malformed JSON, a missing
         * required field, or an unsupported schema [EnterprisePolicy.version].
         *
         * Callers MUST treat a thrown exception as the trigger to enter
         * [FAIL_CLOSED] mode (spec §13.3 step 3) — see [EnterprisePolicySource].
         */
        fun parse(json: String): EnterprisePolicy {
            val policy = JSON.decodeFromString<EnterprisePolicy>(json)
            if (policy.version !in SUPPORTED_VERSIONS) {
                throw IllegalArgumentException(
                    "Unsupported enterprise policy schema version '${policy.version}'; " +
                        "supported: ${SUPPORTED_VERSIONS.joinToString(", ")}"
                )
            }
            return policy
        }
    }
}
