package com.mcos.runtime.llm

/**
 * Route class assigned to a natural-language utterance before planning
 * (06 §13.1 routing strategy). The planner in [PlanMode.LATENCY_TIERED] uses
 * the class to pick the cheapest serving path:
 *
 * - [EXACT_CLI]: the utterance already IS valid CLI/DSL syntax -> parser-only,
 *   no LLM round-trip at all.
 * - [KNOWN_RECIPE]: the utterance matches a registered [Recipe] trigger ->
 *   local [RecipeMatcher], no LLM round-trip.
 * - [PRIVACY_SENSITIVE]: content contains private data keywords -> prefer
 *   ON_DEVICE providers; cloud escalation still requires opt-in.
 * - [COMPLEX]: multi-step / ambiguous / workflow intents -> cloud-first when
 *   cloud planning is opted in, otherwise on-device.
 * - [SIMPLE]: single call, low argument complexity -> on-device-first.
 *
 * Priority when several signals fire: EXACT_CLI > KNOWN_RECIPE >
 * PRIVACY_SENSITIVE > COMPLEX > SIMPLE.
 */
enum class UtteranceClass {
    EXACT_CLI,
    KNOWN_RECIPE,
    PRIVACY_SENSITIVE,
    COMPLEX,
    SIMPLE,
}

/**
 * Lightweight keyword/heuristic classifier implementing the first layer of
 * the 06 §13.1 routing strategy. This is intentionally dependency-free and
 * synchronous: routing must not add measurable latency. The second layer
 * (embedding-vector similarity over Capability.EMBED providers) is reserved
 * for a later milestone and plugs in behind [classify].
 *
 * Keyword sets are configurable for tests and OEM tuning; defaults cover
 * English + Chinese.
 */
class UtteranceClassifier(
    private val privacyKeywords: List<String> = DEFAULT_PRIVACY_KEYWORDS,
    private val complexKeywords: List<String> = DEFAULT_COMPLEX_KEYWORDS,
    private val exactCliRegex: Regex = DEFAULT_EXACT_CLI_REGEX,
) {

    /**
     * Classify [utterance]. [recipes] supplies the KNOWN_RECIPE trigger set.
     */
    fun classify(utterance: String, recipes: List<Recipe> = emptyList()): UtteranceClass {
        val text = utterance.trim()
        if (text.isEmpty()) return UtteranceClass.SIMPLE
        if (exactCliRegex.containsMatchIn(text)) return UtteranceClass.EXACT_CLI
        if (recipes.any { it.matchesUtterance(text) }) return UtteranceClass.KNOWN_RECIPE
        if (containsAny(privacyKeywords, text)) return UtteranceClass.PRIVACY_SENSITIVE
        if (containsAny(complexKeywords, text)) return UtteranceClass.COMPLEX
        return UtteranceClass.SIMPLE
    }

    private fun containsAny(keywords: List<String>, text: String): Boolean {
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }

    companion object {
        /** `command.subcommand(...)` shape, e.g. `camera.capture(flash="on")`. */
        val DEFAULT_EXACT_CLI_REGEX =
            Regex("""[\p{L}\w][\p{L}\w.\-]*\.[\p{L}\w.\-]*\s*\(""")

        val DEFAULT_PRIVACY_KEYWORDS = listOf(
            "password", "passcode", "passwd", "secret", "pin", "token",
            "credential", "credit card", "cvv", "ssn", "otp", "private",
            // Chinese
            "密码", "口令", "密钥", "凭证", "信用卡", "私密", "隐私", "机密", "验证码",
        )

        val DEFAULT_COMPLEX_KEYWORDS = listOf(
            "then", "and then", "after that", "afterwards", "for each",
            "for every", "both", "every", "while", "the one",
            // multi-step connectors (single "and" / "also" also count)
            "and", "also", "plus",
            // Chinese
            "然后", "接着", "随后", "并且", "同时", "之后", "以及", "再", "每个", "逐个", "所有", "全部",
            "上次", "昨天", "之前那个",
        )
    }
}
