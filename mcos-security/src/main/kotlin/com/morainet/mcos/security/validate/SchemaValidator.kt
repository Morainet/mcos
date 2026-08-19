package com.morainet.mcos.security.validate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Result of schema validation.
 */
sealed class ValidationResult {
    /** All args conform to the schema. */
    data object Valid : ValidationResult()

    /** One or more validation errors found. */
    data class Invalid(val errors: List<ValidationError>) : ValidationResult()
}

/**
 * A single schema validation error with JSON Pointer path.
 */
data class ValidationError(
    val path: String,        // JSON Pointer, e.g. "/name", "/items/0/url"
    val expected: String,    // e.g. "string", "required", "minimum 0"
    val actual: String,      // e.g. "number", "missing", "-5"
    val code: String = "SCHEMA_VIOLATION"
)

/**
 * Lightweight JSON Schema (Draft 2020-12 subset) validator for MVOS MVP.
 *
 * Supported keywords:
 * - `type`          — "string" | "number" | "integer" | "boolean" | "array" | "object" | "null"
 * - `required`      — array of required field names
 * - `properties`    — per-field sub-schemas
 * - `additionalProperties` — when `false`, rejects fields not declared in `properties`
 * - `items`         — array item schema
 * - `minimum`       — numeric lower bound (inclusive)
 * - `maximum`       — numeric upper bound (inclusive)
 * - `minLength`     — string min length
 * - `maxLength`     — string max length
 * - `pattern`       — regex the string must fully match (invalid regex → validation error)
 * - `enum`          — array of allowed values
 *
 * Unsupported keywords are silently ignored (not an error).
 *
 * Matches [03-runtime.md 5 Schema validation], [02-command-protocol.md 5].
 */
class SchemaValidator {

