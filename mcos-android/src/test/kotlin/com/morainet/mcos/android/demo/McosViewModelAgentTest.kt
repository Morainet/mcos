package com.morainet.mcos.android.demo

import androidx.lifecycle.viewModelScope
import com.morainet.mcos.llm.AgentBridge
import com.morainet.mcos.llm.AgentTurnResult
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.ir.IrInvoke
import com.morainet.mcos.runtime.core.ir.IrSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Tests for the Agent-loop wiring in [McosViewModel] (06-agent.md §11 +
 * §17 shell integration).
 *
 * UI1 probe progress → plan preview · UI2 Allow → execution summary ·
 * UI3 Deny → declined, nothing executed.
 *
 * The [AgentBridge] is substituted via the ViewModel's test seam so no
 * network provider is constructed; the kernel-side loop behaviour is
 * covered by mcos-llm's AgentLoopTest (A1-A16).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McosViewModelAgentTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val mainRule = object : TestWatcher() {
        override fun starting(description: Description) { Dispatchers.setMain(mainDispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }

    @get:Rule
    val rule = mainRule

    private lateinit var vm: McosViewModel
    private lateinit var bridge: FakeAgentBridge

    @Before
    fun setUp() {
        vm = McosViewModel()
        vm.attach(TestMarketplace.deps(secureStore = TestMarketplace.FakeSecureStore()))
        bridge = FakeAgentBridge()
        vm.agentBridgeOverride = bridge
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `UI1-agent turn streams probing then stages plan preview`() = runBlocking {
        val ir = ExecutionIr.Sequence(
            IrSequence(
                steps = listOf(
                    IrInvoke(id = "photo.search"),
                    IrInvoke(id = "photo.enhance"),
                )
            )
        )
        bridge.turnResults = flowOf(
            AgentTurnResult.Probing("photo.search → {count:47}", "Replanning with observations…"),
            AgentTurnResult.PlanReady(ir, needsConfirmation = true),
        )

        startAgentTurn("find and enhance my photos")
        withTimeout(10_000) { vm.uiState.first { !it.isExecuting } }

        assertEquals("the shell uses one stable session id", "main", bridge.lastSession)
        assertEquals("find and enhance my photos", bridge.lastMessage)
        val state = vm.uiState.value
        assertNotNull("PlanReady should stage a preview for the dialog", state.pendingAgentPlan)
        val preview = state.pendingAgentPlan!!
        assertTrue("preview lists the probed step: $preview", preview.contains("photo.search"))
        assertTrue("preview lists the staged step: $preview", preview.contains("photo.enhance"))
        assertTrue("probe observation is logged", vm.events.value.any { it.contains("probe") })
        assertTrue("replan action is logged", vm.events.value.any { it.contains("Replanning") })
        assertTrue("confirmation hint is logged", vm.events.value.any { it.contains("needs approval") })
        assertFalse("progress indicator cleared at turn end", state.agentWorking)
    }

    @Test
    fun `UI2-allow executes staged plan and reports Done`() = runBlocking {
        bridge.turnResults = flowOf(
            AgentTurnResult.PlanReady(
                ExecutionIr.Invoke(IrInvoke(id = "photo.enhance")),
                needsConfirmation = true,
            )
        )
        startAgentTurn("enhance my photo")
        withTimeout(10_000) { vm.uiState.first { it.pendingAgentPlan != null } }

        bridge.resumeResults = { flowOf(AgentTurnResult.Done("Executed 1 command(s) successfully")) }
        vm.resumeAgentTurn(true)
        withTimeout(10_000) { vm.uiState.first { !it.isExecuting } }

        assertEquals("Allow forwards approval to the bridge", true, bridge.lastApproved)
        assertNull("approval dialog cleared after the decision", vm.uiState.value.pendingAgentPlan)
        assertTrue(
            "Done summary is logged: ${vm.events.value}",
            vm.events.value.any { it.contains("Executed 1 command(s)") },
        )
    }

    @Test
    fun `UI3-deny declines without executing`() = runBlocking {
        bridge.turnResults = flowOf(
            AgentTurnResult.PlanReady(
                ExecutionIr.Invoke(IrInvoke(id = "photo.enhance")),
                needsConfirmation = true,
            )
        )
        startAgentTurn("enhance my photo")
        withTimeout(10_000) { vm.uiState.first { it.pendingAgentPlan != null } }

        bridge.resumeResults = { flowOf(AgentTurnResult.Declined("user_declined")) }
        vm.resumeAgentTurn(false)
        withTimeout(10_000) { vm.uiState.first { !it.isExecuting } }

        assertEquals("Deny forwards rejection to the bridge", false, bridge.lastApproved)
        assertNull(vm.uiState.value.pendingAgentPlan)
        assertTrue("decline is logged", vm.events.value.any { it.contains("Declined") })
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** Enable agent mode, fill key+goal, and fire one turn. */
    private fun startAgentTurn(goal: String) {
        vm.onApiKeyChange("sk-test-key")
        vm.onNlTextChange(goal)
        vm.onAgentModeChange(true)
        vm.agentTurn()
    }
}

/** Records calls and replays canned [AgentTurnResult] flows. */
private class FakeAgentBridge : AgentBridge {
    var turnResults: Flow<AgentTurnResult> = emptyFlow()
    var resumeResults: (Boolean) -> Flow<AgentTurnResult> = { emptyFlow() }

    var lastSession: String? = null
    var lastMessage: String? = null
    var lastApproved: Boolean? = null

    override fun runTurn(sessionId: String, userMessage: String): Flow<AgentTurnResult> {
        lastSession = sessionId
        lastMessage = userMessage
        return turnResults
    }

    override suspend fun resume(sessionId: String, approved: Boolean): Flow<AgentTurnResult> {
        lastApproved = approved
        return resumeResults(approved)
    }

    override suspend fun cancel(sessionId: String) {
        // no-op: the kernel-side cancel path is covered by AgentLoopTest A9.
    }
}
