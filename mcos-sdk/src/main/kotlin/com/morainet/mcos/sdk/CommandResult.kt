package com.morainet.mcos.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Sealed result type for command execution.
 * Matches [04-plugin-sdk.md 5].
 */
sealed class CommandResult {

    /**
     * Successful execution with a typed value and optional artifacts.
     * @param value The JSON-serializable return value.
     * @param artifacts Optional output artifacts (e.g. URIs, file refs).
     */
    data class Ok(
        val value: JsonElement,
        val artifacts: List<Artifact> = emptyList()
    ) : CommandResult()

    /**
     * Expected failure the handler detects and controls.
     * @param code A McosErrorCode or plugin-namespaced code.
     * @param message Human-readable error description.
     * @param retryable Whether a retry may succeed without changes.
     * @param details Structured context per [02-command-protocol.md 8.3] shape B.
     */
    data class Err(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
        val details: JsonObject = JsonObject(emptyMap())
    ) : CommandResult()
}

/**
 * An output artifact produced by a command, e.g. a URI, file reference, or media.
 * Matches [01-architecture.md 11.3].
 */
@Serializable
data class Artifact(
    val type: String,
    val uri: String,
    val mimeType: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
