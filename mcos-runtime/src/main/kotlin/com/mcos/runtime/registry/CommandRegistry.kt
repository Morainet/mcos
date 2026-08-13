package com.mcos.runtime.registry

import com.mcos.sdk.CommandDescriptor
import com.mcos.sdk.CommandHandler
import com.mcos.sdk.CommandResult
import com.mcos.sdk.ExecutionContext
import com.mcos.sdk.McosPlugin
import com.mcos.sdk.PermissionEntry
import com.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.JsonObject

/**
 * A single resolved entry in the command registry — pairs a [CommandDescriptor]
 * with its owning plugin's id, version, and handler.
 */
data class RegistryEntry(
    val pluginId: String,
    val pluginVersion: String,
    val descriptor: CommandDescriptor,
    val handler: CommandHandler
)

/**
 * Result of a [CommandRegistry.resolve] call.
 */
sealed class ResolveResult {
    /** Exact match by command ID or alias. */
    data class Found(val entry: RegistryEntry) : ResolveResult()

    /** No command registered for this ID. */
    data class NotFound(val commandId: String) : ResolveResult()

    /** Command exists but not in the requested version range. */
    data class IncompatibleVersion(val commandId: String, val requestedVersion: String? = null) : ResolveResult()
}

/**
 * Result of registering a plugin's commands into the registry.
 */
sealed class RegisterResult {
    /** All commands registered successfully. */
    data class Ok(val commandsRegistered: Int, val aliasesRegistered: Int) : RegisterResult()

    /** Some commands conflicted with existing registrations. */
    data class Conflict(
        val conflicts: List<ConflictDetail>,
        val commandsRegistered: Int,
        val aliasesRegistered: Int
    ) : RegisterResult()
}

data class ConflictDetail(
    val commandId: String,
    val existingPlugin: String,
    val incomingPlugin: String
)

/**
 * Immutable SemVer representation for version comparison and sorting.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemanticVersion> {

    companion object {
        /**
         * Parse a SemVer string like "1.2.3". Throws [IllegalArgumentException] on invalid input.
         */
        fun parse(version: String): SemanticVersion {
            val parts = version.trim().split(".")
            require(parts.size >= 2) {
                "Invalid SemVer: '$version'. Expected at least MAJOR.MINOR (e.g. '1.0')"
            }
            require(parts.size <= 3) {
                "Invalid SemVer: '$version'. Expected at most MAJOR.MINOR.PATCH (e.g. '1.0.0')"
            }

            val major = parts[0].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid major version in '$version'")
            val minor = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid minor version in '$version'")
            val patch = if (parts.size == 3) {
                parts[2].toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid patch version in '$version'")
            } else {
                0
            }

            require(major >= 0) { "Major version must be ≥ 0 in '$version'" }
            require(minor >= 0) { "Minor version must be ≥ 0 in '$version'" }
            require(patch >= 0) { "Patch version must be ≥ 0 in '$version'" }

            return SemanticVersion(major, minor, patch)
        }
    }

    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Stub handler used when a command is declared in the manifest but has no
 * implementation in [McosPlugin.handlers]. The command is still discoverable
 * (for prompt building, schema validation, etc.) but fails at execution time
 * with a clear NOT_IMPLEMENTED error.
 */
object NotImplementedHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult =
        CommandResult.Err(
            code = "NOT_IMPLEMENTED",
            message = "Command '${ctx.commandId}' is declared but has no handler implementation.",
            retryable = false
        )
}

/**
 * Command registry — the central index that maps command IDs to their
 * descriptors, handlers, and owning plugins.
 *
 * Implements MCOS Runtime spec [03-runtime.md 6]:
 * - Exact-match resolution by ID
 * - Alias resolution
 * - Namespace prefix queries
 * - Version coexistence (sorted set per command ID)
 * - Plugin install/uninstall lifecycle
 *
 * Thread safety (P1-C4): every public method is guarded by the registry's
 * intrinsic monitor. Registrations and unregisters are compound operations
 * that touch several indexes atomically; reads (resolve / list) must observe
 * a consistent snapshot. The monitor is held only briefly and never crosses a
 * suspension point, so this is safe under coroutine concurrency. A
 * `ConcurrentHashMap` alone would not suffice because `register`/`unregister`
 * perform multi-index read-modify-write sequences that must be atomic.
 */
