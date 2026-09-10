package com.morainet.mcos.runtime.core.error

/**
 * Unified error code enum for the entire MCOS system.
 * This is the single source of truth — [01-architecture.md 15.1].
 *
 * The [retryable] flag controls whether a workflow / caller layer may
 * automatically re-dispatch the command. Security-relevant codes
 * (PERMISSION_DENIED, CONFIRMATION_REQUIRED) are non-retryable to
 * prevent brute-force and infinite-confirmation loops.
 */
enum class McosErrorCode(val retryable: Boolean) {
    // ─── Parse / Compile ──────────────────────────────────
    PARSE_ERROR(false),
    COMPILE_FAILED(false),
    UNKNOWN_COMMAND(false),

    // ─── Validation ─────────────────────────────────────
    SCHEMA_VIOLATION(false),

    // ─── Authorization ──────────────────────────────────
    // Non-retryable: a denied command stays denied until the user
    // explicitly grants the permission; auto-retry is a brute-force vector.
    PERMISSION_DENIED(false),
    // Non-retryable: confirmation is a pause, not an error. Auto-retry
    // would loop endlessly waiting for user input.
    CONFIRMATION_REQUIRED(false),

    // ─── Execution ──────────────────────────────────────
    TIMEOUT(true),
    CANCELLED(false),
    PLUGIN_ERROR(false),
    UNAVAILABLE(true),
    RATE_LIMITED(true),
    // Non-retryable: CONFLICT covers deadlock / duplicate manifest —
    // retrying blindly is unlikely to resolve it and may worsen contention.
    CONFLICT(false),
    INTERNAL(false),

    // ─── Workflow ───────────────────────────────────────
    WORKFLOW_INVALID(false),
    MAX_ITERATIONS_EXCEEDED(false),
    COMPENSATION_FAILED(false),
    // Reserved (01 §15.1): parallel branch failures carry their own command
    // code (02 §10.3), so no production path produces JOIN_FAILED today. Kept
    // enumerated because the spec names it — this note is the honest record
    // (10 §17.1: no unassigned or ambiguous codes).
    JOIN_FAILED(false),
    // Triggers may legitimately fire again on the next schedule tick.
    // Reserved (01 §15.1): the managers skip-and-audit a missed fire instead
    // of erroring, so no production path produces this code today (see
    // ScheduleTriggerManager).
    TRIGGER_MISFIRE(true),
}
