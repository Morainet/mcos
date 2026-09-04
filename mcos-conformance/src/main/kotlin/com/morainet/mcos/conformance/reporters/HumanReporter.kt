package com.morainet.mcos.conformance.reporters

import com.morainet.mcos.conformance.api.ConformanceReport

/**
 * Human-readable report — what a plugin author sees when they run the
 * CLI locally without `--output`. Format:
 *
 * ```text
 * MCOS Conformance — 0.1.0 (spec 10 §6.4 / 09 §5.1)
 * 2026-09-03T10:00:00Z · 1234 ms
 *
 * ▸ DSL (spec 02 §16 + 09 §5.1 gates 1/2/3)
 *   ✓ dsl-positive-01-empty-args · "header + empty args"            (12 ms)
 *   ✗ dsl-negative-06-nested-call · "nested invocation rejected"    (3 ms)
 *       PARSE_ERROR: nested invocation in DSL v0.1
 *
 * ── Summary ──
 * 8 total · 7 passed · 1 failed · 0 skipped
 * FAIL
 * ```
 *
 * Output goes to a [Appendable] so the CLI can route it to stdout or a
 * file as needed.
 */
object HumanReporter {

    fun render(report: ConformanceReport, out: Appendable) {
        out.appendLine("MCOS Conformance — ${report.version} (${report.spec})")
        out.appendLine("${report.startedAt} · ${report.durationMs} ms")
        out.appendLine()
        for (suite in report.suites) {
            out.appendLine("▸ ${suite.title} (${suite.spec})")
            for (case in suite.cases) {
                val marker = when (case.status) {
                    "pass" -> "✓"
                    "fail" -> "✗"
                    "skip" -> "○"
                    else -> "?"
                }
                out.appendLine(
                    "  $marker ${case.id} · \"${case.title}\"" +
                        " (${case.durationMs} ms)",
                )
                if (case.status == "fail") {
                    val firstLine = case.message?.lineSequence()?.firstOrNull() ?: "failed"
                    out.appendLine("      $firstLine")
                    if (!case.detail.isNullOrBlank()) {
                        case.detail.lineSequence().forEach { line ->
                            out.appendLine("        $line")
                        }
                    }
                } else if (case.status == "skip") {
                    out.appendLine("      skipped: ${case.message ?: ""}")
                }
            }
            out.appendLine()
        }
        val s = report.summary
        out.appendLine(
            "── Summary ──\n" +
                "${s.total} total · ${s.passed} passed · ${s.failed} failed · ${s.skipped} skipped",
        )
        out.appendLine(if (s.isPass) "PASS" else "FAIL")
    }

    fun renderToString(report: ConformanceReport): String {
        val sb = StringBuilder()
        render(report, sb)
        return sb.toString()
    }
}