class CommandRegistry {

    // ─── Internal indexes ───────────────────────────────────────────────

    /** Command ID (lowercase) → sorted entries, newest version first */
    private val byId = mutableMapOf<String, MutableList<RegistryEntry>>()

    /** Alias (lowercase) → target command ID (lowercase) */
    private val byAlias = mutableMapOf<String, String>()

    /** Plugin ID → all entries owned by this plugin */
    private val byPlugin = mutableMapOf<String, MutableList<RegistryEntry>>()

    /** Namespace prefix → list of command IDs */
    private val byNamespace = mutableMapOf<String, MutableSet<String>>()

    // ─── Plugin lifecycle ──────────────────────────────────────────────

    /**
     * Register all commands from a [McosPlugin].
     *
     * For each handler in [McosPlugin.handlers]:
     * 1. Looks up a matching [CommandManifestEntry] from [PluginManifest.commands]
     * 2. Creates a [CommandDescriptor] from it (or a minimal one)
     * 3. Registers the descriptor + handler + aliases
     *
     * @return [RegisterResult.Ok] if all commands registered cleanly,
     *         [RegisterResult.Conflict] if some commands conflicted.
     */
    fun register(plugin: McosPlugin): RegisterResult = synchronized(this) {
        val manifest = plugin.manifest
        val pluginId = manifest.id
        val pluginVersion = manifest.version
        val handlers = plugin.handlers()

        // Build a lookup from manifest.commands for descriptor enrichment
        val manifestCommands = manifest.commands.associateBy { it.id.lowercase() }

        // Unregister existing entries from this plugin first (re-registration support)
        unregisterSilent(pluginId)

        // Iterate over the union of manifest-declared commands and handler-provided commands.
        // Manifest is the source of truth for what commands a plugin exposes; a command
        // declared in the manifest but lacking a handler is still registered (for prompt
        // building, schema lookup, etc.) and gets a NOT_IMPLEMENTED stub handler.
        val allCommandIds: LinkedHashSet<String> = LinkedHashSet()
        for (cmd in manifest.commands) allCommandIds.add(cmd.id)
        for (id in handlers.keys) allCommandIds.add(id)

        val pluginEntries = mutableListOf<RegistryEntry>()
        val conflicts = mutableListOf<ConflictDetail>()
        var commandsRegistered = 0
        var aliasesRegistered = 0

        for (commandId in allCommandIds) {
            val commandIdLower = commandId.lowercase()
            val manifestEntry = manifestCommands[commandIdLower]
            val handler = handlers[commandId] ?: handlers[commandIdLower]

            // Build descriptor
            val descriptor = if (manifestEntry != null) {
                CommandDescriptor(
                    id = manifestEntry.id,
                    version = manifestEntry.version,
                    pluginId = pluginId,
                    title = manifestEntry.title,
                    description = manifestEntry.description,
                    inputSchema = manifestEntry.inputSchema,
                    outputSchema = manifestEntry.outputSchema,
                    permissions = manifestEntry.permissions + manifest.permissions,
                    sideEffectClass = manifestEntry.sideEffectClass,
                    idempotent = manifestEntry.idempotent,
                    timeoutMs = manifestEntry.timeoutMs,
                    tags = manifestEntry.tags,
                    examples = manifestEntry.examples,
                    deprecated = manifestEntry.deprecated,
                    replacedBy = manifestEntry.replacedBy,
                    aliases = manifestEntry.aliases
                )
            } else {
                // Minimal descriptor — command declared only via handlers() map
                CommandDescriptor(
                    id = commandId,
                    version = pluginVersion,
                    pluginId = pluginId,
                    title = commandId,
                    description = "Command $commandId from $pluginId",
                    inputSchema = JsonObject(emptyMap()),
                    permissions = manifest.permissions,
                    sideEffectClass = SideEffectClass.read,
                    timeoutMs = 60000
                )
            }

            val entry = RegistryEntry(
                pluginId = pluginId,
                pluginVersion = pluginVersion,
                descriptor = descriptor,
                handler = handler ?: NotImplementedHandler
            )

            // Check for conflicts (different plugin has same command ID)
            val existing = byId[commandIdLower]
            if (existing != null && existing.isNotEmpty() && existing.first().pluginId != pluginId) {
                conflicts.add(
                    ConflictDetail(
                        commandId = commandId,
                        existingPlugin = existing.first().pluginId,
                        incomingPlugin = pluginId
                    )
                )
                // Per spec: conflict = loser is not registered; winner keeps its entry
                // For MVP, the existing plugin wins (first-to-load)
                continue
            }

            // Register in byId
            val entries = byId.getOrPut(commandIdLower) { mutableListOf() }
            entries.add(entry)
            // Keep sorted by version descending
            entries.sortByDescending {
                try {
                    SemanticVersion.parse(it.descriptor.version)
                } catch (_: Exception) {
                    SemanticVersion(0, 0, 0)
                }
            }

            // Register aliases
            for (alias in descriptor.aliases) {
                val aliasLower = alias.lowercase()
                if (aliasLower == commandIdLower) continue // skip self-alias
                if (byAlias.containsKey(aliasLower) && byAlias[aliasLower] != commandIdLower) {
                    conflicts.add(
                        ConflictDetail(
                            commandId = alias,
                            existingPlugin = pluginId,
                            incomingPlugin = pluginId
                        )
                    )
                    continue
                }
                byAlias[aliasLower] = commandIdLower
                aliasesRegistered++
            }

            // Register namespace (prefix before first dot)
            val namespace = commandIdLower.substringBefore(".")
            byNamespace.getOrPut(namespace) { mutableSetOf() }.add(commandIdLower)

            commandsRegistered++
            pluginEntries.add(entry)
        }

        // Track by plugin
        byPlugin[pluginId] = pluginEntries

        return if (conflicts.isNotEmpty()) {
            RegisterResult.Conflict(conflicts, commandsRegistered, aliasesRegistered)
        } else {
            RegisterResult.Ok(commandsRegistered, aliasesRegistered)
        }
    } // synchronized(this)

