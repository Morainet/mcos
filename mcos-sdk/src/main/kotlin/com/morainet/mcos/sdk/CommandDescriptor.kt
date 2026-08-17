package com.morainet.mcos.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Command Descriptor — the registry entry that describes an invocable command.
 * Matches [02-command-protocol.md 8].
 */
@Serializable
data class CommandDescriptor(
    /** Fully-qualified command ID, e.g. "camera.capture" */
    val id: String,

    /** Command contract version (SemVer) */
    val version: String,

    /** Owning plugin ID */
    val pluginId: String,

    /** Human-readable title */
    val title: String,

    /** Human-readable description */
    val description: String,

    /** JSON Schema (Draft 2020-12) for input arguments */
    val inputSchema: JsonObject,

    /** JSON Schema (Draft 2020-12) for output value */
    val outputSchema: JsonObject? = null,

    /** Required permissions; additive with plugin-level permissions */
    val permissions: List<PermissionEntry> = emptyList(),

    /** Impact classification */
    val sideEffectClass: SideEffectClass = SideEffectClass.read,

    /** Whether the command is safe to auto-retry */
    val idempotent: Boolean = false,

    /** Executor timeout in milliseconds (1000..600000) */
    val timeoutMs: Long = 60000,

    /** Optional tags for marketplace filtering and dispatch hints */
    val tags: List<String> = emptyList(),

    /** Example DSL invocations for Planner few-shot and CLI help */
    val examples: List<String> = emptyList(),

    /** Whether this command is deprecated */
    val deprecated: Boolean = false,

    /** If deprecated, the replacement command ID */
    val replacedBy: String? = null,

    /** Alternate command IDs that resolve to this handler */
    val aliases: List<String> = emptyList()
)

/**
 * A permission entry required by a command or plugin.
 */
@Serializable
data class PermissionEntry(
    val type: String,   // "android" | "mcos"
    val name: String,
    val reason: String? = null
)
