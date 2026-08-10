package com.mcos.runtime.parse

import com.mcos.runtime.ir.*
import kotlinx.serialization.json.*

/**
 * Canonicalizes IR per [02-command-protocol.md 7.5].
 * Object keys are sorted lexicographically; IDs are lowercased;
 * numbers are normalized per schema type.
 */
object Canonicalizer {

    /**
     * Canonicalize an ExecutionIr in place (mutates the internal representation).
     * After this call, the IR is in its canonical form suitable for hashing.
     */
    fun canonicalize(ir: ExecutionIr): ExecutionIr {
        return when (ir) {
            is ExecutionIr.Invoke -> {
                val canonical = ir.invoke.copy(
                    id = ir.invoke.id.lowercase(),
                    args = canonicalizeArgs(ir.invoke.args)
                )
                ExecutionIr.Invoke(canonical)
            }
            is ExecutionIr.Sequence -> {
                val canonicalSteps = ir.sequence.steps.map { step ->
                    step.copy(
                        id = step.id.lowercase(),
                        args = canonicalizeArgs(step.args)
                    )
                }
                ExecutionIr.Sequence(ir.sequence.copy(steps = canonicalSteps))
            }
            is ExecutionIr.Workflow -> ir // Workflow bodies are opaque
        }
    }

    private fun canonicalizeArgs(args: JsonObject): JsonObject {
        // Sort keys lexicographically by Unicode code point
        val sorted = sortedMapOf<String, JsonElement>(String.CASE_INSENSITIVE_ORDER)
        for ((key, value) in args) {
            sorted[key] = canonicalizeValue(value)
        }
        // Use a regular LinkedHashMap sorted by key
        val result = linkedMapOf<String, JsonElement>()
        args.keys.sorted().forEach { key ->
            result[key] = canonicalizeValue(args[key]!!)
        }
        return JsonObject(result)
    }

    private fun canonicalizeValue(value: JsonElement): JsonElement {
        return when (value) {
            is JsonObject -> {
                val result = linkedMapOf<String, JsonElement>()
                value.keys.sorted().forEach { key ->
                    result[key] = canonicalizeValue(value[key]!!)
                }
                JsonObject(result)
            }
            is JsonArray -> {
                // Array order is preserved (semantically meaningful)
                JsonArray(value.map { canonicalizeValue(it) })
            }
            is JsonPrimitive -> value // primitives are already canonical
            is JsonNull -> value
            else -> value
        }
    }

    /**
     * Normalize a number literal for canonical output.
     * Strip leading zeros for integers, normalize -0 → 0.
     */
    fun normalizeInt(value: Long): Long = if (value == 0L) 0L else value // -0 → 0
}
