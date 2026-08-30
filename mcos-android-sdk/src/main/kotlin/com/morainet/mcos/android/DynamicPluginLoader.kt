package com.morainet.mcos.android

import android.content.Context
import com.morainet.mcos.sdk.McosPlugin
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Instantiates a plugin from an already-verified `.mcos` artifact. This is the
 * one host-specific seam of dynamic loading — the manifest parsing
 * ([McosPackage]) and factory dispatch ([MarketplacePluginFactory]) around it
 * are pure and unit-tested; only the class loading itself needs a device.
 */
fun interface DynamicPluginLoader {
    /**
     * Load and instantiate [entryClass] from the verified [artifact] bytes as an
     * [McosPlugin].
     *
     * @param packageId marketplace id, used to scope the on-disk dex cache.
     * @param artifact the verified `.mcos` bytes (a zip/apk carrying classes.dex).
     * @param entryClass FQ class name declared by the manifest ([McosPackageInfo.entry]).
     * @throws Exception if the class cannot be loaded or is not an [McosPlugin];
     *   the caller (install pipeline) surfaces this as a load failure.
     */
    fun load(packageId: String, artifact: ByteArray, entryClass: String): McosPlugin
}

/**
 * Android [DynamicPluginLoader] using a per-plugin [DexClassLoader]
 * ([03-runtime.md §16.3]): the artifact is DEX-loaded in isolation with the
 * app class loader as parent (so the SDK types resolve), and only the declared
 * [entryClass] is reflected into. The artifact bytes are already SHA-256 +
 * signature verified by the install pipeline before this runs.
 *
 * Not exercised by JVM unit tests (DexClassLoader is an Android runtime API) —
 * verified on device.
 */
class DexPluginLoader(private val context: Context) : DynamicPluginLoader {

    override fun load(packageId: String, artifact: ByteArray, entryClass: String): McosPlugin {
        // Per-plugin cache dir, rewritten each load so stale dex never lingers.
        val dexDir = File(context.codeCacheDir, "mcos-dex/${sanitize(packageId)}").apply {
            deleteRecursively()
            mkdirs()
        }
        val artifactFile = File(dexDir, "plugin.mcos").apply { writeBytes(artifact) }
        val optimizedDir = File(dexDir, "oat").apply { mkdirs() }

        val loader = DexClassLoader(
            artifactFile.absolutePath,
            optimizedDir.absolutePath,
            null,
            // Parent = app class loader so McosPlugin / SDK types resolve, while
            // the plugin's own classes stay isolated in this loader (§16.3).
            javaClass.classLoader,
        )
        val clazz = loader.loadClass(entryClass)
        val instance = clazz.getDeclaredConstructor().newInstance()
        return instance as? McosPlugin
            ?: throw IllegalStateException(
                "entry class '$entryClass' does not implement McosPlugin"
            )
    }

    /** Keep the dex cache path within a single safe directory segment. */
    private fun sanitize(packageId: String): String =
        packageId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
