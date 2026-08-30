package com.morainet.mcos.android

import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.security.permission.PermissionKernel

/**
 * Grants a plugin's declared permissions to the [PermissionKernel] — the
 * "built-ins are trusted" wiring for plugins that ship with the app
 * (registered at `BUILTIN` trust level).
 *
 * This is a deliberate demo-shell simplification of the consent model:
 * marketplace installs surface a `permissionsPreview` confirmation at
 * install time (09-marketplace.md §7) and would grant on consent, but
 * built-in plugins are constructed directly by [CompositionRoot] with no
 * install step, so nothing would ever populate the kernel's grant table —
 * every permission-declaring command (e.g. `camera.capture`) would be hard
 * denied at Executor Stage 6 before the first-use confirmation dialog could
 * even run.
 *
 * Note this only clears the hard permission gate: the side-effect
 * confirmation flow (08-security.md §4/§5) still prompts independently.
 */
object PluginPermissionBootstrap {

    /**
     * Every permission name a plugin declares — manifest-level plus each
     * command's own list, de-duplicated. These are exactly the names
     * [PermissionKernel.authorize] treats as hard requirements (it reads
     * the same command-level `permissions` entries).
     */
    fun declaredPermissions(plugin: McosPlugin): List<String> = buildList {
        plugin.manifest.permissions.forEach { add(it.name) }
        plugin.manifest.commands.forEach { command ->
            command.permissions.forEach { add(it.name) }
        }
    }.distinct()

    /** Grant all [declaredPermissions] under the plugin's manifest id. */
    fun grantAll(kernel: PermissionKernel, plugin: McosPlugin) {
        declaredPermissions(plugin).forEach { kernel.grant(plugin.manifest.id, it) }
    }
}
