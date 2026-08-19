package com.morainet.mcos.security.permission

import com.morainet.mcos.sdk.CommandDescriptor
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Grant-table persistence (08-security.md §5.1): the kernel's durable
 * consent state round-trips through [FileGrantStore] with the
 * FileAuditLog paradigm — replay on construction, atomic rewrite on
 * mutation, corrupt/tampered files fail closed (empty kernel, denials,
 * never a crash).
 */
class PermissionKernelPersistenceTest {

    private val dir = createTempDirectory("mcos-grant-store-test")
    private val file = File(dir.toFile(), "grants.json")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun kernel(hmacKey: ByteArray? = null): DefaultPermissionKernel =
        DefaultPermissionKernel(FileGrantStore(file, hmacKey))

    // ═══════════════════════════════════════════════════════════════
    // G1-G2: grants round-trip, revocation persists
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `G1-grants survive across kernel instances`() {
        kernel().apply {
            grant("plugin.a", "camera.read")
            grant("plugin.a", "files.write")
            grant("plugin.b", "network.fetch")
        }
        val restarted = kernel()
        assertTrue(restarted.hasPermission("plugin.a", "camera.read"))
        assertTrue(restarted.hasPermission("plugin.a", "files.write"))
        assertTrue(restarted.hasPermission("plugin.b", "network.fetch"))
        assertEquals(setOf("camera.read", "files.write"), restarted.getGrants("plugin.a"))
    }

    @Test
    fun `G2-revoke and revokeAll persist removal`() {
        kernel().apply {
            grant("plugin.a", "camera.read")
            grant("plugin.a", "files.write")
        }
        kernel().revoke("plugin.a", "camera.read")
        assertFalse(kernel().hasPermission("plugin.a", "camera.read"))
        assertTrue(kernel().hasPermission("plugin.a", "files.write"))

        kernel().revokeAll("plugin.a")
        assertTrue(kernel().getGrants("plugin.a").isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // G3: auto-approve + always-confirm round-trip, invariant intact
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `G3-autoApprove and alwaysConfirm round-trip, destructive still rejected`() {
        // Auto-approve survives: a write-class command would normally need
        // confirmation (cf. P7), the restarted kernel authorizes outright.
        kernel().setAutoApprove("file.create", true)
        assertIs<AuthorizationResult.Authorized>(kernel().authorize(writeDescriptor()))

        // Always-confirm survives: a read-class command now needs
        // confirmation (cf. P8).
        kernel().setAlwaysConfirm(true)
        assertIs<AuthorizationResult.ConfirmationNeeded>(kernel().authorize(readDescriptor()))

        // The §4.0 invariant fires before anything is written — the store
        // must not contain a destructive auto-approve afterwards.
        assertFailsWith<IllegalArgumentException> {
            kernel().setAutoApprove("files.delete", true, SideEffectClass.destructive)
        }
        assertFalse(file.readText().contains("files.delete"))
    }

    // ═══════════════════════════════════════════════════════════════
    // G4: session grants never hit disk
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `G4-session grants do not survive restart`() {
        val first = kernel()
        first.grant("plugin.a", "camera.read") // durable
        first.grantSession("plugin.a", "location.once") // session-only
        assertTrue(first.hasPermission("plugin.a", "location.once"))

        val restarted = kernel()
        assertTrue(restarted.hasPermission("plugin.a", "camera.read"))
        assertFalse(restarted.hasPermission("plugin.a", "location.once"))
    }

    // ═══════════════════════════════════════════════════════════════
    // G5-G6: corrupt / tampered files fail closed
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `G5-corrupt snapshot starts empty and heals on next mutation`() {
        file.parentFile?.mkdirs()
        file.writeText("not json at all\n")
        val kernel = kernel()
        assertFalse(kernel.hasPermission("plugin.a", "camera.read")) // denied, no crash

        kernel.grant("plugin.a", "camera.read") // rewrite heals the file
        assertTrue(kernel().hasPermission("plugin.a", "camera.read"))
    }

    @Test
    fun `G6-hmac mismatch discards the snapshot`() {
        val key = "device-bound-key".toByteArray()
        kernel(key).apply { grant("plugin.a", "camera.read") }

        // Tamper with the payload line, keep the signature line.
        val lines = file.readLines().toMutableList()
        lines[0] = lines[0].replace("camera.read", "files.write")
        file.writeText(lines.joinToString("\n") + "\n")

        val restarted = kernel(key)
        assertFalse(restarted.hasPermission("plugin.a", "camera.read"))
        assertFalse(restarted.hasPermission("plugin.a", "files.write")) // nothing resurrected
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers (same shape as PermissionKernelTest.createDescriptor)
    // ═══════════════════════════════════════════════════════════════

    private fun writeDescriptor() = CommandDescriptor(
        id = "file.create",
        version = "1.0.0",
        pluginId = "example.files",
        title = "file.create",
        description = "Test command",
        inputSchema = JsonObject(emptyMap()),
        permissions = emptyList(),
        sideEffectClass = SideEffectClass.write,
        timeoutMs = 60000,
    )

    private fun readDescriptor() = CommandDescriptor(
        id = "sys.clock",
        version = "1.0.0",
        pluginId = "example.sys",
        title = "sys.clock",
        description = "Test command",
        inputSchema = JsonObject(emptyMap()),
        permissions = emptyList(),
        sideEffectClass = SideEffectClass.read,
        timeoutMs = 60000,
    )
}
