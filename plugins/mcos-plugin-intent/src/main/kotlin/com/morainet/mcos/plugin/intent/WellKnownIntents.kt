package com.morainet.mcos.plugin.intent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The small allowlist of system intents 02-command-protocol.md §12.6 publishes
 * with **pre-declared extras schemas**. For these actions a caller may omit
 * `extrasSchema`; the runtime validates `extras` against the schema here.
 *
 * Every published schema sets `additionalProperties: false`, which is the
 * whole point: an invented extra key is rejected rather than forwarded into
 * the target app's bundle (§12.6's stated hazard). The list is deliberately
 * small — it covers only actions MCOS can describe precisely; anything else
 * requires the caller to declare its own `extrasSchema`.
 *
 * The schemas are published by this bridge plugin rather than the `sys`
 * plugin because they are specifically the Intent-launch surface of the
 * Intent/Deep Link plugin (10-roadmap §5.4).
 */
object WellKnownIntents {

    /** Action -> pre-declared extras schema (§12.6). */
    val schemas: Map<String, JsonObject> = mapOf(
        // Actions that carry their payload in the data URI, not in extras.
        "android.intent.action.VIEW" to objectSchema(),
        "android.intent.action.MAIN" to objectSchema(),
        "android.intent.action.DIAL" to objectSchema(),
        "android.intent.action.SENDTO" to objectSchema(
            properties = mapOf("android.intent.extra.TEXT" to stringSchema()),
        ),
        "android.intent.action.WEB_SEARCH" to objectSchema(
            properties = mapOf("android.intent.extra.TEXT" to stringSchema()),
            required = listOf("android.intent.extra.TEXT"),
        ),
        "android.intent.action.SEND" to objectSchema(
            properties = mapOf(
                "android.intent.extra.TEXT" to stringSchema(),
                "android.intent.extra.SUBJECT" to stringSchema(),
                // A content:// URI the target app can read.
                "android.intent.extra.STREAM" to stringSchema(),
            ),
        ),
    )

    /** The published schema for [action], or null when it is not allowlisted. */
    fun schemaFor(action: String): JsonObject? = schemas[action]

    /** The allowlisted actions, for docs and diagnostics. */
    val actions: Set<String> get() = schemas.keys

    private fun stringSchema() = JsonObject(mapOf("type" to JsonPrimitive("string")))

    private fun objectSchema(
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(properties))
        put("additionalProperties", JsonPrimitive(false))
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }
}
