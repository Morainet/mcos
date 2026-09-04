package com.morainet.mcos.android

import com.morainet.mcos.plugin.camera.CameraPlugin
import com.morainet.mcos.plugin.files.FilesPlugin
import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.plugin.system.SystemPlugin
import com.morainet.mcos.runtime.core.plugin.McosPackage
import com.morainet.mcos.sdk.McosPlugin

/**
 * [McosPlugin] factory for marketplace installs.
 *
 * [com.morainet.mcos.marketplace.PluginInstaller.installPackage] takes a
 * `pluginFactory: (ByteArray) -> McosPlugin` that decodes the verified artifact
 * bytes into a plugin instance. Two paths:
 *
 * 1. **Curated built-ins** — known package ids resolve to the locally compiled
 *    plugin classes (the returned factory ignores the bytes; they were still
 *    downloaded and cryptographically verified first).
 * 2. **Dynamic `.mcos`** — for any other id, if a [dynamicLoader] is present,
 *    parse the verified artifact's manifest ([McosPackage]) and DEX-load its
 *    declared entry class. Without a loader, the id fails fast rather than
 *    pretending to load code it cannot.
 *
 * The rest of the install chain (download, SHA-256 + signature verification,
 * trust gate, registry activation, uninstall) is fully real.
 */
class MarketplacePluginFactory(
    private val providers: Map<String, () -> McosPlugin> = builtIns(),
    private val dynamicLoader: DynamicPluginLoader? = null,
) {

    /** Whether [packageId] can be decoded — curated, or any id when dynamic loading is on. */
    fun supports(packageId: String): Boolean = packageId in providers || dynamicLoader != null

    /**
     * Factory for [packageId], or `null` when neither a curated implementation
     * nor a dynamic loader can produce it.
     *
     * The dynamic factory parses the manifest from the verified bytes and
     * asserts its declared id matches [packageId] (a signed artifact must not
     * masquerade as a different package), then DEX-loads the entry class.
     */
    fun factoryFor(packageId: String): ((ByteArray) -> McosPlugin)? {
        providers[packageId]?.let { provider -> return { provider() } }
        val loader = dynamicLoader ?: return null
        return { bytes ->
            val info = McosPackage.readManifest(bytes)
            require(info.id == packageId) {
                "package manifest id '${info.id}' does not match requested '$packageId'"
            }
            loader.load(packageId, bytes, info.entry)
        }
    }

    companion object {
        /**
         * The four built-in plugins, keyed by their manifest package ids.
         * A marketplace index serving these ids can therefore be installed
         * end-to-end (artifact verification → MARKETPLACE_VERIFIED → registered).
         */
        fun builtIns(): Map<String, () -> McosPlugin> = mapOf(
            "example.hello" to { HelloPlugin() },
            "mcos.plugin.system" to { SystemPlugin() },
            "mcos.plugin.camera" to { CameraPlugin() },
            "mcos.plugin.files" to { FilesPlugin() },
        )
    }
}
