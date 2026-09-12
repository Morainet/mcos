package com.morainet.mcos.plugin.intent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Fail-closed checker for the `extrasSchema` of an `intent.start` invoke
 * (02-command-protocol.md §12.6).
 *
 * §12.6 makes a declared extras schema mandatory because free-form Intent
 * extras are a historic LLM-hallucination source: the model invents extra
 * keys the receiver does not understand. This checker implements the
 * JSON-Schema subset that is meaningful for extras and **refuses to guess** —
 * a schema using any keyword outside [SUPPORTED_KEYWORDS], or whose own shape
 * is malformed (no `type`, non-array `required`, schema-valued
 * `additionalProperties`, tuple `items[]`), is rejected as
 * `extras_schema_unsupported` rather than half-validated. That mirrors the
 * fail-closed posture the MCP converter takes for unmappable tools
 * (02 §12.4): dropping the invoke is better than silently ignoring a
 * constraint the caller declared.
 *
 * Standard JSON-Schema semantics apply otherwise: properties not listed in
 * `properties` are allowed unless the schema sets `additionalProperties:
 * false` (which is how an author catches invented keys — the published
 * well-known schemas in [WellKnownIntents] all do).
 */
object ExtrasSchema {

    /** Outcome of checking extras against a schema. */
    sealed class Result {
        /** The extras conform to the schema. */
        object Valid : Result()

        /**
         * The extras (or the schema itself) are unusable.
         *
         * @property path value path for `details.path`, e.g.
         *   `/args/extras/android.intent.extra.TEXT`.
         * @property reason machine-readable reason for `details.reason`.
         * @property message human-readable detail.
         */
        data class Invalid(val path: String, val reason: String, val message: String) : Result()
    }

    /** The §12.6 path an extras rejection is reported under. */
    const val ROOT_PATH = "/args/extras"

    /** Machine-readable rejection reasons (`McosException.details.reason`). */
    object Reason {
        /** No `extrasSchema` was supplied and the action is not a published well-known intent (§12.6). */
        const val SCHEMA_REQUIRED = "extras_schema_required"

        /** The schema uses an unsupported keyword or has a malformed shape — fail closed. */
        const val SCHEMA_UNSUPPORTED = "extras_schema_unsupported"

        /** `extras` is present but is not a JSON object. */
        const val NOT_AN_OBJECT = "extras_not_an_object"

        /** A property the schema lists in `required` is absent. */
        const val MISSING_REQUIRED = "extras_required_missing"

        /** A value does not match its declared type. */
        const val TYPE_MISMATCH = "extras_type_mismatch"

        /** An extra key is not declared and the schema forbids additional properties. */
        const val UNKNOWN_PROPERTY = "extras_unknown_property"

        /** A value violates a bound (`enum`/`const`/`minLength`/`maximum`/…). */
        const val CONSTRAINT_VIOLATION = "extras_constraint_violation"
    }

    private val NUMERIC_KEYWORDS =
        listOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")

    /**
     * Keywords this checker understands. Anything else — `oneOf`, `anyOf`,
     * `allOf`, `$ref`, `pattern`, `patternProperties`, `not`, `if`/`then`,
     * `format`, … — fails the invoke closed (02 §12.6 / §12.4 philosophy).
     */
    private val SUPPORTED_KEYWORDS = setOf(
        "type", "properties", "required", "additionalProperties",
        "enum", "const", "items",
        "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
        // Informative only — accepted and ignored.
        "title", "description", "default", "examples", "\$comment",
    )

    /**
     * Check [extras] against [schema].
     *
     * @param extras the caller's `extras` value (an object, or rejected).
     */
    fun validate(schema: JsonObject, extras: JsonElement): Result {
        shapeError(schema, ROOT_PATH)?.let { return it }
        if (extras !is JsonObject) {
            return Result.Invalid(ROOT_PATH, Reason.NOT_AN_OBJECT, "extras must be a JSON object")
        }
        return validateObject(schema, extras, ROOT_PATH)
    }

    // ── schema-shape gate (fail closed before any value is judged) ───────

    private fun shapeError(schema: JsonObject, path: String): Result.Invalid? {
        val unsupportedKey = schema.keys.firstOrNull { it !in SUPPORTED_KEYWORDS }
        if (unsupportedKey != null) {
            return unsupported(path, "unsupported extras-schema keyword '$unsupportedKey'")
        }

        val type = schema["type"]
        if (type == null || type !is JsonPrimitive || !type.isString || type.content.isBlank()) {
            return unsupported(path, "extras schema must declare a string 'type'")
        }

        schema["required"]?.let { required ->
            if (required !is JsonArray) return unsupported(path, "'required' must be an array")
            if (required.any { it !is JsonPrimitive || !it.isString }) {
                return unsupported(path, "'required' entries must be strings")
            }
        }
        schema["enum"]?.let { enum ->
            if (enum !is JsonArray) return unsupported(path, "'enum' must be an array")
        }
        schema["additionalProperties"]?.let { additional ->
            if (additional !is JsonPrimitive || additional.asBooleanOrNull() == null) {
                return unsupported(
                    path,
                    "'additionalProperties' must be a boolean " +
                        "(schema-valued additionalProperties is unsupported)",
                )
            }
        }
        for (keyword in NUMERIC_KEYWORDS) {
            schema[keyword]?.let { bound ->
                if (bound !is JsonPrimitive || (!bound.isString && bound.doubleOrNull == null)) {
                    return unsupported(path, "'$keyword' must be a number")
                }
            }
        }
        schema["properties"]?.let { properties ->
            if (properties !is JsonObject) return unsupported(path, "'properties' must be an object")
            properties.forEach { (name, subSchema) ->
                if (subSchema !is JsonObject) {
                    return unsupported(append(path, name), "'properties.$name' must be an object")
                }
                shapeError(subSchema, append(path, name))?.let { return it }
            }
        }
        schema["items"]?.let { items ->
            // Shape problems in the schema are reported at the value path the
            // schema governs — there is no value here to point at yet.
            if (items !is JsonObject) {
                return unsupported(
                    path,
                    "'items' must be an object (tuple items[] are unsupported)",
                )
            }
            shapeError(items, path)?.let { return it }
        }
        return null
    }

