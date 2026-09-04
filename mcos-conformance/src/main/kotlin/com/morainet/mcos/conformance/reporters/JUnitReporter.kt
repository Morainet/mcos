package com.morainet.mcos.conformance.reporters

import com.morainet.mcos.conformance.api.ConformanceReport

/**
 * JUnit XML reporter — the lingua franca of CI test reports. GitLab CI,
 * GitHub Actions, Jenkins, Bitrise, Buildkite all consume this format, so
 * shipping it lets a plugin author point their existing CI at the
 * conformance artifact with no glue.
 *
 * One `<testsuite>` per [ConformanceReport.SuiteReport]; one `<testcase>`
 * per [ConformanceReport.CaseReport]. Failures attach a `<failure>` child
 * with the case's `message` + `detail`. Skips attach `<skipped />`.
 *
 * The XML escapes user-supplied strings — fixture file names can carry
 * characters that would otherwise corrupt the report.
 */
object JUnitReporter {
    fun render(report: ConformanceReport): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.appendLine(
            "<testsuites " +
                "name=\"mcos-conformance\" " +
                "tests=\"${report.summary.total}\" " +
                "failures=\"${report.summary.failed}\" " +
                "skipped=\"${report.summary.skipped}\" " +
                "time=\"${(report.durationMs / 1000.0)}\" " +
                "timestamp=\"${escape(report.startedAt)}\">",
        )
        for (suite in report.suites) {
            sb.appendLine(
                "  <testsuite " +
                    "name=\"${escape(suite.title)}\" " +
                    "tests=\"${suite.total}\" " +
                    "failures=\"${suite.failed}\" " +
                    "skipped=\"${suite.skipped}\" " +
                    "time=\"${(suite.cases.sumOf { it.durationMs } / 1000.0)}\" " +
                    "id=\"${escape(suite.id)}\" " +
                    "spec=\"${escape(suite.spec)}\">",
            )
            for (case in suite.cases) {
                val className = "conformance.${suite.id}"
                val testName = "${case.id} ${case.title}"
                sb.appendLine(
                    "    <testcase " +
                        "name=\"${escape(testName)}\" " +
                        "classname=\"${escape(className)}\" " +
                        "time=\"${(case.durationMs / 1000.0)}\" " +
                        "category=\"${escape(case.category)}\" " +
                        "spec=\"${escape(case.spec)}\">",
                )
                when (case.status) {
                    "fail" -> {
                        sb.appendLine("      <failure message=\"${escape(case.message ?: "failed")}\" type=\"conformance-fail\">")
                        if (case.detail != null) {
                            sb.appendLine(escape(case.detail))
                        }
                        sb.appendLine("      </failure>")
                    }
                    "skip" -> {
                        sb.appendLine("      <skipped message=\"${escape(case.message ?: "skipped")}\" />")
                    }
                    "pass" -> {
                        // no child element for passes
                    }
                }
                sb.appendLine("    </testcase>")
            }
            sb.appendLine("  </testsuite>")
        }
        sb.appendLine("</testsuites>")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}