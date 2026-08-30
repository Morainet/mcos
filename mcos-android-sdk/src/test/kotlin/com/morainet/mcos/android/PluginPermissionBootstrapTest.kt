package com.morainet.mcos.android

import com.morainet.mcos.plugin.camera.CameraPlugin
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PermissionEntry
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import com.morainet.mcos.security.permission.AuthorizationResult
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PluginPermissionBootstrap: built-in plugins' declared permissions must land
 * in the kernel's grant table under the manifest id, so Executor Stage 6 no
 * longer hard-denies permission-declaring commands like `camera.capture`
 * (the fix for "run failed: Permission denied for 'camera.capture'").
 */
class PluginPermissionBootstrapTest {

    private class FakePlugin(
        override val manifest: PluginManifest,
    ) : McosPlugin {
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = emptyMap()
    }

    private fun manifest(
        pluginId: String,
        manifestPerms: List<String>,
        commandPerms: List<String>,
    ) = PluginManifest(
        id = pluginId,
        name = "Fake",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "test",
        provider = ProviderInfo("test", "https://example.com"),
        entry = "fake.Entry",
        permissions = manifestPerms.map { PermissionEntry("test", it, "desc") },
        commands = listOf(
            CommandManifestEntry(
                id = "fake.cmd", version = "1.0.0",
                title = "Fake", description = "test",
                sideEffectClass = SideEffectClass.read,
                permissions = commandPerms.map { PermissionEntry("test", it, "desc") },
            )
        ),
    )

    @Test
    fun `declaredPermissions unions manifest and command level names, deduplicated`() {
        val plugin = FakePlugin(
            manifest(
                pluginId = "fake.plugin",
                manifestPerms = listOf("perm.manifest", "perm.shared"),
                commandPerms = listOf("perm.command", "perm.shared"),
            )
        )
        assertEquals(
            listOf("perm.manifest", "perm.shared", "perm.command"),
            PluginPermissionBootstrap.declaredPermissions(plugin),
        )
    }

    @Test
    fun `grantAll populates the kernel under the plugin manifest id`() {
        val kernel = DefaultPermissionKernel()
        val plugin = FakePlugin(
            manifest("fake.plugin", listOf("perm.manifest"), listOf("perm.command"))
        )
        PluginPermissionBootstrap.grantAll(kernel, plugin)

        assertTrue(kernel.hasPermission("fake.plugin", "perm.manifest"))
        assertTrue(kernel.hasPermission("fake.plugin", "perm.command"))
        // Grants are per-plugin: another plugin id does not inherit them.
        assertFalse(kernel.hasPermission("other.plugin", "perm.command"))
    }

    @Test
    fun `real camera plugin passes the hard permission gate after bootstrap`() {
        // Regression for the reported demo failure: register the real
        // CameraPlugin, bootstrap its grants, and authorize `camera.capture`
        // through the real kernel + registry pipeline. Before the fix this
        // returned Denied("Required permissions not granted: …CAMERA");
        // after it, the permission gate passes (the command then proceeds to
        // the side-effect confirmation flow, which is a different gate).
        val kernel = DefaultPermissionKernel()
        val camera = CameraPlugin()
        PluginPermissionBootstrap.grantAll(kernel, camera)

        val registry = CommandRegistry()
        registry.register(camera)

        val descriptor = registry.allCommands().first { it.id == "camera.capture" }
        val result = kernel.authorize(descriptor, null)

        assertFalse(
            "camera.capture must not be permission-denied after bootstrap: $result",
            result is AuthorizationResult.Denied,
        )
    }
}