    /**
     * Validate [args] against the given JSON [schema].
     *
     * @param args The JSON object to validate.
     * @param schema A JSON Schema object.
     * @return [ValidationResult.Valid] if args conform, [ValidationResult.Invalid] otherwise.
     */
    fun validate(args: JsonObject, schema: JsonObject): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        validateObject(args, schema, "", errors)
        return if (errors.isEmpty()) ValidationResult.Valid
        else ValidationResult.Invalid(errors)
    }

    // ─── Object validation ──────────────────────────────────────────────

    private fun validateObject(
        value: JsonElement,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>
    ) {
        // Type check
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        if (type != null && !typeMatches(value, type, path, errors)) return

        if (value !is JsonObject) return

        // Required fields
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (required != null) {
            for (field in required) {
                if (!value.containsKey(field)) {
                    errors.add(
                        ValidationError(
                            path = appendPath(path, field),
                            expected = "required",
                            actual = "missing"
                        )
                    )
                }
            }
        }

        // Properties
        val properties = schema["properties"]?.jsonObject
        if (properties != null) {
            for ((key, propSchema) in properties) {
                val valAtKey = value[key] ?: continue
                validateValue(valAtKey, propSchema.jsonObject, appendPath(path, key), errors)
            }

            // additionalProperties: false — reject fields not declared in `properties`.
            // Only honored when `properties` is present; a bare
            // `additionalProperties: false` without `properties` is a no-op
            // (there is nothing to compare against).
            val additional = schema["additionalProperties"]
            if (additional != null) {
                // additionalProperties is either a boolean or a sub-schema.
                // We only special-case `false` (reject undeclared fields); a
                // truthy value or a schema is treated as permissive.
                val allowed = additional.jsonPrimitive.contentOrNull?.let {
                    when (it) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                }
                if (allowed == false) {
                    for (key in value.keys) {
                        if (key !in properties) {
                            errors.add(
                                ValidationError(
                                    path = appendPath(path, key),
                                    expected = "additionalProperties=false (undeclared field)",
                                    actual = "present"
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // ─── Value dispatch ──────────────────────────────────────────────────

    private fun validateValue(
        value: JsonElement,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>
    ) {
        // Type check
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        if (type != null && !typeMatches(value, type, path, errors)) return

        // Enum check
        val enumValues = schema["enum"]?.jsonArray
        if (enumValues != null) {
            if (!enumValues.any { it == value }) {
                errors.add(
                    ValidationError(
                        path = path,
                        expected = "one of ${enumValues.joinToString { it.toString() }}",
                        actual = value.toString()
                    )
                )
                return
            }
        }

        when (value) {
            is JsonPrimitive -> validatePrimitive(value, schema, path, errors)
            is JsonArray -> validateArray(value, schema, path, errors)
            is JsonObject -> validateObject(value, schema, path, errors)
        }
    }

    // ─── Primitive validation ────────────────────────────────────────────

    private fun validatePrimitive(
        value: JsonPrimitive,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>
    ) {
        val content = value.content

        // Numeric constraints — only apply to non-string (numeric) primitives.
        // A JSON string "10" must not be treated as the number 10.
        if (!value.isString) {
            val num = content.toDoubleOrNull()
            if (num != null) {
                val minimum = schema["minimum"]?.jsonPrimitive?.doubleOrNull
                val maximum = schema["maximum"]?.jsonPrimitive?.doubleOrNull

                if (minimum != null && num < minimum) {
                    errors.add(
                        ValidationError(path, "minimum $minimum", "$num")
                    )
                }
                if (maximum != null && num > maximum) {
                    errors.add(
                        ValidationError(path, "maximum $maximum", "$num")
                    )
                }
            }
        }

        // String constraints
        if (value.isString) {
            val minLength = schema["minLength"]?.jsonPrimitive?.intOrNull
            val maxLength = schema["maxLength"]?.jsonPrimitive?.intOrNull

            if (minLength != null && content.length < minLength) {
                errors.add(
                    ValidationError(path, "minLength $minLength", "length ${content.length}")
                )
            }
            if (maxLength != null && content.length > maxLength) {
                errors.add(
                    ValidationError(path, "maxLength $maxLength", "length ${content.length}")
                )
            }

            // pattern — the string must fully match the supplied regex. A
            // syntactically invalid pattern is itself a schema error: rather
            // than silently swallowing the exception (which would let a
            // malformed schema accept anything), we surface it as a
            // validation error citing the bad pattern.
            val patternStr = schema["pattern"]?.jsonPrimitive?.contentOrNull
            if (patternStr != null) {
                try {
                    val regex = Regex(patternStr)
                    if (!regex.matches(content)) {
                        errors.add(
                            ValidationError(path, "pattern /$patternStr/", content)
                        )
                    }
                } catch (e: Exception) {
                    errors.add(
                        ValidationError(path, "pattern (invalid: ${e.message?.take(80)})", content)
                    )
                }
            }
        }
    }

    // ─── Array validation ────────────────────────────────────────────────

    private fun validateArray(
        value: JsonArray,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>
    ) {
        val itemsSchema = schema["items"]?.jsonObject ?: return
        for ((i, item) in value.withIndex()) {
            validateValue(item, itemsSchema, "$path/$i", errors)
        }
    }

    // ─── Type matching ──────────────────────────────────────────────────

    private fun typeMatches(
        value: JsonElement,
        expectedType: String,
        path: String,
        errors: MutableList<ValidationError>
    ): Boolean {
        val actual = actualType(value)
        // "number" matches both integer and floating-point numbers
        val match = when (expectedType) {
            "string"  -> value is JsonPrimitive && value.isString
            // Numeric checks must reject strings: a JSON string "5" is not a number.
            "number"  -> value is JsonPrimitive && !value.isString && (value.content.toDoubleOrNull() != null)
            "integer" -> value is JsonPrimitive && !value.isString && (value.content.toLongOrNull() != null)
            // Parentheses are critical: without them, && binds tighter than ||
            // and a string "false" would be accepted as a boolean.
            "boolean" -> value is JsonPrimitive && !value.isString && (value.content == "true" || value.content == "false")
            "array"   -> value is JsonArray
            "object"  -> value is JsonObject
            // JsonNull is a SIBLING of JsonPrimitive in kotlinx.serialization
            // (both extend JsonElement), NOT a subclass — so `value is JsonPrimitive`
            // is always false for null. This case must check `value is JsonNull`
            // directly; otherwise a `type: "null"` schema would fall through to
            // the `else -> true` branch and accept any value.
            "null"    -> value is JsonNull
            else      -> true // unknown type → pass
        }
        if (!match) {
            errors.add(ValidationError(path, expectedType, actual))
        }
        return match
    }

    private fun actualType(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray  -> "array"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.content.toLongOrNull() != null -> "integer"
            value.content.toDoubleOrNull() != null -> "number"
            value.content == "true" || value.content == "false" -> "boolean"
            else -> "null"
        }
    }

    // ─── Path helpers ───────────────────────────────────────────────────

    private fun appendPath(prefix: String, segment: String): String =
        if (prefix.isEmpty()) "/$segment" else "$prefix/$segment"
}