    // ── value checks ─────────────────────────────────────────────────────

    private fun validateObject(schema: JsonObject, value: JsonObject, path: String): Result {
        val type = (schema["type"] as JsonPrimitive).content
        if (type != "object") {
            return unsupported(path, "extras schema entry must be type 'object', got '$type'")
        }
        for (entry in (schema["required"] as? JsonArray).orEmpty()) {
            val name = (entry as JsonPrimitive).content
            if (!value.containsKey(name)) {
                return Result.Invalid(
                    append(path, name),
                    Reason.MISSING_REQUIRED,
                    "required extra '$name' is missing",
                )
            }
        }
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val additionalAllowed =
            (schema["additionalProperties"] as? JsonPrimitive)?.asBooleanOrNull() ?: true
        for ((name, element) in value) {
            val subSchema = properties[name]
            if (subSchema == null) {
                if (!additionalAllowed) {
                    return Result.Invalid(
                        append(path, name),
                        Reason.UNKNOWN_PROPERTY,
                        "extra '$name' is not declared by extrasSchema",
                    )
                }
            } else {
                // The shape gate already proved every declared property is an object.
                validateValue(subSchema as JsonObject, element, append(path, name))?.let { return it }
            }
        }
        return Result.Valid
    }

    private fun validateValue(schema: JsonObject, value: JsonElement, path: String): Result.Invalid? {
        val type = (schema["type"] as JsonPrimitive).content
        if (!typeMatches(type, value)) {
            return Result.Invalid(path, Reason.TYPE_MISMATCH, "expected $type, got ${actualType(value)}")
        }

        schema["const"]?.let { expected ->
            if (value != expected) {
                return Result.Invalid(path, Reason.CONSTRAINT_VIOLATION, "must equal $expected")
            }
        }
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (allowed.none { it == value }) {
                return Result.Invalid(path, Reason.CONSTRAINT_VIOLATION, "must be one of $allowed")
            }
        }

        if (value is JsonPrimitive && value.isString) {
            intBound(schema, "minLength")?.let { bound ->
                if (value.content.length < bound) {
                    return Result.Invalid(
                        path,
                        Reason.CONSTRAINT_VIOLATION,
                        "length ${value.content.length} < minLength $bound",
                    )
                }
            }
            intBound(schema, "maxLength")?.let { bound ->
                if (value.content.length > bound) {
                    return Result.Invalid(
                        path,
                        Reason.CONSTRAINT_VIOLATION,
                        "length ${value.content.length} > maxLength $bound",
                    )
                }
            }
        }

        if (value is JsonPrimitive && !value.isString) {
            value.doubleOrNull?.let { number ->
                doubleBound(schema, "minimum")?.let { bound ->
                    if (number < bound) {
                        return Result.Invalid(path, Reason.CONSTRAINT_VIOLATION, "$number < minimum $bound")
                    }
                }
                doubleBound(schema, "maximum")?.let { bound ->
                    if (number > bound) {
                        return Result.Invalid(path, Reason.CONSTRAINT_VIOLATION, "$number > maximum $bound")
                    }
                }
            }
        }

        if (value is JsonArray) {
            intBound(schema, "minItems")?.let { bound ->
                if (value.size < bound) {
                    return Result.Invalid(path, Reason.CONSTRAINT_VIOLATION, "size ${value.size} < minItems $bound")
                }
            }
            intBound(schema, "maxItems")?.let { bound ->
                if (value.size > bound) {
                    return Result.Invalid(path, Reason.CONSTRAINT_VIOLATION, "size ${value.size} > maxItems $bound")
                }
            }
            (schema["items"] as? JsonObject)?.let { itemSchema ->
                value.forEachIndexed { index, element ->
                    validateValue(itemSchema, element, append(path, index.toString()))?.let { return it }
                }
            }
        }

        if (value is JsonObject) {
            val nested = validateObject(schema, value, path)
            if (nested is Result.Invalid) return nested
        }

        return null
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun unsupported(path: String, message: String) =
        Result.Invalid(path, Reason.SCHEMA_UNSUPPORTED, message)

    private fun append(path: String, segment: String): String = "$path/$segment"

    private fun intBound(schema: JsonObject, keyword: String): Int? =
        (schema[keyword] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun doubleBound(schema: JsonObject, keyword: String): Double? =
        (schema[keyword] as? JsonPrimitive)?.content?.toDoubleOrNull()

    /**
     * Booleans must be JSON literals — the string `"false"` is NOT `false`
     * (the same parenthesisation trap the runtime validator records).
     */
    private fun JsonPrimitive.asBooleanOrNull(): Boolean? = when {
        isString -> null
        content == "true" -> true
        content == "false" -> false
        else -> null
    }

    private fun typeMatches(type: String, value: JsonElement): Boolean = when (type) {
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && value.asBooleanOrNull() != null
        "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "array" -> value is JsonArray
        "object" -> value is JsonObject
        "null" -> value is JsonNull
        else -> false
    }

    private fun actualType(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.asBooleanOrNull() != null -> "boolean"
            value.content.toLongOrNull() != null -> "integer"
            value.doubleOrNull != null -> "number"
            else -> "primitive"
        }
        else -> "null"
    }
}
