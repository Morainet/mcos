package com.mcos.runtime.parse

import com.mcos.runtime.ir.ExecutionIr
import com.mcos.runtime.ir.IrInvoke
import com.mcos.runtime.ir.IrSequence
import com.mcos.runtime.ir.ParseResult
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Conformance tests for DslParser v0.1.
 * Verifies all golden fixtures under docs/fixtures/.
 * Matches [02-command-protocol.md §16].
 */
class DslParserTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    // ═══════════════════════════════════════════════════════════════
    // Positive tests (round-trip DSL → IR)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `01-empty-args — header + empty args`() {
        val input = "# mcos-dsl: 0.1\ncamera.capture()"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Invoke
        assertEquals("camera.capture", ir.invoke.id)
        assertEquals("0.1", ir.invoke.dslVersion)
        assertTrue(ir.invoke.args.isEmpty())
    }

    @Test
    fun `01-empty-args — no header, just empty invocation`() {
        val input = "camera.capture()"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Invoke
        assertEquals("camera.capture", ir.invoke.id)
        assertEquals("0.1", ir.invoke.dslVersion)
        assertTrue(ir.invoke.args.isEmpty())
    }

    @Test
    fun `02-named-string — single named string arg`() {
        val input = """hello.world(name="Tom")"""
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Invoke
        assertEquals("hello.world", ir.invoke.id)
        assertEquals("Tom", ir.invoke.args["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `03-array-and-int — int + string array with keys sorted`() {
        val input = """photo.compress(quality=80, uris=["content://1", "content://2"])"""
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Invoke
        assertEquals("photo.compress", ir.invoke.id)
        assertEquals(80, ir.invoke.args["quality"]?.jsonPrimitive?.long)
        val uris = ir.invoke.args["uris"]?.jsonArray
        assertEquals(2, uris?.size)
        assertEquals("content://1", uris?.get(0)?.jsonPrimitive?.content)
        assertEquals("content://2", uris?.get(1)?.jsonPrimitive?.content)
        // Keys must be sorted lexicographically
        val keys = ir.invoke.args.keys.toList()
        assertEquals(listOf("quality", "uris"), keys)
    }

    @Test
    fun `04-sequence — multi-statement with comment`() {
        val input = "# comment\ncamera.capture()\nphoto.compress(quality=80)"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Sequence
        assertEquals(2, ir.sequence.steps.size)
        assertEquals("camera.capture", ir.sequence.steps[0].id)
        assertEquals("photo.compress", ir.sequence.steps[1].id)
        assertEquals(80, ir.sequence.steps[1].args["quality"]?.jsonPrimitive?.long)
    }

    @Test
    fun `05-mixed-literals — bool, float, null, string — keys sorted`() {
        val input = """home.light.set(id="living-room", on=true, brightness=0.8, meta=null)"""
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Invoke
        assertEquals("home.light.set", ir.invoke.id)
        assertEquals("living-room", ir.invoke.args["id"]?.jsonPrimitive?.content)
        assertEquals(true, ir.invoke.args["on"]?.jsonPrimitive?.boolean)
        assertEquals(0.8, ir.invoke.args["brightness"]?.jsonPrimitive?.double)
        assertIs<JsonNull>(ir.invoke.args["meta"])
        // Keys must be sorted: brightness, id, meta, on
        val keys = ir.invoke.args.keys.toList()
        assertEquals(listOf("brightness", "id", "meta", "on"), keys)
    }

    // ═══════════════════════════════════════════════════════════════
    // Negative tests (must reject)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `06-nested-call — reject nested invocation`() {
        val input = """mail.send(to="Tom", body=photo.compress())"""
        val result = DslParser.parse(input)
        assertIs<ParseResult.Err>(result)
        assertEquals("PARSE_ERROR", result.code)
        assertTrue(result.message!!.contains("Nested"))
        // Column: 'p' in photo.compress is at column 26
        assertTrue(result.column == 26 || result.column == 27) // ±1 tolerance per §6.7 rule 6
        assertEquals(1, result.line)
        assertEquals("nested_call", result.reason)
    }

    @Test
    fun `07-positional-arg — reject positional args`() {
        val input = """camera.capture("front")"""
        val result = DslParser.parse(input)
        assertIs<ParseResult.Err>(result)
        assertEquals("PARSE_ERROR", result.code)
        assertTrue(result.message!!.contains("Positional"))
        assertEquals(1, result.line)
        assertEquals(16, result.column)
        assertEquals("positional_arg", result.reason)
    }

    @Test
    fun `08-malformed — unclosed parenthesis`() {
        val input = "camera.capture(quality=80"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Err>(result)
        assertEquals("PARSE_ERROR", result.code)
        assertTrue(result.message!!.contains("Unterminated"))
        assertEquals(1, result.line)
        assertEquals("unterminated_invocation", result.reason)
    }

    // ═══════════════════════════════════════════════════════════════
    // Additional suggested tests (from §16.1)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S1 — int overflow`() {
        val input = "a(n=99999999999999999999)"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Err>(result)
        assertEquals("PARSE_ERROR", result.code)
        assertEquals("int_overflow", result.reason)
    }

    @Test
    fun `S3 — empty script`() {
        val input = "# mcos-dsl: 0.1\n# only a comment"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Err>(result)
        assertEquals("PARSE_ERROR", result.code)
        assertEquals("empty_script", result.reason)
    }

    @Test
    fun `S4 — unsupported version`() {
        val input = "# mcos-dsl: 0.2\na()"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Err>(result)
        assertEquals("PARSE_ERROR", result.code)
        assertEquals("unsupported_version", result.reason)
    }

    @Test
    fun `S5 — non-ASCII CJK arg`() {
        val input = """a(name="王小明")"""
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Invoke
        assertEquals("王小明", ir.invoke.args["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `S6 — mixed-case command ID is lowercased`() {
        val input = "A.B()"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Invoke
        assertEquals("a.b", ir.invoke.id)
    }

    @Test
    fun `S7 — leading zero rejected`() {
        val input = "a(x=007)"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Err>(result)
        assertEquals("PARSE_ERROR", result.code)
        assertEquals("leading_zero", result.reason)
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON round-trip tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `IR to JSON and back`() {
        val input = """hello.world(name="Tom")"""
        val parsed = DslParser.parse(input)
        assertIs<ParseResult.Ok>(parsed)
        val jsonStr = DslParser.toJson(parsed.ir)
        val deserialized = DslParser.fromJson(jsonStr)
        val reSerialized = DslParser.toJson(deserialized)
        assertEquals(parseJson(jsonStr), parseJson(reSerialized))
    }

    @Test
    fun `sequence IR to JSON and back`() {
        val input = "# comment\ncamera.capture()\nphoto.compress(quality=80)"
        val parsed = DslParser.parse(input)
        assertIs<ParseResult.Ok>(parsed)
        val jsonStr = DslParser.toJson(parsed.ir)
        val deserialized = DslParser.fromJson(jsonStr)
        assertIs<ExecutionIr.Sequence>(deserialized)
        assertEquals(2, (deserialized as ExecutionIr.Sequence).sequence.steps.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // Canonicalization tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `canonicalization — keys sorted regardless of input order`() {
        // Input: args in reverse order
        val input1 = """f(quality=80, uris=["a"])"""
        val input2 = """f(uris=["a"], quality=80)"""
        val ir1 = DslParser.toJson((DslParser.parse(input1) as ParseResult.Ok).ir)
        val ir2 = DslParser.toJson((DslParser.parse(input2) as ParseResult.Ok).ir)
        assertEquals(ir1, ir2)
    }

    @Test
    fun `canonicalization — zero vs negative zero`() {
        val input = "a(x=0)"
        val result = DslParser.parse(input)
        assertIs<ParseResult.Ok>(result)
        val ir = result.ir as ExecutionIr.Invoke
        assertEquals(0L, ir.invoke.args["x"]?.jsonPrimitive?.long)
    }

    // Helper
    private fun parseJson(s: String): JsonElement = json.parseToJsonElement(s)
}
