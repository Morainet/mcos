package com.mcos.runtime.error

/**
 * Unified error code enum for the entire MCOS system.
 * This is the single source of truth — [01-architecture.md 15.1].
 */
enum class McosErrorCode(val retryable: Boolean) {
    // ─── Parse / Compile ──────────────────────────────────
    PARSE_ERROR(false),
    COMPILE_FAILED(true),
    UNKNOWN_COMMAND(false),

    // ─── Validation ─────────────────────────────────────
    SCHEMA_VIOLATION(false),

    // ─── Authorization ──────────────────────────────────
    PERMISSION_DENIED(true),
    CONFIRMATION_REQUIRED(true),

    // ─── Execution ──────────────────────────────────────
    TIMEOUT(true),
    CANCELLED(false),
    PLUGIN_ERROR(false),
    UNAVAILABLE(true),
    RATE_LIMITED(true),
    CONFLICT(true),
    INTERNAL(false),

    // ─── Workflow ───────────────────────────────────────
    WORKFLOW_INVALID(false),
    MAX_ITERATIONS_EXCEEDED(false),
    COMPENSATION_FAILED(false),
    JOIN_FAILED(false),
    TRIGGER_MISFIRE(false),
}
