package com.mcos.runtime.parse

import com.mcos.runtime.ir.*
import kotlinx.serialization.json.*

/**
 * Canonicalizes IR per [02-command-protocol.md 7.5].
 *
 * Canonicalization rules:
 * - Command IDs are lowercased.
 * - Object keys (both `args` and nested objects) are sorted by Unicode code
 *   point (case-sensitive lexicographic), per §7.5.
 * - Array order is preserved (semantically meaningful).
 * - Primitives are already canonical.
 *
 * Note: number normalization per schema type (§7.5) requires schema access
 * which the canonicalizer does not have at this stage. Integer `-0` → `0`
 * is already handled by the parser's `toLong()` conversion. Full
 * schema-aware number normalization is deferred.
 */
object Canonicalizer {

    /**
     * Canonicalize an ExecutionIr.
     * Returns a new IR in canonical form suitable for hashing.
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

    /**
     * Sort arg keys lexicographically by Unicode code point (case-sensitive)
     * and recursively canonicalize values. Per spec §7.5.
     */
    private fun canonicalizeArgs(args: JsonObject): JsonObject {
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
}
