package com.mcos.sdk

import kotlinx.serialization.json.JsonElement

/**
 * Execution context passed to every CommandHandler.invoke().
 * Matches [01-architecture.md 11.2], [04-plugin-sdk.md 5].
 */
data class ExecutionContext(
    /** Unique run identifier */
    val runId: String,

    /** Command ID being invoked (canonical, never an alias) */
    val commandId: String,

    /** Step ID within a workflow run (null for standalone invocations) */
    val stepId: String? = null,

    /** Validated input arguments as JSON */
    val args: JsonElement,

    /** Authorization stamp — which grants justify this execution */
    val auth: AuthStamp? = null,

    /** Deadline after which execution will be cancelled (epoch ms) */
    val deadline: Long? = null,

    /** Progress emitter for streaming updates */
    val progress: ProgressEmitter? = null,

    /** Host services facade */
    val services: HostServices
)

/**
 * Authorization stamp minted by Permission Kernel at Stage 6.
 * Matches [01-architecture.md 11.4].
 */
data class AuthStamp(
    val runId: String,
    val commandId: String,
    val pluginId: String,
    val grantsUsed: Set<String>,
    val issuedAt: Long,
    val expiresAt: Long
)

/**
 * Progress emitter for long-running commands.
 * Matches [04-plugin-sdk.md 5.1].
 */
interface ProgressEmitter {
    suspend fun progress(percent: Int?, message: String? = null)
    suspend fun log(level: LogLevel, message: String)
}

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}
