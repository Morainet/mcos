package com.morainet.mcos.plugin.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Converts an MCP tool's JSON Schema into an MCOS command `inputSchema`,
 * field by field, per the normative table in [02-command-protocol.md §12.4].
 *
 * The output is a JSON-Schema-shaped object using the keyword vocabulary the
 * runtime's `SchemaValidator` understands (`string` / `number` / `integer` /
 * `boolean` / `array` / `object` / `null` plus `enum` / `required` /
 * `properties` / `items` / bounds). The §12.4 "MCOS primitive" distinctions
 * that collapse onto `string` at the validator layer (`datetime` / `bytes` /
 * `uri` / `duration`) are preserved as an informational `format` keyword and
 * carry their §12.4 size caps.
 *
 * **Fail-closed contract (§12.4):** when the converter meets an unmappable
 * keyword (`oneOf` / `anyOf` / `patternProperties` / a `format` outside the
 * table / an unresolvable local `$ref`) it returns [Result.Unmapped] rather
 * than silently dropping the argument, and the adapter refuses to register the
 * tool. This turns "a schema we cannot faithfully represent" into a visible
 * rejection instead of silent argument loss.
 */
object McpSchemaConverter {

    /** §12.4 string `maxLength` cap. */
    const val STRING_MAX_LENGTH: Int = 65536

    /** §12.4 `format: "byte"` decoded-size cap (10 MiB). */
    const val BYTES_MAX_BYTES: Int = 10 * 1024 * 1024

    /**
     * Base64 length ceiling for a [BYTES_MAX_BYTES] payload: every 3 input
     * bytes encode to 4 output chars, rounded up to the next 4-char group.
     */
    val BYTES_BASE64_MAX_LENGTH: Int = 4 * ((BYTES_MAX_BYTES + 2) / 3)

    /** `format` values the §12.4 table maps onto an MCOS primitive. */
    private val KNOWN_STRING_FORMATS = setOf("date-time", "byte", "uri", "duration")

    sealed class Result {
        /** The schema was fully mappable; [inputSchema] is executor-ready. */
        data class Converted(val inputSchema: JsonObject) : Result()

        /**
         * The schema contained an unmappable keyword. [unmappedType] names it
         * (e.g. `"oneOf"`, `"format:email"`), [reason] is machine-readable, and
         * [path] locates the offending node (JSON-pointer-ish, `""` = root).
         */
        data class Unmapped(
            val unmappedType: String,
            val reason: String,
            val path: String,
        ) : Result()
    }

    /** Raised internally to unwind recursion on the first unmappable node. */
    private class UnmappedException(
        val unmappedType: String,
        val reason: String,
        val path: String,
    ) : RuntimeException()

    /**
     * Convert an MCP tool [mcpSchema] (its `inputSchema`) to an MCOS
     * `inputSchema`. Local `$ref`s are resolved against the schema's own
     * top-level `$defs` / `definitions` map.
     */
    fun convert(mcpSchema: JsonObject): Result {
        val defs = collectDefs(mcpSchema)
        return try {
            Result.Converted(convertNode(mcpSchema, defs, path = "").jsonObject)
        } catch (e: UnmappedException) {
            Result.Unmapped(e.unmappedType, e.reason, e.path)
        }
    }

