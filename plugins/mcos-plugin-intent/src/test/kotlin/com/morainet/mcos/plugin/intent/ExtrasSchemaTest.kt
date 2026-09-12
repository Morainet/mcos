package com.morainet.mcos.plugin.intent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §12.6 mandatory `extrasSchema` checking (02-command-protocol.md).
 */
class ExtrasSchemaTest {

    private fun assertInvalid(
        result: ExtrasSchema.Result,
        reason: String,
        path: String = ExtrasSchema.ROOT_PATH,
    ): ExtrasSchema.Result.Invalid {
        val invalid = assertIs<ExtrasSchema.Result.Invalid>(result)
        assertEquals(reason, invalid.reason, "reason (message: ${invalid.message})")
        assertEquals(path, invalid.path, "path (message: ${invalid.message})")
        return invalid
    }

    private fun objectSchema(
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        additional: Boolean? = null,
    ): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
        additional?.let { put("additionalProperties", JsonPrimitive(it)) }
    }

    private fun stringSchema(vararg keywords: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            keywords.forEach { (k, v) -> put(k, v) }
        }

    // ── happy path ───────────────────────────────────────────────────────

    @Test
    fun `ES1-empty object schema accepts empty extras`() {
        assertEquals(
            ExtrasSchema.Result.Valid,
            ExtrasSchema.validate(objectSchema(), JsonObject(emptyMap())),
        )
    }

    @Test
    fun `ES2-declared properties of each type are accepted`() {
        val schema = objectSchema(
            properties = mapOf(
                "text" to stringSchema(),
                "count" to buildJsonObject { put("type", JsonPrimitive("integer")) },
                "ratio" to buildJsonObject { put("type", JsonPrimitive("number")) },
                "flag" to buildJsonObject { put("type", JsonPrimitive("boolean")) },
                "tags" to buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            ),
        )
        val extras = buildJsonObject {
            put("text", JsonPrimitive("hi"))
            put("count", JsonPrimitive(2))
            put("ratio", JsonPrimitive(0.5))
            put("flag", JsonPrimitive(true))
            put("tags", buildJsonArray { add(JsonPrimitive("a")) })
        }
        assertEquals(ExtrasSchema.Result.Valid, ExtrasSchema.validate(schema, extras))
    }

    // ── required / additionalProperties ──────────────────────────────────

    @Test
    fun `ES3-missing required extra is rejected with its path`() {
        val schema = objectSchema(required = listOf("android.intent.extra.TEXT"))
        assertInvalid(
            ExtrasSchema.validate(schema, JsonObject(emptyMap())),
            ExtrasSchema.Reason.MISSING_REQUIRED,
            path = "/args/extras/android.intent.extra.TEXT",
        )
    }

    @Test
    fun `ES4-invented extra key is rejected when additionalProperties is false`() {
        val schema = objectSchema(additional = false)
        val invalid = assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("made.up.KEY", JsonPrimitive("x")) }),
            ExtrasSchema.Reason.UNKNOWN_PROPERTY,
            path = "/args/extras/made.up.KEY",
        )
        assertTrue(invalid.message.contains("not declared"), invalid.message)
    }

    @Test
    fun `ES5-undeclared keys are allowed when the schema does not forbid them`() {
        // Standard JSON-Schema semantics: additionalProperties defaults to
        // true. Authors catch invented keys by opting in to false (all the
        // published well-known schemas do).
        val schema = objectSchema()
        assertEquals(
            ExtrasSchema.Result.Valid,
            ExtrasSchema.validate(schema, buildJsonObject { put("anything", JsonPrimitive(1)) }),
        )
    }

    // ── value constraints ────────────────────────────────────────────────

    @Test
    fun `ES6-type mismatch is rejected and reported at the value path`() {
        val schema = objectSchema(properties = mapOf("level" to buildJsonObject { put("type", JsonPrimitive("integer")) }))
        val invalid = assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("level", JsonPrimitive("three")) }),
            ExtrasSchema.Reason.TYPE_MISMATCH,
            path = "/args/extras/level",
        )
        assertTrue(invalid.message.contains("expected integer"), invalid.message)
    }

    @Test
    fun `ES7-a fractional number is not an integer`() {
        val schema = objectSchema(properties = mapOf("n" to buildJsonObject { put("type", JsonPrimitive("integer")) }))
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("n", JsonPrimitive(1.5)) }),
            ExtrasSchema.Reason.TYPE_MISMATCH,
            path = "/args/extras/n",
        )
    }

    @Test
    fun `ES8-the string false is not the boolean false`() {
        val schema = objectSchema(properties = mapOf("flag" to buildJsonObject { put("type", JsonPrimitive("boolean")) }))
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("flag", JsonPrimitive("false")) }),
            ExtrasSchema.Reason.TYPE_MISMATCH,
            path = "/args/extras/flag",
        )
    }

    @Test
    fun `ES9-string length and numeric bounds are enforced`() {
        val schema = objectSchema(
            properties = mapOf(
                "s" to stringSchema("minLength" to JsonPrimitive(2), "maxLength" to JsonPrimitive(4)),
                "n" to buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("minimum", JsonPrimitive(1))
                    put("maximum", JsonPrimitive(9))
                },
            ),
        )
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("s", JsonPrimitive("a")) }),
            ExtrasSchema.Reason.CONSTRAINT_VIOLATION,
            path = "/args/extras/s",
        )
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("s", JsonPrimitive("abcde")) }),
            ExtrasSchema.Reason.CONSTRAINT_VIOLATION,
            path = "/args/extras/s",
        )
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("n", JsonPrimitive(0)) }),
            ExtrasSchema.Reason.CONSTRAINT_VIOLATION,
            path = "/args/extras/n",
        )
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("n", JsonPrimitive(10)) }),
            ExtrasSchema.Reason.CONSTRAINT_VIOLATION,
            path = "/args/extras/n",
        )
        assertEquals(
            ExtrasSchema.Result.Valid,
            ExtrasSchema.validate(schema, buildJsonObject { put("s", JsonPrimitive("ab")); put("n", JsonPrimitive(5)) }),
        )
    }

    @Test
    fun `ES10-enum and const are enforced`() {
        val schema = objectSchema(
            properties = mapOf(
                "mode" to buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
                },
                "kind" to buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("const", JsonPrimitive("fixed"))
                },
            ),
        )
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("mode", JsonPrimitive("c")) }),
            ExtrasSchema.Reason.CONSTRAINT_VIOLATION,
            path = "/args/extras/mode",
        )
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("kind", JsonPrimitive("other")) }),
            ExtrasSchema.Reason.CONSTRAINT_VIOLATION,
            path = "/args/extras/kind",
        )
        assertEquals(
            ExtrasSchema.Result.Valid,
            ExtrasSchema.validate(schema, buildJsonObject { put("mode", JsonPrimitive("a")); put("kind", JsonPrimitive("fixed")) }),
        )
    }

    @Test
    fun `ES11-array items are validated with an index path`() {
        val schema = objectSchema(
            properties = mapOf(
                "tags" to buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("minItems", JsonPrimitive(1))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            ),
        )
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("tags", buildJsonArray { }) }),
            ExtrasSchema.Reason.CONSTRAINT_VIOLATION,
            path = "/args/extras/tags",
        )
        assertInvalid(
            ExtrasSchema.validate(
                schema,
                buildJsonObject {
                    put("tags", buildJsonArray { add(JsonPrimitive("ok")); add(JsonPrimitive(7)) })
                },
            ),
            ExtrasSchema.Reason.TYPE_MISMATCH,
            path = "/args/extras/tags/1",
        )
    }

    @Test
    fun `ES12-nested objects are validated recursively`() {
        val schema = objectSchema(
            properties = mapOf(
                "opts" to objectSchema(
                    properties = mapOf("deep" to stringSchema()),
                    additional = false,
                ),
            ),
        )
        assertInvalid(
            ExtrasSchema.validate(schema, buildJsonObject { put("opts", buildJsonObject { put("nope", JsonPrimitive(1)) }) }),
            ExtrasSchema.Reason.UNKNOWN_PROPERTY,
            path = "/args/extras/opts/nope",
        )
    }

    @Test
    fun `ES13-extras that are not an object are rejected`() {
        assertInvalid(
            ExtrasSchema.validate(objectSchema(), JsonPrimitive("nope")),
            ExtrasSchema.Reason.NOT_AN_OBJECT,
        )
    }

    // ── fail-closed schema shapes ────────────────────────────────────────

    @Test
    fun `ES14-unsupported keywords fail closed at any depth`() {
        assertInvalid(
            ExtrasSchema.validate(
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("oneOf", buildJsonArray { })
                },
                JsonObject(emptyMap()),
            ),
            ExtrasSchema.Reason.SCHEMA_UNSUPPORTED,
        )
        assertInvalid(
            ExtrasSchema.validate(
                objectSchema(properties = mapOf("p" to stringSchema("pattern" to JsonPrimitive("^a$")))),
                buildJsonObject { put("p", JsonPrimitive("b")) },
            ),
            ExtrasSchema.Reason.SCHEMA_UNSUPPORTED,
            path = "/args/extras/p",
        )
        assertInvalid(
            ExtrasSchema.validate(
                objectSchema(properties = mapOf("t" to stringSchema("format" to JsonPrimitive("date-time")))),
                JsonObject(emptyMap()),
            ),
            ExtrasSchema.Reason.SCHEMA_UNSUPPORTED,
            path = "/args/extras/t",
        )
    }

    @Test
    fun `ES15-malformed schema shapes fail closed`() {
        // No `type` at all, and a `type` that is not a string, respectively.
        assertInvalid(
            ExtrasSchema.validate(JsonObject(emptyMap()), JsonObject(emptyMap())),
            ExtrasSchema.Reason.SCHEMA_UNSUPPORTED,
        )
        assertInvalid(
            ExtrasSchema.validate(
                buildJsonObject { put("type", JsonPrimitive(7)) },
                JsonObject(emptyMap()),
            ),
            ExtrasSchema.Reason.SCHEMA_UNSUPPORTED,
        )
    }

    @Test
    fun `ES16-schema-valued additionalProperties and tuple items fail closed`() {
        assertInvalid(
            ExtrasSchema.validate(
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("additionalProperties", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                JsonObject(emptyMap()),
            ),
            ExtrasSchema.Reason.SCHEMA_UNSUPPORTED,
        )
        assertInvalid(
            ExtrasSchema.validate(
                buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", buildJsonArray { add(buildJsonObject { put("type", JsonPrimitive("string")) }) })
                },
                JsonObject(emptyMap()),
            ),
            ExtrasSchema.Reason.SCHEMA_UNSUPPORTED,
        )
    }
}
