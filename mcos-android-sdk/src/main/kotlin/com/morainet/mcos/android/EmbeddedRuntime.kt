package com.morainet.mcos.android

import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.registry.RegisterResult
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Base host assembly for app-shipped, trusted plugins. No planner, marketplace,
 * installer or schedule bootstrap is initialized. Declared plugin permissions
 * are host-approved here; runtime side-effect confirmation still applies.
 * The host owns the supplied services and audit writer for the process lifetime.
 */
class EmbeddedRuntime private constructor(
    val runtime: McosRuntime,
    val auditLog: AuditLog,
) {
    companion object {
        suspend fun create(
            hostServices: HostServices,
            plugins: List<McosPlugin>,
            auditLog: AuditLog,
        ): EmbeddedRuntime {
            val registry = CommandRegistry()
            val kernel = DefaultPermissionKernel()
            val loaded = mutableListOf<McosPlugin>()
            try {
                // Validate the whole catalog before any lifecycle side effects.
                plugins.forEach { plugin ->
                    require(registry.register(plugin) is RegisterResult.Ok) {
                        "Conflicting or invalid host plugin: ${plugin.manifest.id}"
                    }
                }
                plugins.forEach { plugin ->
                    loaded += plugin
                    PluginPermissionBootstrap.activate(plugin, hostServices, kernel)
                }
                val runtime = McosRuntime.Builder()
                    .withRegistry(registry)
                    .withPermissionKernel(kernel)
                    .withHostServices(hostServices)
                    .withAuditLog(auditLog)
                    .withAuditFailClosed()
                    .build()
                return EmbeddedRuntime(runtime, auditLog)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    loaded.asReversed().forEach { plugin ->
                        runCatching { plugin.onUnload() }.exceptionOrNull()?.let(failure::addSuppressed)
                    }
                }
                throw failure
            }
        }
    }
}
