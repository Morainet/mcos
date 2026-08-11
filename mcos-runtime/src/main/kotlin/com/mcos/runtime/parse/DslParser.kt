package com.mcos.runtime.parse

import com.mcos.runtime.ir.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Main entry point for DSL parsing.
 * Converts DSL text to ExecutionIr, applying canonicalization.
 * Matches [02-command-protocol.md 6] and the golden test fixtures.
 *
 * Usage:
 * ```kotlin
 * val result = DslParser.parse("camera.capture()")
 * when (result) {
 *     is ParseResult.Ok -> println("IR: ${DslParser.toJson(result.ir)}")
 *     is ParseResult.Err -> println("Error: ${result.message}")
 * }
 * ```
 */
object DslParser {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Parse DSL text into ExecutionIr. Applies canonicalization.
     *
     * @param input Raw DSL text
     * @return ParseResult.Ok with canonicalized IR, or ParseResult.Err with error details
     */
    fun parse(input: String): ParseResult {
        // Size pre-check
        if (input.toByteArray(Charsets.UTF_8).size > Lexer.MAX_INPUT_BYTES) {
            return ParseResult.Err(
                code = "PARSE_ERROR",
                message = "Input exceeds max size of ${Lexer.MAX_INPUT_BYTES} bytes",
                reason = "size_limit"
            )
        }

        // Tokenize
        val lexer = Lexer(input)
        val tokens = try {
            lexer.tokenize()
        } catch (e: Exception) {
            return ParseResult.Err(
                code = "PARSE_ERROR",
                message = "Lexer error: ${e.message}",
                reason = "lexer_error"
            )
        }

        // Parse
        val parser = Parser(tokens)
        return try {
            val result = parser.parse()
            if (result is ParseResult.Ok) {
                ParseResult.Ok(Canonicalizer.canonicalize(result.ir))
            } else {
                result
            }
        } catch (e: ParseException) {
            e.result
        } catch (e: Exception) {
            ParseResult.Err(
                code = "PARSE_ERROR",
                message = "Parser error: ${e.message}",
                reason = "internal_error"
            )
        }
    }

    /**
     * Serialize ExecutionIr to canonical JSON string.
     */
    fun toJson(ir: ExecutionIr): String {
        return when (ir) {
            is ExecutionIr.Invoke -> json.encodeToString(IrInvoke.serializer(), ir.invoke)
            is ExecutionIr.Sequence -> json.encodeToString(IrSequence.serializer(), ir.sequence)
            is ExecutionIr.Workflow -> json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                ir.body
            )
        }
    }

    /**
     * Deserialize JSON string to ExecutionIr.
     *
     * This is the contract-style entry: input is assumed to be valid IR JSON,
     * so malformed input throws. Callers that prefer the error-channel style
     * of [parse] should use [tryFromJson].
     */
    fun fromJson(jsonString: String): ExecutionIr {
        val element = json.parseToJsonElement(jsonString).jsonObject
        return when (element["type"]?.jsonPrimitive?.content) {
            "invoke" -> {
                val invoke = json.decodeFromJsonElement(IrInvoke.serializer(), element)
                ExecutionIr.Invoke(invoke)
            }
            "sequence" -> {
                val sequence = json.decodeFromJsonElement(IrSequence.serializer(), element)
                ExecutionIr.Sequence(sequence)
            }
            "workflow" -> ExecutionIr.Workflow(element)
            else -> throw IllegalArgumentException(
                "Unknown IR type: ${element["type"]} — expected one of: invoke, sequence, workflow"
            )
        }
    }

    /**
     * Deserialize JSON string to ExecutionIr via the [ParseResult] error
     * channel, mirroring [parse]'s style.
     */
    fun tryFromJson(jsonString: String): ParseResult {
        return try {
            ParseResult.Ok(fromJson(jsonString))
        } catch (e: Exception) {
            ParseResult.Err(
                code = "PARSE_ERROR",
                message = "Invalid IR JSON: ${e.message}",
                reason = "invalid_ir_json"
            )
        }
    }
}
