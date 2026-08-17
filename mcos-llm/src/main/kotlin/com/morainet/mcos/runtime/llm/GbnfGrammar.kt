package com.morainet.mcos.runtime.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds llama.cpp GBNF grammars that constrain a model's token sampling to
 * the MCOS IR shape (06 §3.2 V2).
 *
 * The generated grammar makes malformed output impossible at decode time: the
 * reply is guaranteed to be a single IR JSON object -- `invoke` / `sequence`
 * / `clarify` / `refuse` -- with
 *
 * - command ids restricted to the registered catalog (invented ids cannot be
 *   produced),
 * - `args` restricted to each command's JSON Schema (key names, value types,
 *   enum members, nesting),
 * - no markdown fences or surrounding prose.
 *
 * Object members follow llama.cpp's official `json.gbnf` style: within an
 * object the decoder cannot express "set semantics", so members may appear in
 * any order and may repeat; semantic post-checks (required fields, duplicate
 * keys) are enforced downstream by [LlmPlanner.parseIrJson] and the
 * executor's schema validation.
 */
object GbnfGrammar {

    /**
     * Build the full IR GBNF grammar for the given command catalog.
     *
     * @param tools Commands projected as [ToolDescriptor]s (same projection
     *        [LlmPlanner] uses for NATIVE_TOOL_CALL).
     */
    fun buildIrGrammar(tools: List<ToolDescriptor>): String {
        val sb = StringBuilder()
        sb.appendLine("# MCOS IR grammar (llama.cpp GBNF) -- generated from the command catalog")
        sb.appendLine("# Output: exactly one IR JSON object (invoke | sequence | clarify | refuse).")
        sb.appendLine("# 06 §3.2 V2 grammar-constrained decoding.")
        sb.appendLine()

        val invokes = tools.map { tool ->
            "ir-invoke-${ruleName(tool.command)} ::= " + objectGbnf(
                listOf(
                    gbnfKey("type") to gbnfLiteral("invoke"),
                    gbnfKey("command") to gbnfLiteral(tool.command),
                    gbnfKey("args") to "args-${ruleName(tool.command)}",
                )
            )
        }
        val steps = tools.map { tool ->
            "step-${ruleName(tool.command)} ::= " + objectGbnf(
                listOf(
                    gbnfKey("command") to gbnfLiteral(tool.command),
                    gbnfKey("args") to "args-${ruleName(tool.command)}",
                )
            )
        }

        val invokeRefs = tools.joinToString(" | ") { "ir-invoke-${ruleName(it.command)}" }
        val stepRefs = tools.joinToString(" | ") { "step-${ruleName(it.command)}" }

        // With no commands registered, only the terminal states are possible.
        val rootChoices = when {
            tools.isEmpty() -> "ir-clarify | ir-refuse"
            else -> "$invokeRefs | ir-sequence | ir-clarify | ir-refuse"
        }
        sb.appendLine("root ::= ws ( $rootChoices ) ws")
        sb.appendLine()

        if (tools.isNotEmpty()) {
            invokes.forEach { sb.appendLine(it); sb.appendLine() }
            steps.forEach { sb.appendLine(it); sb.appendLine() }
            sb.appendLine(
                "ir-sequence ::= " + objectGbnf(
                    listOf(
                        gbnfKey("type") to gbnfLiteral("sequence"),
                        gbnfKey("steps") to "\"[\" ws ( $stepRefs ( \",\" ws $stepRefs )* )? \"]\" ws",
                    )
                )
            )
            sb.appendLine()
        }
        sb.appendLine(
            "ir-clarify ::= " + objectGbnf(
                listOf(
                    gbnfKey("type") to gbnfLiteral("clarify"),
                    gbnfKey("question") to "string",
                )
            )
        )
        sb.appendLine()
        sb.appendLine(
            "ir-refuse ::= " + objectGbnf(
                listOf(
                    gbnfKey("type") to gbnfLiteral("refuse"),
                    gbnfKey("reason") to "string",
                )
            )
        )
        sb.appendLine()

        tools.forEach { tool ->
            sb.appendLine("args-${ruleName(tool.command)} ::= " + argsGbnf(tool.inputSchema))
            sb.appendLine()
        }

        sb.append(sharedRules())
        return sb.toString()
    }