    private fun collectDefs(root: JsonObject): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        (root["\$defs"] as? JsonObject)?.forEach { (k, v) ->
            (v as? JsonObject)?.let { out["\$defs/$k"] = it }
        }
        (root["definitions"] as? JsonObject)?.forEach { (k, v) ->
            (v as? JsonObject)?.let { out["definitions/$k"] = it }
        }
        return out
    }

    private fun convertNode(
        node: JsonObject,
        defs: Map<String, JsonObject>,
        path: String,
    ): JsonObject {
        // Local $ref — resolve against the top-level $defs/definitions map.
        node["\$ref"]?.jsonPrimitive?.contentOrNull?.let { ref ->
            val resolved = resolveRef(ref, defs)
                ?: throw UnmappedException("\$ref", "unresolvable_ref:$ref", path)
            return convertNode(resolved, defs, path)
        }

        // Union / pattern keywords have no v0.1 mapping — fail closed.
        rejectUnmappedKeywords(node, path)

        // const: null is a literal null arg; a non-null const is a 1-value enum.
        node["const"]?.let { constValue ->
            return if (constValue is JsonNull) {
                buildJsonObject { put("type", JsonPrimitive("null")) }
            } else {
                buildJsonObject {
                    put("type", typeOfPrimitive(constValue, path))
                    put("enum", buildJsonArray { add(constValue) })
                    copyDescription(node, this)
                }
            }
        }

        val type = node["type"]?.jsonPrimitive?.contentOrNull

        // enum without an explicit type: infer from the enum's members
        // (§12.4 `string + enum`, but numbers/bools are equally representable).
        val enum = node["enum"]?.jsonArray
        if (type == null && enum != null) {
            return buildJsonObject {
                put("type", inferEnumType(enum, path))
                put("enum", enum)
                copyDescription(node, this)
            }
        }

        return when (type) {
            "string" -> convertString(node, enum, path)
            "integer" -> buildScalar("integer", node, enum)
            "number" -> buildScalar("number", node, enum)
            "boolean" -> buildScalar("boolean", node, enum)
            "null" -> buildJsonObject { put("type", JsonPrimitive("null")) }
            "array" -> convertArray(node, defs, path)
            "object" -> convertObject(node, defs, path)
            null -> throw UnmappedException(
                "type", "missing_type", path,
            )
            else -> throw UnmappedException("type:$type", "unknown_type:$type", path)
        }
    }

    private fun rejectUnmappedKeywords(node: JsonObject, path: String) {
        if ("oneOf" in node) throw UnmappedException("oneOf", "union_type_unsupported", path)
        if ("anyOf" in node) throw UnmappedException("anyOf", "union_type_unsupported", path)
        if ("allOf" in node) throw UnmappedException("allOf", "schema_composition_unsupported", path)
        if ("patternProperties" in node) {
            throw UnmappedException("patternProperties", "pattern_properties_unsupported", path)
        }
    }

    private fun convertString(node: JsonObject, enum: JsonArray?, path: String): JsonObject {
        val format = node["format"]?.jsonPrimitive?.contentOrNull
        if (format != null && format !in KNOWN_STRING_FORMATS) {
            throw UnmappedException("format:$format", "unsupported_format:$format", path)
        }
        return buildJsonObject {
            put("type", JsonPrimitive("string"))
            if (format != null) put("format", JsonPrimitive(format))
            if (enum != null) put("enum", enum)
            // §12.4 caps: byte payloads cap on their base64 length, all other
            // strings cap at 65536. A declared maxLength only tightens the cap.
            val ceiling = if (format == "byte") BYTES_BASE64_MAX_LENGTH else STRING_MAX_LENGTH
            val declared = node["maxLength"]?.jsonPrimitive?.longOrNull
            put("maxLength", JsonPrimitive(minOf(declared ?: ceiling.toLong(), ceiling.toLong())))
            node["minLength"]?.let { put("minLength", it) }
            copyDescription(node, this)
        }
    }

    private fun buildScalar(mcosType: String, node: JsonObject, enum: JsonArray?): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive(mcosType))
            if (enum != null) put("enum", enum)
            node["minimum"]?.let { put("minimum", it) }
            node["maximum"]?.let { put("maximum", it) }
            copyDescription(node, this)
        }

    private fun convertArray(node: JsonObject, defs: Map<String, JsonObject>, path: String): JsonObject {
        val items = node["items"]
        // Tuple-form items (an array of positional schemas) has no MCOS mapping.
        if (items is JsonArray) {
            throw UnmappedException("items[]", "tuple_items_unsupported", path)
        }
        return buildJsonObject {
            put("type", JsonPrimitive("array"))
            if (items is JsonObject) {
                put("items", convertNode(items, defs, "$path/items"))
            }
            node["minItems"]?.let { put("minItems", it) }
            node["maxItems"]?.let { put("maxItems", it) }
            copyDescription(node, this)
        }
    }

    private fun convertObject(node: JsonObject, defs: Map<String, JsonObject>, path: String): JsonObject {
        val properties = node["properties"] as? JsonObject
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            if (properties != null) {
                put("properties", buildJsonObject {
                    properties.forEach { (name, sub) ->
                        val subObj = sub as? JsonObject
                            ?: throw UnmappedException(
                                "properties/$name", "non_object_property_schema", "$path/properties/$name",
                            )
                        put(name, convertNode(subObj, defs, "$path/properties/$name"))
                    }
                })
                node["required"]?.let { put("required", it) }
            }
            copyDescription(node, this)
        }
    }

    private fun resolveRef(ref: String, defs: Map<String, JsonObject>): JsonObject? {
        // Only local pointers into $defs / definitions are supported.
        val key = when {
            ref.startsWith("#/\$defs/") -> "\$defs/" + ref.removePrefix("#/\$defs/")
            ref.startsWith("#/definitions/") -> "definitions/" + ref.removePrefix("#/definitions/")
            else -> return null
        }
        return defs[key]
    }

    private fun typeOfPrimitive(value: JsonElement, path: String): JsonPrimitive {
        val prim = value as? JsonPrimitive
            ?: throw UnmappedException("const", "non_primitive_const", path)
        return JsonPrimitive(
            when {
                prim.isString -> "string"
                prim.content == "true" || prim.content == "false" -> "boolean"
                prim.content.toLongOrNull() != null -> "integer"
                prim.content.toDoubleOrNull() != null -> "number"
                else -> "string"
            }
        )
    }

    private fun inferEnumType(enum: JsonArray, path: String): JsonPrimitive {
        if (enum.isEmpty()) throw UnmappedException("enum", "empty_enum", path)
        // Homogeneous enums map to their member type; mixed enums fall back to
        // string so the validator still accepts the declared literals.
        val types = enum.map { typeOfPrimitive(it, path).content }.toSet()
        return JsonPrimitive(if (types.size == 1) types.first() else "string")
    }

    private fun copyDescription(from: JsonObject, into: JsonObjectBuilder) {
        from["description"]?.jsonPrimitive?.contentOrNull?.let {
            into.put("description", JsonPrimitive(it))
        }
    }
}
