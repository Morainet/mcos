package com.morainet.mcos.conformance.api

import kotlinx.serialization.Serializable

/**
 * A single conformance case — the smallest unit the runner reports on.
 *
 * Each case has a stable, machine-readable [id] (so CI can diff over time
 * and baselines survive renames of the human-readable [title]) and a [spec]
 * pointer so a "why does this matter" link lives in the report itself.
 *
 * Cases are produced by [com.morainet.mcos.conformance.api.ConformanceSuite]
 * factories; the runner is the single point that invokes [run] and the
 * single point that maps [Result] to exit codes.
 *
 * @property id machine-readable id, e.g. `"dsl-positive-01-empty-args"`.
 * @property title one-line human description.
 * @property spec pointer to the spec section the case pins (e.g. "02 §16",
 *              "09 §5.1 gate 1"). Embedded in the report so reviewers can
 *              audit the source of truth without leaving the report.
 * @property category one of `"dsl-positive"`, `"dsl-negative"`,
 *                    `"manifest"`, `"trust"`, `"ir"`. Drives the report's
 *                    section grouping.
 */
interface ConformanceCase {
    val id: String
    val title: String
    val spec: String
    val category: String

    /**
     * Execute the case synchronously. The runner invokes each case from a
     * single thread (sequential by design — see [com.morainet.mcos.conformance.api.ConformanceRunner]).
     *
     * Implementations MUST NOT mutate shared state outside of fixtures they
     * construct locally — the runner is not concurrency-safe.
     */
    fun run(): Result

    /**
     * Outcome of a case.
     */
    sealed class Result {
        /** Case produced the expected outcome. */
        data object Pass : Result()

        /** Case produced an outcome other than the expected one. */
        data class Fail(
            val message: String,
            /**
             * Optional structured detail (JSON string) — typically a
             * pretty-printed expected-vs-actual diff. Embedded verbatim in
             * the report.
             */
            val detail: String? = null,
        ) : Result()

        /** Case could not be evaluated (e.g. fixture file missing). */
        data class Skip(val reason: String) : Result()
    }
}