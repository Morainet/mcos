package com.morainet.mcos.conformance.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Aggregated report of one conformance run.
 *
 * Serialised verbatim by both the JSON and JUnit reporters; the wire shape
 * is the contract the marketplace CI mirrors (spec 09 §5.1). Adding a
 * field here is a wire change — preserve backward compatibility by
 * appending only (never rename existing fields, never change types).
 */
@Serializable
data class ConformanceReport(
    /** Schema version of this report; bump when adding/removing fields. */
    val version: String = "0.1.0",
    /** ISO-8601 instant the run started. */
    val startedAt: String,
    /** Total wall clock duration in milliseconds (cases run sequentially). */
    val durationMs: Long,
    /** Spec pointer summarising which set of gates this run covers. */
    val spec: String = "10 §6.4 / 09 §5.1",
    val summary: Summary,
    val suites: List<SuiteReport>,
) {
    @Serializable
    data class Summary(
        val total: Int,
        val passed: Int,
        val failed: Int,
        val skipped: Int,
    ) {
        val isPass: Boolean get() = failed == 0
    }

    @Serializable
    data class SuiteReport(
        val id: String,
        val title: String,
        val spec: String,
        val cases: List<CaseReport>,
    ) {
        val passed: Int get() = cases.count { it.status == "pass" }
        val failed: Int get() = cases.count { it.status == "fail" }
        val skipped: Int get() = cases.count { it.status == "skip" }
        val total: Int get() = cases.size
    }

    @Serializable
    data class CaseReport(
        val id: String,
        val title: String,
        val category: String,
        val spec: String,
        val status: String,
        val durationMs: Long,
        val message: String? = null,
        val detail: String? = null,
    )

    fun toJsonElement() = Json.encodeToJsonElement(this)

    companion object {
        val PRETTY_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        fun encodePretty(report: ConformanceReport): String =
            PRETTY_JSON.encodeToString(kotlinx.serialization.serializer(), report)
    }
}