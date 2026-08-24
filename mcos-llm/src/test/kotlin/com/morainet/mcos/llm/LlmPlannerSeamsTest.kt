package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Seam tests for the Agent-loop planner extensions (06 §11.0 / slice 2):
 *
 * - C17: `clarify` / `refuse` IR outcomes surface as typed fields on
 *   [LlmPlan] instead of collapsing into `thoughts` (forwarded verbatim by
 *   the Agent loop as `AgentTurnResult.Clarify` / `.Refuse`).
 * - C18: `LlmPlanner.plan(extraContext = ...)` folds probe observations into
 *   the user message under the fixed `[Probe observations]` header
 *   (06 §11.0 item 2 "observation folding").
 */
class LlmPlannerSeamsTest {

    private fun planner(provider: LlmProvider): LlmPlanner =
        LlmPlanner(provider, CommandRegistry())

    // ═══════════════════════════════════════════════════════════════
    // C17: clarify / refuse typed fields
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `C17-clarify IR outcome populates LlmPlan-dot-clarify`() {
        val plan = LlmPlanner(FakeLlmProvider(emptyList()), CommandRegistry()).parseIrJson(
            """{"type":"clarify","question":"Which Tom — Tom Zhang or Tom Li?"}"""
        )

        assertTrue(!plan.isSuccess, "clarify plans carry no executable commands")
        assertEquals("Which Tom — Tom Zhang or Tom Li?", plan.clarify)
        assertNull(plan.refuse, "clarify must not set refuse")
    }

    @Test
    fun `C17b-refuse IR outcome populates LlmPlan-dot-refuse`() {
        val plan = LlmPlanner(FakeLlmProvider(emptyList()), CommandRegistry()).parseIrJson(
            """{"type":"refuse","reason":"goal requires unavailable device"}"""
        )

        assertTrue(!plan.isSuccess, "refuse plans carry no executable commands")
        assertNotNull(plan.refuse, "refuse must surface as RefuseInfo, not just thoughts")
        assertEquals("goal requires unavailable device", plan.refuse!!.reason)
        assertNull(plan.clarify, "refuse must not set clarify")
    }

    @Test
    fun `C17c-invoke IR outcome leaves clarify and refuse null`() {
        val plan = LlmPlanner(FakeLlmProvider(emptyList()), CommandRegistry()).parseIrJson(
            """{"type":"invoke","command":"camera.capture","args":{"quality":"high"}}"""
        )

        assertTrue(plan.isSuccess, "invoke outcome should parse to executable commands")
        assertNull(plan.clarify)
        assertNull(plan.refuse)
    }

    // ═══════════════════════════════════════════════════════════════
    // C18: observation folding via extraContext
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `C18-extraContext is folded into the user message under the observations header`() = runBlocking {
        val recording = RecordingChatProvider("""hello.world(name="probe")""")
        val p = planner(recording)

        p.plan(
            "把今天照片发给Tom，如果太多先压缩",
            extraContext = "photo.search → {\"count\":47}"
        )

        assertEquals(1, recording.capturedMessages.size, "one chat round-trip expected")
        val user = recording.capturedMessages.single().lastOrNull { it.role == "user" }
        assertNotNull(user, "user message must be present")
        assertTrue(user!!.content.contains("把今天照片发给Tom"), "goal text preserved")
        assertTrue(user.content.contains("[Probe observations]"), "fixed header appended")
        assertTrue(user.content.contains("photo.search → {\"count\":47}"), "observation text folded in")
    }

    @Test
    fun `C18b-null extraContext keeps the user message as the plain goal`() = runBlocking {
        val recording = RecordingChatProvider("""hello.world(name="plain")""")
        val p = planner(recording)

        p.plan("take a photo")

        val user = recording.capturedMessages.single().lastOrNull { it.role == "user" }
        assertNotNull(user)
        assertEquals("take a photo", user!!.content, "no header or context should be appended")
    }
}

/** Chat-only provider that records every message list it is handed. */
class RecordingChatProvider(private val replyDsl: String) : LlmProvider {
    val capturedMessages = mutableListOf<List<ChatMessage>>()

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse {
        capturedMessages.add(messages)
        return LlmResponse.Ok(replyDsl)
    }
}