    /**
     * Unregister all commands owned by a plugin.
     * @return the number of entries removed.
     */
    fun unregister(pluginId: String): Int = synchronized(this) {
        val entries = byPlugin.remove(pluginId) ?: return@synchronized 0
        var removed = 0

        for (entry in entries) {
            val commandIdLower = entry.descriptor.id.lowercase()

            // Remove from byId
            val idEntries = byId[commandIdLower]
            if (idEntries != null) {
                idEntries.removeAll { it.pluginId == pluginId }
                removed++
                if (idEntries.isEmpty()) {
                    byId.remove(commandIdLower)
                }
            }

            // Remove from byNamespace
            val namespace = commandIdLower.substringBefore(".")
            byNamespace[namespace]?.remove(commandIdLower)
            if (byNamespace[namespace]?.isEmpty() == true) {
                byNamespace.remove(namespace)
            }

            // Remove aliases
            val aliasesToRemove = byAlias.entries
                .filter { it.value == commandIdLower }
                .map { it.key }
            aliasesToRemove.forEach { byAlias.remove(it) }
        }

        removed
    }

    /**
     * Silently unregister a plugin's entries without tracking count.
     * Used during re-registration.
     */
    private fun unregisterSilent(pluginId: String) {
        unregister(pluginId)
    }

    // ─── Resolution ──────────────────────────────────────────────────────

    /**
     * Resolve a command ID to a [RegistryEntry].
     *
     * Resolution order:
     * 1. Exact match on command ID (case-insensitive)
     * 2. Alias lookup
     * 3. If multiple versions exist for the same ID, returns the highest SemVer
     *
     * @param commandId The fully-qualified command ID, e.g. "camera.capture"
     * @return [ResolveResult.Found] if found, [ResolveResult.NotFound] otherwise.
     */
    fun resolve(commandId: String): ResolveResult = synchronized(this) {
        val idLower = commandId.lowercase()

        // Step 1: Exact match
        var entries = byId[idLower]

        // Step 2: Alias lookup
        if (entries == null) {
            val resolvedId = byAlias[idLower]
            if (resolvedId != null) {
                entries = byId[resolvedId]
            }
        }

        if (entries.isNullOrEmpty()) {
            return@synchronized ResolveResult.NotFound(commandId)
        }

        // Step 3: Return highest version (list is already sorted desc)
        ResolveResult.Found(entries.first())
    }

