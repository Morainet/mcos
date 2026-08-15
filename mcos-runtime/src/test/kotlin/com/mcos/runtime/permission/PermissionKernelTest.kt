package com.mcos.runtime.permission

import com.mcos.runtime.security.EnterprisePolicy
import com.mcos.sdk.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/**
 * Conformance tests for PermissionKernel v0.1.
 * Matches [03-runtime.md 7], [08-security.md].
 */
class PermissionKernelTest {

    private lateinit var kernel: PermissionKernel

    @BeforeTest
    fun setUp() {
        kernel = PermissionKernel()
    }

    @AfterTest
    fun tearDown() {
        kernel.clearAll()
    }

    // ═══════════════════════════════════════════════════════════════
    // P1-P3: Read commands (no permissions needed)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P1-read command with no permissions is authorized`() {
        val descriptor = createDescriptor(
            id = "sys.clock",
            pluginId = "example.sys",
            sideEffectClass = SideEffectClass.read
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Authorized>(result)
    }

    @Test
    fun `P2-read command with granted permissions passes`() {
        kernel.grant("example.sys", "android.permission.CAMERA")

        val descriptor = createDescriptor(
            id = "camera.scan",
            pluginId = "example.sys",
            sideEffectClass = SideEffectClass.read,
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA"))
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Authorized>(result)
    }

    @Test
    fun `P3-read command with missing permission is denied`() {
        val descriptor = createDescriptor(
            id = "camera.scan",
            pluginId = "example.sys",
            sideEffectClass = SideEffectClass.read,
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA"))
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Denied>(result)
        assertTrue(result.missingPermissions.contains("android.permission.CAMERA"))
    }

    // ═══════════════════════════════════════════════════════════════
    // P4-P6: Write/destructive commands (confirmation needed)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P4-write command needs confirmation`() {
        val descriptor = createDescriptor(
            id = "file.create",
            pluginId = "example.files",
            sideEffectClass = SideEffectClass.write
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.ConfirmationNeeded>(result)
        assertEquals(SideEffectClass.write, result.sideEffectClass)
        assertTrue(result.reason.contains("modify"))
    }

    @Test
    fun `P5-destructive command needs confirmation`() {
        val descriptor = createDescriptor(
            id = "file.delete",
            pluginId = "example.files",
            sideEffectClass = SideEffectClass.destructive
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.ConfirmationNeeded>(result)
        assertEquals(SideEffectClass.destructive, result.sideEffectClass)
    }

    @Test
    fun `P6-network command needs confirmation`() {
        val descriptor = createDescriptor(
            id = "net.fetch",
            pluginId = "example.net",
            sideEffectClass = SideEffectClass.network
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.ConfirmationNeeded>(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // P7-P8: Auto-approve and always-confirm
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P7-auto-approved command skips confirmation`() {
        val descriptor = createDescriptor(
            id = "file.create",
            pluginId = "example.files",
            sideEffectClass = SideEffectClass.write
        )
        kernel.setAutoApprove("file.create", true)

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Authorized>(result)
    }

    @Test
    fun `P8-alwaysConfirm forces confirmation even for read`() {
        kernel.setAlwaysConfirm(true)

        val descriptor = createDescriptor(
            id = "sys.clock",
            pluginId = "example.sys",
            sideEffectClass = SideEffectClass.read
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.ConfirmationNeeded>(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // P9-P11: Grant management
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P9-grant permission makes it checkable`() {
        assertFalse(kernel.hasPermission("example.app", "android.permission.CAMERA"))

        kernel.grant("example.app", "android.permission.CAMERA")

        assertTrue(kernel.hasPermission("example.app", "android.permission.CAMERA"))
    }

    @Test
    fun `P10-revoke permission removes it`() {
        kernel.grant("example.app", "android.permission.CAMERA")
        assertTrue(kernel.hasPermission("example.app", "android.permission.CAMERA"))

        kernel.revoke("example.app", "android.permission.CAMERA")
        assertFalse(kernel.hasPermission("example.app", "android.permission.CAMERA"))
    }

    @Test
    fun `P11-revokeAll clears all plugin permissions`() {
        kernel.grant("example.app", "perm.a")
        kernel.grant("example.app", "perm.b")
        kernel.grant("example.other", "perm.c")

        kernel.revokeAll("example.app")

        assertFalse(kernel.hasPermission("example.app", "perm.a"))
        assertFalse(kernel.hasPermission("example.app", "perm.b"))
        assertTrue(kernel.hasPermission("example.other", "perm.c")) // unchanged
    }

    // ═══════════════════════════════════════════════════════════════
    // P12-P13: Session grants
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P12-session grant is temporary`() {
        kernel.grantSession("example.temp", "perm.temp")
        assertTrue(kernel.hasPermission("example.temp", "perm.temp"))
        assertTrue(kernel.isSessionGrant("example.temp", "perm.temp"))

        kernel.clearSessionGrants()
        assertFalse(kernel.hasPermission("example.temp", "perm.temp"))
    }

    @Test
    fun `P13-session grant mixed with permanent grants`() {
        kernel.grant("example.app", "perm.perm")         // permanent
        kernel.grantSession("example.app", "perm.sess")   // session

        assertTrue(kernel.hasPermission("example.app", "perm.perm"))
        assertTrue(kernel.hasPermission("example.app", "perm.sess"))

        kernel.clearSessionGrants()

        assertTrue(kernel.hasPermission("example.app", "perm.perm")) // survives
        assertFalse(kernel.hasPermission("example.app", "perm.sess")) // gone
    }

    // ═══════════════════════════════════════════════════════════════
    // P14-P15: Implicit permissions from sideEffectClass
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P14-network sideEffectClass implies network_scope permission`() {
        // Network commands now require a network.* scope (aligned with
        // NetworkEgressPolicy which expects network.<domain> grants).
        kernel.grant("example.net", "network.*")

        val descriptor = createDescriptor(
            id = "net.fetch",
            pluginId = "example.net",
            sideEffectClass = SideEffectClass.network
        )

        val result = kernel.authorize(descriptor)
        // Should pass (permission granted) but still needs confirmation (sideEffect ≥ write)
        assertIs<AuthorizationResult.ConfirmationNeeded>(result)
    }

    @Test
    fun `P15-destructive command needs confirmation without explicit perms`() {
        // Destructive commands without explicit permissions are not Denied
        // (implicit sideEffectClass scopes are not hard requirements);
        // they reach the confirmation gate, which forces CONFIRM_ONCE
        // per spec 08 §4.0.
        val descriptor = createDescriptor(
            id = "file.wipe",
            pluginId = "example.files",
            sideEffectClass = SideEffectClass.destructive
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.ConfirmationNeeded>(result)
        assertEquals(SideEffectClass.destructive, result.sideEffectClass)
    }

    // ═══════════════════════════════════════════════════════════════
    // P16: Combined permissions (explicit + implicit)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P16-explicit permission checked, network scope not a hard requirement`() {
        // Grant explicit permission
        kernel.grant("example.app", "android.permission.CAMERA")

        val descriptor = createDescriptor(
            id = "camera.upload",
            pluginId = "example.app",
            sideEffectClass = SideEffectClass.network,
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA"))
        )

        val result = kernel.authorize(descriptor)
        // Explicit permission is satisfied; network.* is an implicit scope
        // (not a hard requirement). The command reaches the confirmation gate.
        assertIs<AuthorizationResult.ConfirmationNeeded>(result)
        assertEquals(SideEffectClass.network, result.sideEffectClass)
    }

    // ═══════════════════════════════════════════════════════════════
    // P17-P18: AuthStamp issuance
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P17-AuthStamp contains plugin and command info`() {
        val descriptor = createDescriptor(
            id = "cmd.execute",
            pluginId = "example.plugin",
            sideEffectClass = SideEffectClass.read
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Authorized>(result)
        assertEquals("cmd.execute", result.stamp.commandId)
        assertEquals("example.plugin", result.stamp.pluginId)
    }

    @Test
    fun `P18-AuthStamp has expiration in the future`() {
        val now = System.currentTimeMillis()
        val descriptor = createDescriptor(
            id = "cmd.execute",
            pluginId = "example.plugin",
            sideEffectClass = SideEffectClass.read,
            timeoutMs = 30000
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Authorized>(result)
        assertTrue(result.stamp.issuedAt >= now - 100)
        assertTrue(result.stamp.expiresAt > result.stamp.issuedAt)
        // expiresAt is derived from authStampTtlMs (5 min), NOT timeoutMs (30 s)
        assertEquals(result.stamp.issuedAt + PermissionKernel.DEFAULT_AUTH_TTL_MS, result.stamp.expiresAt)
    }

    @Test
    fun `P19-AuthStamp expiresAt is decoupled from command timeoutMs`() {
        // A command with a very short timeout (1s) should still get a stamp
        // valid for the full authStampTtlMs (5 min). This proves the fix for
        // the timeout-conflation bug where executeSequence would reject later
        // steps because they inherited the first command's tiny timeout.
        val descriptor = createDescriptor(
            id = "cmd.short",
            pluginId = "example.plugin",
            sideEffectClass = SideEffectClass.read,
            timeoutMs = 1000
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Authorized>(result)
        val stamp = result.stamp
        val ttl = stamp.expiresAt - stamp.issuedAt

        // The stamp lifetime must be the full authStampTtlMs, not 1s
        assertEquals(PermissionKernel.DEFAULT_AUTH_TTL_MS, ttl)
        assertTrue(ttl > 1000, "Stamp TTL ($ttl ms) must exceed timeoutMs (1000 ms) so sequences don't expire early")
    }

    @Test
    fun `P20-AuthStamp uses custom authStampTtlMs when configured`() {
        kernel.authStampTtlMs = 10_000 // 10 seconds

        val descriptor = createDescriptor(
            id = "cmd.custom",
            pluginId = "example.plugin",
            sideEffectClass = SideEffectClass.read,
            timeoutMs = 60000
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Authorized>(result)
        val ttl = result.stamp.expiresAt - result.stamp.issuedAt
        assertEquals(10_000, ttl)
    }

    // ═══════════════════════════════════════════════════════════════
    // P21-P26: Enterprise policy integration (§13.2, §4.3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P21-enterprise deny list rejects command even with grant`() {
        kernel.grant("example.sys", "android.permission.CAMERA")
        val descriptor = createDescriptor(
            id = "camera.scan",
            pluginId = "example.sys",
            sideEffectClass = SideEffectClass.read,
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA")),
        )
        val policy = EnterprisePolicy(denyCommands = listOf("camera.*"))

        val result = kernel.authorize(descriptor, policy)
        assertIs<AuthorizationResult.Denied>(result)
        assertContains(result.reason, "Enterprise policy")
    }

    @Test
    fun `P22-enterprise allow list rejects unlisted command`() {
        val descriptor = createDescriptor(
            id = "mail.send",
            pluginId = "example.mail",
            sideEffectClass = SideEffectClass.network,
        )
        val policy = EnterprisePolicy(allowCommands = listOf("sys.notify", "camera.*"))

        val result = kernel.authorize(descriptor, policy)
        assertIs<AuthorizationResult.Denied>(result)
    }

    @Test
    fun `P22b-enterprise allow list passes listed command`() {
        kernel.grant("example.cam", "android.permission.CAMERA")
        val descriptor = createDescriptor(
            id = "camera.scan",
            pluginId = "example.cam",
            sideEffectClass = SideEffectClass.read,
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA")),
        )
        val policy = EnterprisePolicy(allowCommands = listOf("camera.*"))

        val result = kernel.authorize(descriptor, policy)
        assertIs<AuthorizationResult.Authorized>(result)
    }

    @Test
    fun `P23-enterprise deny wins over user grant and allow list`() {
        kernel.grant("example.mcp", "android.permission.INTERNET")
        val descriptor = createDescriptor(
            id = "mcp.exfil",
            pluginId = "example.mcp",
            sideEffectClass = SideEffectClass.network,
            permissions = listOf(PermissionEntry("android", "android.permission.INTERNET")),
        )
        val policy = EnterprisePolicy(
            allowCommands = listOf("mcp.*"),
            denyCommands = listOf("mcp.exfil"),
        )

        val result = kernel.authorize(descriptor, policy)
        assertIs<AuthorizationResult.Denied>(result)
    }

    @Test
    fun `P24-force confirm upgrades auto-approved write command`() {
        kernel.grant("example.fs", "android.permission.WRITE_EXTERNAL_STORAGE")
        kernel.setAutoApprove("file.create", true)
        val descriptor = createDescriptor(
            id = "file.create",
            pluginId = "example.fs",
            sideEffectClass = SideEffectClass.write,
            permissions = listOf(PermissionEntry("android", "android.permission.WRITE_EXTERNAL_STORAGE")),
        )
        // Without enterprise policy → auto-approved write command
        val before = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Authorized>(before)

        // With force-confirm on write → upgraded to ConfirmationNeeded
        val policy = EnterprisePolicy(forceConfirm = listOf(SideEffectClass.write))
        val after = kernel.authorize(descriptor, policy)
        assertIs<AuthorizationResult.ConfirmationNeeded>(after)
        assertContains(after.reason, "Enterprise policy")
    }

    @Test
    fun `P25-force confirm does not downgrade already-authorizing commands`() {
        kernel.grant("example.sys", "android.permission.SET_ALARM")
        kernel.setAutoApprove("sys.notify", true)
        val descriptor = createDescriptor(
            id = "sys.notify",
            pluginId = "example.sys",
            sideEffectClass = SideEffectClass.write,
            permissions = listOf(PermissionEntry("android", "android.permission.SET_ALARM")),
        )
        val policy = EnterprisePolicy(forceConfirm = listOf(SideEffectClass.control)) // unrelated class

        val result = kernel.authorize(descriptor, policy)
        assertIs<AuthorizationResult.Authorized>(result)
    }

    @Test
    fun `P26-null enterprise policy is pass-through`() {
        val descriptor = createDescriptor(
            id = "sys.clock",
            pluginId = "example.sys",
            sideEffectClass = SideEffectClass.read,
        )
        val result = kernel.authorize(descriptor, null)
        assertIs<AuthorizationResult.Authorized>(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun createDescriptor(
        id: String,
        pluginId: String,
        sideEffectClass: SideEffectClass,
        permissions: List<PermissionEntry> = emptyList(),
        timeoutMs: Long = 60000
    ) = CommandDescriptor(
        id = id,
        version = "1.0.0",
        pluginId = pluginId,
        title = id,
        description = "Test command $id",
        inputSchema = JsonObject(emptyMap()),
        permissions = permissions,
        sideEffectClass = sideEffectClass,
        timeoutMs = timeoutMs
    )
}
