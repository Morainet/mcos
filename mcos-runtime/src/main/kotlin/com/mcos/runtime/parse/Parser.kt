package com.mcos.runtime.parse

import com.mcos.runtime.ir.IrInvoke
import com.mcos.runtime.ir.IrSequence
import com.mcos.runtime.ir.ExecutionIr
import com.mcos.runtime.ir.ParseResult
import kotlinx.serialization.json.*

/**
 * Recursive-descent parser for MCOS DSL v0.1.
 * Converts token stream to ExecutionIr AST.
 * Matches [02-command-protocol.md 6, 18].
 */
class Parser(private val tokens: List<Token>) {

    companion object {
        const val MAX_DEPTH = 32
        const val MAX_STATEMENTS = 64
        const val MAX_TOKEN_COUNT = 4096
        private val HEADER_REGEX = Regex("""#\s*mcos-dsl:\s*(\d+\.\d+)\s*""")
    }

    private var current: Int = 0

    // Skip comments when peeking
    private var headerVersion: String? = null

    fun parse(): ParseResult {
        if (tokens.size > MAX_TOKEN_COUNT) {
            return error("token_limit", "Input exceeds maximum token count of $MAX_TOKEN_COUNT")
        }

        // Lexical error pre-check: ERROR tokens (unknown character,
        // unterminated string) surface precise diagnostics instead of
        // generic parse errors.
        tokens.firstOrNull { it.type == TokenType.ERROR }?.let { bad ->
            val message = when {
                bad.lexeme.startsWith("unexpected_character:") ->
                    "Unexpected character '${bad.lexeme.removePrefix("unexpected_character:")}'"
                else -> "Lexical error: ${bad.lexeme}"
            }
            return ParseResult.Err(
                code = "PARSE_ERROR",
                message = message,
                line = bad.line,
                column = bad.column,
                reason = "lexical_error",
                token = bad.lexeme
            )
        }

        // Check for header
        if (current < tokens.size && at(TokenType.HEADER)) {
            val headerText = advance().lexeme
            val match = HEADER_REGEX.find(headerText)
            headerVersion = match?.groupValues?.get(1)
            if (headerVersion != null && headerVersion != "0.1") {
                return error("unsupported_version", "Unsupported DSL version: $headerVersion (expected 0.1)")
            }
        }

        // Skip leading comments
        skipComments()

        // Empty script check
        val firstNonComment = tokens.indexOfFirst {
            it.type != TokenType.COMMENT && it.type != TokenType.HEADER
        }
        if (firstNonComment < 0 || tokens[firstNonComment].type == TokenType.EOF) {
            return error("empty_script", "Script must contain at least one command invocation")
        }

        // Parse statements
        val invocations = mutableListOf<IrInvoke>()
        var statementCount = 0
        while (!isAtEnd()) {
            skipComments()
            if (isAtEnd()) break
            statementCount++
            if (statementCount > MAX_STATEMENTS) {
                return error("statement_limit", "Script exceeds max statement count of $MAX_STATEMENTS")
            }
            val invoke = parseInvoke()
            invocations.add(invoke)
        }

        return if (invocations.size == 1) {
            ParseResult.Ok(ExecutionIr.Invoke(invocations[0]))
        } else {
            ParseResult.Ok(
                ExecutionIr.Sequence(
                    IrSequence(
                        dslVersion = headerVersion ?: "0.1",
                        steps = invocations
                    )
                )
            )
        }
    }

