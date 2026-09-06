package com.morainet.mcos.security

import kotlin.test.*

/**
 * Unit tests for [HttpEnterprisePolicySource] — remote policy pull with
 * fail-closed semantics. Matches [08-security.md 13.3] steps 1/3/4/5 and
 * mirrors [FileEnterprisePolicySourceTest] case-for-case where the semantics
 * overlap.
 */
class HttpEnterprisePolicySourceTest {

    private val url = "https://policy.example/enterprise/policy"

    /** Scriptable transport — records calls and serves a mutable result. */
    private class FakeTransport(var result: PolicyFetchResult) : EnterprisePolicyHttpTransport {
        val urls = mutableListOf<String>()
        override fun fetch(fetchUrl: String): PolicyFetchResult {
            urls.add(fetchUrl)
            return result
        }
    }

    private fun minimalPolicy(version: String = "1.0", issuedBy: String = "mdm") =
        """{"version": "$version", "issuedBy": "$issuedBy"}"""

    private fun source(
        transport: EnterprisePolicyHttpTransport,
        refreshIntervalMs: Long = 60_000,
    ): HttpEnterprisePolicySource = HttpEnterprisePolicySource(url, transport, refreshIntervalMs)

    // ═══════════════════════════════════════════════════════════════
    // H1-H2: Initial fetch and URL passthrough (§13.3 step 1)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H1-fetches and parses policy on first access`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy(issuedBy = "server-mdm")))
        val src = source(t, refreshIntervalMs = 0)
        val policy = src.current()
        assertEquals("1.0", policy.version)
        assertEquals("server-mdm", policy.issuedBy)
        assertEquals(listOf(url), t.urls) // the configured URL is what gets fetched
        val event = assertIs<PolicyEvent.PolicyUpdated>(src.lastEvent)
        assertEquals("1.0", event.newVersion)
        assertEquals("server-mdm", event.issuedBy)
    }

    // ═══════════════════════════════════════════════════════════════
    // H3-H5: Fetch failure keeps last good policy (§13.3 step 4)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H3-transport failure with no cache is fail-closed`() {
        val t = FakeTransport(PolicyFetchResult.Failure("HTTP 401"))
        val src = source(t, refreshIntervalMs = 0)
        assertEquals(EnterprisePolicy.FAIL_CLOSED, src.current())
        val event = assertIs<PolicyEvent.PolicyFetchFailed>(src.lastEvent)
        assertEquals("HTTP 401", event.reason)
    }

    @Test
    fun `H4-transport failure after a good load keeps the cached policy`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy()))
        val src = source(t, refreshIntervalMs = 0)
        assertEquals("1.0", src.current().version)

        t.result = PolicyFetchResult.Failure("Connection refused")
        val policy = src.current()
        assertEquals("1.0", policy.version) // stale-but-good policy still served
        val event = assertIs<PolicyEvent.PolicyFetchFailed>(src.lastEvent)
        assertEquals("Connection refused", event.reason)
    }

    @Test
    fun `H5-non-2xx status is a fetch failure, not a parse error`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy()))
        val src = source(t, refreshIntervalMs = 0)
        src.current()
        t.result = PolicyFetchResult.Failure("HTTP 404")
        assertEquals("1.0", src.current().version)
        assertEquals("HTTP 404", assertIs<PolicyEvent.PolicyFetchFailed>(src.lastEvent).reason)
    }

    // ═══════════════════════════════════════════════════════════════
    // H6-H7: Fail-closed on parse error (§13.3 step 3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H6-parse failure switches to fail-closed and emits fingerprint event`() {
        val t = FakeTransport(PolicyFetchResult.Success("{broken json"))
        val src = source(t, refreshIntervalMs = 0)
        assertEquals(EnterprisePolicy.FAIL_CLOSED, src.current())
        val event = assertIs<PolicyEvent.PolicyParseFailed>(src.lastEvent)
        assertEquals(64, event.documentHash.length) // SHA-256 hex
        assertTrue(event.documentHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `H7-unsupported version is fail-closed`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy(version = "9.9")))
        val src = source(t, refreshIntervalMs = 0)
        assertEquals(EnterprisePolicy.FAIL_CLOSED, src.current())
        assertIs<PolicyEvent.PolicyParseFailed>(src.lastEvent)
    }

    // ═══════════════════════════════════════════════════════════════
    // H8: Refresh interval throttling (§13.3 step 1 cadence)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H8-refresh interval suppresses refetches`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy()))
        val src = source(t, refreshIntervalMs = 60_000)
        assertEquals("1.0", src.current().version)

        t.result = PolicyFetchResult.Success(minimalPolicy(issuedBy = "mdm-v2"))
        // Within the window → no network fetch, still the old policy.
        assertEquals("1.0", src.current().version)
        assertEquals("mdm", src.current().issuedBy)
        assertEquals(1, t.urls.size)
    }

    @Test
    fun `H8b-refresh interval expiry refetches`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy()))
        val src = source(t, refreshIntervalMs = 1)
        assertEquals("1.0", src.current().version)

        t.result = PolicyFetchResult.Success(minimalPolicy(issuedBy = "mdm-v2"))
        Thread.sleep(10) // let the refresh window pass
        val reloaded = src.current()
        assertEquals("mdm-v2", reloaded.issuedBy)
        val event = assertIs<PolicyEvent.PolicyUpdated>(src.lastEvent)
        assertEquals("1.0", event.previousVersion)
        assertEquals("mdm-v2", event.issuedBy)
    }

    // ═══════════════════════════════════════════════════════════════
    // H9: Unchanged document does not re-emit
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H9-unchanged document after refetch is served without re-parsing or re-emitting`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy()))
        val src = source(t, refreshIntervalMs = 1)
        val events = mutableListOf<PolicyEvent>()
        src.addListener { events.add(it) }
        assertEquals("1.0", src.current().version) // initial load → PolicyUpdated

        Thread.sleep(10) // pass the window; next call refetches identical bytes
        assertEquals("1.0", src.current().version)
        assertEquals(2, t.urls.size) // it did hit the network again…
        assertEquals(1, events.size) // …but did not re-emit PolicyUpdated
        assertIs<PolicyEvent.PolicyUpdated>(events.single())
    }

    // ═══════════════════════════════════════════════════════════════
    // H10: Recovery from FAIL_CLOSED
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H10-good document after a parse failure recovers via PolicyUpdated`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy()))
        val src = source(t, refreshIntervalMs = 1)
        assertEquals("1.0", src.current().version)

        t.result = PolicyFetchResult.Success("{broken json")
        Thread.sleep(10)
        assertEquals(EnterprisePolicy.FAIL_CLOSED, src.current())
        assertIs<PolicyEvent.PolicyParseFailed>(src.lastEvent)

        // The server returns the EXACT document that was cached before the
        // failure: FAIL_CLOSED is a state, not a sticky cache — the identical
        // bytes must be re-parsed and recovery must happen (the content-hash
        // shortcut must not swallow it).
        t.result = PolicyFetchResult.Success(minimalPolicy())
        Thread.sleep(10)
        val recovered = src.current()
        assertEquals("1.0", recovered.version)
        assertEquals("mdm", recovered.issuedBy)
        val event = assertIs<PolicyEvent.PolicyUpdated>(src.lastEvent)
        assertEquals(EnterprisePolicy.FAIL_CLOSED.version, event.previousVersion)
        assertEquals("mdm", event.issuedBy)
    }

    // ═══════════════════════════════════════════════════════════════
    // H11: Listener notification
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H11-listeners receive lifecycle events`() {
        val t = FakeTransport(PolicyFetchResult.Success(minimalPolicy()))
        val src = source(t, refreshIntervalMs = 1)
        src.current() // initial load — no listener attached yet

        val events = mutableListOf<PolicyEvent>()
        src.addListener { events.add(it) }

        t.result = PolicyFetchResult.Failure("HTTP 503")
        Thread.sleep(10)
        src.current()
        assertEquals(1, events.size)
        assertEquals("HTTP 503", assertIs<PolicyEvent.PolicyFetchFailed>(events.single()).reason)
    }
}
