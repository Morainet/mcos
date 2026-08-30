package com.morainet.mcos.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The manifest fields a `.mcos` package must declare for dynamic loading
 * ([04-plugin-sdk.md §4]).
 *
 * @param id reverse-DNS plugin id; must match the marketplace package id.
 * @param entry fully-qualified class name of the [com.morainet.mcos.sdk.McosPlugin]
 *              entry point the runtime instantiates ([03-runtime.md §16.3] — the
 *              runtime only ever reflects into this one declared class).
 * @param version SemVer plugin version.
 */
data class McosPackageInfo(
    val id: String,
    val entry: String,
    val version: String,
)

/**
 * Pure reader for the `.mcos` package format — a signed zip
 * ([04-plugin-sdk.md §3]) carrying `plugin.json` (manifest) alongside the
 * compiled `classes.dex`. Signature/hash verification happens upstream in the
 * install pipeline; this reader only parses the already-verified bytes, so it
 * has no Android dependency and is unit-testable on the JVM.
 *
 * The dex loading itself is host-specific and lives behind
 * [DynamicPluginLoader].
 */
object McosPackage {

    /** The `.mcos` package was malformed (not a zip, or missing/invalid manifest). */
    class FormatException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    /** Manifest file name at the root of the `.mcos` zip. */
    const val MANIFEST_ENTRY = "plugin.json"

    /**
     * Read and validate the `plugin.json` manifest from a `.mcos` [artifact].
     *
     * @throws FormatException if the zip has no `plugin.json`, the JSON is
     *   invalid, or a required field (`id` / `entry`) is missing.
     */
    fun readManifest(artifact: ByteArray): McosPackageInfo {
        val manifestBytes = extractEntry(artifact, MANIFEST_ENTRY)
            ?: throw FormatException("$MANIFEST_ENTRY missing from .mcos package")

        val obj = try {
            json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
        } catch (e: Exception) {
            throw FormatException("$MANIFEST_ENTRY is not a valid JSON object")
        }

        fun field(name: String): String? = obj[name]?.jsonPrimitive?.contentOrNull

        val id = field("id")?.takeIf { it.isNotBlank() }
            ?: throw FormatException("$MANIFEST_ENTRY missing required 'id'")
        val entry = field("entry")?.takeIf { it.isNotBlank() }
            ?: throw FormatException("$MANIFEST_ENTRY missing required 'entry' (McosPlugin class)")
        val version = field("version").orEmpty()

        return McosPackageInfo(id = id, entry = entry, version = version)
    }

    /** Returns the bytes of the zip entry named [name], or null if absent. */
    private fun extractEntry(artifact: ByteArray, name: String): ByteArray? {
        return try {
            ZipInputStream(ByteArrayInputStream(artifact)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == name) return zis.readBytes()
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            throw FormatException(".mcos package is not a readable zip")
        }
    }
}
