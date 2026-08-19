package com.morainet.mcos.sdk

import kotlinx.serialization.json.JsonObject

/**
 * Plugin-declared exception that carries a structured error code.
 * When a handler throws [McosException], the Executor maps it directly
 * to [CommandResult.Err] — bypassing the generic Throwable → PLUGIN_ERROR heuristic.
 *
 * This is the ONLY channel for a plugin to emit error codes other than PLUGIN_ERROR.
 *
 * Matches [03-runtime.md 9.5], [01-architecture.md 10.3].
 */
data class McosException(
    /** MUST be a valid [com.morainet.mcos.runtime.core.error.McosErrorCode] or plugin-namespaced code. */
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    val details: JsonObject = JsonObject(emptyMap())
) : RuntimeException(message)
