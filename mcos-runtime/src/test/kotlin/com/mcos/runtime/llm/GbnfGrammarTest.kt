package com.mcos.runtime.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [GbnfGrammar]: llama.cpp GBNF generation for CONSTRAINED mode
 * (06 §3.2 V2). The grammar constrains model output to a single IR JSON
 * object whose command ids and args shapes come from the command catalog.
 */
class GbnfGrammarTest {

    // ---- G1/G2: root and per-command rules ---------------------------------

    @Test
    fun `G1-root enumerates cataloged invoke variants and terminal states`() {
        val grammar = GbnfGrammar.buildIrGrammar(twoTools())

        assertContains(grammar, "root ::= ws ( ir-invoke-test_hello | ir-invoke-sys_notify | ir-sequence | ir-clarify | ir-refuse ) ws")
        assertContains(grammar, "ir-invoke-test_hello ::= ")
        assertContains(grammar, "ir-invoke-sys_notify ::= ")
        assertContains(grammar, "ir-sequence ::= ")
        assertContains(grammar, "ir-clarify ::= ")
        assertContains(grammar, "ir-refuse ::= ")
    }

    @Test
    fun `G2-args rules constrain key names and value types from the schema`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(
                ToolDescriptor(
                    command = "test.hello",
                    description = "Say hello",
                    inputSchema = schema(mapOf("greeting" to strSchema())),
                )
            )
        )

        // Command id literal and its args rule.
        assertContains(grammar, "\"test.hello\"")
        assertContains(grammar, "args-test_hello ::= \"{\" ws ( \"greeting\" ws \":\" ws string )* \"}\" ws")
    }

    @Test
    fun `G3-command ids with separators are sanitized to rule names`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(
                ToolDescriptor("camera.capture", "Snap", schema(emptyMap()), emptyList()),
                ToolDescriptor("sys.notify-2", "Notify", schema(emptyMap()), emptyList()),
            )
        )

        assertContains(grammar, "args-camera_capture ::= \"{\" ws \"}\" ws")
        assertContains(grammar, "args-sys_notify_2 ::= \"{\" ws \"}\" ws")
        assertFalse(grammar.contains("args-camera.capture"))
    }

    // ---- G4/G5: scalar constraints ------------------------------------------

    @Test
    fun `G4-string enum is constrained to member literals`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(
                ToolDescriptor(
                    "camera.capture",
                    "Snap",
                    schema(mapOf("flash" to strSchema(listOf("on", "off", "auto")))),
                    emptyList(),
                )
            )
        )

        // JSON string members are matched as quoted literals: "\"on\"" etc.
        assertContains(grammar, "\"flash\" ws \":\" ws (\"\\\"on\\\"\" | \"\\\"off\\\"\" | \"\\\"auto\\\"\") ws")
    }

    @Test
    fun `G5-number integer and boolean map to shared rules`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(
                ToolDescriptor(
                    "timer.set",
                    "Set a timer",
                    schema(
                        mapOf(
                            "seconds" to buildJsonObject { put("type", JsonPrimitive("integer")) },
                            "enabled" to buildJsonObject { put("type", JsonPrimitive("boolean")) },
                        )
                    ),
                    emptyList(),
                )
            )
        )

        assertContains(grammar, "\"seconds\" ws \":\" ws number")
        assertContains(grammar, "\"enabled\" ws \":\" ws boolean")
    }

    // ---- G6/G7: nesting and empty schemas -----------------------------------

    @Test
    fun `G6-nested object and array schemas are expanded inline`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(
                ToolDescriptor(
                    "nav.go",
                    "Navigate",
                    schema(
                        mapOf(
                            "dest" to buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put("properties", buildJsonObject {
                                    put("lat", buildJsonObject { put("type", JsonPrimitive("number")) })
                                    put("lng", buildJsonObject { put("type", JsonPrimitive("number")) })
                                })
                            },
                            "tags" to buildJsonObject {
                                put("type", JsonPrimitive("array"))
                                put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                            },
                        )
                    ),
                    emptyList(),
                )
            )
        )

        assertContains(grammar, "\"dest\" ws \":\" ws \"{\" ws ( \"lat\" ws \":\" ws number | \"lng\" ws \":\" ws number )* \"}\" ws")
        assertContains(grammar, "\"tags\" ws \":\" ws \"[\" ws ( string ( \",\" ws string )* )? \"]\" ws")
    }

    @Test
    fun `G7-empty properties produce an empty object rule`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(ToolDescriptor("test.noargs", "No args", schema(emptyMap()), emptyList()))
        )

        assertContains(grammar, "args-test_noargs ::= \"{\" ws \"}\" ws")
    }

    // ---- G8/G9: degenerate catalog and shared rules --------------------------

    @Test
    fun `G8-empty catalog only allows terminal states`() {
        val grammar = GbnfGrammar.buildIrGrammar(emptyList())

        assertContains(grammar, "root ::= ws ( ir-clarify | ir-refuse ) ws")
        assertFalse(grammar.contains("ir-invoke-"))
        assertFalse(grammar.contains("ir-sequence"))
    }

    @Test
    fun `G9-shared JSON rules are present`() {
        val grammar = GbnfGrammar.buildIrGrammar(twoTools())

        assertContains(grammar, "ws ::= ([ \\t\\n] ws)?")
        assertContains(grammar, "string ::= ")
        assertContains(grammar, "number ::= ")
        assertContains(grammar, "boolean ::= ")
        assertContains(grammar, "value ::= ")
        assertContains(grammar, "object ::= ")
        assertContains(grammar, "array ::= ")
    }

    // ---- G10/G11: enum JSON representation and const -------------------------

    @Test
    fun `G10-enum values use JSON string literals`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(
                ToolDescriptor(
                    "mode.select",
                    "Pick mode",
                    schema(mapOf("mode" to strSchema(listOf("eco", "sport", "snow")))),
                    emptyList(),
                )
            )
        )

        // Each enum member appears as a quoted GBNF literal matching the JSON
        // string: "\"eco\"" etc.
        assertContains(grammar, "(\"\\\"eco\\\"\" | \"\\\"sport\\\"\" | \"\\\"snow\\\"\") ws")
    }

    @Test
    fun `G11-const collapses the value to a single literal`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(
                ToolDescriptor(
                    "test.fixed",
                    "Fixed value",
                    schema(mapOf("level" to buildJsonObject { put("const", JsonPrimitive("high")) })),
                    emptyList(),
                )
            )
        )

        assertContains(grammar, "\"level\" ws \":\" ws \"\\\"high\\\"\" ws")
    }

    // ---- G12: sequence steps reuse step rules --------------------------------

    @Test
    fun `G12-sequence steps are constrained per command`() {
        val grammar = GbnfGrammar.buildIrGrammar(
            listOf(
                ToolDescriptor(
                    "test.hello",
                    "Say hello",
                    schema(mapOf("greeting" to strSchema())),
                    emptyList(),
                )
            )
        )

        assertContains(grammar, "step-test_hello ::= ")
        assertContains(grammar, "\"steps\" ws \":\" ws \"[\" ws ( step-test_hello ( \",\" ws step-test_hello )* )? \"]\" ws")
    }

    // ---- Helpers ----------------------------------------------------------

    private fun twoTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor("test.hello", "Say hello", schema(mapOf("greeting" to strSchema())), emptyList()),
        ToolDescriptor("sys.notify", "Notify", schema(mapOf("message" to strSchema())), emptyList()),
    )

    private fun schema(properties: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            properties.forEach { (name, sub) -> put(name, sub) }
        })
    }

    private fun strSchema(enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        enum?.let {
            put("enum", buildJsonArray { it.forEach { e -> add(JsonPrimitive(e)) } })
        }
    }
}
