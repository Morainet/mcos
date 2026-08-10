package com.mcos.runtime.security

import com.mcos.sdk.AuthStamp
import kotlin.test.*

/**
 * Unit tests for [NetworkEgressPolicy] decideEgress algorithm.
 * Matches [08-security.md 12].
 */
class NetworkEgressPolicyTest {

    private val policy = NetworkEgressPolicy()

    // ═══════════════════════════════════════════════════════════════
    // N1-N2: Global kill switch and HTTPS enforcement
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `N1-global kill switch denies all egress`() {
        val result = policy.decideEgress(
            url = "https://example.com",
            authStamp = stampWithScopes("network.*"),
            globalKillSwitch = true,
        )
        assertIs<EgressDecision.Deny>(result)
        assertEquals("kill_switch_active", result.reason)
    }

    @Test
    fun `N2-http without debug mode is denied with https_required`() {
        val result = policy.decideEgress(
            url = "http://example.com/api",
            authStamp = stampWithScopes("network.*"),
        )
        assertIs<EgressDecision.Deny>(result)
        assertEquals("https_required", result.reason)
    }

    @Test
    fun `N2b-http in debug mode is allowed`() {
        val result = policy.decideEgress(
            url = "http://localhost:8080/api",
            authStamp = stampWithScopes("network.*"),
            debugMode = true,
        )
        assertIs<EgressDecision.Allow>(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // N3-N5: Domain scope glob matching
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `N3-no network scope granted denies egress`() {
        val result = policy.decideEgress(
            url = "https://example.com",
            authStamp = AuthStamp("r1", "cmd", "p", emptySet(), 0, 0),
        )
        assertIs<EgressDecision.Deny>(result)
        assertEquals("no_network_scope_granted", result.reason)
    }

    @Test
    fun `N3b-null authStamp denies egress`() {
        val result = policy.decideEgress(
            url = "https://example.com",
            authStamp = null,
        )
        assertIs<EgressDecision.Deny>(result)
        assertEquals("no_network_scope_granted", result.reason)
    }

    @Test
    fun `N4-exact domain match allows egress`() {
        val result = policy.decideEgress(
            url = "https://api.example.com/path?q=1",
            authStamp = stampWithScopes("network.api.example.com"),
        )
        assertIs<EgressDecision.Allow>(result)
    }

    @Test
    fun `N5-wildcard domain match allows subdomains`() {
        val result = policy.decideEgress(
            url = "https://cdn.example.com/asset.js",
            authStamp = stampWithScopes("network.*.example.com"),
        )
        assertIs<EgressDecision.Allow>(result)
    }

    @Test
    fun `N5b-wildcard does not match completely different domain`() {
        val result = policy.decideEgress(
            url = "https://evil.com",
            authStamp = stampWithScopes("network.*.example.com"),
        )
        assertIs<EgressDecision.Deny>(result)
        assertEquals("domain_not_in_scope", result.reason)
    }

    @Test
    fun `N5c-catch-all wildcard matches any domain`() {
        val result = policy.decideEgress(
            url = "https://anything.example.org/path",
            authStamp = stampWithScopes("network.*"),
        )
        assertIs<EgressDecision.Allow>(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // N6: URL parsing edge cases
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `N6-invalid URL returns deny`() {
        val result = policy.decideEgress(
            url = "not a url",
            authStamp = stampWithScopes("network.*"),
        )
        assertIs<EgressDecision.Deny>(result)
        assertEquals("invalid_url", result.reason)
    }

    @Test
    fun `N6b-URL with port number extracts host correctly`() {
        // Port should be stripped from host extraction
        val result = policy.decideEgress(
            url = "https://api.example.com:8443/v1/data",
            authStamp = stampWithScopes("network.api.example.com"),
        )
        assertIs<EgressDecision.Allow>(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // extractHost unit tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `extractHost — standard HTTPS URL`() {
        assertEquals("example.com", policy.extractHost("https://example.com/path"))
    }

    @Test
    fun `extractHost — URL with port`() {
        assertEquals("example.com", policy.extractHost("https://example.com:8080/api"))
    }

    @Test
    fun `extractHost — URL with query string`() {
        assertEquals("api.example.com", policy.extractHost("https://api.example.com/data?key=value"))
    }

    @Test
    fun `extractHost — URL without scheme`() {
        assertEquals("example.com", policy.extractHost("example.com/path"))
    }

    @Test
    fun `extractHost — IP address`() {
        assertEquals("192.168.1.1", policy.extractHost("https://192.168.1.1:3000/status"))
    }

    @Test
    fun `extractHost — returns lowercase`() {
        assertEquals("example.com", policy.extractHost("https://EXAMPLE.COM/Path"))
    }

    // ═══════════════════════════════════════════════════════════════
    // globMatch unit tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `globMatch — exact match`() {
        assertTrue(policy.globMatch("api.example.com", "api.example.com"))
    }

    @Test
    fun `globMatch — wildcard prefix`() {
        assertTrue(policy.globMatch("api.example.com", "*.example.com"))
    }

    @Test
    fun `globMatch — wildcard prefix no match`() {
        assertFalse(policy.globMatch("api.other.com", "*.example.com"))
    }

    @Test
    fun `globMatch — catch-all wildcard`() {
        assertTrue(policy.globMatch("anything.example.org", "*"))
    }

    @Test
    fun `globMatch — wildcard must match at least one label`() {
        // "*.example.com" should NOT match "example.com" itself
        assertFalse(policy.globMatch("example.com", "*.example.com"))
    }

    @Test
    fun `globMatch — case insensitive`() {
        assertTrue(policy.globMatch("API.Example.COM", "api.example.com"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun stampWithScopes(vararg scopes: String): AuthStamp = AuthStamp(
        runId = "test",
        commandId = "test.cmd",
        pluginId = "test.plugin",
        grantsUsed = scopes.toSet(),
        issuedAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 60000,
    )
}