    private fun parseInvoke(): IrInvoke {
        // Parse command ID: ident { "." ident }
        if (!at(TokenType.IDENT)) {
            throw ParseException(error("expected_ident", "Expected command identifier"))
        }
        val cmdParts = mutableListOf<String>()
        cmdParts.add(advance().lexeme.lowercase())
        while (at(TokenType.DOT)) {
            advance() // consume '.'
            if (!at(TokenType.IDENT)) {
                throw ParseException(error("expected_ident", "Expected identifier after '.'"))
            }
            cmdParts.add(advance().lexeme.lowercase())
        }
        val commandId = cmdParts.joinToString(".")

        // Expect '('
        if (!at(TokenType.LPAREN)) {
            throw ParseException(error("expected_lparen", "Expected '(' after command ID"))
        }
        advance()

        // Parse args
        val args = mutableMapOf<String, JsonElement>()
        var argCount = 0
        while (!at(TokenType.RPAREN) && !isAtEnd()) {
            skipComments()
            if (at(TokenType.RPAREN)) break

            if (argCount > 0) {
                // Expect ','
                if (!at(TokenType.COMMA)) {
                    throw ParseException(error("expected_comma", "Expected ',' between arguments"))
                }
                advance()
                skipComments()
            }

            // Parse named arg: ident '=' value
            if (!at(TokenType.IDENT)) {
                // Positional argument detection
                val t = peek()
                if (t.type == TokenType.STRING || t.type == TokenType.NUMBER ||
                    t.type == TokenType.BOOL || t.type == TokenType.NULL
                ) {
                    throw ParseException(
                        error(
                            "positional_arg",
                            "Positional arguments are not allowed in DSL v0.1 — all arguments must be named (protocol 6.1).",
                            token = t.lexeme
                        )
                    )
                }
                throw ParseException(error("expected_named_arg", "Expected named argument 'name=value'"))
            }
            val argName = advance().lexeme.lowercase()

            // Expect '='
            if (!at(TokenType.EQUALS)) {
                throw ParseException(error("expected_equals", "Expected '=' after argument name '$argName'"))
            }
            advance()

            // Parse value
            if (args.containsKey(argName)) {
                throw ParseException(error("duplicate_arg", "Duplicate argument '$argName'"))
            }
            val value = parseValue()
            args[argName] = value
            argCount++
        }

        // Expect ')'
        if (isAtEnd()) {
            throw ParseException(
                error(
                    "unterminated_invocation",
                    "Unterminated invocation — expected ')' before end of input (protocol 18)."
                )
            )
        }
        if (at(TokenType.RPAREN)) {
            advance()
        } else {
            throw ParseException(error("expected_rparen", "Expected ')' to close invocation"))
        }

        return IrInvoke(
            dslVersion = headerVersion ?: "0.1",
            id = commandId,
            args = JsonObject(args)
        )
    }

    private fun parseValue(depth: Int = 0): JsonElement {
        if (depth > MAX_DEPTH) {
            throw ParseException(error("nesting_depth", "Value exceeds max nesting depth of $MAX_DEPTH"))
        }
        skipComments()

        return when {
            at(TokenType.STRING) -> {
                val lexeme = advance().lexeme
                JsonPrimitive(lexeme)
            }
            at(TokenType.NUMBER) -> parseNumber()
            at(TokenType.BOOL) -> {
                val lexeme = advance().lexeme
                JsonPrimitive(lexeme == "true")
            }
            at(TokenType.NULL) -> {
                advance()
                JsonNull
            }
            at(TokenType.LBRACKET) -> parseArray(depth)
            at(TokenType.LBRACE) -> parseObject(depth)
            at(TokenType.IDENT) -> {
                // Nested invocation detection: ident '(' ... or
                // qualified ident '.' ident* '(' for commands like photo.compress()
                if (isNestedInvocation(current)) {
                    throw ParseException(
                        error(
                            "nested_call",
                            "Nested command invocations are forbidden in DSL v0.1 — use Workflow IR for output binding (protocol 6.2)."
                        )
                    )
                }
                // Otherwise it's a bare identifier that looks like a value — invalid
                throw ParseException(error("unexpected_value", "Unexpected token: expected a value literal"))
            }
            else -> {
                throw ParseException(error("unexpected_value", "Unexpected token: expected a value literal"))
            }
        }
    }

    private fun parseNumber(): JsonElement {
        val token = peek()
        val lexeme = token.lexeme

        // Reject exponent notation per spec §6.8.
        // The lexer tags exponent literals with an "EXPONENT:" prefix.
        if (lexeme.startsWith("EXPONENT:")) {
            throw ParseException(error("exponent_notation", "Exponent notation is not allowed: ${lexeme.removePrefix("EXPONENT:")}"))
        }

        // Check leading zeros
        if (lexeme.startsWith('-')) {
            val absPart = lexeme.substring(1)
            if (absPart.startsWith("0") && absPart.length > 1 && !absPart.startsWith("0.")) {
                throw ParseException(error("leading_zero", "Leading zeros are not allowed: $lexeme"))
            }
        } else {
            if (lexeme.startsWith("0") && lexeme.length > 1 && !lexeme.startsWith("0.")) {
                throw ParseException(error("leading_zero", "Leading zeros are not allowed: $lexeme"))
            }
        }

        advance()

        return if (lexeme.contains('.')) {
            try {
                JsonPrimitive(lexeme.toDouble())
            } catch (e: NumberFormatException) {
                throw ParseException(error("invalid_float", "Invalid float literal: $lexeme"))
            }
        } else {
            try {
                val intVal = lexeme.toLong()
                JsonPrimitive(intVal)
            } catch (e: NumberFormatException) {
                throw ParseException(error("int_overflow", "Integer literal overflow: $lexeme"))
            }
        }
    }

