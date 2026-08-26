package com.morainet.mcos.plugin.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * McpSchemaConverter — MCP JSON Schema -> MCOS inputSchema, per 02 §12.4.
 * SC1-SC24: one per conversion-table row plus the fail-closed contract.
 */
class McpSchemaConverterTest {

    private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun converted(json: String): JsonObject {
        return when (val r = McpSchemaConverter.convert(schema(json))) {
            is McpSchemaConverter.Result.Converted -> r.inputSchema
            is McpSchemaConverter.Result.Unmapped ->
                fail("expected Converted, got Unmapped(${r.unmappedType}, ${r.reason}, ${r.path})")
        }
    }

    private fun unmapped(json: String): McpSchemaConverter.Result.Unmapped {
        return when (val r = McpSchemaConverter.convert(schema(json))) {
            is McpSchemaConverter.Result.Unmapped -> r
            is McpSchemaConverter.Result.Converted ->
                fail("expected Unmapped, got Converted($r)")
        }
    }

    private fun JsonObject.prop(name: String): JsonObject =
        this["properties"]!!.jsonObject[name]!!.jsonObject

    // ─── Positive: conversion-table rows ──────────────────────────────────

    @Test fun `SC1 plain string maps to string and gets the 65536 cap`() {
        val out = converted("""{"type":"string"}""")
        assertEquals("string", out["type"]?.jsonPrimitive?.content)
        assertEquals(65536L, out["maxLength"]?.jsonPrimitive?.longOrNull)
    }

    @Test fun `SC2 declared maxLength only tightens the string cap`() {
        val out = converted("""{"type":"string","maxLength":32}""")
        assertEquals(32L, out["maxLength"]?.jsonPrimitive?.longOrNull)
    }

    @Test fun `SC3 oversized declared maxLength is clamped to 65536`() {
        val out = converted("""{"type":"string","maxLength":9999999}""")
        assertEquals(65536L, out["maxLength"]?.jsonPrimitive?.longOrNull)
    }

    @Test fun `SC4 date-time format is preserved as string with format`() {
        val out = converted("""{"type":"string","format":"date-time"}""")
        assertEquals("string", out["type"]?.jsonPrimitive?.content)
        assertEquals("date-time", out["format"]?.jsonPrimitive?.content)
    }

    @Test fun `SC5 byte format caps on the base64 length`() {
        val out = converted("""{"type":"string","format":"byte"}""")
        assertEquals("string", out["type"]?.jsonPrimitive?.content)
        assertEquals("byte", out["format"]?.jsonPrimitive?.content)
        assertEquals(
            McpSchemaConverter.BYTES_BASE64_MAX_LENGTH.toLong(),
            out["maxLength"]?.jsonPrimitive?.longOrNull,
        )
    }

    @Test fun `SC6 uri format preserved`() {
        val out = converted("""{"type":"string","format":"uri"}""")
        assertEquals("uri", out["format"]?.jsonPrimitive?.content)
    }

    @Test fun `SC7 duration format preserved`() {
        val out = converted("""{"type":"string","format":"duration"}""")
        assertEquals("duration", out["format"]?.jsonPrimitive?.content)
    }

    @Test fun `SC8 string enum is carried through`() {
        val out = converted("""{"type":"string","enum":["a","b","c"]}""")
        assertEquals("string", out["type"]?.jsonPrimitive?.content)
        assertEquals(3, out["enum"]?.jsonArray?.size)
    }

    @Test fun `SC9 integer maps to integer with range passthrough`() {
        val out = converted("""{"type":"integer","minimum":0,"maximum":10}""")
        assertEquals("integer", out["type"]?.jsonPrimitive?.content)
        assertEquals(0L, out["minimum"]?.jsonPrimitive?.longOrNull)
        assertEquals(10L, out["maximum"]?.jsonPrimitive?.longOrNull)
    }

    @Test fun `SC10 number maps to number`() {
        val out = converted("""{"type":"number"}""")
        assertEquals("number", out["type"]?.jsonPrimitive?.content)
    }

    @Test fun `SC11 boolean maps to boolean`() {
        val out = converted("""{"type":"boolean"}""")
        assertEquals("boolean", out["type"]?.jsonPrimitive?.content)
    }

