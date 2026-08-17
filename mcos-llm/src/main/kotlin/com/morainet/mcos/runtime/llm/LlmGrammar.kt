package com.morainet.mcos.runtime.llm

/**
 * Concrete grammar representations injected into a CONSTRAINED-mode request
 * (06 §3.2 V2).
 *
 * The planner picks the highest-fidelity format a provider advertises via
 * [LlmProvider.grammarFormats]: token-level [GBNF] grammars are preferred,
 * [JSON_SCHEMA] is the portable fallback (OpenAI `response_format`, vLLM
 * `guided_json`, Outlines `json_schema`).
 *
 * @property format Which representation [content] is.
 * @property content Grammar payload: a llama.cpp GBNF grammar for [GBNF], or
 *        JSON Schema text for [JSON_SCHEMA].
 */
enum class GrammarFormat {
    /** llama.cpp GBNF grammar -- constrained sampling at token level. */
    GBNF,

    /** JSON Schema text -- structured-output hint (OpenAI / vLLM / Outlines). */
    JSON_SCHEMA,
}

/**
 * A grammar payload plus its format (see [GrammarFormat]).
 */
data class LlmGrammar(
    val format: GrammarFormat,
    val content: String,
) {
    companion object {
        /** A llama.cpp GBNF grammar. */
        fun gbnf(grammar: String): LlmGrammar = LlmGrammar(GrammarFormat.GBNF, grammar)

        /** JSON Schema text. */
        fun jsonSchema(schema: String): LlmGrammar = LlmGrammar(GrammarFormat.JSON_SCHEMA, schema)
    }
}
