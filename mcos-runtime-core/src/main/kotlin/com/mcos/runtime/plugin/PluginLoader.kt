package com.mcos.runtime.plugin

import com.mcos.runtime.registry.CommandRegistry
import com.mcos.runtime.registry.RegisterResult
import com.mcos.runtime.security.ArtifactSignature
import com.mcos.runtime.security.PluginTrustGate
import com.mcos.runtime.security.TrustLevel
import com.mcos.sdk.McosPlugin

/**
 * Outcome of [PluginLoader.load].
 */
sealed class LoadResult {
    /** Plugin passed the trust gate and was registered. */
    data class Installed(
        val pluginId: String,
        val trustLevel: TrustLevel,
        val commandsRegistered: Int,
        val aliasesRegistered: Int,
        val note: String? = null,
    ) : LoadResult()

    /** Plugin was rejected by the trust gate ([08-security.md §7.1]). */
    data class Denied(
        val pluginId: String,
        val reason: String,
        val code: String,
    ) : LoadResult()

    /** Loading failed before or during registration (non-trust related). */
    data class Failed(
        val pluginId: String,
        val message: String,
    ) : LoadResult()
}

/**
 * Loads plugin artifacts into the runtime ([09-marketplace.md §7.0]).
 *
 * Pipeline (fail-closed):
 *
 *   evaluate via [PluginTrustGate] → register via [CommandRegistry]
 *
 * A plugin is only ever registered after it passed the trust gate; the
 * registry itself additionally rejects [TrustLevel.UNTRUSTED] registrations
 * as a second line of defense.
 *
 * For the MVP the plugin instance is supplied by the caller (host or a
 * dynamic package loader); this class is responsible for the trust decision
 * and registry hand-off. Dynamic `.mcos` package unpacking (manifest
 * extraction, class loading) is layered on top without changing this API.
 */
class PluginLoader(
    private val trustGate: PluginTrustGate,
    private val registry: CommandRegistry,
) {

    /**
     * Evaluate and (if allowed) register a plugin.
     *
     * @param packageId plugin package id.
     * @param version plugin version.
     * @param payload artifact bytes; null for builtin plugins.
     * @param signature signature envelope; null when unsigned.
     * @param builtin true if the plugin ships with the runtime (skips
     *        artifact verification, registers as [TrustLevel.BUILTIN]).
     * @param plugin the fully-constructed plugin instance to register.
     */
    fun load(
        packageId: String,
        version: String,
        payload: ByteArray?,
        signature: ArtifactSignature?,
        builtin: Boolean = false,
        plugin: McosPlugin,
    ): LoadResult {
        val decision = trustGate.evaluate(
            packageId = packageId,
            version = version,
            payload = payload,
            signature = signature,
            builtin = builtin,
        )

        return when (decision) {
            is com.mcos.runtime.security.TrustDecision.Allow -> {
                val result = registry.register(plugin, decision.trustLevel)
                when (result) {
                    is RegisterResult.Ok -> LoadResult.Installed(
                        pluginId = plugin.manifest.id,
                        trustLevel = decision.trustLevel,
                        commandsRegistered = result.commandsRegistered,
                        aliasesRegistered = result.aliasesRegistered,
                        note = decision.note,
                    )
                    is RegisterResult.Conflict -> LoadResult.Installed(
                        pluginId = plugin.manifest.id,
                        trustLevel = decision.trustLevel,
                        commandsRegistered = result.commandsRegistered,
                        aliasesRegistered = result.aliasesRegistered,
                        note = "conflicts: ${result.conflicts.joinToString { it.commandId }}",
                    )
                    is RegisterResult.Rejected -> LoadResult.Denied(
                        pluginId = plugin.manifest.id,
                        reason = result.reason,
                        code = "registry_rejected",
                    )
                }
            }
            is com.mcos.runtime.security.TrustDecision.Deny -> LoadResult.Denied(
                pluginId = packageId,
                reason = decision.reason,
                code = decision.code,
            )
        }
    }
}
