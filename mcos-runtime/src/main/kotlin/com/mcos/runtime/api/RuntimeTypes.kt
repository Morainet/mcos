package com.mcos.runtime.api

import kotlinx.serialization.json.JsonElement

/**
 * Core runtime types for the MCOS execution pipeline.
 * Matches [01-architecture.md 11], [03-runtime.md 4].
 */

// ─── Execution Request ──────────────────────────────────────────────────

/** Origin of an execution request. */
enum class Source { CLI, CHAT, VOICE, EVENT, API }

/** Payload variants that the runtime accepts. */
sealed class Payload {
    /** Raw DSL text, e.g. "camera.capture(quality=\"high\")" */
    data class DslText(val text: String) : Payload()

    /** Pre-parsed IR JSON */
    data class IrJson(val json: JsonElement) : Payload()

    /** Reference to a stored workflow definition */
    data class WorkflowRef(val workflowId: String) : Payload()
}

/** Controls whether confirmation dialogs are shown during execution. */
enum class ConfirmationMode {
    /** Follow the permission policy (default). */
    POLICY,
    /** Require confirmation for every command in the run. */
    ALWAYS_CONFIRM,
    /** Skip all confirmations (only allowed for read-only commands). */
    NEVER_CONFIRM
}

data class ExecuteRequest(
    val source: Source,
    val payload: Payload,
    val dryRun: Boolean = false,
    val confirmationMode: ConfirmationMode = ConfirmationMode.POLICY,
    val correlationId: String? = null,
)

// ─── Execution Handle & Status ──────────────────────────────────────────

enum class ExecutionStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

data class ExecuteHandle(
    val runId: String,
    val status: ExecutionStatus,
)

// ─── Runtime Events ─────────────────────────────────────────────────────

/**
 * Event stream produced by [McosRuntime.observe].
 * 11-variant sealed class per [01-architecture.md 11.5].
 */
sealed class RuntimeEvent {
    /** A new run has started. */
    data class RunStarted(
        val runId: String,
        val commandId: String?,
        val timestamp: Long
    ) : RuntimeEvent()

    /** A step within the run has started executing. */
    data class StepStarted(
        val runId: String,
        val stepIndex: Int,
        val commandId: String
    ) : RuntimeEvent()

    /** Progress update from a long-running command. */
    data class Progress(
        val runId: String,
        val percent: Int?,
        val message: String?
    ) : RuntimeEvent()

    /** An artifact was produced by the command. */
    data class ArtifactEmitted(
        val runId: String,
        val type: String,
        val uri: String,
        val mimeType: String?
    ) : RuntimeEvent()

    /** Log message from the runtime or a command. */
    data class LogEmitted(
        val runId: String,
        val level: String,
        val message: String
    ) : RuntimeEvent()

    /** The runtime needs user confirmation before proceeding. */
    data class ConfirmationNeeded(
        val runId: String,
        val commandId: String,
        val reason: String
    ) : RuntimeEvent()

    /** A step completed successfully. */
    data class StepSucceeded(
        val runId: String,
        val stepIndex: Int,
        val commandId: String,
        val durationMs: Long
    ) : RuntimeEvent()

    /** A step failed with an error. */
    data class StepFailed(
        val runId: String,
        val stepIndex: Int,
        val commandId: String,
        val error: String
    ) : RuntimeEvent()

    /** The entire run completed successfully. */
    data class RunSucceeded(
        val runId: String,
        val durationMs: Long
    ) : RuntimeEvent()

    /** The entire run failed. */
    data class RunFailed(
        val runId: String,
        val error: String
    ) : RuntimeEvent()

    /** The run was cancelled by the user or system. */
    data class RunCancelled(
        val runId: String
    ) : RuntimeEvent()
}

// ─── Preview ────────────────────────────────────────────────────────────

data class PreviewResult(
    val commandCount: Int,
    val commands: List<PreviewCommand>,
    val warnings: List<String> = emptyList(),
)

data class PreviewCommand(
    val id: String,
    val args: Map<String, String>,
    val sideEffectClass: String,
)
