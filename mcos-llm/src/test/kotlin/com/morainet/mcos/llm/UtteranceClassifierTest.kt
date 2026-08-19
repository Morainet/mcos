package com.morainet.mcos.llm

import kotlin.test.*

/**
 * Tests for the [UtteranceClassifier] -- the lightweight keyword/heuristic
 * layer of the 06 §13.1 routing strategy used by PlanMode.LATENCY_TIERED.
 *
 * Priority when several signals fire: EXACT_CLI > KNOWN_RECIPE >
 * PRIVACY_SENSITIVE > COMPLEX > SIMPLE.
 */
class UtteranceClassifierTest {

    private val classifier = UtteranceClassifier()
    private val recipes = listOf(
        Recipe("morning", listOf("good morning", "早上好"), "hello.greet(name=\"MCOS\")")
    )

    // ---- U1-U2: exact CLI / DSL syntax -------------------------------

    @Test
    fun `U1-DSL with named args is EXACT_CLI`() {
        assertEquals(UtteranceClass.EXACT_CLI, classifier.classify("""camera.capture(flash="on")"""))
    }

    @Test
    fun `U2-bare command call is EXACT_CLI`() {
        assertEquals(UtteranceClass.EXACT_CLI, classifier.classify("take.photo()"))
    }

    // ---- U3: known recipe ----------------------------------------------

    @Test
    fun `U3-recipe trigger is KNOWN_RECIPE`() {
        assertEquals(UtteranceClass.KNOWN_RECIPE, classifier.classify("good morning", recipes))
    }

    @Test
    fun `U3b-chinese recipe trigger is KNOWN_RECIPE`() {
        assertEquals(UtteranceClass.KNOWN_RECIPE, classifier.classify("早上好", recipes))
    }

    // ---- U4-U5: privacy sensitive -------------------------------------

    @Test
    fun `U4-privacy keyword english is PRIVACY_SENSITIVE`() {
        assertEquals(UtteranceClass.PRIVACY_SENSITIVE, classifier.classify("tell me my password"))
    }

    @Test
    fun `U5-privacy keyword chinese is PRIVACY_SENSITIVE`() {
        assertEquals(UtteranceClass.PRIVACY_SENSITIVE, classifier.classify("打开私密相册"))
    }

    // ---- U6-U7: complex (multi-step) -----------------------------------

    @Test
    fun `U6-multi-step connector is COMPLEX`() {
        assertEquals(UtteranceClass.COMPLEX, classifier.classify("take a photo and share it"))
    }

    @Test
    fun `U7-chinese connector is COMPLEX`() {
        assertEquals(UtteranceClass.COMPLEX, classifier.classify("拍一张照片然后发送"))
    }

    // ---- U8-U9: simple --------------------------------------------------

    @Test
    fun `U8-single intent english is SIMPLE`() {
        assertEquals(UtteranceClass.SIMPLE, classifier.classify("open the living room light"))
    }

    @Test
    fun `U9-single intent chinese is SIMPLE`() {
        assertEquals(UtteranceClass.SIMPLE, classifier.classify("打开客厅的灯"))
    }

    // ---- U10: edge cases ------------------------------------------------

    @Test
    fun `U10-empty utterance is SIMPLE`() {
        assertEquals(UtteranceClass.SIMPLE, classifier.classify("   "))
    }

    // ---- U11-U12: precedence --------------------------------------------

    @Test
    fun `U11-EXACT_CLI wins over privacy keywords in DSL args`() {
        assertEquals(UtteranceClass.EXACT_CLI, classifier.classify("""app.set(secret="token")"""))
    }

    @Test
    fun `U12-PRIVACY_SENSITIVE wins over COMPLEX`() {
        assertEquals(
            UtteranceClass.PRIVACY_SENSITIVE,
            classifier.classify("save my password and delete the file")
        )
    }
}
