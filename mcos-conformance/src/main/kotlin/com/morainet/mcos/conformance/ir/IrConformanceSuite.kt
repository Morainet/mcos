package com.morainet.mcos.conformance.ir

import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.conformance.api.ConformanceSuite
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.ir.ParseResult
import com.morainet.mcos.runtime.core.parse.DslParser
import kotlinx.serialization.json.JsonObject

/**
 * IR shape conformance (spec 02 §7 + 03 §5.1).
 *
 * Structural invariants on the Execution IR, asserted through the real
 * production parser ([DslParser]) so the suite stays attached to the
 * wire contract rather than hand-built fixtures:
 *
 *  - a bare invoke parses to [ExecutionIr.Invoke] whose `IrInvoke.type`
 *    is the literal `"invoke"` and whose `dslVersion` defaults to `"0.1"`;
 *  - a sequence parses to [ExecutionIr.Sequence] whose `type` is
 *    `"sequence"` and whose `steps` is never empty for ≥ 1 step input;
 *  - `args` object keys sort lexicographically in the parsed IR
 *    (canonicalisation, 02 §7.4) regardless of DSL declaration order;
 *  - parse errors surface with a protocol-defined code (02 §18) and a
 *    1-based location.
 *
 * These are the runtime-side invariants every release must keep green —
 * the DSL golden fixtures (docs/fixtures) exercise the happy-path
 * text, this suite pins the *shape* the runtime hands to the executor.
 */
class IrConformanceSuite : ConformanceSuite {
    override val id = "ir"
    override val title = "Execution IR structural invariants"
    override val spec = "02 §7 + 03 §5.1"

    override fun cases(): List<ConformanceCase> = listOf(
        irInvokeDefaultsCase(),
        irSequenceShapeCase(),
        irArgsKeySortCase(),
        irEmptyArgsIsObjectCase(),
        irParseErrorKnownCodeCase(),
        irParseErrorLocationCase(),
    )

    // ─── Cases ───────────────────────────────────────────────────────────

    private fun irInvokeDefaultsCase(): ConformanceCase = object : ConformanceCase {
        override val id = "ir-invoke-defaults"
        override val title = "bare invoke parses with type='invoke', dslVersion='0.1'"
        override val spec = "02 §7.1 + 03 §5.1"
        override val category = "ir"

        override fun run(): ConformanceCase.Result {
            // The parser consumes DSL TEXT (02 §6), not IR JSON — the IR is
            // what it EMITS. Bare invocation, no args.
            val parsed = DslParser.parse("demo.x()")
            val invoke = (parsed as? ParseResult.Ok)?.ir as? ExecutionIr.Invoke
            return when {
                invoke == null -> ConformanceCase.Result.Fail(
                    message = "expected Invoke IR, got $parsed",
                )
                invoke.invoke.type != "invoke" -> ConformanceCase.Result.Fail(
                    message = "type must default to 'invoke', got '${invoke.invoke.type}'",
                )
                invoke.invoke.dslVersion != "0.1" -> ConformanceCase.Result.Fail(
                    message = "dslVersion must default to '0.1', got '${invoke.invoke.dslVersion}'",
                )
                else -> ConformanceCase.Result.Pass
            }
        }
    }

    private fun irSequenceShapeCase(): ConformanceCase = object : ConformanceCase {
        override val id = "ir-sequence-shape"
        override val title = "two-step input parses to Sequence{type='sequence', steps=2}"
        override val spec = "02 §7.2 + 03 §5.1"
        override val category = "ir"

        override fun run(): ConformanceCase.Result {
            // Two statements on separate lines → one sequence of two invokes.
            val parsed = DslParser.parse("a.a()\nb.b()")
            val seq = (parsed as? ParseResult.Ok)?.ir as? ExecutionIr.Sequence
            return when {
                seq == null -> ConformanceCase.Result.Fail(
                    message = "expected Sequence IR, got $parsed",
                )
                seq.sequence.type != "sequence" -> ConformanceCase.Result.Fail(
                    message = "type must be 'sequence', got '${seq.sequence.type}'",
                )
                seq.sequence.steps.size != 2 -> ConformanceCase.Result.Fail(
                    message = "expected 2 steps, got ${seq.sequence.steps.size}",
                )
                seq.sequence.steps.any { it.type != "invoke" } -> ConformanceCase.Result.Fail(
                    message = "sequence steps must be invokes, got ${seq.sequence.steps}",
                )
                else -> ConformanceCase.Result.Pass
            }
        }
    }

