package com.morainet.mcos.conformance.api

/**
 * A named group of [ConformanceCase]s, typically a single sub-system or
 * single CI gate from spec 09 §5.1. The runner discovers suites via the
 * central [com.morainet.mcos.conformance.suites] registry — adding a new
 * suite there is the only step needed to light it up.
 *
 * Suite names are stable identifiers; `mcos-conformance run --suite <name>`
 * and `mcos-conformance list` both key on them. Renames must ship a
 * baseline-migration note (the baseline JSON carries the suite name per
 * case).
 *
 * @property id stable machine-readable id (e.g. `"dsl"`, `"manifest"`,
 *              `"trust"`, `"ir"`). Lowercase kebab/short — appears in CLI
 *              flags and in the report.
 * @property title human-readable label shown in the report's section
 *               headings.
 * @property spec pointer to the spec section the suite covers as a whole
 *              (e.g. "02 §16 + 09 §5.1").
 */
interface ConformanceSuite {
    val id: String
    val title: String
    val spec: String
    fun cases(): List<ConformanceCase>
}