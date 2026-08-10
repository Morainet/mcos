package com.mcos.runtime.parse

/**
 * Token types produced by the DSL lexer.
 * Matches [02-command-protocol.md 6.6].
 */
enum class TokenType {
    HEADER,
    COMMENT,
    IDENT,
    DOT,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    LBRACE,
    RBRACE,
    COMMA,
    COLON,
    EQUALS,
    STRING,
    NUMBER,
    BOOL,
    NULL,
    EOF
}

data class Token(
    val type: TokenType,
    val lexeme: String,
    val line: Int,
    val column: Int
)

data class SourceLocation(
    val line: Int,
    val column: Int
)
