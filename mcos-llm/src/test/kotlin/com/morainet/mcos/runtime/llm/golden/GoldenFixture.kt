package com.morainet.mcos.runtime.llm.golden

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single golden NL→IR fixture (06-agent §16.0), loaded from
 * `docs/fixtures/planner/golden-*.json`.
 *
 * The fixture is the regression baseline for the planner pipeline: given
 * [utterance] plus the fixture's registry/memory, the compiler must produce a
 * plan whose *structure* (command ids, step order, arg keys) matches
 * [expectedIr]. `$ref:...` marker values in [ExpectedCommand.args] mean the
 * arg is bound from the user statement or memory, so only the key and a
 * non-empty value are asserted (06-agent §16.0: assert structure, not exact
 * arg values).
 *
 * [llmReply] is the stub model's raw output that drives the regression path:
 * an IR JSON object when [mode] is `constrained`, DSL text when `freeform`.
 * A real evaluation run replaces the stub with a live provider and compares
 * against [expectedIr] instead.
 */
@Serializable
data class GoldenFixture(
    /** Stable unique id, e.g. `golden-001-invoke-camera`. */
    val id: String,

    /** The natural-language user utterance. */
    val utterance: String,

    /** Expected compiler verdict: invoke | sequence | clarify | refuse. */
    val expectedType: String,

    /**
     * Planning mode the stub provider advertises, which selects the compile
     * path: `constrained` (grammar-constrained IR JSON, 06 §3.2 V2) or
     * `freeform` (DSL, 06 §3.2).
     */
    val mode: String = "freeform",

    /** Stub model reply used by the regression path (IR JSON or DSL). */
    val llmReply: String,

    /** Expected compiled structure (command ids + arg keys). */
    val expectedIr: ExpectedIr = ExpectedIr(emptyList()),

    /** Command ids registered before planning (system prompt contents). */
    val registryFixture: List<String> = emptyList(),

    /** Memory facts (path → value) injected into the store before planning. */
    val memoryFixture: Map<String, String> = emptyMap(),

    /** Human-readable notes describing the scenario. */
    val notes: String = "",
) {
    val isClarifyOrRefuse: Boolean
        get() = expectedType == TYPE_CLARIFY || expectedType == TYPE_REFUSE

    companion object {
        const val TYPE_INVOKE = "invoke"
        const val TYPE_SEQUENCE = "sequence"
        const val TYPE_CLARIFY = "clarify"
        const val TYPE_REFUSE = "refuse"
        const val MODE_CONSTRAINED = "constrained"
        const val MODE_FREEFORM = "freeform"

        val VALID_TYPES = setOf(TYPE_INVOKE, TYPE_SEQUENCE, TYPE_CLARIFY, TYPE_REFUSE)
        val VALID_MODES = setOf(MODE_CONSTRAINED, MODE_FREEFORM)

        /** Marker prefix for `\$ref:...` bound args in expectedIr. */
        const val REF_MARKER = "\$ref"
    }
}

/** Expected compiled structure: an ordered list of commands. */
@Serializable
data class ExpectedIr(val commands: List<ExpectedCommand> = emptyList())

/** One expected command in the compiled plan. */
@Serializable
data class ExpectedCommand(
    val id: String,
    val args: Map<String, JsonElement> = emptyMap(),
)
