package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Skill packages (Claude-style) are rendered into a `## Skills` section of both
 * the FREEFORM_JSON and CONSTRAINED system prompts. An empty skill list adds
 * nothing; a long instruction body is truncated so it can't crowd out the
 * command list.
 */
class SkillPromptTest {

    private lateinit var registry: CommandRegistry

    @BeforeTest
    fun setUp() { registry = CommandRegistry() }

    private val skill = Skill(
        id = "weather-etiquette",
        name = "Weather Etiquette",
        description = "How to answer weather questions",
        instructions = "Always confirm the city before checking the weather.",
    )

    @Test
    fun `no skills adds no Skills section`() = runBlocking {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val prompt = planner.buildSystemPrompt()
        assertFalse(prompt.contains("## Skills"), "empty skill list must not emit a heading")
    }

    @Test
    fun `skills appear in freeform prompt`() = runBlocking {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry, skills = listOf(skill))
        val prompt = planner.buildSystemPrompt(PlanMode.FREEFORM_JSON)
        assertTrue(prompt.contains("## Skills"), "should have a skills section")
        assertTrue(prompt.contains("Weather Etiquette"), "should list the skill name")
        assertTrue(prompt.contains("confirm the city"), "should include the instructions")
    }

    @Test
    fun `skills appear in constrained prompt`() = runBlocking {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry, skills = listOf(skill))
        val prompt = planner.buildSystemPrompt(PlanMode.CONSTRAINED)
        assertTrue(prompt.contains("## Skills"), "constrained prompt should also carry skills")
        assertTrue(prompt.contains("Weather Etiquette"))
    }

    @Test
    fun `long instructions are truncated`() = runBlocking {
        val huge = "x".repeat(Skill.MAX_INSTRUCTION_CHARS + 500)
        val planner = LlmPlanner(
            FakeLlmProvider(emptyList()),
            registry,
            skills = listOf(skill.copy(instructions = huge)),
        )
        val prompt = planner.buildSystemPrompt()
        assertTrue(prompt.contains("…(truncated)"), "over-long instructions must be truncated")
    }
}