    private fun parseArray(depth: Int): JsonElement {
        val token = peek()
        advance() // skip '['
        val elements = mutableListOf<JsonElement>()
        skipComments()
        if (!at(TokenType.RBRACKET)) {
            elements.add(parseValue(depth + 1))
            skipComments()
            while (at(TokenType.COMMA)) {
                advance()
                skipComments()
                if (at(TokenType.RBRACKET)) break // trailing comma allowed
                elements.add(parseValue(depth + 1))
                skipComments()
            }
        }
        if (!at(TokenType.RBRACKET)) {
            throw ParseException(error("unterminated_array", "Expected ']' to close array"))
        }
        advance() // skip ']'
        return JsonArray(elements)
    }

    private fun parseObject(depth: Int): JsonElement {
        advance() // skip '{'
        val fields = mutableMapOf<String, JsonElement>()
        skipComments()
        if (!at(TokenType.RBRACE)) {
            parseObjectField(fields, depth)
            skipComments()
            while (at(TokenType.COMMA)) {
                advance()
                skipComments()
                if (at(TokenType.RBRACE)) break
                parseObjectField(fields, depth)
                skipComments()
            }
        }
        if (!at(TokenType.RBRACE)) {
            throw ParseException(error("unterminated_object", "Expected '}' to close object"))
        }
        advance() // skip '}'
        return JsonObject(fields)
    }

    private fun parseObjectField(fields: MutableMap<String, JsonElement>, depth: Int) {
        if (!at(TokenType.IDENT) && !at(TokenType.STRING)) {
            throw ParseException(error("expected_field_key", "Expected object field key (identifier or string)"))
        }
        val key = advance().lexeme
        if (!at(TokenType.COLON)) {
            throw ParseException(error("expected_colon", "Expected ':' after object field key '$key'"))
        }
        advance()
        if (fields.containsKey(key)) {
            throw ParseException(error("duplicate_field", "Duplicate object field '$key'"))
        }
        val value = parseValue(depth + 1)
        fields[key] = value
    }

    // ─── Error helpers ──────────────────────────────────────────────────

    private fun error(
        reason: String,
        message: String,
        token: String? = null,
        expected: List<String>? = null
    ): ParseResult.Err {
        val t = peek()
        return ParseResult.Err(
            code = "PARSE_ERROR",
            message = message,
            line = t.line,
            column = t.column,
            reason = reason,
            token = token ?: t.lexeme,
            expected = expected
        )
    }

    // ─── Token stream helpers ───────────────────────────────────────────

    /**
     * Detect a nested invocation starting at [idx]: IDENT (DOT IDENT)* LPAREN.
     * This catches both bare (foo() and qualified (foo.bar()) nested calls.
     */
    private fun isNestedInvocation(idx: Int): Boolean {
        var i = idx
        // Walk through IDENT (DOT IDENT)* pattern
        if (i >= tokens.size || tokens[i].type != TokenType.IDENT) return false
        i++ // skip first IDENT
        while (i + 1 < tokens.size && tokens[i].type == TokenType.DOT && tokens[i + 1].type == TokenType.IDENT) {
            i += 2 // skip DOT and IDENT
        }
        // Check if we land on LPAREN
        return i < tokens.size && tokens[i].type == TokenType.LPAREN
    }

    private fun peek(): Token = tokens.getOrElse(current) { tokens.last() }
    private fun advance(): Token = tokens[current++]
    private fun at(type: TokenType): Boolean =
        current < tokens.size && tokens[current].type == type

    private fun isAtEnd(): Boolean =
        current >= tokens.size || tokens[current].type == TokenType.EOF

    private fun skipComments() {
        while (current < tokens.size &&
            (tokens[current].type == TokenType.COMMENT || tokens[current].type == TokenType.HEADER)
        ) {
            current++
        }
    }
}

/** Internal exception to break out of parsing on error */
internal class ParseException(val result: ParseResult.Err) : RuntimeException()
