package com.morainet.mcos.conformance.kernel

import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.conformance.api.ConformanceSuite
import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.ir.ParseResult
import com.morainet.mcos.runtime.core.parse.DslParser

/**
 * Kernel 1.0 stabilization-gate conformance (10 §17).
 *
 * The gate freezes three contracts — Command Protocol, Runtime Semantics,
 * Plugin Contract — and §17.1 demands two executable guarantees this suite
 * pins:
 *
 *  1. **The error-code table has no unassigned or ambiguous codes, and every
 *     code has at least one test asserting it** — the enum must equal the set
 *     the specs name (02 §10.3 + 01 §15.1), the retryable set must equal the
 *     documented one, and every constant must be registered in
 *     [PINNED_BY_TEST] against the test that pins it (or be an explicitly
 *     reserved constant with its rationale in the enum KDoc). Adding a
 *     constant without registering it fails this suite — the executable form
 *     of 02 §14's "no silent evolution path" applied to the error vocabulary.
 *  2. **Protocol versioning is the mechanism for change** — a future
 *     `dslVersion` header must be rejected, never silently accepted
 *     (02 §14; golden fixture S4).
 */
class KernelConformanceSuite : ConformanceSuite {
    override val id = "kernel"
    override val title = "Kernel 1.0 stabilization-gate surfaces"
    override val spec = "10 §17.1 + 02 §10.3/§14 + 01 §15.1"

    override fun cases(): List<ConformanceCase> = listOf(
        errorCodeVocabularyCase(),
        errorCodeRetryableCase(),
        errorCodeTestCoverageCase(),
        futureDslVersionRejectedCase(),
    )

    // ─── Cases ───────────────────────────────────────────────────────────

    private fun errorCodeVocabularyCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-error-code-vocabulary"
        override val title = "McosErrorCode equals the spec-pinned 18-code vocabulary"
        override val spec = "02 §10.3 + 01 §15.1"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result {
            val actual = McosErrorCode.entries.map { it.name }.toSet()
            val missing = SPEC_CODES - actual
            val extra = actual - SPEC_CODES
            return when {
                missing.isNotEmpty() -> ConformanceCase.Result.Fail(
                    message = "codes named by the spec are missing from the enum: $missing",
                )
                extra.isNotEmpty() -> ConformanceCase.Result.Fail(
                    message = "enum constants the spec does not name — " +
                        "add them to 01 §15.1 or remove them: $extra",
                )
                else -> ConformanceCase.Result.Pass
            }
        }
    }

    private fun errorCodeRetryableCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-error-code-retryable"
        override val title = "retryable flags match the documented set"
        override val spec = "02 §10.3 + McosErrorCode KDoc"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result {
            val actual = McosErrorCode.entries.filter { it.retryable }.map { it.name }.toSet()
            return if (actual == RETRYABLE_CODES) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "retryable set drifted: actual=$actual expected=$RETRYABLE_CODES — " +
                        "a retryable flag is protocol semantics; changing it is a spec revision",
                )
            }
        }
    }

    private fun errorCodeTestCoverageCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-error-code-test-coverage"
        override val title = "every error code is pinned by a test or explicitly reserved"
        override val spec = "10 §17.1"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result {
            val unregistered = McosErrorCode.entries.filter { it !in PINNED_BY_TEST }
            return if (unregistered.isEmpty()) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "error codes with no pinning test and no reserved-note registration: " +
                        unregistered.map { it.name } +
                        " — write the test, then register it in KernelConformanceSuite.PINNED_BY_TEST",
                )
            }
        }
    }

    private fun futureDslVersionRejectedCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-future-dsl-version-rejected"
        override val title = "a future dslVersion header is rejected, never silently accepted"
        override val spec = "02 §14 + §16 S4"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result {
            val parsed = DslParser.parse("# mcos-dsl: 0.2\nhello.world()")
            val err = parsed as? ParseResult.Err
                ?: return ConformanceCase.Result.Fail(
                    message = "a future dslVersion must be rejected, got $parsed",
                )
            return if (err.code == "PARSE_ERROR") {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "version rejection must surface PARSE_ERROR, got '${err.code}'",
                )
            }
        }
    }

    companion object {
        /** The 18 codes the specs name: 02 §10.3 plus the 01 §15.1 addendum. */
        val SPEC_CODES: Set<String> = setOf(
            // 02 §10.3 — command-level subset
            "PARSE_ERROR", "UNKNOWN_COMMAND", "SCHEMA_VIOLATION", "PERMISSION_DENIED",
            "CONFIRMATION_REQUIRED", "TIMEOUT", "CANCELLED", "PLUGIN_ERROR",
            "UNAVAILABLE", "RATE_LIMITED", "INTERNAL", "CONFLICT",
            // 01 §15.1 — compile + workflow addendum
            "COMPILE_FAILED", "WORKFLOW_INVALID", "MAX_ITERATIONS_EXCEEDED",
            "COMPENSATION_FAILED", "JOIN_FAILED", "TRIGGER_MISFIRE",
        )

        /** The documented retryable set — every other code is non-retryable. */
        val RETRYABLE_CODES: Set<String> = setOf(
            "TIMEOUT", "UNAVAILABLE", "RATE_LIMITED", "TRIGGER_MISFIRE",
        )

        /**
         * Executable form of 10 §17.1 "every code has at least one test
         * asserting it". Reserved constants (no production path) register
         * their enum-KDoc rationale instead of a test.
         */
        val PINNED_BY_TEST: Map<McosErrorCode, String> = mapOf(
            McosErrorCode.PARSE_ERROR to "DslParserTest + golden fixtures (02 §16)",
            McosErrorCode.COMPILE_FAILED to "AgentLoopTest A22",
            McosErrorCode.UNKNOWN_COMMAND to "WorkflowEngineTest W2",
            McosErrorCode.SCHEMA_VIOLATION to "WorkflowInputTest",
            McosErrorCode.PERMISSION_DENIED to "IsolatedHostServicesProxyTest",
            McosErrorCode.CONFIRMATION_REQUIRED to "ConfirmationCoordinatorTest",
            McosErrorCode.TIMEOUT to "ExecutorRuntimeConfigTest",
            McosErrorCode.CANCELLED to "WorkflowEngineTest (cancellation)",
            McosErrorCode.PLUGIN_ERROR to "IsolatedPluginRunnerTest",
            McosErrorCode.UNAVAILABLE to "IsolatedHostServicesProxyTest",
            McosErrorCode.RATE_LIMITED to "RunSchedulerTest",
            McosErrorCode.CONFLICT to "DeviceMutexMapTest DM4/DM8",
            McosErrorCode.INTERNAL to "AuditFailClosedWiringTest",
            McosErrorCode.WORKFLOW_INVALID to "WorkflowEngineTest W30",
            McosErrorCode.MAX_ITERATIONS_EXCEEDED to "WorkflowEngineTest (loop cap)",
            McosErrorCode.COMPENSATION_FAILED to "WorkflowEngineTest W31",
            McosErrorCode.JOIN_FAILED to "RESERVED — enum KDoc (no production path)",
            McosErrorCode.TRIGGER_MISFIRE to "RESERVED — enum KDoc (no production path)",
        )
    }
}
