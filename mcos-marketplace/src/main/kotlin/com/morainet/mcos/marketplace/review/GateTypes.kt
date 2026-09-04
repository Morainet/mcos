package com.morainet.mcos.marketplace.review

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CI gate report model (spec 09 §5.1 / §5.4, 04 §13.2).
 *
 * Shared between the index server (review pipeline response), the conformance
 * "market" suite (author-side verdicts) and the client tooling. The JSON shape
 * is the canonical one — see 09-marketplace.md §5.4:
 *
 * ```json
 * { "overall": "CI_REJECTED",
 *   "checks": [{ "gate": 4, "rule": "sideEffectClass honesty",
 *                "status": "warning", "severity": "warning",
 *                "message": "…", "location": { "commandId": "iot.ac.set" } }] }
 * ```
 */
@Serializable
data class GateCheck(
    /** CI gate number per 09 §5.1 (1–11). */
    val gate: Int,
    /** Human-readable rule name, e.g. "sideEffectClass honesty". */
    val rule: String,
    /** "pass" | "warning" | "fail". */
    val status: String,
    /** "error" | "warning" | "none" (none for pass entries). */
    val severity: String,
    /** Author-facing explanation. */
    val message: String,
    /** Machine location: { "commandId": … } or { "field": …, "line": … }. */
    val location: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun pass(gate: Int, rule: String): GateCheck =
            GateCheck(gate, rule, "pass", "none", "OK", buildJsonObject {})

        fun warning(gate: Int, rule: String, message: String, commandId: String): GateCheck =
            GateCheck(gate, rule, "warning", "warning", message, locationOf(commandId))

        fun fail(gate: Int, rule: String, message: String, commandId: String): GateCheck =
            GateCheck(gate, rule, "fail", "error", message, locationOf(commandId))

        private fun locationOf(commandId: String): JsonObject =
            buildJsonObject { put("commandId", commandId) }
    }
}

/**
 * Overall submission verdict derived from [CiReviewEngine.evaluate]:
 *  - [ReviewOverall.CI_REJECTED] — at least one `severity: "error"` check,
 *  - [ReviewOverall.HUMAN_REVIEW] — only warnings (09 §5.2 escalation),
 *  - [ReviewOverall.APPROVED] — every evaluated gate green.
 */
@Serializable
enum class ReviewOverall {
    APPROVED,
    HUMAN_REVIEW,
    CI_REJECTED,
}

@Serializable
data class CiReviewReport(
    val overall: ReviewOverall,
    val checks: List<GateCheck>,
) {
    val rejected: Boolean get() = overall == ReviewOverall.CI_REJECTED
    val needsHumanReview: Boolean get() = overall == ReviewOverall.HUMAN_REVIEW
}

/**
 * Outcome of the external malware-scanning stage (gate 9).
 *
 * The engine never invents a scan result: an operator with no AV engine wired
 * passes [Unscanned] so gate 9 reports a warning and the submission routes to
 * human review instead of silently passing unscanned bytes.
 */
@Serializable
enum class AvVerdict {
    CLEAN,
    MALICIOUS,
    UNSCANNED,
}

@Serializable
data class ArtifactScan(
    val verdict: AvVerdict,
    /** Engine/hash-list label shown in the gate-9 message. */
    val engineLabel: String = "no-engine",
) {
    companion object {
        val Clean = ArtifactScan(AvVerdict.CLEAN, "test-stub")
        val Unscanned = ArtifactScan(AvVerdict.UNSCANNED)
    }
}

/**
 * Facts about the previously published release of the same package, used by
 * gates 5 (SemVer coupling + monotonicity) and 11 (min-runtime monotonicity).
 */
@Serializable
data class PreviousRelease(
    val version: String,
    val minRuntimeVersion: String,
    /** command id → its version in the previous release. */
    val commandVersions: Map<String, String> = emptyMap(),
)

/**
 * Central approved-world snapshot a review runs against (gates 5/10/11).
 * The index server derives it from its registry; conformance fixtures provide
 * hand-built snapshots for local author validation.
 */
@Serializable
data class RegistrySnapshot(
    /** Previous release of the submitted package (update path). Null = first publish. */
    val previous: PreviousRelease? = null,
    /** Command ids already approved and visible in the marketplace (gate 10). */
    val knownCommandIds: Set<String> = emptySet(),
)