    @Test fun `SC12 array recurses into items`() {
        val out = converted("""{"type":"array","items":{"type":"integer"}}""")
        assertEquals("array", out["type"]?.jsonPrimitive?.content)
        assertEquals("integer", out["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test fun `SC13 object recurses per property and carries required`() {
        val out = converted(
            """{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}},"required":["name"]}"""
        )
        assertEquals("object", out["type"]?.jsonPrimitive?.content)
        assertEquals("string", out.prop("name")["type"]?.jsonPrimitive?.content)
        assertEquals("integer", out.prop("age")["type"]?.jsonPrimitive?.content)
        assertEquals("name", out["required"]?.jsonArray?.first()?.jsonPrimitive?.content)
    }

    @Test fun `SC14 const null maps to null type`() {
        val out = converted("""{"const":null}""")
        assertEquals("null", out["type"]?.jsonPrimitive?.content)
    }

    @Test fun `SC15 non-null const becomes a single-value enum`() {
        val out = converted("""{"const":"fixed"}""")
        assertEquals("string", out["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("fixed"), out["enum"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test fun `SC16 typeless enum infers a homogeneous member type`() {
        val out = converted("""{"enum":[1,2,3]}""")
        assertEquals("integer", out["type"]?.jsonPrimitive?.content)
    }

    @Test fun `SC17 mixed typeless enum falls back to string`() {
        val out = converted("""{"enum":["a",1,true]}""")
        assertEquals("string", out["type"]?.jsonPrimitive?.content)
    }

    @Test fun `SC18 local ref into defs is resolved`() {
        val out = converted(
            """{"type":"object","properties":{"p":{"${'$'}ref":"#/${'$'}defs/Point"}},"${'$'}defs":{"Point":{"type":"integer"}}}"""
        )
        assertEquals("integer", out.prop("p")["type"]?.jsonPrimitive?.content)
    }

    @Test fun `SC19 local ref into definitions is resolved`() {
        val out = converted(
            """{"type":"object","properties":{"p":{"${'$'}ref":"#/definitions/T"}},"definitions":{"T":{"type":"boolean"}}}"""
        )
        assertEquals("boolean", out.prop("p")["type"]?.jsonPrimitive?.content)
    }

    @Test fun `SC20 description is copied verbatim`() {
        val out = converted("""{"type":"string","description":"a path"}""")
        assertEquals("a path", out["description"]?.jsonPrimitive?.content)
    }

    // ─── Negative: fail-closed contract (§12.4) ───────────────────────────

    @Test fun `SC21 oneOf is unmapped`() {
        val r = unmapped("""{"oneOf":[{"type":"string"},{"type":"integer"}]}""")
        assertEquals("oneOf", r.unmappedType)
    }

    @Test fun `SC22 anyOf is unmapped`() {
        val r = unmapped("""{"anyOf":[{"type":"string"},{"type":"integer"}]}""")
        assertEquals("anyOf", r.unmappedType)
    }

    @Test fun `SC23 patternProperties is unmapped`() {
        val r = unmapped("""{"type":"object","patternProperties":{"^x":{"type":"string"}}}""")
        assertEquals("patternProperties", r.unmappedType)
    }

    @Test fun `SC24 unknown string format is unmapped and located`() {
        val r = unmapped(
            """{"type":"object","properties":{"email":{"type":"string","format":"email"}}}"""
        )
        assertEquals("format:email", r.unmappedType)
        assertEquals("/properties/email", r.path)
    }

    @Test fun `SC25 a nested unmapped keyword rejects the whole tool`() {
        // A single unmappable leaf must fail the entire conversion — no
        // partial schema with the bad field silently dropped.
        val r = unmapped(
            """{"type":"object","properties":{"ok":{"type":"string"},"bad":{"oneOf":[{"type":"string"}]}}}"""
        )
        assertEquals("oneOf", r.unmappedType)
        assertEquals("/properties/bad", r.path)
    }

    @Test fun `SC26 unresolvable ref is unmapped`() {
        val r = unmapped("""{"${'$'}ref":"#/${'$'}defs/Missing"}""")
        assertEquals("\$ref", r.unmappedType)
    }

    @Test fun `SC27 tuple-form array items are unmapped`() {
        val r = unmapped("""{"type":"array","items":[{"type":"string"},{"type":"integer"}]}""")
        assertEquals("items[]", r.unmappedType)
    }

    @Test fun `SC28 object with no properties converts to a bare object`() {
        val out = converted("""{"type":"object"}""")
        assertEquals("object", out["type"]?.jsonPrimitive?.content)
        assertNull(out["properties"])
    }
}