    private fun irArgsKeySortCase(): ConformanceCase = object : ConformanceCase {
        override val id = "ir-args-key-sort"
        override val title = "args keys canonicalise to lexicographic order (02 §7.4)"
        override val spec = "02 §7.4"
        override val category = "ir"

        override fun run(): ConformanceCase.Result {
            // Insertion order deliberately NOT sorted. Canonical form is
            // defined by the protocol; the parser must emit sorted keys.
            val dsl = """demo.x(zeta=1, alpha=2, mid="v")"""
            val parsed = DslParser.parse(dsl)
            val invoke = (parsed as? ParseResult.Ok)?.ir as? ExecutionIr.Invoke
                ?: return ConformanceCase.Result.Fail(message = "expected Invoke IR, got $parsed")
            val keys = invoke.invoke.args.keys.toList()
            return if (keys == keys.sorted()) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "args keys not canonical: $keys",
                )
            }
        }
    }

    private fun irEmptyArgsIsObjectCase(): ConformanceCase = object : ConformanceCase {
        override val id = "ir-empty-args-object"
        override val title = "empty args defaults to {} (object, not null)"
        override val spec = "02 §7.1"
        override val category = "ir"

        override fun run(): ConformanceCase.Result {
            val parsed = DslParser.parse("demo.x()")
            val invoke = (parsed as? ParseResult.Ok)?.ir as? ExecutionIr.Invoke
                ?: return ConformanceCase.Result.Fail(message = "expected Invoke IR, got $parsed")
            return when {
                invoke.invoke.args == JsonObject(emptyMap()) -> ConformanceCase.Result.Pass
                else -> ConformanceCase.Result.Fail(
                    message = "empty args must round-trip as {}, got ${invoke.invoke.args}",
                )
            }
        }
    }

    private fun irParseErrorKnownCodeCase(): ConformanceCase = object : ConformanceCase {
        override val id = "ir-parse-error-known-code"
        override val title = "malformed DSL → Err with protocol-defined code + location"
        override val spec = "03 §5.1 + 02 §18"
        override val category = "ir"

        override fun run(): ConformanceCase.Result {
            val parsed = DslParser.parse("{not valid dsl")
            val err = parsed as? ParseResult.Err
                ?: return ConformanceCase.Result.Fail(message = "expected Err, got $parsed")
            return when {
                err.code !in KNOWN_ERROR_CODES -> ConformanceCase.Result.Fail(
                    message = "error code '${err.code}' not in protocol vocabulary $KNOWN_ERROR_CODES",
                )
                err.line < 1 || err.column < 1 -> ConformanceCase.Result.Fail(
                    message = "location must be 1-based: line=${err.line} column=${err.column}",
                )
                else -> ConformanceCase.Result.Pass
            }
        }
    }

    private fun irParseErrorLocationCase(): ConformanceCase = object : ConformanceCase {
        override val id = "ir-parse-error-location-one-based"
        override val title = "schema violation Err reports 1-based line/column"
        override val spec = "02 §18"
        override val category = "ir"

        override fun run(): ConformanceCase.Result {
            val parsed = DslParser.parse("""{"type":"invoke"}""") // missing required id
            val err = parsed as? ParseResult.Err
                ?: return ConformanceCase.Result.Fail(message = "expected Err, got $parsed")
            return if (err.line >= 1 && err.column >= 1) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "location must be 1-based: line=${err.line} column=${err.column}",
                )
            }
        }
    }

    companion object {
        /**
         * Protocol-defined error codes (02 §18, 03 §5.1, 08 §6/§12).
         * The marketplace CI surfaces the same codes to end users; this
         * vocabulary set is pinned here so an accidental rename is caught
         * by the suite before it reaches a published plugin.
         */
        val KNOWN_ERROR_CODES: Set<String> = setOf(
            "PARSE_ERROR",
            "SCHEMA_VIOLATION",
            "WORKFLOW_INVALID",
            "RATE_LIMITED",
            "UNAVAILABLE",
            "INTERNAL",
            "CONFLICT",
            "CONFIRMATION_REQUIRED",
            "CANCELLED",
        )
    }
}