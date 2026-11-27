package com.morainet.mcos.android.demo

import androidx.lifecycle.viewModelScope
import com.morainet.mcos.android.demo.records.RecordExportPlugin
import com.morainet.mcos.android.demo.shell.AgentProgress
import com.morainet.mcos.android.demo.shell.ChatMessage
import com.morainet.mcos.android.demo.shell.ChatRole
import com.morainet.mcos.android.demo.shell.McosViewModel
import com.morainet.mcos.android.demo.shell.SavedTask
import com.morainet.mcos.android.demo.shell.TaskWorkspace
import com.morainet.mcos.android.demo.shell.TaskWorkspaceStore
import com.morainet.mcos.llm.AgentBridge
import com.morainet.mcos.llm.AgentTurnResult
import com.morainet.mcos.llm.ChatMessage as LlmChatMessage
import com.morainet.mcos.llm.LlmErrorCode
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.ir.IrInvoke
import com.morainet.mcos.runtime.core.ir.IrSequence
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
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
 * Tests for the Agent-loop wiring in [com.morainet.mcos.android.demo.shell.McosViewModel] (06-agent.md §11 +
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
    private lateinit var secureStore: TestMarketplace.FakeSecureStore

    @Before
    fun setUp() {
        vm = McosViewModel()
        secureStore = TestMarketplace.FakeSecureStore()
        vm.attach(TestMarketplace.deps(secureStore = secureStore))
        bridge = FakeAgentBridge()
        vm.agentBridgeOverride = bridge
        runBlocking { withTimeout(5_000) { vm.uiState.first { it.tasksReady } } }
    }

    @After
    fun tearDown() = runBlocking {
        vm.viewModelScope.coroutineContext.job.cancelAndJoin()
    }

    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `new tasks isolate agent sessions and restore drafts and results after restart`() = runBlocking {
        bridge.turnResults = flowOf(AgentTurnResult.Done("First result"))
        startAgentTurn("First goal")
        val first = withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome == "First result" } }
        val firstId = first.activeTaskId
        assertEquals(firstId, bridge.lastSession)
        vm.onNlTextChange("First draft")

        vm.newTask()
        val secondId = vm.uiState.value.activeTaskId
        assertTrue(secondId != firstId)
        assertTrue(vm.uiState.value.transcript.isEmpty())
        assertEquals("", vm.uiState.value.nlText)
        bridge.turnResults = flowOf(AgentTurnResult.Done("Second result"))
        startAgentTurn("Second goal")
        withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome == "Second result" } }
        assertEquals(secondId, bridge.lastSession)

        vm.searchTaskHistory("  fIrSt ReSuLt ")
        assertEquals(listOf(firstId), vm.uiState.value.taskSearchMatches.orEmpty().map { it.id })
        vm.renameTask(firstId, "  Nightly inspection  ")
        vm.renameTask(firstId, " ")
        assertEquals(secondId, vm.uiState.value.activeTaskId)
        vm.searchTaskHistory("nightly")
        assertEquals(listOf("Nightly inspection"), vm.uiState.value.taskSearchMatches.orEmpty().map { it.title })
        vm.searchTaskHistory("First goal")
        assertEquals(listOf(firstId), vm.uiState.value.taskSearchMatches.orEmpty().map { it.id })

        vm.selectTask(firstId)
        assertEquals("First draft", vm.uiState.value.nlText)
        assertEquals("First result", vm.uiState.value.lastAgentOutcome)
        assertTrue(vm.uiState.value.transcript.none { it.text.contains("Second") })

        val store = TaskWorkspaceStore(secureStore)
        withTimeout(5_000) {
            while (store.load()?.let { it.activeId == firstId && it.tasks.size == 2 } != true) delay(20)
        }
        val restarted = McosViewModel().also { it.attach(TestMarketplace.deps(secureStore = secureStore)) }
        try {
            val restored = withTimeout(5_000) { restarted.uiState.first { it.tasksReady } }
            assertEquals("First draft", restored.nlText)
            assertEquals(firstId, restored.activeTaskId)
            assertEquals(setOf("Nightly inspection", "Second goal"), restored.taskHistory.map { it.title }.toSet())
            restarted.selectTask(secondId)
            assertEquals("Second result", restarted.uiState.value.lastAgentOutcome)
            assertTrue(restarted.uiState.value.transcript.none { it.text.contains("First") })
        } finally {
            restarted.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `an interrupted approval is shown in history without restoring an executable plan`() = runBlocking {
        val store = TestMarketplace.FakeSecureStore()
        TaskWorkspaceStore(store).save(TaskWorkspace("task", listOf(SavedTask(
            id = "task", title = "Export inspection", unfinished = true,
            messages = listOf(ChatMessage(5, ChatRole.USER, "Export inspection")),
        ))))
        val restarted = McosViewModel().also { it.attach(TestMarketplace.deps(secureStore = store)) }
        try {
            val restored = withTimeout(5_000) { restarted.uiState.first { it.tasksReady } }
            assertNull(restored.pendingAgentPlan)
            assertNull(restored.pendingConfirmation)
            assertTrue(restored.lastAgentOutcome.orEmpty().startsWith("Task interrupted"))
            assertEquals(listOf(5L, 6L), restored.transcript.map { it.id })
        } finally {
            restarted.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `the built-in inspection task asks once then reports a verified file without a model`() = runBlocking {
        val directory = Files.createTempDirectory("mcos-inspection-task-").toFile()
        val deps = TestMarketplace.deps(
            secureStore = TestMarketplace.FakeSecureStore(),
            plugins = listOf(RecordExportPlugin(directory)),
        )
        try {
            vm.attach(deps)
            vm.runInspectionTask()
            val waiting = withTimeout(5_000) { vm.uiState.first { it.pendingConfirmation != null } }
            assertEquals("demo-002", waiting.inspectionRecordId)
            assertNull(waiting.pendingAgentPlan)
            assertTrue(directory.listFiles().orEmpty().isEmpty())

            vm.respondConfirmation(true)
            val completed = withTimeout(5_000) { vm.uiState.first { !it.isExecuting } }
            assertTrue(completed.lastAgentOutcome.orEmpty().contains("Export verified"))
            assertTrue(directory.listFiles().orEmpty().single().readText().startsWith("Record demo-002\n"))
            assertNull(completed.pendingConfirmation)
            assertFalse(completed.agentWorking)
        } finally {
            deps.runtime.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `read-only plans continue without a plan dialog`() = runBlocking {
        bridge.turnResults = flowOf(
            AgentTurnResult.PlanReady(
                ExecutionIr.Invoke(IrInvoke(id = "sys.device.battery")),
                needsConfirmation = false,
                headline = "Read battery",
            ),
        )
        bridge.resumeResults = { flowOf(AgentTurnResult.Done("Battery checked")) }
        startAgentTurn("Check my battery")
        withTimeout(5_000) { vm.uiState.first { it.lastAgentOutcome == "Battery checked" } }
        assertEquals(true, bridge.lastApproved)
        assertNull(vm.uiState.value.pendingAgentPlan)
    }

    @Test
    fun `follow-ups use only completed dialogue from the active conversation`() = runBlocking {
        bridge.turnResults = flowOf(
            AgentTurnResult.Probing("Device observation", "Replanning"),
            AgentTurnResult.Answer("Project name: Cedar"),
        )
        vm.onNlTextChange("Remember the project name Cedar")
        vm.agentTurn()
        val first = withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome != null } }
        assertTrue(bridge.lastHistory.isEmpty())

        bridge.turnResults = flowOf(AgentTurnResult.Answer("Cedar"))
        vm.onNlTextChange("What was the name?")
        vm.agentTurn()
        withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome == "Cedar" } }
        assertEquals(
            listOf(
                LlmChatMessage("user", "Remember the project name Cedar"),
                LlmChatMessage("assistant", "Project name: Cedar"),
            ),
            bridge.lastHistory,
        )

        vm.newTask()
        vm.onNlTextChange("What was the name?")
        vm.agentTurn()
        withTimeout(5_000) { vm.uiState.first { !it.isExecuting } }
        assertTrue(vm.uiState.value.activeTaskId != first.activeTaskId)
        assertTrue(bridge.lastHistory.isEmpty())
    }

    @Test
    fun `timed out request can be restored after restart and sent only by the user`() = runBlocking {
        bridge.turnResults = flowOf(AgentTurnResult.Refuse("QUOTA", AgentTurnResult.Refuse.WALL_CLOCK_EXCEEDED))
        startAgentTurn("Draft a plan\nfor this week")
        val failed = withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome != null } }
        val failure = failed.transcript.last()
        assertEquals("The request took too long.", failure.text)
        assertEquals(failed.transcript.first().id, failure.retryOfUserMessageId)

        val store = TaskWorkspaceStore(secureStore)
        withTimeout(5_000) {
            while (store.load()?.tasks?.single()?.let { !it.unfinished && it.messages.lastOrNull() == failure } != true) {
                delay(20)
            }
        }
        val restarted = McosViewModel().also { it.attach(TestMarketplace.deps(secureStore = secureStore)) }
        val restartedBridge = FakeAgentBridge().apply { turnResults = flowOf(AgentTurnResult.Answer("A plan")) }
        try {
            restarted.agentBridgeOverride = restartedBridge
            withTimeout(5_000) { restarted.uiState.first { it.tasksReady } }
            restarted.onNlTextChange("New draft")
            restarted.restoreRequestForEditing(failure.id)
            assertEquals("New draft", restarted.uiState.value.nlText)

            restarted.onNlTextChange("")
            restarted.restoreRequestForEditing(failure.id)
            assertEquals("Draft a plan\nfor this week", restarted.uiState.value.nlText)
            assertEquals(failed.transcript, restarted.uiState.value.transcript)
            assertNull(restartedBridge.lastMessage)

            restarted.onNlTextChange("Draft a plan for Monday")
            restarted.agentTurn()
            withTimeout(5_000) { restarted.uiState.first { !it.isExecuting && it.lastAgentOutcome == "A plan" } }
            assertEquals("Draft a plan for Monday", restartedBridge.lastMessage)
            assertTrue(restartedBridge.lastHistory.isEmpty())
            restarted.restoreRequestForEditing(failure.id)
            assertEquals("", restarted.uiState.value.nlText)
        } finally {
            restarted.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `a model failure during replanning does not offer to retry the whole task`() = runBlocking {
        bridge.turnResults = flowOf(
            AgentTurnResult.Probing("Device observation", "Replanning"),
            AgentTurnResult.Refuse(LlmErrorCode.NETWORK_ERROR, "Test connection failure"),
        )
        startAgentTurn("Check the device")
        val failed = withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome != null } }
        val failure = failed.transcript.last()
        assertTrue(failure.text.startsWith("Couldn’t reach the model"))
        assertNull(failure.retryOfUserMessageId)

        vm.restoreRequestForEditing(failure.id)
        assertEquals("", vm.uiState.value.nlText)
    }

    @Test
    fun `legacy transcript can resume dialogue after restart without including device observations`() = runBlocking {
        val store = TestMarketplace.FakeSecureStore()
        store.put("agent_task_workspace", """
            {"version":1,"activeId":"restored","tasks":[
              {"id":"restored","outcome":"Project name: Cedar","messages":[
                {"id":1,"role":"USER","text":"Export the latest completed inspection"},
                {"id":2,"role":"AGENT","text":"Found private sample"},
                {"id":3,"role":"USER","text":"Remember the project name Cedar"},
                {"id":4,"role":"AGENT","text":"Project name: Cedar"}
              ]}
            ]}
        """.trimIndent().encodeToByteArray())
        val restored = McosViewModel().also { it.attach(TestMarketplace.deps(secureStore = store)) }
        val restoredBridge = FakeAgentBridge().apply { turnResults = flowOf(AgentTurnResult.Answer("Cedar")) }
        try {
            restored.agentBridgeOverride = restoredBridge
            withTimeout(5_000) { restored.uiState.first { it.tasksReady } }
            restored.onNlTextChange("What was the name?")
            restored.agentTurn()
            withTimeout(5_000) { restored.uiState.first { !it.isExecuting && it.lastAgentOutcome == "Cedar" } }
            assertEquals(
                listOf(
                    LlmChatMessage("user", "Remember the project name Cedar"),
                    LlmChatMessage("assistant", "Project name: Cedar"),
                ),
                restoredBridge.lastHistory,
            )
        } finally {
            restored.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `slow model feedback gives way to the first streamed text and clears when the turn completes`() = runBlocking {
        val firstToken = CompletableDeferred<Unit>()
        val completeReply = CompletableDeferred<Unit>()
        bridge.turnResults = flow {
            firstToken.await()
            emit(AgentTurnResult.AnswerDelta("A short"))
            completeReply.await()
            emit(AgentTurnResult.Answer("A short answer"))
        }

        startAgentTurn("Write an answer")
        assertEquals(AgentProgress.WAITING_FOR_MODEL, vm.uiState.value.agentProgress)
        assertEquals(listOf(ChatRole.USER), vm.uiState.value.transcript.map { it.role })

        mainDispatcher.scheduler.advanceTimeBy(8_001)
        assertEquals(AgentProgress.SLOW_MODEL_RESPONSE, vm.uiState.value.agentProgress)

        firstToken.complete(Unit)
        val streaming = withTimeout(5_000) { vm.uiState.first { it.streamingReply != null } }
        assertEquals(AgentProgress.STREAMING_REPLY, streaming.agentProgress)
        assertTrue(streaming.agentWorking)

        completeReply.complete(Unit)
        val completed = withTimeout(5_000) { vm.uiState.first { !it.isExecuting } }
        assertNull(completed.agentProgress)
        assertEquals(listOf("Write an answer", "A short answer"), completed.transcript.map { it.text })
        assertEquals(streaming.streamingReply?.id, completed.transcript.last().id)
    }

    @Test
    fun `stopping before the first token clears the wait and cancels the slow response reminder`() = runBlocking {
        val modelEntered = CompletableDeferred<Unit>()
        bridge.turnResults = flow {
            modelEntered.complete(Unit)
            awaitCancellation()
        }

        startAgentTurn("Wait for a reply")
        withTimeout(5_000) { modelEntered.await() }
        assertEquals(AgentProgress.WAITING_FOR_MODEL, vm.uiState.value.agentProgress)

        vm.cancelAgentTurn()
        withTimeout(5_000) { vm.uiState.first { !it.isExecuting } }
        mainDispatcher.scheduler.advanceTimeBy(8_001)

        assertNull(vm.uiState.value.agentProgress)
        assertNull(vm.uiState.value.streamingReply)
        assertEquals(listOf(ChatRole.USER, ChatRole.SYSTEM), vm.uiState.value.transcript.map { it.role })
        val transcript = vm.uiState.value.transcript
        assertEquals("Task stopped", transcript.last().text)
        assertEquals("Task stopped", vm.uiState.value.lastAgentOutcome)
        assertEquals(transcript.first().id, transcript.last().retryOfUserMessageId)
        vm.restoreRequestForEditing(transcript.last().id)
        assertEquals("Wait for a reply", vm.uiState.value.nlText)
        assertEquals(transcript, vm.uiState.value.transcript)
    }

    @Test
    fun `stopping a streamed answer preserves partial text and retries with completed context`() = runBlocking {
        bridge.turnResults = flowOf(AgentTurnResult.Answer("Topic saved"))
        startAgentTurn("Remember the topic")
        withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome == "Topic saved" } }

        bridge.turnResults = flow {
            emit(AgentTurnResult.AnswerDelta("Partial"))
            emit(AgentTurnResult.AnswerDelta(" answer"))
            awaitCancellation()
        }
        startAgentTurn("Write an answer")
        val partial = withTimeout(5_000) {
            vm.uiState.first { it.streamingReply?.text == "Partial answer" }.streamingReply
        }

        vm.cancelAgentTurn()
        val stopped = withTimeout(5_000) { vm.uiState.first { !it.isExecuting } }

        assertNull(stopped.streamingReply)
        assertEquals(partial, stopped.transcript[stopped.transcript.lastIndex - 1])
        assertTrue(stopped.transcript.last().text.contains("Task stopped"))
        vm.restoreRequestForEditing(stopped.transcript.last().id)
        assertEquals("Write an answer", vm.uiState.value.nlText)
        assertEquals(stopped.transcript, vm.uiState.value.transcript)

        bridge.turnResults = flowOf(AgentTurnResult.Answer("A short answer"))
        startAgentTurn("Write a shorter answer")
        withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome == "A short answer" } }
        assertEquals("Write a shorter answer", bridge.lastMessage)
        assertEquals(
            listOf(LlmChatMessage("user", "Remember the topic"), LlmChatMessage("assistant", "Topic saved")),
            bridge.lastHistory,
        )
    }

    @Test
    fun `stopping after a device observation leaves no retry entry even if text started`() = runBlocking {
        bridge.turnResults = flow {
            emit(AgentTurnResult.Probing("Device observation", "Updating the plan"))
            emit(AgentTurnResult.AnswerDelta("Partial answer"))
            awaitCancellation()
        }
        startAgentTurn("Check the device")
        withTimeout(5_000) { vm.uiState.first { it.streamingReply != null } }

        vm.cancelAgentTurn()
        val stopped = withTimeout(5_000) { vm.uiState.first { !it.isExecuting } }
        assertNull(stopped.transcript.last().retryOfUserMessageId)
    }

    @Test
    fun `a cancellation from the bridge does not count as a user stop`() = runBlocking {
        bridge.turnResults = flow { throw CancellationException("Bridge cancelled") }
        startAgentTurn("Write an answer")
        val stopped = withTimeout(5_000) { vm.uiState.first { !it.isExecuting && it.lastAgentOutcome != null } }
        assertNull(stopped.transcript.last().retryOfUserMessageId)
    }

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
            AgentTurnResult.PlanReady(
                ir,
                needsConfirmation = true,
                headline = "Plan needs approval: photo.search, photo.enhance",
            ),
        )

        startAgentTurn("find and enhance my photos")
        withTimeout(10_000) { vm.uiState.first { !it.isExecuting } }

        assertEquals("the shell uses one stable session id", "main", bridge.lastSession)
        assertEquals("find and enhance my photos", bridge.lastMessage)
        val state = vm.uiState.value
        assertNotNull("PlanReady should stage a preview for the dialog", state.pendingAgentPlan)
        vm.newTask()
        assertEquals(state.activeTaskId, vm.uiState.value.activeTaskId)
        val preview = state.pendingAgentPlan!!
        assertTrue("preview lists the probed step: $preview", preview.contains("photo.search"))
        assertTrue("preview lists the staged step: $preview", preview.contains("photo.enhance"))
        assertTrue("probe observation is logged", vm.events.value.any { it.contains("probe") })
        assertTrue("replan action is logged", vm.events.value.any { it.contains("Replanning") })
        assertTrue("confirmation hint is logged", vm.events.value.any { it.contains("needs approval") })
        assertFalse("progress indicator cleared at turn end", state.agentWorking)
        assertEquals(
            "terminal headline surfaced verbatim as the turn outcome",
            "Plan needs approval: photo.search, photo.enhance",
            state.lastAgentOutcome,
        )
    }

    @Test
    fun `UI2-allow executes staged plan and reports Done`() = runBlocking {
        bridge.turnResults = flowOf(
            AgentTurnResult.PlanReady(
                ExecutionIr.Invoke(IrInvoke(id = "photo.enhance")),
                needsConfirmation = true,
                headline = "Plan needs approval: photo.enhance",
            )
        )
        startAgentTurn("enhance my photo")
        withTimeout(10_000) { vm.uiState.first { it.pendingAgentPlan != null } }

        bridge.resumeResults = {
            flowOf(AgentTurnResult.Done("Executed 1 command(s) successfully: photo.enhance"))
        }
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
                headline = "Plan needs approval: photo.enhance",
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

    @Test
    fun `UI4-suspended turn records the headline and auto-resumes`() = runBlocking {
        // Resume time already in the past → the scheduled delay is ~0.
        bridge.turnResults = flowOf(
            AgentTurnResult.Suspended(
                resumeAtEpochMs = System.currentTimeMillis() - 1,
                reason = "still downloading",
                headline = "Suspended for 30s (still downloading); will re-evaluate",
            )
        )
        bridge.resumeSuspendedResults = flowOf(
            AgentTurnResult.Done("Executed 1 command(s) successfully: photo.enhance"),
        )

        startAgentTurn("enhance when the download finishes")
        // The suspension is logged and, since the resume time is already past,
        // the demo auto-resumes via resumeSuspended to the terminal Done outcome.
        withTimeout(10_000) {
            vm.uiState.first { it.lastAgentOutcome?.contains("Executed") == true && !it.isExecuting }
        }

        assertEquals("the demo re-entered via resumeSuspended", 1, bridge.resumeSuspendedCalls)
        assertTrue("suspension is logged", vm.events.value.any { it.contains("Suspended") })
        assertTrue("resume is logged", vm.events.value.any { it.contains("resuming suspended") })
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun startAgentTurn(goal: String) {
        vm.onNlTextChange(goal)
        vm.agentTurn()
    }
}

/** Records calls and replays canned [AgentTurnResult] flows. */
private class FakeAgentBridge : AgentBridge {
    var turnResults: Flow<AgentTurnResult> = emptyFlow()
    var resumeResults: (Boolean) -> Flow<AgentTurnResult> = { emptyFlow() }

    var resumeSuspendedResults: Flow<AgentTurnResult> = emptyFlow()

    var lastSession: String? = null
    var lastMessage: String? = null
    var lastHistory: List<LlmChatMessage> = emptyList()
    var lastApproved: Boolean? = null
    var resumeSuspendedCalls = 0

    override fun runTurn(sessionId: String, userMessage: String): Flow<AgentTurnResult> {
        lastSession = sessionId
        lastMessage = userMessage
        return turnResults
    }

    override fun runTurn(
        sessionId: String,
        userMessage: String,
        history: List<LlmChatMessage>,
    ): Flow<AgentTurnResult> {
        lastHistory = history
        return runTurn(sessionId, userMessage)
    }

    override suspend fun resume(sessionId: String, approved: Boolean): Flow<AgentTurnResult> {
        lastApproved = approved
        return resumeResults(approved)
    }

    override suspend fun cancel(sessionId: String) {
        // no-op: the kernel-side cancel path is covered by AgentLoopTest A9.
    }

    override fun resumeSuspended(sessionId: String): Flow<AgentTurnResult> {
        resumeSuspendedCalls++
        lastSession = sessionId
        return resumeSuspendedResults
    }
}
