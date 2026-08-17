package com.mcos.runtime.security

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.*

/**
 * Unit tests for [FileEnterprisePolicySource] hot reload and fail-closed
 * behavior. Matches [08-security.md 13.3].
 */
class FileEnterprisePolicySourceTest {

    private lateinit var dir: java.nio.file.Path
    private lateinit var policyFile: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("mcos-policy-test")
        policyFile = dir.resolve("enterprise-policy.json")
        policyFile.writeText(minimalPolicy("1.0"))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun minimalPolicy(version: String = "1.0", issuedBy: String = "mdm") =
        """{"version": "$version", "issuedBy": "$issuedBy"}"""

    private fun source(): FileEnterprisePolicySource =
        FileEnterprisePolicySource(policyFile, refreshIntervalMs = 0)

    // ═══════════════════════════════════════════════════════════════
    // F1-F2: Initial load and hot reload
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F1-loads policy on first access`() {
        val src = source()
        val policy = src.current()
        assertEquals("1.0", policy.version)
        assertEquals("mdm", policy.issuedBy)
        assertIs<PolicyEvent.PolicyUpdated>(src.lastEvent)
    }

    @Test
    fun `F2-reloads when file changes`() {
        val src = source()
        assertEquals("1.0", src.current().version)

        // Force a different mtime (same-length rewrite may keep mtime on
        // coarse filesystems, so bump it explicitly).
        policyFile.writeText(minimalPolicy("1.0", issuedBy = "mdm-v2"))
        Files.setLastModifiedTime(policyFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000))

        val reloaded = src.current()
        assertEquals("mdm-v2", reloaded.issuedBy)
        val event = assertIs<PolicyEvent.PolicyUpdated>(src.lastEvent)
        assertEquals("1.0", event.newVersion)
        assertEquals("mdm-v2", event.issuedBy)
    }

    // ═══════════════════════════════════════════════════════════════
    // F3-F4: Fail-closed on parse error (§13.3 step 3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F3-parse failure switches to fail-closed and emits event`() {
        policyFile.writeText("{broken json")
        Files.setLastModifiedTime(policyFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000))

        val src = source()
        val policy = src.current()
        assertEquals(EnterprisePolicy.FAIL_CLOSED, policy)
        val event = assertIs<PolicyEvent.PolicyParseFailed>(src.lastEvent)
        assertEquals(64, event.documentHash.length) // SHA-256 hex
        assertTrue(event.documentHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `F4-unsupported version is fail-closed`() {
        policyFile.writeText(minimalPolicy("9.9"))
        Files.setLastModifiedTime(policyFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000))

        val src = source()
        assertEquals(EnterprisePolicy.FAIL_CLOSED, src.current())
        assertIs<PolicyEvent.PolicyParseFailed>(src.lastEvent)
    }

    // ═══════════════════════════════════════════════════════════════
    // F5-F6: Fetch failure keeps last good policy (§13.3 step 4)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F5-missing file with no cache is fail-closed`() {
        val missing = dir.resolve("does-not-exist.json")
        val src = FileEnterprisePolicySource(missing, refreshIntervalMs = 0)
        assertEquals(EnterprisePolicy.FAIL_CLOSED, src.current())
        assertIs<PolicyEvent.PolicyFetchFailed>(src.lastEvent)
    }

    @Test
    fun `F6-missing file after a good load keeps the cached policy`() {
        val src = source()
        assertEquals("1.0", src.current().version)

        Files.delete(policyFile)
        val policy = src.current()
        assertEquals("1.0", policy.version) // stale-but-good policy still served
        assertIs<PolicyEvent.PolicyFetchFailed>(src.lastEvent)
    }

    // ═══════════════════════════════════════════════════════════════
    // F7: Listener notification
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F7-listeners receive lifecycle events`() {
        val src = source()
        src.current() // initial load — no listener attached yet

        val events = mutableListOf<PolicyEvent>()
        src.addListener { events.add(it) }

        policyFile.writeText("{bad")
        Files.setLastModifiedTime(policyFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000))
        src.current()

        assertEquals(1, events.size)
        assertIs<PolicyEvent.PolicyParseFailed>(events.single())
    }

    // ═══════════════════════════════════════════════════════════════
    // F8: Refresh interval throttling
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F8-refresh interval suppresses reloads`() {
        val src = FileEnterprisePolicySource(policyFile, refreshIntervalMs = 60_000)
        assertEquals("1.0", src.current().version)

        policyFile.writeText(minimalPolicy("1.0", issuedBy = "mdm-v2"))
        Files.setLastModifiedTime(policyFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000))

        // Within the refresh window → still old policy
        assertEquals("mdm", src.current().issuedBy)
    }

    @Test
    fun `F8b-refresh interval expiry reloads`() {
        val src = FileEnterprisePolicySource(policyFile, refreshIntervalMs = 1)
        assertEquals("1.0", src.current().version)

        policyFile.writeText(minimalPolicy("1.0", issuedBy = "mdm-v2"))
        Files.setLastModifiedTime(policyFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000))
        Thread.sleep(10) // let the refresh window pass

        assertEquals("mdm-v2", src.current().issuedBy)
    }
}
