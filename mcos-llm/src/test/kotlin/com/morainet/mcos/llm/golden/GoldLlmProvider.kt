package com.morainet.mcos.llm.golden

import com.morainet.mcos.llm.Capability
import com.morainet.mcos.llm.ChatMessage
import com.morainet.mcos.llm.LlmGrammar
import com.morainet.mcos.llm.LlmProvider
import com.morainet.mcos.llm.LlmResponse

/**
 * Deterministic stub provider used by the golden suite's regression path.
 *
 * The provider's [capabilities] select the compile path exactly like a real
 * backend would (06 §3.2): a fixture with `mode = constrained` advertises
 * [Capability.CONSTRAINED] so the planner routes through `constrainedChat`
 * (grammar-constrained IR JSON); `mode = freeform` advertises plain chat so
 * the planner parses DSL text. Either way the model's raw reply is the
 * fixture's [GoldenFixture.llmReply].
 */
class GoldLlmProvider(
    private val reply: String,
    mode: String,
) : LlmProvider {
    private val constrained = mode == GoldenFixture.MODE_CONSTRAINED

    override val id: String = "gold-llm"

    override val capabilities: Set<Capability> =
        if (constrained) setOf(Capability.CONSTRAINED) else setOf(Capability.CHAT)

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse =
        LlmResponse.Ok(reply)

    override suspend fun constrainedChat(
        messages: List<ChatMessage>,
        grammar: LlmGrammar,
    ): LlmResponse = LlmResponse.Ok(reply)
}
