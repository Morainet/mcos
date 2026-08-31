package com.morainet.mcos.android

import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.PermissionEntry
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    /** The `.mcos` package was malformed (not a zip, missing/invalid manifest, or an invalid field value). */
    class FormatException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    /** Manifest file name at the root of the `.mcos` zip. */
    const val MANIFEST_ENTRY = "plugin.json"

    /**
     * Read the minimal load fields (`id` / `entry` / `version`) from the
     * `plugin.json` manifest. Packages declaring the full schema (commands,
     * permissions, …) should use [readPluginManifest] instead — this is the
     * plugin-process side, which only needs the entry class to instantiate.
     *
     * @throws FormatException if the zip has no `plugin.json`, the JSON is
     *   invalid, or a required field (`id` / `entry`) is missing.
     */
    fun readManifest(artifact: ByteArray): McosPackageInfo {
        val manifest = readPluginManifest(artifact)
        return McosPackageInfo(id = manifest.id, entry = manifest.entry, version = manifest.version)
    }

    /**
     * Read the FULL [PluginManifest] from the `.mcos` `plugin.json` — the
     * manifest-only registration schema (08-security.md §8): the MAIN process
     * registers commands, schemas and permissions from these bytes without
     * loading any plugin dex; the plugin's code exists only in the plugin
     * process.
     *
     * Required fields: `id`, `entry` (same contract as [readManifest]).
     * Everything else is lenient with spec-shaped defaults, EXCEPT the
     * values that gate security decisions — an unknown `commands[].sideEffectClass`
     * or a command entry without an `id` is a [FormatException] (fail the
     * decode, fail the install) rather than a silent downgrade to `read`.
     * Unknown top-level fields and unknown command fields are ignored so the
     * two schema versions upgrade independently.
     *
     * A package whose `plugin.json` predates the schema extension (only
     * id/entry/version) decodes with an empty command list — it cannot
     * register manifest-only and must take the dex-load path.
     *
     * Not decoded: `threadHint` and `i18n` keep their defaults (registration
     * does not consume them).
     *
     * @throws FormatException if the zip has no `plugin.json`, the JSON is
     *   invalid, a required field is missing, or a security-relevant field
     *   carries an invalid value.
     */
    fun readPluginManifest(artifact: ByteArray): PluginManifest {
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

        return PluginManifest(
            id = id,
            entry = entry,
            version = version,
            name = field("name").takeIf { !it.isNullOrBlank() } ?: id,
            minRuntimeVersion = field("minRuntimeVersion").orEmpty(),
            description = field("description").orEmpty(),
            provider = decodeProvider(obj["provider"]),
            permissions = decodePermissions(obj["permissions"]),
            commands = decodeCommands(obj["commands"]),
            namespaces = decodeStrings(obj["namespaces"]),
            eventsEmitted = decodeStrings(obj["eventsEmitted"]),
            eventsConsumed = decodeStrings(obj["eventsConsumed"]),
            tags = decodeStrings(obj["tags"]),
        )
    }

    private fun decodeProvider(element: JsonElement?): ProviderInfo {
        val provider = element as? JsonObject ?: return ProviderInfo(name = "", url = "")
        fun p(name: String): String =
            (provider[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ProviderInfo(name = p("name"), url = p("url"))
    }

    private fun decodePermissions(element: JsonElement?): List<PermissionEntry> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            PermissionEntry(
                type = type,
                name = name,
                reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    private fun decodeCommands(element: JsonElement?): List<CommandManifestEntry> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapIndexed { index, item ->
            val obj = item as? JsonObject
                ?: throw FormatException("$MANIFEST_ENTRY commands[$index] is not an object")
            val commandId = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw FormatException("$MANIFEST_ENTRY commands[$index] missing required 'id'")

            fun s(name: String, default: String): String =
                (obj[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: default
            fun b(name: String): Boolean =
                (obj[name] as? JsonPrimitive)?.contentOrNull == "true"
            fun n(name: String, default: Long): Long =
                (obj[name] as? JsonPrimitive)?.longOrNull ?: default
            fun strings(name: String): List<String> = decodeStrings(obj[name])

            val sideEffect = (obj["sideEffectClass"] as? JsonPrimitive)?.contentOrNull
            CommandManifestEntry(
                id = commandId,
                version = s("version", "1.0.0"),
                title = s("title", commandId),
                description = s("description", ""),
                sideEffectClass = when {
                    sideEffect == null -> SideEffectClass.read
                    else -> SideEffectClass.entries.firstOrNull {
                        it.name.equals(sideEffect, ignoreCase = true)
                    } ?: throw FormatException(
                        "$MANIFEST_ENTRY commands[$index] has unknown sideEffectClass '$sideEffect'",
                    )
                },
                idempotent = b("idempotent"),
                timeoutMs = n("timeoutMs", 60_000),
                permissions = decodePermissions(obj["permissions"]),
                aliases = strings("aliases"),
                examples = strings("examples"),
                tags = strings("tags"),
                deprecated = b("deprecated"),
                replacedBy = (obj["replacedBy"] as? JsonPrimitive)?.contentOrNull,
                inputSchema = obj["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                outputSchema = obj["outputSchema"] as? JsonObject,
            )
        }
    }

    private fun decodeStrings(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
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
