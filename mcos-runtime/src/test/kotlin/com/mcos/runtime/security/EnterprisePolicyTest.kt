package com.mcos.runtime.security

import com.mcos.sdk.SideEffectClass
import kotlin.test.*

/**
 * Unit tests for [EnterprisePolicy] parsing and allow/deny-list semantics.
 * Matches [08-security.md 13].
 */
class EnterprisePolicyTest {

    // ═══════════════════════════════════════════════════════════════
    // E1-E3: Parsing and fail-closed behavior (§13.3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E1-policy document with all fields parses`() {
        val json = """
            {
              "allowCommands": ["camera.*", "sys.notify", "vpn.connect"],
              "denyCommands": ["mcp.*"],
              "forceConfirm": ["control", "destructive", "network"],
              "disableSideload": true,
              "disableCloudMemorySync": true,
              "auditFailClosed": true,
              "networkAllow": ["*.corp.example.com", "api.example.com"],
              "networkDeny": ["api.example.com/private"],
              "disableAllPluginNetwork": false,
              "secretTtlDays": 90,
              "version": "1.0",
              "issuedAt": "2026-08-15T00:00:00Z",
              "issuedBy": "mdm.corp"
            }
        """.trimIndent()

        val policy = EnterprisePolicy.parse(json)
        assertEquals(listOf("camera.*", "sys.notify", "vpn.connect"), policy.allowCommands)
        assertEquals(listOf("mcp.*"), policy.denyCommands)
        assertEquals(
            listOf(SideEffectClass.control, SideEffectClass.destructive, SideEffectClass.network),
            policy.forceConfirm,
        )
        assertTrue(policy.disableSideload)
        assertTrue(policy.disableCloudMemorySync)
        assertTrue(policy.auditFailClosed)
        assertEquals(90, policy.secretTtlDays)
        assertEquals("1.0", policy.version)
        assertEquals("mdm.corp", policy.issuedBy)
    }

    @Test
    fun `E2-unsupported schema version throws`() {
        val json = """{"version": "9.9"}"""
        val e = assertFailsWith<IllegalArgumentException> { EnterprisePolicy.parse(json) }
        assertContains(e.message.orEmpty(), "9.9")
    }

    @Test
    fun `E2b-malformed json throws`() {
        val e = assertFailsWith<Exception> { EnterprisePolicy.parse("{not json") }
        assertNotNull(e.message)
    }

    @Test
    fun `E3-fail closed is maximally restrictive`() {
        val fc = EnterprisePolicy.FAIL_CLOSED
        // Hardcoded safe-set: only sys.notify / sys.share survive the allow-list
        assertTrue(fc.commandAllowed("sys.notify"))
        assertTrue(fc.commandAllowed("sys.share"))
        assertFalse(fc.commandAllowed("camera.scan"))
        // Every side-effect class force-confirmed
        assertEquals(SideEffectClass.entries.toList(), fc.forceConfirm)
        // Sideload, cloud sync, all plugin network disabled, audit fail-closed
        assertTrue(fc.disableSideload)
        assertTrue(fc.disableCloudMemorySync)
        assertTrue(fc.disableAllPluginNetwork)
        assertTrue(fc.auditFailClosed)
        // Network: allow-list empty → everything outside deny-list is allowed,
        // but the global kill switch is on so egress is dead anyway.
        assertTrue(fc.networkAllowed("any.example.com"))
    }

    // ═══════════════════════════════════════════════════════════════
    // E4-E7: Command allow/deny lists (§13.2)
    // ═══════════════════════════════════════════════════════════════

    private fun policyWith(
        allow: List<String> = emptyList(),
        deny: List<String> = emptyList(),
    ) = EnterprisePolicy(allowCommands = allow, denyCommands = deny)

    @Test
    fun `E4-empty lists allow everything`() {
        val p = policyWith()
        assertTrue(p.commandAllowed("anything.at.all"))
    }

    @Test
    fun `E5-deny list wins unconditionally`() {
        val p = policyWith(allow = listOf("camera.*", "mcp.*"), deny = listOf("mcp.exfil"))
        assertTrue(p.commandAllowed("camera.scan"))
        assertTrue(p.commandAllowed("mcp.weather.get"))
        assertFalse(p.commandAllowed("mcp.exfil"))
    }

    @Test
    fun `E6-allow list is an upper bound`() {
        val p = policyWith(allow = listOf("camera.*", "sys.notify", "vpn.connect"))
        assertTrue(p.commandAllowed("camera.scan"))
        assertTrue(p.commandAllowed("camera.capture"))
        assertTrue(p.commandAllowed("sys.notify"))
        assertTrue(p.commandAllowed("vpn.connect"))
        assertFalse(p.commandAllowed("mail.send"))
        assertFalse(p.commandAllowed("camera"))   // prefix.* requires dot boundary
    }

    @Test
    fun `E7-star wildcard matches everything`() {
        val p = policyWith(allow = listOf("*"))
        assertTrue(p.commandAllowed("camera.scan"))
        assertTrue(p.commandAllowed("vpn.connect"))
    }

    // ═══════════════════════════════════════════════════════════════
    // E8-E9: Network allow/deny lists (§12.0 step 4, §13.2)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E8-network deny list wins over allow list`() {
        val p = EnterprisePolicy(
            networkAllow = listOf("*.corp.example.com"),
            networkDeny = listOf("blocked.corp.example.com"),
        )
        assertTrue(p.networkAllowed("mail.corp.example.com"))
        assertFalse(p.networkAllowed("blocked.corp.example.com"))
        assertFalse(p.networkAllowed("public.example.com"))
    }

    @Test
    fun `E9-domain glob semantics`() {
        val p = EnterprisePolicy(networkAllow = listOf("*.example.com", "api.example.org"))
        assertTrue(p.networkAllowed("a.example.com"))
        assertTrue(p.networkAllowed("deep.a.example.com"))
        assertFalse(p.networkAllowed("example.com"))      // *.-prefix matches subdomains only
        assertFalse(p.networkAllowed("notexample.com"))   // suffix boundary respected
        assertTrue(p.networkAllowed("api.example.org"))
        assertFalse(p.networkAllowed("evil-api.example.org"))
    }

    // ═══════════════════════════════════════════════════════════════
    // E10: Force-confirm list (§4.3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E10-force confirm classes are always confirmed`() {
        val p = EnterprisePolicy(forceConfirm = listOf(SideEffectClass.control))
        assertTrue(p.requiresForceConfirm(SideEffectClass.control))
        assertFalse(p.requiresForceConfirm(SideEffectClass.read))
        assertFalse(p.requiresForceConfirm(SideEffectClass.destructive)) // already always-confirm by class
    }

    @Test
    fun `E10b-force confirm empty means no upgrade`() {
        val p = EnterprisePolicy()
        assertFalse(p.requiresForceConfirm(SideEffectClass.control))
    }

    // ═══════════════════════════════════════════════════════════════
    // E11: Defaults
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E11-minimal document gets default version`() {
        val policy = EnterprisePolicy.parse("""{"issuedBy": "mdm"}""")
        assertEquals("1.0", policy.version)
        assertTrue(policy.allowCommands.isEmpty())
        assertFalse(policy.disableAllPluginNetwork)
    }
}
