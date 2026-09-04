package com.morainet.mcos.conformance.dsl

import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.conformance.api.ConformanceSuite
import com.morainet.mcos.conformance.fixture.Fixture
import com.morainet.mcos.conformance.fixture.FixtureDiscovery
import com.morainet.mcos.conformance.fixture.FixtureType
import com.morainet.mcos.runtime.core.ir.ParseResult
import com.morainet.mcos.runtime.core.parse.DslParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * DSL ↔ IR conformance (spec 02 §16, 09 §5.1 gates 1/2/3/5/7).
 *
 * Walks `docs/fixtures/<case-id>/` and runs each fixture against
 * [DslParser]. Positive cases must round-trip to the exact expected IR
 * (canonical form, lexicographically sorted keys, `dslVersion` defaults
 * applied); negative cases must be rejected with the matching error
 * envelope.
 *
 * The on-disk fixture layout is the single source of truth
 * (see `docs/fixtures/README.md`) — adding a fixture under that
 * directory lights it up here with zero code change.
 */
class DslConformanceSuite(
    private val fixturesRoot: File = File(FixtureDiscovery.DEFAULT_ROOT),
) : ConformanceSuite {
    override val id = "dsl"
    override val title = "DSL ↔ IR (golden fixtures)"
    override val spec = "02 §16 + 09 §5.1 gates 1/2/3/5/7"

    override fun cases(): List<ConformanceCase> {
        val fixtures = FixtureDiscovery.discover(fixturesRoot)
        if (fixtures.isEmpty()) {
            // A missing fixtures directory is a setup bug — surface as a Skip
            // rather than silently pass. Plugin authors who point the CLI at
            // the wrong directory see "skipped" and the runner exits non-zero.
            return listOf(
                fixtureMissingCase(fixturesRoot),
            )
        }
        return fixtures.map { fixture ->
            when (fixture.type) {
                FixtureType.POSITIVE -> positiveCase(fixture)
                FixtureType.NEGATIVE -> negativeCase(fixture)
            }
        }
    }

    private fun fixtureMissingCase(root: File): ConformanceCase = object : ConformanceCase {
        override val id = "dsl-fixtures-missing"
        override val title = "fixtures directory present"
        override val spec = "02 §16"
        override val category = "dsl-positive"
        override fun run(): ConformanceCase.Result = ConformanceCase.Result.Skip(
            reason = "no fixtures discovered under ${root.absolutePath}; " +
                "point --fixtures-root at the directory containing input.dsl / expected.*.json",
        )
    }

    private fun positiveCase(fixture: Fixture): ConformanceCase = object : ConformanceCase {
        override val id = "dsl-positive-${fixture.caseId}"
        override val title = "round-trip ${fixture.caseId}"
        override val spec = "02 §16"
        override val category = "dsl-positive"

        override fun run(): ConformanceCase.Result {
            val input = readDslInput(fixture)
            return when (val parsed = DslParser.parse(input)) {
                is ParseResult.Ok -> {
                    val actualJson = DslParser.toJson(parsed.ir)
                    val expectedJson = fixture.expectedPath.readText()
                    compareCanonicalJson(actualJson, expectedJson)
                }
                is ParseResult.Err -> ConformanceCase.Result.Fail(
                    message = "positive case rejected by parser: ${parsed.code}: ${parsed.message}",
                    detail = "expected ok=true, got ${parsed.code}",
                )
            }
        }
    }

    private fun negativeCase(fixture: Fixture): ConformanceCase = object : ConformanceCase {
        override val id = "dsl-negative-${fixture.caseId}"
        override val title = "reject ${fixture.caseId}"
        override val spec = "02 §18"
        override val category = "dsl-negative"

        override fun run(): ConformanceCase.Result {
            val input = readDslInput(fixture)
            return when (val parsed = DslParser.parse(input)) {
                is ParseResult.Err -> compareErrorEnvelope(parsed, fixture.expectedPath)
                is ParseResult.Ok -> ConformanceCase.Result.Fail(
                    message = "negative case parsed successfully — parser must reject",
                    detail = "expected error envelope, got IR: ${DslParser.toJson(parsed.ir).take(200)}",
                )
            }
        }
    }

    /**
     * The trailing line terminator on `input.dsl` is a text-file convention,
     * not DSL content — end-of-input errors point one past the last
     * character of the DSL TEXT (02 §16), so the file's final newline is
     * stripped before parsing.
     */
    private fun readDslInput(fixture: Fixture): String =
        fixture.inputPath.readText().trimEnd('\r', '\n')

    private fun compareErrorEnvelope(actual: ParseResult.Err, expectedPath: File): ConformanceCase.Result {
        val expected = try {
            Json.parseToJsonElement(expectedPath.readText()).jsonObject
        } catch (e: Exception) {
            return ConformanceCase.Result.Fail(
                message = "expected.error.json is not a JSON object: ${e.message}",
                detail = expectedPath.absolutePath,
            )
        }
        val okFlag = (expected["ok"] as? JsonPrimitive)?.content?.toBoolean()
        if (okFlag != false) {
            return ConformanceCase.Result.Fail(
                message = "expected.error.json 'ok' must be false",
                detail = expectedPath.absolutePath,
            )
        }
        val error = expected["error"]?.jsonObject
            ?: return ConformanceCase.Result.Fail(
                message = "expected.error.json missing 'error' object",
                detail = expectedPath.absolutePath,
            )
        val expectedCode = (error["code"] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
        val expectedMessage = (error["message"] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
        val location = error["location"]?.jsonObject
        val expectedLine = (location?.get("line") as? JsonPrimitive)?.content?.toIntOrNull()
        val expectedColumn = (location?.get("column") as? JsonPrimitive)?.content?.toIntOrNull()

        val mismatches = buildList {
            if (expectedCode != null && actual.code != expectedCode) {
                add("code: expected '$expectedCode', got '${actual.code}'")
            }
            if (expectedMessage != null && actual.message != expectedMessage) {
                add("message: expected '$expectedMessage', got '${actual.message}'")
            }
            if (expectedLine != null && actual.line != expectedLine) {
                add("line: expected $expectedLine, got ${actual.line}")
            }
            if (expectedColumn != null && actual.column != expectedColumn) {
                add("column: expected $expectedColumn, got ${actual.column}")
            }
        }
        return if (mismatches.isEmpty()) ConformanceCase.Result.Pass else ConformanceCase.Result.Fail(
            message = mismatches.joinToString("; "),
            detail = "expected.code=${expectedCode}, actual.code=${actual.code}\n" +
                "expected.message=${expectedMessage}\nactual.message=${actual.message}",
        )
    }

    private fun compareCanonicalJson(actual: String, expected: String): ConformanceCase.Result {
        val actualEl = try {
            Json.parseToJsonElement(actual)
        } catch (e: Exception) {
            return ConformanceCase.Result.Fail(
                message = "actual IR did not parse: ${e.message}",
                detail = actual,
            )
        }
        val expectedEl = try {
            Json.parseToJsonElement(expected)
        } catch (e: Exception) {
            return ConformanceCase.Result.Fail(
                message = "expected.ir.json did not parse: ${e.message}",
                detail = expected,
            )
        }
        return if (canonical(actualEl) == canonical(expectedEl)) {
            ConformanceCase.Result.Pass
        } else {
            ConformanceCase.Result.Fail(
                message = "IR mismatch",
                detail = "expected:\n${Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), expectedEl)}\n\n" +
                    "actual:\n${Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), actualEl)}",
            )
        }
    }

    /**
     * Canonicalise: drop null-valued optional fields, sort object keys.
     * This is a structural equality check, not a textual one — the parser
     * may add fields (e.g. `dslVersion` defaults) but the canonical form
     * must match.
     */
    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .filter { (_, v) -> !isNullish(v) }
                .map { (k, v) -> k to canonical(v) }
                .sortedBy { it.first }
                .toMap(),
        )
        is JsonArray -> JsonArray(element.map { canonical(it) })
        else -> element
    }

    /**
     * A "nullish" value is either the JSON literal `null` (a JsonPrimitive
     * whose content is the four-character string "null") or an empty
     * JsonObject. Both can appear as encoder artifacts for defaulted
     * nullable fields and are dropped before structural comparison.
     */
    private fun isNullish(v: JsonElement): Boolean = when (v) {
        is JsonPrimitive -> v.content == "null"
        is JsonObject -> v.isEmpty()
        is JsonArray -> v.isEmpty()
    }
}