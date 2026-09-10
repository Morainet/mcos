package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.ResolveResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * DeviceSemantics — extracts device ids from command args via the
 * `x-mcos-semantic: "device"` schema extension (02 §5.3 vendor-extension
 * table; 04 §4.5's canonical `deviceId` field pairs `"x-mcos-ref": true`
 * with the semantic).
 *
 * This is the args-driven half of 03 §8.5's "resolved from args by the
 * Runtime at Stage 4 Expand, via the device-id field": a workflow step
 * declares `requiresDevices` literally (05 §5.0), and the Runtime
 * additionally derives device mutex keys from any arg whose input-schema
 * property is marked device-semantic.
 *
 * Honest boundary: [deviceIds] takes values **literally** — it is the pure
 * schema-driven extraction and stays testable without a Memory.
 * [resolveDeviceIds] layers the canonicalization on top, resolving a
 * natural-language value to its canonical Memory id so that two spellings of
 * the same device serialize against ONE mutex key.
 */
object DeviceSemantics {

    /** The schema vendor extension key (02 §5.3). */
    const val EXTENSION_KEY = "x-mcos-semantic"

    /** The extension value marking a property as a device id (04 §4.5). */
    const val VALUE_DEVICE = "device"

    /**
     * Device ids among [args], as declared device-semantic by [inputSchema]:
     * every `properties` entry carrying `x-mcos-semantic: "device"` whose
     * matching arg is a non-blank string contributes that value. Properties
     * with other semantics, missing args, and non-string values are ignored.
     */
    fun deviceIds(inputSchema: JsonObject, args: JsonObject): List<String> {
        val properties = inputSchema["properties"] as? JsonObject ?: return emptyList()
        val ids = mutableListOf<String>()
        for ((key, propSchema) in properties) {
            val semantic = ((propSchema as? JsonObject)?.get(EXTENSION_KEY) as? JsonPrimitive)
                ?.contentOrNull
            if (semantic != VALUE_DEVICE) continue
            // Only string primitives are device ids — numbers/booleans/nulls
            // never serialize a device.
            val value = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: continue
            if (value.isBlank()) continue
            ids.add(value)
        }
        return ids
    }

    /**
     * [deviceIds] with the 03 §8.5 canonicalization applied: every extracted
     * value is resolved through [memory] (`resolveRef(value, "device")`) to
     * its canonical Memory id.
     *
     * This is what makes a natural-language alias and the canonical id
     * serialize against the *same* mutex. Without it, two concurrent runs
     * naming the same device differently would not contend at all — precisely
     * the cross-run hazard §8.5 exists to prevent.
     *
     * Honest boundary: canonicalization is **best-effort**. A ref that does
     * not resolve ([ResolveResult.NotFound]) or resolves ambiguously
     * ([ResolveResult.Ambiguous]) keeps its literal spelling, i.e. the
     * pre-canonicalization behaviour. A run is therefore never failed because
     * Memory could not resolve a name — but an unresolvable alias still gets
     * its own key and so serializes against itself, not against the canonical
     * id. A resolver that *throws* is treated the same way: Memory is an
     * enhancement here, not a hard dependency of the mutex key.
     */
    suspend fun resolveDeviceIds(
        inputSchema: JsonObject,
        args: JsonObject,
        memory: MemoryFacade,
    ): List<String> {
        val literal = deviceIds(inputSchema, args)
        if (literal.isEmpty()) return literal
        return literal.map { canonicalize(it, memory) }.distinct()
    }

    /** Canonical id for [value], or [value] itself whenever Memory cannot supply one. */
    private suspend fun canonicalize(value: String, memory: MemoryFacade): String =
        try {
            when (val result = memory.resolveRef(value, VALUE_DEVICE)) {
                is ResolveResult.Resolved -> result.id.takeIf { it.isNotBlank() } ?: value
                // Ambiguous / NotFound — keep the literal spelling (see KDoc).
                else -> value
            }
        } catch (_: Exception) {
            value
        }
}
