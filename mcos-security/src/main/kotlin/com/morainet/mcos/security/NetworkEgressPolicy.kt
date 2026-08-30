package com.morainet.mcos.security

import com.morainet.mcos.sdk.AuthStamp

/**
 * Network egress policy — controls whether a command may access a given URL.
 *
 * Implements [08-security.md 12] — `decideEgress` algorithm.
 *
 * The policy is an interface so the executor wiring is never `null`
 * (null would silently skip egress validation — fail-open). Production uses
 * [ScopeBasedEgressPolicy]; tests use the named [AllowAllEgressPolicy] or
 * [DenyAllEgressPolicy] to choose the behaviour explicitly.
 */
interface NetworkEgressPolicy {

    /**
     * Decide whether egress to [url] should be allowed.
     *
     * @param url The target URL the command intends to access.
     * @param authStamp Optional authorization stamp whose `grantsUsed` may contain
     *        `network.<domain>` scope entries.
     * @param globalKillSwitch If true, all network egress is denied regardless of
     *        other checks.
     * @param debugMode If true, HTTP (non-HTTPS) URLs are allowed for development.
     * @param enterprisePolicy Optional enterprise policy whose network lists
     *        tighten the granted scopes (spec §12.0 step 4). `null` skips the
     *        enterprise checks entirely (pass-through).
     * @return [EgressDecision.Allow] or [EgressDecision.Deny] with reason.
     */
    fun decideEgress(
        url: String,
        authStamp: AuthStamp? = null,
        globalKillSwitch: Boolean = false,
        debugMode: Boolean = false,
        enterprisePolicy: EnterprisePolicy? = null,
    ): EgressDecision
}

/**
 * Production [NetworkEgressPolicy] — scope-glob matching over granted
 * `network.<domain>` scopes.
 *
 * ## Pipeline (ordered)
 *
 * 1. **Global kill switch** — if active, deny all egress immediately.
 * 2. **HTTPS enforcement** — non-`https://` URLs are denied unless in debug mode.
 * 3. **Domain scope glob matching** — check [AuthStamp.grantsUsed] for
 *    `network.<domain>` scopes that match the target host via glob rules.
 * 4. **Enterprise policy** — [EnterprisePolicy.networkDeny] /
 *    [EnterprisePolicy.networkAllow] lists tighten the granted scopes
 *    (spec §12.0 step 4). `disableAllPluginNetwork` participates in the
 *    kill-switch check of step 1 (spec §13.2).
 *
 * ## P1 scope
 *
 * Pure function — no I/O, no side effects. P1 MVP is best-effort;
 * in-process plugins can bypass NetService and use raw HTTP clients.
 * Process isolation (P3) provides true enforcement.
 */
class ScopeBasedEgressPolicy : NetworkEgressPolicy {

    // ─── Public API ────────────────────────────────────────────────────────

    override fun decideEgress(
        url: String,
        authStamp: AuthStamp?,
        globalKillSwitch: Boolean,
        debugMode: Boolean,
        enterprisePolicy: EnterprisePolicy?,
    ): EgressDecision {
        // Step 1: Global kill switch — absolute first check.
        // Enterprise `disableAllPluginNetwork` (spec §13.2) also raises the
        // kill switch, so it is folded into the same gate.
        if (globalKillSwitch || (enterprisePolicy?.disableAllPluginNetwork == true)) {
            return EgressDecision.Deny(
                reason = if (enterprisePolicy?.disableAllPluginNetwork == true && !globalKillSwitch) {
                    "enterprise_kill_switch_active"
                } else {
                    "kill_switch_active"
                }
            )
        }

        // Extract host from URL
        val host = extractHost(url)
            ?: return EgressDecision.Deny(reason = "invalid_url", missingDomain = "<parse error>")

        // Step 2: HTTPS enforcement
        if (!url.startsWith("https://") && !debugMode) {
            return EgressDecision.Deny(reason = "https_required", missingDomain = host)
        }

        // Step 3: Domain scope glob matching
        val networkScopes = authStamp?.grantsUsed?.filter { it.startsWith("network.") } ?: emptySet()
        if (networkScopes.isEmpty()) {
            // No network scopes granted — deny (default-deny for network egress)
            return EgressDecision.Deny(reason = "no_network_scope_granted", missingDomain = host)
        }

        val hasMatchingScope = networkScopes.any { scope ->
            val pattern = scope.removePrefix("network.")
            globMatch(host, pattern)
        }

        if (!hasMatchingScope) {
            return EgressDecision.Deny(
                reason = "domain_not_in_scope",
                missingDomain = host
            )
        }

        // Step 4: Enterprise policy — network allow/deny lists tighten the
        // granted scopes (spec §12.0 step 4 / §13.2). Deny-list wins, then a
        // non-empty allow-list is an upper bound. `null` policy is pass-through.
        val policy = enterprisePolicy
        if (policy != null) {
            if (policy.networkDeny.any { pattern -> globMatch(host, pattern) }) {
                return EgressDecision.Deny(
                    reason = "enterprise_network_deny",
                    missingDomain = host
                )
            }
            if (policy.networkAllow.isNotEmpty() &&
                policy.networkAllow.none { pattern -> globMatch(host, pattern) }
            ) {
                return EgressDecision.Deny(
                    reason = "enterprise_network_allowlist_miss",
                    missingDomain = host
                )
            }
        }

        return EgressDecision.Allow
    }

    // ─── Internal helpers ──────────────────────────────────────────────────
    // Thin delegates to [DomainGlob] — the shared single source of truth for
    // host extraction and scope-glob matching, so Stage 6.5 (command-argument
    // URLs) and the §8.2 facade gate can never drift apart.

    /** @see DomainGlob.extractHost */
    internal fun extractHost(url: String): String? = DomainGlob.extractHost(url)

    /** @see DomainGlob.globMatch */
    internal fun globMatch(host: String, pattern: String): Boolean = DomainGlob.globMatch(host, pattern)
}

// ─── Public types ──────────────────────────────────────────────────────────

/** Result of [NetworkEgressPolicy.decideEgress]. */
sealed class EgressDecision {
    /** Egress is allowed. */
    data object Allow : EgressDecision()

    /** Egress is denied with a [reason] and optional [missingDomain]. */
    data class Deny(
        val reason: String,
        val missingDomain: String? = null,
    ) : EgressDecision()
}
