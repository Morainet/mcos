package com.morainet.mcos.security

import com.morainet.mcos.security.permission.AuthorizationResult
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.security.permission.PermissionKernel
import com.morainet.mcos.sdk.CommandDescriptor
import com.morainet.mcos.sdk.PermissionEntry
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * UP1-UP9 — the §19 [UserPolicy] confirmation-tightening flags wired into
 * [DefaultPermissionKernel] (08-security.md §4.2). Every flag is upgrade-only
 * and effective on the next `authorize` via a live supplier.
 */
class UserPolicyKernelTest {

    @Volatile
    private var policy = UserPolicy()

    private fun kernel() = DefaultPermissionKernel(userPolicy = { policy })

    // ─── confirmEveryNetwork ───────────────────────────────────────────────

    @Test
    fun `UP1-confirmEveryNetwork upgrades a network command to confirmation`() {
        val k = kernel()
        val net = descriptor("http.get", SideEffectClass.network)
        // Default policy: network already needs confirmation (>= write), so
        // set an auto-approve first, then prove the flag re-confirms anyway.
        k.setAutoApprove("http.get", enabled = true, sideEffectClass = SideEffectClass.network)
        // Auto-approved → authorized without the flag.
        assertIs<AuthorizationResult.Authorized>(k.authorize(net))
        // Flag on → confirmation regardless of the cached auto-approve.
        policy = UserPolicy(confirmEveryNetwork = true)
        assertIs<AuthorizationResult.ConfirmationNeeded>(k.authorize(net))
    }

    @Test
    fun `UP2-confirmEveryNetwork leaves non-network commands untouched`() {
        val k = kernel()
        policy = UserPolicy(confirmEveryNetwork = true)
        val read = descriptor("sys.clock", SideEffectClass.read)
        assertIs<AuthorizationResult.Authorized>(k.authorize(read))
    }

    @Test
    fun `UP3-confirmEveryNetwork is effective on the next invocation via supplier`() {
        val k = kernel()
        val net = descriptor("http.get", SideEffectClass.network)
        k.setAutoApprove("http.get", enabled = true, sideEffectClass = SideEffectClass.network)
        assertIs<AuthorizationResult.Authorized>(k.authorize(net))
        policy = UserPolicy(confirmEveryNetwork = true)
        assertIs<AuthorizationResult.ConfirmationNeeded>(k.authorize(net)) // no rebuild needed
    }

    // ─── backgroundEventsRequireForeground ─────────────────────────────────

    @Test
    fun `UP4-background flag upgrades a read command from an EVENT source`() {
        val k = kernel()
        val read = descriptor("sys.clock", SideEffectClass.read)
        // Without the flag, a background read is authorized (only network /
        // destructive are held to the base stricter matrix).
        assertIs<AuthorizationResult.Authorized>(
            k.authorize(read, null, PermissionKernel.SOURCE_EVENT)
        )
        policy = UserPolicy(backgroundEventsRequireForeground = true)
        assertIs<AuthorizationResult.ConfirmationNeeded>(
            k.authorize(read, null, PermissionKernel.SOURCE_EVENT)
        )
    }

    @Test
    fun `UP5-background flag upgrades a SCHEDULE-source read command`() {
        val k = kernel()
        policy = UserPolicy(backgroundEventsRequireForeground = true)
        val read = descriptor("sys.clock", SideEffectClass.read)
        assertIs<AuthorizationResult.ConfirmationNeeded>(
            k.authorize(read, null, PermissionKernel.SOURCE_SCHEDULE)
        )
    }

    @Test
    fun `UP6-background flag does not affect foreground CLI runs`() {
        val k = kernel()
        policy = UserPolicy(backgroundEventsRequireForeground = true)
        val read = descriptor("sys.clock", SideEffectClass.read)
        assertIs<AuthorizationResult.Authorized>(k.authorize(read, null, "CLI"))
    }

    // ─── disableSessionGrants ──────────────────────────────────────────────

    @Test
    fun `UP7-disableSessionGrants drops a session grant so it never persists`() {
        val k = kernel()
        policy = UserPolicy(disableSessionGrants = true)
        k.grantSession("example.net", "network.example")
        assertTrue(!k.hasPermission("example.net", "network.example"))
        assertTrue(!k.isSessionGrant("example.net", "network.example"))
    }

    @Test
    fun `UP8-disableSessionGrants off keeps the historical session-grant behaviour`() {
        val k = kernel()
        policy = UserPolicy() // flag off
        k.grantSession("example.net", "network.example")
        assertTrue(k.hasPermission("example.net", "network.example"))
        assertTrue(k.isSessionGrant("example.net", "network.example"))
    }

    // ─── upgrade-only invariant ────────────────────────────────────────────

    @Test
    fun `UP9-user flags never loosen an enterprise force-confirm decision`() {
        val k = kernel()
        // Enterprise forces confirmation for write; the user policy is empty.
        policy = UserPolicy()
        val write = descriptor("files.write", SideEffectClass.write)
        val policyDoc = EnterprisePolicy(forceConfirm = listOf(SideEffectClass.write))
        assertIs<AuthorizationResult.ConfirmationNeeded>(k.authorize(write, policyDoc))
        // Turning on user flags can only keep it a confirmation (never authorize).
        policy = UserPolicy(confirmEveryNetwork = true, disableSessionGrants = true)
        assertIs<AuthorizationResult.ConfirmationNeeded>(k.authorize(write, policyDoc))
    }

    private fun descriptor(id: String, cls: SideEffectClass, perms: List<PermissionEntry> = emptyList()) =
        CommandDescriptor(
            id = id,
            version = "1.0.0",
            pluginId = id.substringBefore('.'),
            title = id,
            description = "test $id",
            inputSchema = JsonObject(emptyMap()),
            permissions = perms,
            sideEffectClass = cls,
        )
}
