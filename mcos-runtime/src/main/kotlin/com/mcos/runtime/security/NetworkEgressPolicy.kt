package com.mcos.runtime.security

import com.mcos.sdk.AuthStamp

/**
 * Network egress policy — controls whether a command may access a given URL.
 *
 * Implements [08-security.md 12] — `decideEgress` algorithm.
 *
 * ## Pipeline (ordered)
 *
 * 1. **Global kill switch** — if active, deny all egress immediately.
 * 2. **HTTPS enforcement** — non-`https://` URLs are denied unless in debug mode.
 * 3. **Domain scope glob matching** — check [AuthStamp.grantsUsed] for
 *    `network.<domain>` scopes that match the target host via glob rules.
 * 4. **Enterprise policy** (P1 stub) — optional override; defaults to pass-through.
 *
 * ## P1 scope
 *
 * Pure function — no I/O, no side effects. P1 MVP is best-effort;
 * in-process plugins can bypass NetService and use raw HTTP clients.
 * Process isolation (P3) provides true enforcement.
 */
class NetworkEgressPolicy {

    // ─── Public API ────────────────────────────────────────────────────────

    /**
     * Decide whether egress to [url] should be allowed.
     *
     * @param url The target URL the command intends to access.
     * @param authStamp Optional authorization stamp whose `grantsUsed` may contain
     *        `network.<domain>` scope entries.
     * @param globalKillSwitch If true, all network egress is denied regardless of
     *        other checks.
     * @param debugMode If true, HTTP (non-HTTPS) URLs are allowed for development.
     * @return [EgressDecision.Allow] or [EgressDecision.Deny] with reason.
     */
    fun decideEgress(
        url: String,
        authStamp: AuthStamp? = null,
        globalKillSwitch: Boolean = false,
        debugMode: Boolean = false,
    ): EgressDecision {
        // Step 1: Global kill switch — absolute first check
        if (globalKillSwitch) {
            return EgressDecision.Deny(reason = "kill_switch_active")
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

        // Step 4: Enterprise policy (P1 stub — always pass-through)
        // P3 will check EnterprisePolicy.egressAllowlist/egressBlocklist here.

        return EgressDecision.Allow
    }

    // ─── Internal helpers ──────────────────────────────────────────────────

    /**
     * Extract the host portion from a URL string.
     * Handles common formats: `https://example.com/path`, `http://foo:8080/bar`.
     */
    internal fun extractHost(url: String): String? {
        // Find scheme separator
        val schemeEnd = url.indexOf("://")
        val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0

        // Find host end (first of: '/', '?', '#', ':' for port)
        val remaining = url.substring(hostStart)
        val hostEnd = remaining.indexOfAny(charArrayOf('/', '?', '#'))
        val portColon = remaining.indexOf(':')

        val hostEndFinal = when {
            hostEnd >= 0 && portColon >= 0 -> minOf(hostEnd, portColon)
            hostEnd >= 0 -> hostEnd
            portColon >= 0 -> portColon
            else -> remaining.length
        }

        return remaining.substring(0, hostEndFinal).lowercase().ifEmpty { null }
    }

    /**
     * Simple glob match for domain patterns.
     *
     * Supports `*` as a wildcard matching one or more domain labels.
     * Examples:
     * - `*.example.com` matches `api.example.com` and `cdn.example.com`
     * - `api.example.com` matches only `api.example.com`
     * - `*` matches any host
     *
     * P1 scope: basic glob only. P2 may add full glob syntax (?, [...], **).
     */
    internal fun globMatch(host: String, pattern: String): Boolean {
        if (pattern == "*") return true

        val patternParts = pattern.lowercase().split('.')
        val hostParts = host.lowercase().split('.')

        // Wildcard must consume at least one label
        if (patternParts.size > hostParts.size) return false

        var pi = patternParts.size - 1
        var hi = hostParts.size - 1

        while (pi >= 0 && hi >= 0) {
            if (patternParts[pi] == "*") {
                // Wildcard matches remaining host labels (at least one)
                return hi >= pi - 1 // ensure at least one label consumed
            }
            if (patternParts[pi] != hostParts[hi]) return false
            pi--
            hi--
        }

        return pi < 0 && hi < 0
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