    // ---- Schema -> GBNF --------------------------------------------------

    /** A JSON object with a fixed, required member list. */
    private fun objectGbnf(members: List<Pair<String, String>>): String {
        if (members.isEmpty()) return "\"{\" ws \"}\" ws"
        val body = members.joinToString(" \",\" ws ") { (k, v) -> "$k ws \":\" ws $v" }
        return "\"{\" ws ( $body ) \"}\" ws"
    }

    /** A JSON object restricted to the schema's properties (any order, repeatable). */
    private fun argsGbnf(schema: JsonObject): String {
        val props = schema["properties"]?.jsonObject
        if (props.isNullOrEmpty()) return "\"{\" ws \"}\" ws"
        val choices = props.map { (name, sub) ->
            "${gbnfKey(name)} ws \":\" ws ${valueGbnf(sub.jsonObject)}"
        }.joinToString(" | ")
        return "\"{\" ws ( $choices )* \"}\" ws"
    }

    private fun valueGbnf(schema: JsonObject): String {
        when (schema["type"]?.jsonPrimitive?.content) {
            "string" -> return enumOrConst(schema, "string")
            "integer", "number" -> return enumOrConst(schema, "number")
            "boolean" -> return enumOrConst(schema, "boolean")
            "null" -> return "\"null\" ws"
            "array" -> {
                val items = schema["items"]?.jsonObject
                val item = if (items != null) valueGbnf(items) else "value"
                return "\"[\" ws ( $item ( \",\" ws $item )* )? \"]\" ws"
            }
            "object" -> {
                val props = schema["properties"]?.jsonObject
                if (props.isNullOrEmpty()) return "\"{\" ws \"}\" ws"
                val choices = props.map { (name, sub) ->
                    "${gbnfKey(name)} ws \":\" ws ${valueGbnf(sub.jsonObject)}"
                }.joinToString(" | ")
                return "\"{\" ws ( $choices )* \"}\" ws"
            }
        }
        // No declared type: enum / const, otherwise any JSON value.
        return enumOrConst(schema, "value")
    }

    /** Enum / const members if present (JSON-representation literals), else the fallback rule. */
    private fun enumOrConst(schema: JsonObject, fallback: String): String {
        schema["enum"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { arr ->
            return "(" + arr.joinToString(" | ") { gbnfLiteral(it.toString()) } + ") ws"
        }
        schema["const"]?.let { c ->
            return gbnfLiteral(c.toString()) + " ws"
        }
        return fallback
    }

    // ---- GBNF building blocks -------------------------------------------

    /** GBNF string literal for a JSON key (e.g. `"type"` -> `"\"type\""`). */
    private fun gbnfKey(name: String): String = "\"${gbnfEscape(name)}\""

    /** GBNF string literal for a JSON representation (e.g. `"on"` -> `"\"on\""`, `123` -> `"123"`). */
    private fun gbnfLiteral(jsonRepr: String): String = "\"${gbnfEscape(jsonRepr)}\""

    private fun gbnfEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> if (c.code < 0x20) append("\\x%02X".format(c.code)) else append(c)
            }
        }
    }

    /** GBNF rule names allow `[A-Za-z0-9_]` only; collapse everything else to `_`. */
    private fun ruleName(id: String): String {
        val sanitized = id.replace(Regex("[^A-Za-z0-9_]"), "_")
        return if (sanitized.isEmpty() || sanitized.first().isDigit()) "_$sanitized" else sanitized
    }

    private fun sharedRules(): String = """
        ws ::= ([ \t\n] ws)?
        string ::= "\"" ( [^"\\] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )* "\"" ws
        number ::= "-"? ([0-9] | [1-9] [0-9]{0,15}) ("." [0-9]+)? ([eE] [-+]? [0-9]+)? ws
        boolean ::= "true" ws | "false" ws
        value ::= string | number | boolean | "null" ws | object | array
        object ::= "{" ws ( string ":" ws value ( "," ws string ":" ws value )* )? "}" ws
        array ::= "[" ws ( value ( "," ws value )* )? "]" ws
    """.trimIndent()
}
