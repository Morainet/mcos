package com.mcos.runtime.llm

import kotlin.test.*

/**
 * Tests for the [RecipeMatcher] -- the zero-latency local matcher for FAQ /
 * known recipes (06 §13.1 routing strategy).
 */
class RecipeMatcherTest {

    private val recipes = listOf(
        Recipe("morning", listOf("good morning", "早上好"), "hello.greet(name=\"MCOS\")"),
        Recipe("home", listOf("go home", "回家"), "nav.to(destination=\"home\")"),
    )

    private val matcher = RecipeMatcher(recipes)

    // ---- R1-R2: matching semantics --------------------------------------

    @Test
    fun `R1-exact trigger match returns the recipe`() {
        val hit = matcher.match("good morning")
        assertNotNull(hit)
        assertEquals("morning", hit.id)
    }

    @Test
    fun `R2-case and punctuation are normalized`() {
        assertEquals("morning", matcher.match("Good morning!")?.id)
        assertEquals("home", matcher.match("  Go  Home?? ")?.id)
    }

    // ---- R3-R4: containment rules ----------------------------------------

    @Test
    fun `R3-phrase of 4+ chars matches by containment`() {
        assertEquals("morning", matcher.match("please run the good morning routine")?.id)
    }

    @Test
    fun `R4-short trigger never matches by containment`() {
        // "go" appears inside "let's go now" but is < 4 chars -> no match
        val short = RecipeMatcher(listOf(Recipe("go", listOf("go"), "app.start()")))
        assertNull(short.match("let's go now"))
    }

    // ---- R5-R7: misses ---------------------------------------------------

    @Test
    fun `R5-unrelated utterance returns null`() {
        assertNull(matcher.match("turn off the alarm"))
    }

    @Test
    fun `R6-empty recipe list never matches`() {
        assertNull(RecipeMatcher().match("good morning"))
    }

    @Test
    fun `R7-blank utterance returns null`() {
        assertNull(matcher.match("   "))
    }

    // ---- R8: ordering -----------------------------------------------------

    @Test
    fun `R8-first registered matching recipe wins`() {
        val dup = RecipeMatcher(
            listOf(
                Recipe("a", listOf("hello"), "app.a()"),
                Recipe("b", listOf("hello"), "app.b()"),
            )
        )
        assertEquals("a", dup.match("hello")?.id)
    }
}