    /**
     * Resolve a command ID with optional version range constraint.
     *
     * @param commandId The fully-qualified command ID.
     * @param versionRange Minimum compatible version (same major, minor/patch ≥ this).
     * @return [ResolveResult.Found] if a compatible version exists,
     *         [ResolveResult.IncompatibleVersion] if entries exist but none match the range,
     *         [ResolveResult.NotFound] if no entries at all.
     */
    fun resolve(commandId: String, versionRange: String): ResolveResult = synchronized(this) {
        val idLower = commandId.lowercase()

        var entries = byId[idLower]
        if (entries == null) {
            val resolvedId = byAlias[idLower]
            if (resolvedId != null) {
                entries = byId[resolvedId]
            }
        }

        if (entries.isNullOrEmpty()) {
            return@synchronized ResolveResult.NotFound(commandId)
        }

        val minVersion = try {
            SemanticVersion.parse(versionRange)
        } catch (_: Exception) {
            return@synchronized ResolveResult.IncompatibleVersion(commandId, versionRange)
        }

        // Find highest compatible version (same major, ≥ minVersion)
        val compatible = entries.firstOrNull { entry ->
            try {
                val entryVersion = SemanticVersion.parse(entry.descriptor.version)
                entryVersion.major == minVersion.major && entryVersion >= minVersion
            } catch (_: Exception) {
                false
            }
        }

        if (compatible != null) {
            ResolveResult.Found(compatible)
        } else {
            ResolveResult.IncompatibleVersion(commandId, versionRange)
        }
    }

    // ─── Query methods ───────────────────────────────────────────────────

    /**
     * List all registered command descriptors.
     */
    fun allCommands(): List<CommandDescriptor> = synchronized(this) {
        byId.values.flatMap { entries ->
            entries.map { it.descriptor }
        }.distinctBy { it.id.lowercase() }
    }

    /**
     * List commands filtered by namespace prefix.
     * @param prefix The namespace prefix, e.g. "camera".
     */
    fun listByNamespace(prefix: String): List<CommandDescriptor> = synchronized(this) {
        val prefixLower = prefix.lowercase()
        val ids = byNamespace[prefixLower] ?: return@synchronized emptyList()
        ids.mapNotNull { id -> byId[id]?.firstOrNull()?.descriptor }
    }

    /**
     * List all known namespace prefixes.
     */
    fun namespaces(): Set<String> = synchronized(this) { byNamespace.keys.toSet() }

    /**
     * List all registered aliases as (alias → target) pairs.
     */
    fun aliases(): Map<String, String> = synchronized(this) { byAlias.toMap() }

    /**
     * Get the total number of registered command entries.
     */
    fun entryCount(): Int = synchronized(this) { byId.values.sumOf { it.size } }

    /**
     * Get the total number of unique command IDs.
     */
    fun commandCount(): Int = synchronized(this) { byId.size }

    /**
     * Check if a command ID is registered.
     */
    fun isRegistered(commandId: String): Boolean = synchronized(this) {
        val idLower = commandId.lowercase()
        byId.containsKey(idLower) || byAlias.containsKey(idLower)
    }

    /**
     * Get all commands owned by a specific plugin.
     */
    fun getByPlugin(pluginId: String): List<CommandDescriptor> = synchronized(this) {
        byPlugin[pluginId]?.map { it.descriptor } ?: emptyList()
    }

    // ─── Clear ──────────────────────────────────────────────────────────

    /**
     * Clear all entries from the registry. Primarily for testing.
     */
    fun clear() = synchronized(this) {
        byId.clear()
        byAlias.clear()
        byPlugin.clear()
        byNamespace.clear()
    }
}
