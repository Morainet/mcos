package com.morainet.mcos.security

import com.morainet.mcos.sdk.SideEffectClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UE1-UE7 — [UnionEnterprisePolicySource] composes a base policy source with a
 * read-through §19 overlay, most-restrictive-wins (03-runtime.md §19, 08 §13.4).
 */
class UnionEnterprisePolicySourceTest {

    @Test
    fun `UE1-null overlay passes the base policy through unchanged`() {
        val base = EnterprisePolicy(allowCommands = listOf("camera.*"))
        val union = UnionEnterprisePolicySource(EnterprisePolicySource.fixed(base)) { null }
        assertEquals(base.allowCommands, union.current().allowCommands)
    }

    @Test
    fun `UE2-allowCommands intersect when both sides constrain`() {
        val base = EnterprisePolicy(allowCommands = listOf("camera.scan", "files.read"))
        val overlay = EnterprisePolicy(allowCommands = listOf("files.read", "sys.notify"))
        val union = UnionEnterprisePolicySource(EnterprisePolicySource.fixed(base)) { overlay }
        // Intersection: only files.read is permitted by BOTH.
        assertEquals(listOf("files.read"), union.current().allowCommands)
    }

    @Test
    fun `UE3-an empty side yields to the other (no constraint)`() {
        val base = EnterprisePolicy(allowCommands = emptyList())
        val overlay = EnterprisePolicy(allowCommands = listOf("files.read"))
        val union = UnionEnterprisePolicySource(EnterprisePolicySource.fixed(base)) { overlay }
        assertEquals(listOf("files.read"), union.current().allowCommands)
    }

    @Test
    fun `UE4-networkAllow intersects the same way`() {
        val base = EnterprisePolicy(networkAllow = listOf("*.example.com", "api.foo.com"))
        val overlay = EnterprisePolicy(networkAllow = listOf("api.foo.com"))
        val union = UnionEnterprisePolicySource(EnterprisePolicySource.fixed(base)) { overlay }
        assertEquals(listOf("api.foo.com"), union.current().networkAllow)
    }

    @Test
    fun `UE5-deny lists and boolean tightenings union`() {
        val base = EnterprisePolicy(denyCommands = listOf("vpn.*"), disableSideload = false)
        val overlay = EnterprisePolicy(denyCommands = listOf("files.delete"), disableSideload = true)
        val union = UnionEnterprisePolicySource(EnterprisePolicySource.fixed(base)) { overlay }
        val merged = union.current()
        assertTrue("vpn.*" in merged.denyCommands)
        assertTrue("files.delete" in merged.denyCommands)
        assertTrue(merged.disableSideload)
    }

    @Test
    fun `UE6-a command permitted by base but not overlay is denied by the intersection`() {
        val base = EnterprisePolicy(allowCommands = listOf("camera.scan", "files.read"))
        val overlay = EnterprisePolicy(allowCommands = listOf("files.read"))
        val union = UnionEnterprisePolicySource(EnterprisePolicySource.fixed(base)) { overlay }
        val merged = union.current()
        assertFalse(merged.commandAllowed("camera.scan"))
        assertTrue(merged.commandAllowed("files.read"))
    }

    @Test
    fun `UE7-forceConfirm unions across both sides`() {
        val base = EnterprisePolicy(forceConfirm = listOf(SideEffectClass.destructive))
        val overlay = EnterprisePolicy(forceConfirm = listOf(SideEffectClass.network))
        val union = UnionEnterprisePolicySource(EnterprisePolicySource.fixed(base)) { overlay }
        val merged = union.current()
        assertTrue(merged.requiresForceConfirm(SideEffectClass.destructive))
        assertTrue(merged.requiresForceConfirm(SideEffectClass.network))
    }
}
