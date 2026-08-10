package com.mcos.runtime.permission

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
    fun `P14-network sideEffectClass implies mcos_network permission`() {
        kernel.grant("example.net", "mcos:network")

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
    fun `P15-destructive with no implicit permission denied`() {
        // No mcos:destructive granted → should fail on permission check
        val descriptor = createDescriptor(
            id = "file.wipe",
            pluginId = "example.files",
            sideEffectClass = SideEffectClass.destructive
        )

        val result = kernel.authorize(descriptor)
        assertIs<AuthorizationResult.Denied>(result)
        assertTrue(result.missingPermissions.contains("mcos:destructive"))
    }

    // ═══════════════════════════════════════════════════════════════
    // P16: Combined permissions (explicit + implicit)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P16-combined explicit and implicit permissions checked together`() {
        // grant explicit but not implicit
        kernel.grant("example.app", "android.permission.CAMERA")
        // mcos:network is NOT granted

        val descriptor = createDescriptor(
            id = "camera.upload",
            pluginId = "example.app",
            sideEffectClass = SideEffectClass.network,
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA"))
        )

        val result = kernel.authorize(descriptor)
        // Should be denied because mcos:network is missing
        assertIs<AuthorizationResult.Denied>(result)
        assertTrue(result.missingPermissions.contains("mcos:network"))
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
        assertEquals(result.stamp.issuedAt + 30000, result.stamp.expiresAt)
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
