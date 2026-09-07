package com.morainet.mcos.llm

/**
 * A user-imported *skill*: a named block of natural-language guidance the user
 * adds to extend how the planner/agent behaves (Claude-style skill packages).
 *
 * A skill is **not** a command — it carries no code and registers no handlers.
 * It is prompt-level augmentation: [LlmPlanner] renders enabled skills into a
 * `## Skills` section of the system prompt (see [LlmPlanner.buildSkillsSection]),
 * so the model follows a skill's [instructions] when a request falls within the
 * scope its [description] names. The command allow-list is unchanged — a skill
 * can only steer the model toward the commands already registered.
 *
 * @property id Stable identifier used for diagnostics and de-duplication.
 * @property name Short human label shown to the model and in the UI.
 * @property description One-line summary of when this skill applies; the model
 *   uses it to decide relevance (mirrors the frontmatter `description` of a
 *   Claude SKILL.md).
 * @property instructions The skill body — the guidance the model follows when
 *   the skill applies. Truncated to [MAX_INSTRUCTION_CHARS] on render so one
 *   long skill cannot crowd out the command list.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
) {
    companion object {
        /** Per-skill instruction cap applied at prompt-render time. */
        const val MAX_INSTRUCTION_CHARS: Int = 8_000
    }
}
