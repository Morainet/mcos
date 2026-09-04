package com.morainet.mcos.conformance.reporters

import com.morainet.mcos.conformance.api.ConformanceReport

/**
 * JSON reporter. Emits the verbatim [ConformanceReport] schema; downstream
 * tools (CI dashboards, baseline diffs) consume this directly.
 *
 * The marketplace CI mirrors this same JSON shape on its side (spec 09
 * §5.1) — the on-disk contract here is the wire contract there.
 */
object JsonReporter {
    fun render(report: ConformanceReport): String =
        ConformanceReport.encodePretty(report)
}