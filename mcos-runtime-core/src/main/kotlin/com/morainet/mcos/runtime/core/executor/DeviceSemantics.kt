package com.morainet.mcos.runtime.core.executor

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
 * Honest boundary (as-built): values are taken **literally** as mutex keys —
 * an `x-mcos-ref` natural-language value ("空调") serializes against the same
 * string, not against its canonicalized Memory id. Ref canonicalization via
 * `MemoryFacade.resolveRef` belongs to the full Stage-4 Expand (the Executor
 * pipeline has no such stage today); same-alias runs still serialize
 * correctly because the key is stable per spelling.
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
}
