package com.morainet.mcos.runtime.parse

/**
 * Hand-written lexer for MCOS DSL v0.1.
 * Matches [02-command-protocol.md 6.6] token specification.
 */
class Lexer(private val input: String) {

    companion object {
        /** Maximum input size: 256 KB */
        const val MAX_INPUT_BYTES = 256 * 1024
    }

    private val chars: CharArray = input.toCharArray()
    private var pos: Int = 0
    private var line: Int = 1
    private var column: Int = 1

    private val tokens = mutableListOf<Token>()
    private var hasParsed = false

    fun tokenize(): List<Token> {
        if (!hasParsed) {
            // Check BOM at start
            if (input.startsWith('\uFEFF')) {
                pos = 1
                column = 2
            }
            // Check input size (bytes, not chars)
            if (input.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
                tokens.add(
                    Token(TokenType.EOF, "", line, column)
                )
                hasParsed = true
                return tokens
            }
            while (pos < chars.size) {
                val token = nextToken() ?: break
                if (token.type == TokenType.COMMENT && token.lexeme.startsWith("# mcos-dsl:")) {
                    // Special handling: treat as HEADER if it's the first non-comment line
                    tokens.add(if (tokens.isEmpty()) Token(TokenType.HEADER, token.lexeme, token.line, token.column) else token)
                } else {
                    tokens.add(token)
                }
            }
            tokens.add(Token(TokenType.EOF, "", line, column))
            hasParsed = true
        }
        return tokens
    }

    private fun nextToken(): Token? {
        skipWhitespace()
        if (pos >= chars.size) return null

        val startLine = line
        val startColumn = column

        return when {
            // Comments: # ... until end of line
            chars[pos] == '#' -> readComment(startLine, startColumn)
            // Strings
            chars[pos] == '"' || chars[pos] == '\'' -> readString(startLine, startColumn)
            // Numbers: must start with digit or '-'
            chars[pos].isDigit() || (chars[pos] == '-' && pos + 1 < chars.size && chars[pos + 1].isDigit()) ->
                readNumber(startLine, startColumn)
            // Identifiers / keywords
            chars[pos].isLetter() -> readIdentifier(startLine, startColumn)
            // Single-char tokens
            chars[pos] == '.' -> { advance(); Token(TokenType.DOT, ".", startLine, startColumn) }
            chars[pos] == '(' -> { advance(); Token(TokenType.LPAREN, "(", startLine, startColumn) }
            chars[pos] == ')' -> { advance(); Token(TokenType.RPAREN, ")", startLine, startColumn) }
            chars[pos] == '[' -> { advance(); Token(TokenType.LBRACKET, "[", startLine, startColumn) }
            chars[pos] == ']' -> { advance(); Token(TokenType.RBRACKET, "]", startLine, startColumn) }
            chars[pos] == '{' -> { advance(); Token(TokenType.LBRACE, "{", startLine, startColumn) }
            chars[pos] == '}' -> { advance(); Token(TokenType.RBRACE, "}", startLine, startColumn) }
            chars[pos] == ',' -> { advance(); Token(TokenType.COMMA, ",", startLine, startColumn) }
            chars[pos] == ':' -> { advance(); Token(TokenType.COLON, ":", startLine, startColumn) }
            chars[pos] == '=' -> { advance(); Token(TokenType.EQUALS, "=", startLine, startColumn) }
            else -> {
                // Unknown character — emit an ERROR token instead of silently
                // swallowing it as COMMENT (previously made typos undiagnosable)
                val bad = chars[pos]
                advance()
                Token(TokenType.ERROR, "unexpected_character:$bad", startLine, startColumn)
            }
        }
    }

    private fun readComment(startLine: Int, startColumn: Int): Token {
        val sb = StringBuilder()
        sb.append('#')
        advance()
        while (pos < chars.size && chars[pos] != '\n') {
            sb.append(chars[pos])
            advance()
        }
        return Token(TokenType.COMMENT, sb.toString(), startLine, startColumn)
    }

