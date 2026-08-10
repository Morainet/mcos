package com.mcos.sdk

import kotlinx.serialization.Serializable

/**
 * Plugin manifest as loaded from plugin.json.
 * Matches [04-plugin-sdk.md §4.2].
 */
@Serializable
data class PluginManifest(
    /** Reverse-DNS unique plugin ID */
    val id: String,

    /** Human-readable name */
    val name: String,

    /** Plugin version (SemVer) */
    val version: String,

    /** Minimum required Runtime version (SemVer) */
    val minRuntimeVersion: String,

    /** One-line description */
    val description: String,

    /** Provider information */
    val provider: ProviderInfo,

    /** Fully-qualified plugin class name implementing McosPlugin */
    val entry: String,

    /** Plugin-level permissions (additive with per-command permissions) */
    val permissions: List<PermissionEntry> = emptyList(),

    /** Command entries declared by this plugin */
    val commands: List<CommandManifestEntry> = emptyList(),

    /** Namespace roots claimed by this plugin */
    val namespaces: List<String> = emptyList(),

    /** Event type prefixes this plugin publishes */
    val eventsEmitted: List<String> = emptyList(),

    /** Event type prefixes this plugin subscribes to */
    val eventsConsumed: List<String> = emptyList(),

    /** Marketplace filter tags */
    val tags: List<String> = emptyList(),

    /** Plugin-wide dispatch hint */
    val threadHint: String = "io",

    /** Per-locale overrides */
    val i18n: Map<String, I18nOverrides>? = null
)

/**
 * Provider/publisher information.
 */
@Serializable
data class ProviderInfo(
    val name: String,
    val url: String
)

/**
 * A command entry inside plugin.json commands[] array.
 * Maps to CommandDescriptor after plugin load.
 */
@Serializable
data class CommandManifestEntry(
    val id: String,
    val version: String,
    val title: String,
    val description: String,
    val sideEffectClass: SideEffectClass,
    val idempotent: Boolean = false,
    val timeoutMs: Long = 60000,
    val permissions: List<PermissionEntry> = emptyList(),
    val aliases: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val deprecated: Boolean = false,
    val replacedBy: String? = null
)

/**
 * Per-locale string overrides for i18n.
 */
@Serializable
data class I18nOverrides(
    val name: String? = null,
    val description: String? = null
)
