package com.morainet.mcos.runtime.llm

/**
 * A known recipe: a fixed natural-language trigger mapped to a fixed IR DSL
 * snippet (06 §13.1 "FAQ / known recipes -> local recipe matcher, no LLM").
 * Recipes give a zero-latency path for repeated, well-known requests.
 *
 * @property id stable identifier used for diagnostics (`route = recipe:<id>`).
 * @property triggers normalized trigger phrases; any of them matching the
 *   utterance (exact equality or containment for phrases >= 4 chars) hits.
 * @property dsl the IR DSL snippet emitted when the recipe matches, e.g.
 *   `hello.greet(name="MCOS")\nweather.now(city="auto")`.
 */
data class Recipe(
    val id: String,
    val triggers: List<String>,
    val dsl: String,
    val description: String = "",
) {
    /**
     * True when [utterance] (raw form) matches one of this recipe's triggers.
     */
    fun matchesUtterance(utterance: String): Boolean {
        val norm = RecipeMatcher.normalize(utterance)
        return triggers.any { trigger ->
            val t = RecipeMatcher.normalize(trigger)
            when {
                t.isEmpty() -> false
                norm == t -> true
                t.length >= 4 && norm.contains(t) -> true
                else -> false
            }
        }
    }
}

/**
 * Local recipe matcher (06 §13.1): normalizes text and finds the first
 * registered [Recipe] whose triggers hit. Runs synchronously in
 * [PlanMode.LATENCY_TIERED] before any LLM round-trip.
 */
class RecipeMatcher(private val recipes: List<Recipe> = emptyList()) {

    /** First matching recipe, or `null` when none matches. */
    fun match(utterance: String): Recipe? {
        val norm = normalize(utterance)
        if (norm.isEmpty()) return null
        return recipes.firstOrNull { it.matchesUtterance(utterance) }
    }

    companion object {
        private val punctuationRegex = Regex("""[.,!?;:'"()\[\]{}<>，。！？；：、（）【】]""")
        private val whitespaceRegex = Regex("""\s+""")

        /** Lowercase, strip punctuation, collapse whitespace. */
        fun normalize(text: String): String =
            text.lowercase()
                .replace(punctuationRegex, " ")
                .replace(whitespaceRegex, " ")
                .trim()
    }
}