    private fun readString(startLine: Int, startColumn: Int): Token {
        val quote = chars[pos]
        advance() // skip opening quote
        val sb = StringBuilder()
        readLoop@ while (pos < chars.size && chars[pos] != quote) {
            if (chars[pos] == '\\') {
                advance() // skip backslash
                if (pos >= chars.size) {
                    // Dangling backslash at end of input — unterminated string
                    return Token(TokenType.ERROR, "unterminated_string", startLine, startColumn)
                }
                when (val escaped = chars[pos]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '/' -> sb.append('/')
                    'u' -> {
                        // \uXXXX — 4 hex digits, per JSON spec (RFC 8259 §7).
                        // The DSL shares the JSON string grammar, so Unicode
                        // escapes must be honoured. Fewer than 4 hex digits, or
                        // hitting EOF mid-escape, is an unterminated-string
                        // error rather than silent literal retention of "\u".
                        advance() // consume the 'u'
                        val codePoint = readHexEscape(startLine, startColumn)
                            ?: return Token(TokenType.ERROR, "invalid_unicode_escape", startLine, startColumn)
                        sb.appendCodePoint(codePoint)
                        // readHexEscape already consumed all 4 hex digits; skip
                        // the trailing advance() below that the single-char
                        // escapes rely on.
                        continue@readLoop
                    }
                    else -> {
                        sb.append('\\')
                        sb.append(escaped)
                    }
                }
                advance()
            } else if (chars[pos] == '\n') {
                // Unescaped newline before closing quote — unterminated string
                return Token(TokenType.ERROR, "unterminated_string", startLine, startColumn)
            } else {
                sb.append(chars[pos])
                advance()
            }
        }
        if (pos >= chars.size) {
            // Hit EOF before closing quote — unterminated string
            return Token(TokenType.ERROR, "unterminated_string", startLine, startColumn)
        }
        advance() // skip closing quote
        return Token(TokenType.STRING, sb.toString(), startLine, startColumn)
    }

    private fun readNumber(startLine: Int, startColumn: Int): Token {
        val sb = StringBuilder()
        if (chars[pos] == '-') {
            sb.append('-')
            advance()
        }
        while (pos < chars.size && chars[pos].isDigit()) {
            sb.append(chars[pos])
            advance()
        }
        // Decimal part — per spec §6.8: "5." and ".5" are invalid.
        // The '.' must be followed by at least one digit, otherwise we stop
        // (leaving the '.' to be lexed as a DOT token, which the parser
        // will reject in value position → PARSE_ERROR).
        if (pos < chars.size && chars[pos] == '.' &&
            pos + 1 < chars.size && chars[pos + 1].isDigit()
        ) {
            sb.append('.')
            advance()
            while (pos < chars.size && chars[pos].isDigit()) {
                sb.append(chars[pos])
                advance()
            }
        }
        // Reject exponent notation per spec §6.8: "1e3", "1.5E-2" → PARSE_ERROR.
        // We detect the presence of 'e' or 'E' immediately after the number
        // and emit a special COMMENT token to signal an error to the parser.
        if (pos < chars.size && (chars[pos] == 'e' || chars[pos] == 'E')) {
            // Consume the exponent characters so the parser sees one bad token
            sb.append(chars[pos]) // e/E
            advance()
            if (pos < chars.size && (chars[pos] == '+' || chars[pos] == '-')) {
                sb.append(chars[pos])
                advance()
            }
            while (pos < chars.size && chars[pos].isDigit()) {
                sb.append(chars[pos])
                advance()
            }
            // Return as a NUMBER token; the parser's parseNumber will reject it
            // because Double.parseDouble succeeds but the spec forbids exponents.
            // We tag it so the parser can detect it.
            return Token(TokenType.NUMBER, "EXPONENT:${sb}", startLine, startColumn)
        }
        return Token(TokenType.NUMBER, sb.toString(), startLine, startColumn)
    }

    private fun readIdentifier(startLine: Int, startColumn: Int): Token {
        val sb = StringBuilder()
        while (pos < chars.size && (chars[pos].isLetterOrDigit() || chars[pos] == '-')) {
            sb.append(chars[pos])
            advance()
        }
        val lexeme = sb.toString()
        return when (lexeme) {
            "true", "false" -> Token(TokenType.BOOL, lexeme, startLine, startColumn)
            "null" -> Token(TokenType.NULL, lexeme, startLine, startColumn)
            else -> Token(TokenType.IDENT, lexeme, startLine, startColumn)
        }
    }

    private fun skipWhitespace() {
        while (pos < chars.size) {
            when {
                chars[pos] == ' ' || chars[pos] == '\t' -> advance()
                chars[pos] == '\r' -> {
                    advance()
                    if (pos < chars.size && chars[pos] == '\n') {
                        advance()
                    }
                    line++
                    column = 1
                }
                chars[pos] == '\n' -> {
                    advance()
                    line++
                    column = 1
                }
                else -> return
            }
        }
    }

    /**
     * Read exactly 4 hex digits following a `\u` escape and return the decoded
     * code point, or null if fewer than 4 hex digits remain (or EOF is hit).
     * The leading `u` has already been consumed by the caller.
     */
    private fun readHexEscape(startLine: Int, startColumn: Int): Int? {
        var code = 0
        repeat(4) {
            if (pos >= chars.size) return null
            val c = chars[pos]
            if (!isHexDigit(c)) return null
            code = (code shl 4) or hexValue(c)
            advance()
        }
        return code
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    private fun advance() {
        if (pos < chars.size) {
            column++
            pos++
        }
    }
}
