package com.mcos.runtime.security

import com.mcos.sdk.AuthStamp

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

    /**
     * Extract the host portion from a URL string.
     * Handles common formats: `https://example.com/path`, `http://foo:8080/bar`,
     * `https://user:pass@host/path` (userinfo stripped), `https://[::1]:8080/`
     * (IPv6 brackets stripped).
     */
    internal fun extractHost(url: String): String? {
        // Find scheme separator
        val schemeEnd = url.indexOf("://")
        val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0

        var remaining = url.substring(hostStart)

        // Strip userinfo: everything before the LAST '@' before the first '/',
        // '?', or '#' (which delimit the end of the authority component).
        val authEnd = remaining.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) remaining.length else it }
        val authority = remaining.substring(0, authEnd)
        remaining = authority + remaining.substring(authEnd)

        // If there is an '@' in the authority, drop the userinfo before it.
        val atIdx = authority.indexOf('@')
        if (atIdx >= 0) {
            remaining = remaining.substring(atIdx + 1)
        }

        // Now extract host from the (possibly userinfo-stripped) remaining string
        val afterUserInfo = remaining
        val pathEnd = afterUserInfo.indexOfAny(charArrayOf('/', '?', '#'))
        val hostPort = if (pathEnd >= 0) afterUserInfo.substring(0, pathEnd) else afterUserInfo

        // Handle IPv6 brackets: [::1]:8080 or [::1]
        var host = if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket > 0) hostPort.substring(1, closeBracket) else return null
        } else {
            // Strip port (the last ':' if present — but only if it's after the host)
            val colonIdx = hostPort.indexOf(':')
            if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
        }

        host = host.lowercase()
        // IDN hardening (P0-S3): normalise the host to its ASCII (Punycode)
        // form before returning, so a Unicode hostname (e.g. "例え.jp") is
        // compared against granted scopes in canonical form. Two attack
        // classes are closed by this:
        //  - A granted scope "network.example.com" must not be bypassable by
        //    an IDN homograph like "network.exámple.com" whose Punycode form
        //    differs from the granted ASCII scope.
        //  - A Unicode/Punycode host that fails IDN conversion is rejected
        //    rather than silently passing.
        host = try {
            java.net.IDN.toASCII(host)
        } catch (e: Exception) {
            return null
        }
        // Validate: a host must not contain whitespace, and must not be empty.
        // This catches strings like "not a url" that have no scheme separator.
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        return host
    }

    /**
     * Glob match for domain patterns.
     *
     * Implements the normative algorithm from [08-security.md 12.1]:
     * - `*` matches any host (catch-all).
     * - `*.example.com` matches any subdomain of `example.com` (one or more
     *   labels prefixing `example.com`).
     * - `example.com` matches exactly `example.com`.
     *
     * Wildcard-in-the-middle (e.g. `api.*.com`) is not defined by the spec
     * and returns false to avoid over-matching.
     */
    internal fun globMatch(host: String, pattern: String): Boolean {
        val h = host.lowercase()
        val p = pattern.lowercase()

        // Catch-all
        if (p == "*") return true

        // Prefix wildcard: *.suffix — matches one or more labels before suffix
        if (p.startsWith("*.")) {
            val suffix = p.substring(2) // drop "*."
            // Host must end with ".suffix" (at least one label prefixing it)
            return h.endsWith(".$suffix")
        }

        // Exact match
        return h == p
    }
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
