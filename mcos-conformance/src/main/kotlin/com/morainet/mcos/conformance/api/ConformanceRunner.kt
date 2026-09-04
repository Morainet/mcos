package com.morainet.mcos.conformance.api

import java.time.Instant

/**
 * Orchestrates a conformance run: discovers suites, executes cases
 * sequentially, and aggregates a [ConformanceReport].
 *
 * Sequential by design (one thread, deterministic ordering): the suites
 * only touch isolated state they construct locally, and the report's
 * wall-clock is meaningful for performance regressions. Parallelism
 * belongs inside a case (e.g. cross-key signature loops), not between
 * cases.
 */
/**
 * Orchestrates a conformance run: discovers suites, executes cases
 * sequentially, and aggregates a [ConformanceReport].
 *
 * Sequential by design (one thread, deterministic ordering): the suites
 * only touch isolated state they construct locally, and the report's
 * wall-clock is meaningful for performance regressions. Parallelism
 * belongs inside a case (e.g. cross-key signature loops), not between
 * cases.
 */
class ConformanceRunner(
    private val suites: List<ConformanceSuite>,
    private val clock: () -> Instant = Instant::now,
    private val timeSource: () -> Long = System::currentTimeMillis,
) {

    /**
     * Execute every case in [suites] (or the named subset, in declaration
     * order) and return a [ConformanceReport].
     *
     * @param onlySuiteIds if non-null, run only the suites with these ids;
     *                     unknown ids are ignored (no error — `list` is the
     *                     discovery command).
     */
    fun run(onlySuiteIds: Set<String>? = null): ConformanceReport {
        val startedAt = clock().toString()
        val startMs = timeSource()
        val selected = if (onlySuiteIds == null) {
            suites
        } else {
            suites.filter { it.id in onlySuiteIds }
        }
        val suiteReports = selected.map { suite ->
            val caseReports = suite.cases().map { case ->
                val caseStart = timeSource()
                val status = try {
                    case.run()
                } catch (t: Throwable) {
                    // A case that throws is treated as a Fail — the runner
                    // never crashes the whole run on a single-case bug.
                    ConformanceCase.Result.Fail(
                        message = "case threw ${t::class.simpleName}: ${t.message ?: "<no message>"}",
                        detail = t.stackTraceToString().take(2_000),
                    )
                }
                val caseEnd = timeSource()
                val (statusName, message, detail) = when (status) {
                    ConformanceCase.Result.Pass -> Triple("pass", null, null)
                    is ConformanceCase.Result.Fail -> Triple("fail", status.message, status.detail)
                    is ConformanceCase.Result.Skip -> Triple("skip", status.reason, null)
                }
                ConformanceReport.CaseReport(
                    id = case.id,
                    title = case.title,
                    category = case.category,
                    spec = case.spec,
                    status = statusName,
                    durationMs = caseEnd - caseStart,
                    message = message,
                    detail = detail,
                )
            }
            ConformanceReport.SuiteReport(
                id = suite.id,
                title = suite.title,
                spec = suite.spec,
                cases = caseReports,
            )
        }
        val endMs = timeSource()
        val allCases = suiteReports.flatMap { it.cases }
        val summary = ConformanceReport.Summary(
            total = allCases.size,
            passed = allCases.count { it.status == "pass" },
            failed = allCases.count { it.status == "fail" },
            skipped = allCases.count { it.status == "skip" },
        )
        return ConformanceReport(
            startedAt = startedAt,
            durationMs = endMs - startMs,
            summary = summary,
            suites = suiteReports,
        )
    }

    /**
     * Translate a [ConformanceReport] into a process exit code. Centralised
     * here so the CLI never needs to inspect the report shape.
     *
     * 0 = pass · 1 = at least one case failed · 2 = baseline mismatch
     * (only set by the CLI's `baseline-check` subcommand, not by this method) ·
     * 3 = configuration / IO error (CLI surface only).
     */
    fun exitCode(report: ConformanceReport): Int =
        if (report.summary.isPass) 0 else 1
}