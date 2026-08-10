package com.mcos.runtime.parse

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
                // Unknown character
                advance()
                Token(TokenType.COMMENT, chars[pos - 1].toString(), startLine, startColumn)
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
        while (pos < chars.size && chars[pos] != quote) {
            if (chars[pos] == '\\') {
                advance() // skip backslash
                if (pos >= chars.size) {
                    // Dangling backslash at end of input
                    return Token(TokenType.STRING, sb.toString(), startLine, startColumn)
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
                    else -> {
                        sb.append('\\')
                        sb.append(escaped)
                    }
                }
                advance()
            } else if (chars[pos] == '\n') {
                // Unescaped newline in string — stop here, error will be caught later
                break
            } else {
                sb.append(chars[pos])
                advance()
            }
        }
        if (pos < chars.size) {
            advance() // skip closing quote
        }
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
        // Decimal part
        if (pos < chars.size && chars[pos] == '.') {
            sb.append('.')
            advance()
            if (pos < chars.size && chars[pos].isDigit()) {
                while (pos < chars.size && chars[pos].isDigit()) {
                    sb.append(chars[pos])
                    advance()
                }
            }
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

    private fun advance() {
        if (pos < chars.size) {
            column++
            pos++
        }
    }
}
