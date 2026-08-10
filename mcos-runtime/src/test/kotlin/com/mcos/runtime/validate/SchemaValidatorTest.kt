package com.mcos.runtime.validate

import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Conformance tests for SchemaValidator v0.1.
 * Matches [03-runtime.md §5], [02-command-protocol.md §5].
 */
class SchemaValidatorTest {

    private val validator = SchemaValidator()

    // ═══════════════════════════════════════════════════════════════
    // V1-V3: Type validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V1-valid args pass validation`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("age"))
            })
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                })
                put("age", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                })
            })
        }
        val args = buildJsonObject {
            put("name", JsonPrimitive("Alice"))
            put("age", JsonPrimitive(30))
        }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Valid>(result)
    }

    @Test
    fun `V2-missing required field fails`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("email"))
            })
            put("properties", buildJsonObject {
                put("name", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("email", buildJsonObject { put("type", JsonPrimitive("string")) })
            })
        }
        val args = buildJsonObject {
            put("name", JsonPrimitive("Bob"))
            // email is missing
        }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals(1, result.errors.size)
        assertEquals("/email", result.errors[0].path)
        assertEquals("required", result.errors[0].expected)
        assertEquals("missing", result.errors[0].actual)
    }

    @Test
    fun `V3-wrong type fails`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("count", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                })
            })
        }
        val args = buildJsonObject {
            put("count", JsonPrimitive("not-a-number"))
        }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals(1, result.errors.size)
        assertEquals("/count", result.errors[0].path)
        assertEquals("integer", result.errors[0].expected)
        assertEquals("string", result.errors[0].actual)
    }

    // ═══════════════════════════════════════════════════════════════
    // V4-V6: String constraints
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V4-minLength violation fails`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("minLength", JsonPrimitive(3))
                })
            })
        }
        val args = buildJsonObject { put("name", JsonPrimitive("ab")) }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals("minLength 3", result.errors[0].expected)
        assertEquals("length 2", result.errors[0].actual)
    }

    @Test
    fun `V5-maxLength violation fails`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("code", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("maxLength", JsonPrimitive(5))
                })
            })
        }
        val args = buildJsonObject { put("code", JsonPrimitive("123456")) }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals("maxLength 5", result.errors[0].expected)
    }

    @Test
    fun `V6-string with valid minLength and maxLength passes`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("code", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("minLength", JsonPrimitive(3))
                    put("maxLength", JsonPrimitive(10))
                })
            })
        }
        val args = buildJsonObject { put("code", JsonPrimitive("abcde")) }

        assertIs<ValidationResult.Valid>(validator.validate(args, schema))
    }

    // ═══════════════════════════════════════════════════════════════
    // V7-V9: Numeric constraints
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V7-minimum violation fails`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("age", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("minimum", JsonPrimitive(18))
                })
            })
        }
        val args = buildJsonObject { put("age", JsonPrimitive(15)) }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals("minimum 18.0", result.errors[0].expected)
        assertEquals("15.0", result.errors[0].actual)
    }

    @Test
    fun `V8-maximum violation fails`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("temperature", buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("maximum", JsonPrimitive(100))
                })
            })
        }
        val args = buildJsonObject { put("temperature", JsonPrimitive(150.5)) }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals("maximum 100.0", result.errors[0].expected)
    }

    @Test
    fun `V9-number within range passes`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("score", buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("minimum", JsonPrimitive(0))
                    put("maximum", JsonPrimitive(100))
                })
            })
        }
        val args = buildJsonObject { put("score", JsonPrimitive(85)) }

        assertIs<ValidationResult.Valid>(validator.validate(args, schema))
    }

    // ═══════════════════════════════════════════════════════════════
    // V10-V11: Enum validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V10-enum valid value passes`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("color", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("red"))
                        add(JsonPrimitive("green"))
                        add(JsonPrimitive("blue"))
                    })
                })
            })
        }
        val args = buildJsonObject { put("color", JsonPrimitive("blue")) }
        assertIs<ValidationResult.Valid>(validator.validate(args, schema))
    }

    @Test
    fun `V11-enum invalid value fails`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("color", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("red"))
                        add(JsonPrimitive("green"))
                        add(JsonPrimitive("blue"))
                    })
                })
            })
        }
        val args = buildJsonObject { put("color", JsonPrimitive("purple")) }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertTrue(result.errors[0].expected.startsWith("one of"))
    }

    // ═══════════════════════════════════════════════════════════════
    // V12-V13: Array validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V12-array items validated against items schema`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("tags", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("minLength", JsonPrimitive(1))
                    })
                })
            })
        }
        val args = buildJsonObject {
            put("tags", buildJsonArray {
                add(JsonPrimitive("android"))
                add(JsonPrimitive("")) // empty string → minLength violated
                add(JsonPrimitive("kotlin"))
            })
        }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals("/tags/1", result.errors[0].path)
    }

    @Test
    fun `V13-array with valid items passes`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("ids", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                    })
                })
            })
        }
        val args = buildJsonObject {
            put("ids", buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(2))
                add(JsonPrimitive(3))
            })
        }

        assertIs<ValidationResult.Valid>(validator.validate(args, schema))
    }

    // ═══════════════════════════════════════════════════════════════
    // V14-V16: Edge cases
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V14-empty schema passes everything`() {
        val schema = buildJsonObject { }
        val args = buildJsonObject {
            put("anything", JsonPrimitive("goes"))
        }
        assertIs<ValidationResult.Valid>(validator.validate(args, schema))
    }

    @Test
    fun `V15-multiple errors collected`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("age"))
            })
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("minLength", JsonPrimitive(3))
                })
                put("age", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("minimum", JsonPrimitive(0))
                })
            })
        }
        val args = buildJsonObject {
            put("name", JsonPrimitive("ab"))        // → minLength violated
            put("age", JsonPrimitive(-5))           // → minimum violated
            // email is missing                     // → required violated
        }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertTrue(result.errors.size >= 3) // at least: minLength, minimum, missing email
    }

    @Test
    fun `V16-boolean type validation`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("enabled", buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                })
            })
        }

        // Valid
        assertIs<ValidationResult.Valid>(
            validator.validate(buildJsonObject { put("enabled", JsonPrimitive(true)) }, schema)
        )

        // Invalid
        val result = validator.validate(
            buildJsonObject { put("enabled", JsonPrimitive("yes")) }, schema
        )
        assertIs<ValidationResult.Invalid>(result)
        assertEquals("boolean", result.errors[0].expected)
    }

    // ═══════════════════════════════════════════════════════════════
    // V17-V18: Real-world command schemas
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V17-camera capture schema — valid args`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("flash")) })
            put("properties", buildJsonObject {
                put("flash", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("auto"))
                        add(JsonPrimitive("on"))
                        add(JsonPrimitive("off"))
                    })
                })
                put("quality", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("minimum", JsonPrimitive(1))
                    put("maximum", JsonPrimitive(100))
                })
            })
        }
        val args = buildJsonObject {
            put("flash", JsonPrimitive("auto"))
            put("quality", JsonPrimitive(90))
        }

        assertIs<ValidationResult.Valid>(validator.validate(args, schema))
    }

    @Test
    fun `V18-camera capture schema — invalid flash value`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("flash")) })
            put("properties", buildJsonObject {
                put("flash", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("auto"))
                        add(JsonPrimitive("on"))
                        add(JsonPrimitive("off"))
                    })
                })
            })
        }
        val args = buildJsonObject { put("flash", JsonPrimitive("strobe")) }

        val result = validator.validate(args, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals("/flash", result.errors[0].path)
    }

    // ═══════════════════════════════════════════════════════════════
    // V19: Nested object validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V19-nested object validation`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("config", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("timeout")) })
                    put("properties", buildJsonObject {
                        put("timeout", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(100))
                        })
                        put("retry", buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                        })
                    })
                })
            })
        }

        // Valid nested
        val validArgs = buildJsonObject {
            put("config", buildJsonObject {
                put("timeout", JsonPrimitive(500))
                put("retry", JsonPrimitive(true))
            })
        }
        assertIs<ValidationResult.Valid>(validator.validate(validArgs, schema))

        // Invalid: missing required timeout
        val invalidArgs = buildJsonObject {
            put("config", buildJsonObject {
                put("retry", JsonPrimitive(false))
            })
        }
        val result = validator.validate(invalidArgs, schema)
        assertIs<ValidationResult.Invalid>(result)
        assertEquals("/config/timeout", result.errors[0].path)
    }
}
