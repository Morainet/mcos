package com.morainet.mcos.runtime.core.api

import kotlinx.serialization.json.JsonElement

/**
 * Core runtime types for the MCOS execution pipeline.
 * Matches [01-architecture.md 11], [03-runtime.md 4].
 *
 * Lives under `runtime.core.api` (not the facade's `runtime.api`) so the
 * package maps 1:1 onto the owning module — no split packages across
 * Gradle modules (see 01-architecture.md §3.3).
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
 * Event stream produced by [com.morainet.mcos.runtime.api.McosRuntime.observe].
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
        val reason: String,
        /**
         * The side-effect class of the command awaiting confirmation, when the
         * request originated from the permission kernel (e.g. "write"). The UI
         * uses this to render an appropriate warning level.
         */
        val sideEffectClass: String? = null,
        /** Permissions that are already granted but whose side-effect class required confirmation. */
        val missingPermissions: List<String> = emptyList(),
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

/**
 * User decision in response to a [RuntimeEvent.ConfirmationNeeded].
 *
 * The host UI passes an instance to
 * [com.morainet.mcos.runtime.api.McosRuntime.respondConfirmation]; the run
 * stays suspended until it is answered or the confirmation timeout elapses.
 * Matches the confirmation flow in [08-security.md 5].
 */
sealed interface ConfirmationDecision {
    /** The user allowed the command to run. */
    data class Approve(
        /**
         * When true, the runtime also grants the underlying permission for the
         * remainder of this process session (08-security.md §5.2), so the same
         * command does not ask again until the runtime restarts.
         */
        val rememberForSession: Boolean = false,
    ) : ConfirmationDecision

    /** The user blocked the command; the run fails with a rejection error. */
    data object Reject : ConfirmationDecision
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
