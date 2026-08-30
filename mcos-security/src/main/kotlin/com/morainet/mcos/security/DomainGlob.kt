package com.morainet.mcos.security

/**
 * URL-host extraction and domain-glob matching — the single source of truth
 * for "does this host fall under this `network.<pattern>` scope" questions.
 *
 * Shared by [ScopeBasedEgressPolicy] (Stage 6.5, command-argument URLs) and
 * the stamp-scoped facade gate (08-security.md §8.2, handler-internal
 * `NetService` calls) so the two enforcement points can never drift apart:
 * a host that passes one matcher passes the other, byte for byte.
 *
 * Implements the normative algorithm from [08-security.md 12.1]:
 * - `*` matches any host (catch-all).
 * - `*.example.com` matches any subdomain of `example.com` (one or more
 *   labels prefixing `example.com`).
 * - `example.com` matches exactly `example.com`.
 *
 * Wildcard-in-the-middle (e.g. `api.*.com`) is not defined by the spec and
 * returns false to avoid over-matching.
 */
object DomainGlob {

    /**
     * Extract the host portion from a URL string.
     * Handles common formats: `https://example.com/path`, `http://foo:8080/bar`,
     * `https://user:pass@host/path` (userinfo stripped), `https://[::1]:8080/`
     * (IPv6 brackets stripped). Returns null when no usable host remains.
     */
    fun extractHost(url: String): String? {
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

    /** Glob match for domain patterns (see class kdoc for the normative rules). */
    fun globMatch(host: String, pattern: String): Boolean {
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
