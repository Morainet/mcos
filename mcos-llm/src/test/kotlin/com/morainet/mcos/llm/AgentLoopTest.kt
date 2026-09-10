package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.ExecuteHandle
import com.morainet.mcos.runtime.core.api.ExecutionStatus
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.RuntimeGateway
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.EventEnvelope
import com.morainet.mcos.runtime.core.events.EventFilter
import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tests for [McosAgent] — the multi-turn Agent loop (06-agent.md §11,
 * §18.1 test matrix).
 *
 * A1 read-prefix only · A2 observation folding · A3 §18.1 streamed sequence ·
 * A4 approve→execute→Done · A5/A5b decline paths · A6 replan cap ·
 * A7 probe cap · A8 wall-clock cap · A9 cancel mid-probe ·
 * A10 read auto-run / write never auto-run · A11 clarify passthrough ·
 * A12 refuse passthrough · A13 session persistence & isolation ·
 * A14 unresolvable step is never probed · A15 §14.1 replan drift guard ·
 * A16 agent.* lifecycle events.
 */
class AgentLoopTest {

    private lateinit var registry: CommandRegistry

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        // reads
        registerCommand("photo.search", SideEffectClass.read)
        registerCommand("photo.stats", SideEffectClass.read)
        registerCommand("weather.today", SideEffectClass.read)
        registerCommand("camera.scan", SideEffectClass.read)
        // beyond read
        registerCommand("photo.enhance", SideEffectClass.write)
        registerCommand("fs.delete", SideEffectClass.destructive)
        registerCommand("mail.send", SideEffectClass.network)
    }

    // ═══════════════════════════════════════════════════════════════
    // A1/A2/A3: the canonical probe → replan → plan-ready sequence
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A1-mixed read-write plan auto-executes only the read prefix`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"sequence","steps":[""" +
                    """{"command":"photo.search","args":{"query":"cats"}},""" +
                    """{"command":"photo.enhance","args":{"strength":2}}]}""",
                """{"type":"invoke","command":"photo.enhance","args":{"strength":2}}""",
            )
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        val results = agent.runTurn("s1", "find my cat photos and enhance the best one").toList()

        assertEquals(2, results.size, "expected Probing + PlanReady, got $results")
        assertEquals(listOf(listOf("photo.search")), gateway.probeCalls, "only the leading read step may auto-run")
        assertTrue(
            gateway.probeCalls.flatten().none { it == "photo.enhance" },
            "write-class steps must never be auto-probed",
        )
    }

    @Test
    fun `A2-probe observations are folded into the next compile`() = runBlocking {
        val gateway = FakeRuntimeGateway(
            probeResults = { cmds ->
                cmds.map { CommandResult.Ok(buildJsonObject { put("count", 47) }) }
            },
        )
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"sequence","steps":[""" +
                    """{"command":"photo.search","args":{"query":"cats"}},""" +
                    """{"command":"photo.enhance","args":{"strength":2}}]}""",
                """{"type":"invoke","command":"photo.enhance","args":{"strength":2}}""",
            )
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        agent.runTurn("s2", "find my cat photos and enhance the best one").toList()

        assertEquals(2, provider.capturedMessages.size, "probe + replan = two compiles")
        val secondUser = provider.capturedMessages[1].last { it.role == "user" }.content
        assertTrue(secondUser.contains("[Probe observations]"), "second compile must carry the folded header")
        assertTrue(secondUser.contains("47"), "the observed count=47 must be folded in: $secondUser")
    }

    @Test
    fun `A3-18_1 sequence Probing then PlanReady with needsConfirmation`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"sequence","steps":[""" +
                    """{"command":"photo.search","args":{"query":"cats"}},""" +
                    """{"command":"photo.enhance","args":{"strength":2}}]}""",
                """{"type":"invoke","command":"photo.enhance","args":{"strength":2}}""",
            )
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        val results = agent.runTurn("s3", "find my cat photos and enhance the best one").toList()

        val probing = results[0] as AgentTurnResult.Probing
        assertTrue(probing.observation.contains("photo.search"), "observation names the probed command")
        assertTrue(probing.nextAction.contains("Replanning"), "nextAction tells the UI a replan follows")

        val planReady = results[1] as AgentTurnResult.PlanReady
        assertTrue(planReady.needsConfirmation, "plan containing a write step needs confirmation")
        val invoke = planReady.ir as ExecutionIr.Invoke
        assertEquals("photo.enhance", invoke.invoke.id, "staged plan is the post-observation plan")
        // The terminal headline is self-contained: names the outcome and the
        // command without needing the original message or the probe state.
        assertTrue(
            planReady.headline.contains("needs approval") && planReady.headline.contains("photo.enhance"),
            "PlanReady headline is self-contained: ${planReady.headline}",
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // A4/A5/A5b: the approval loop
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A4-approve executes the staged plan via IrJson and reports Done`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        val provider = FakeConstrainedProvider(
            listOf("""{"type":"invoke","command":"photo.enhance","args":{"strength":2}}"""),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        agent.runTurn("s4", "enhance my photo").toList()
        val outcome = agent.resume("s4", approved = true).toList().single()

        assertEquals(1, gateway.executedRequests.size, "approval submits exactly one run")
        val request = gateway.executedRequests[0]
        assertEquals(Source.CHAT, request.source, "agent execution is attributed to CHAT")
        val payload = request.payload as Payload.IrJson
        assertTrue(payload.json.toString().contains("photo.enhance"), "payload carries the staged IR")
        assertTrue(outcome is AgentTurnResult.Done, "expected Done, got $outcome")
        assertTrue(
            (outcome as AgentTurnResult.Done).summary.contains("1 command(s)"),
            "summary reports the step count",
        )
    }

    @Test
    fun `A5-deny returns Declined without executing`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        val provider = FakeConstrainedProvider(
            listOf("""{"type":"invoke","command":"photo.enhance","args":{"strength":2}}"""),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        agent.runTurn("s5", "enhance my photo").toList()
        val outcome = agent.resume("s5", approved = false).toList().single()

        assertEquals(AgentTurnResult.Declined("user_declined"), outcome)
        assertTrue(gateway.executedRequests.isEmpty(), "denied plans never reach the runtime")
    }

    @Test
    fun `A5b-resume without a pending plan declines instead of throwing`() = runBlocking {
        val agent = McosAgent(
            LlmPlanner(FakeConstrainedProvider(emptyList()), registry),
            FakeRuntimeGateway(),
            registry,
        )

        val outcome = agent.resume("missing-session", approved = true).toList().single()

        assertEquals(AgentTurnResult.Declined("no_pending_plan"), outcome)
    }

    // ═══════════════════════════════════════════════════════════════
    // A6/A7/A8: §11.2 caps — any exceeded cap is a QUOTA refusal
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A6-exceeding maxReplanRounds refuses with QUOTA`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        // Every compile proposes a *different* read step, so the loop never
        // converges and must burn through the replan budget.
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"invoke","command":"photo.search"}""",
                """{"type":"invoke","command":"photo.stats"}""",
                """{"type":"invoke","command":"weather.today"}""",
            )
        )
        val agent = McosAgent(
            LlmPlanner(provider, registry), gateway, registry,
            caps = AgentCaps(maxProbeSteps = 10, maxReplanRounds = 2),
        )

        val results = agent.runTurn("s6", "keep looking things up").toList()
        val terminal = results.last() as AgentTurnResult.Refuse

        assertEquals("QUOTA", terminal.category)
        assertEquals("agent_cap_exceeded", terminal.reason)
        assertEquals(2, gateway.probeCalls.size, "exactly maxReplanRounds probes ran before the refusal")
    }

    @Test
    fun `A7-exceeding maxProbeSteps refuses with QUOTA without executing`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"sequence","steps":[""" +
                    """{"command":"photo.search"},{"command":"photo.stats"},""" +
                    """{"command":"weather.today"},{"command":"camera.scan"}]}""",
            )
        )
        val agent = McosAgent(
            LlmPlanner(provider, registry), gateway, registry,
            caps = AgentCaps(maxProbeSteps = 3, maxReplanRounds = 5),
        )

        val results = agent.runTurn("s7", "read everything at once").toList()
        val terminal = results.last() as AgentTurnResult.Refuse

        assertEquals("QUOTA", terminal.category)
        assertEquals("agent_cap_exceeded", terminal.reason)
        assertTrue(gateway.probeCalls.isEmpty(), "an over-budget probe batch must not partially execute")
    }

    @Test
    fun `A8-exceeding maxWallClockMs refuses with QUOTA`() = runBlocking {
        val provider = FakeConstrainedProvider(
            listOf("""{"type":"invoke","command":"photo.search"}"""),
            delayMs = 5_000,
        )
        val agent = McosAgent(
            LlmPlanner(provider, registry), FakeRuntimeGateway(), registry,
            caps = AgentCaps(maxWallClockMs = 100),
        )

        val results = withTimeout(5_000) {
            agent.runTurn("s8", "slow compile").toList()
        }
        val terminal = results.single() as AgentTurnResult.Refuse

        assertEquals("QUOTA", terminal.category)
        assertEquals("agent_cap_exceeded", terminal.reason)
    }

    // ═══════════════════════════════════════════════════════════════
    // A9: user cancel always wins
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A9-cancel during a probe aborts the turn immediately`() = runBlocking {
        val gateway = FakeRuntimeGateway(probeDelayMs = 5_000)
        val provider = FakeConstrainedProvider(
            listOf("""{"type":"invoke","command":"photo.search"}"""),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        val collected = mutableListOf<AgentTurnResult>()
        val job = launch {
            agent.runTurn("s9", "interruptible lookup").toList(collected)
        }
        awaitTrue { gateway.probeCalls.size == 1 } // probe entered
        agent.cancel("s9")
        withTimeout(3_000) { job.join() }

        assertTrue(gateway.probeCalls.size == 1, "the in-flight probe was entered once")
        assertTrue(collected.isEmpty(), "no Probing state may be emitted after cancel: $collected")
    }

    // ═══════════════════════════════════════════════════════════════
    // A10: read auto-runs, everything beyond read never does
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A10-pure write plan is staged directly without any probe`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        val provider = FakeConstrainedProvider(
            listOf("""{"type":"invoke","command":"photo.enhance","args":{"strength":2}}"""),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        val results = agent.runTurn("s10", "enhance my photo").toList()

        assertTrue(gateway.probeCalls.isEmpty(), "no read prefix — nothing auto-runs")
        val planReady = results.single() as AgentTurnResult.PlanReady
        assertTrue(planReady.needsConfirmation)
    }

    // ═══════════════════════════════════════════════════════════════
    // A11/A12: structured clarify / refuse passthrough (06 §7)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A11-planner clarify is forwarded verbatim`() = runBlocking {
        val provider = FakeConstrainedProvider(
            listOf("""{"type":"clarify","question":"Which photo — the first or the best?"}"""),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), FakeRuntimeGateway(), registry)

        val results = agent.runTurn("s11", "enhance my photo").toList()

        assertEquals(AgentTurnResult.Clarify("Which photo — the first or the best?"), results.single())
    }

    @Test
    fun `A12-planner refuse is forwarded with category`() = runBlocking {
        val provider = FakeConstrainedProvider(
            listOf("""{"type":"refuse","reason":"no photo library access","category":"POLICY"}"""),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), FakeRuntimeGateway(), registry)

        val results = agent.runTurn("s12", "delete everything").toList()

        assertEquals(AgentTurnResult.Refuse("POLICY", "no photo library access"), results.single())
    }

    @Test
    fun `A22-a compile that yields no commands surfaces as Refuse COMPILE_FAILED`() = runBlocking {
        // A plan that neither succeeds (no commands) nor carries a structured
        // Clarify/Refuse/Defer lands in the COMPILE_FAILED refuse category —
        // the terminal the host renders when the planner produced nothing
        // usable (01 §15.1 error-code coverage, 10 §17.1).
        val provider = FakeConstrainedProvider(listOf("""{"type":"sequence","steps":[]}"""))
        val agent = McosAgent(LlmPlanner(provider, registry), FakeRuntimeGateway(), registry)

        val results = agent.runTurn("s22", "do something").toList()

        val refuse = assertIs<AgentTurnResult.Refuse>(results.single())
        assertEquals("COMPILE_FAILED", refuse.category)
    }

    // ═══════════════════════════════════════════════════════════════
    // A13: session persistence and isolation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A13-observations persist within a session and isolate across sessions`() = runBlocking {
        // One agent, one provider, four compiles across three turns.
        val gateway = FakeRuntimeGateway(
            probeResults = { cmds ->
                cmds.map { CommandResult.Ok(buildJsonObject { put("count", 47) }) }
            },
        )
        val provider = FakeConstrainedProvider(
            listOf(
                // Turn 1, compile 1: read step → probed (observation recorded).
                """{"type":"invoke","command":"photo.search"}""",
                // Turn 1, compile 2: replan stages the write step.
                """{"type":"invoke","command":"photo.enhance"}""",
                // Turn 2 (same session): compile must inherit the observations.
                """{"type":"invoke","command":"photo.enhance"}""",
                // Turn 3 (different session): must start clean.
                """{"type":"invoke","command":"photo.enhance"}""",
            )
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        // Turn 1 in session "keep": probe folds an observation.
        agent.runTurn("keep", "search then enhance").toList()
        assertEquals(2, provider.capturedMessages.size, "turn 1 = probe compile + replan compile")

        // Turn 2 in the SAME session: prior observations ride along.
        agent.runTurn("keep", "enhance it now").toList()
        val sameSessionUser = provider.capturedMessages[2].last { it.role == "user" }.content
        assertTrue(sameSessionUser.contains("[Probe observations]"), "same-session turn inherits observations")
        assertTrue(sameSessionUser.contains("47"), "the stored observation value survives into the next turn")

        // A DIFFERENT session starts clean.
        agent.runTurn("fresh", "enhance it now").toList()
        val freshUser = provider.capturedMessages[3].last { it.role == "user" }.content
        assertFalse(freshUser.contains("[Probe observations]"), "other sessions must not see this session's data")
    }

    // ═══════════════════════════════════════════════════════════════
    // A14: unresolvable commands are never auto-run
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A14-unregistered leading command stops the read prefix`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"sequence","steps":[""" +
                    """{"command":"ghost.cmd"},{"command":"photo.enhance"}]}""",
            )
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        val results = agent.runTurn("s14", "do the impossible").toList()

        assertTrue(gateway.probeCalls.isEmpty(), "an unresolvable step is not `read` — never probed")
        val planReady = results.single() as AgentTurnResult.PlanReady
        assertTrue(planReady.needsConfirmation, "unresolvable steps always require explicit approval")
    }

    // ═══════════════════════════════════════════════════════════════
    // A15: §14.1 replan drift guard
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A15-replan introducing a new destructive command forces Clarify`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        // Compile 1: read + write. Compile 2 (after observation): swaps in a
        // destructive step never seen before — §14.1 must interpose.
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"sequence","steps":[""" +
                    """{"command":"photo.search"},{"command":"photo.enhance"}]}""",
                """{"type":"sequence","steps":[""" +
                    """{"command":"photo.search"},{"command":"fs.delete"}]}""",
            )
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        val results = agent.runTurn("s15", "clean up my photos").toList()

        val clarify = results.last() as AgentTurnResult.Clarify
        assertTrue(clarify.question.contains("fs.delete"), "the question names the drifting command")
        assertTrue(clarify.question.contains("§14.1"), "the question cites the spec rule")
        assertEquals(1, gateway.probeCalls.size, "the guard fires after exactly one probe")
    }

    // ═══════════════════════════════════════════════════════════════
    // A16: agent.* lifecycle events on the system EventBus
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A16-loop publishes agent lifecycle events`() = runBlocking {
        val bus = TypedEventBus(externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val events = mutableListOf<EventEnvelope>()
        bus.subscribe(EventFilter(typePrefix = "agent.")) { envelope ->
            synchronized(events) { events += envelope }
        }
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"sequence","steps":[""" +
                    """{"command":"photo.search"},{"command":"photo.enhance"}]}""",
                """{"type":"invoke","command":"photo.enhance"}""",
            )
        )
        val agent = McosAgent(
            LlmPlanner(provider, registry), FakeRuntimeGateway(), registry,
            eventBus = bus,
        )

        agent.runTurn("s16", "search then enhance").toList()
        awaitTrue { synchronized(events) { events.size } >= 3 }

        val types = synchronized(events) { events.map { it.type } }
        assertTrue(types.contains("agent.probe"), "probe event missing: $types")
        assertTrue(types.contains("agent.replan"), "replan event missing: $types")
        assertTrue(types.contains("agent.plan_ready"), "plan_ready event missing: $types")
        assertTrue(
            synchronized(events) { events }.all { it.source == "agent" },
            "all agent events carry source=agent",
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // A17/A18/A19: self-paced suspension (06 §11.4)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A17-defer suspends the turn with a clamped resume time and self-contained headline`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        // delay_seconds below MIN is clamped up to MIN_DELAY_SECONDS.
        val provider = FakeConstrainedProvider(
            listOf("""{"type":"defer","reason":"upload still in progress","delay_seconds":5}"""),
        )
        var fakeNow = 1_000_000L
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry, clock = { fakeNow })

        val outcome = agent.runTurn("s17", "wait for the upload then notify me").toList().single()

        assertTrue(outcome is AgentTurnResult.Suspended, "expected Suspended, got $outcome")
        outcome as AgentTurnResult.Suspended
        assertEquals("upload still in progress", outcome.reason)
        // 5s requested → clamped to the 30s floor.
        val expected = fakeNow + DeferInfo.MIN_DELAY_SECONDS * 1_000L
        assertEquals(expected, outcome.resumeAtEpochMs, "resume time clamped to MIN and offset from clock")
        assertTrue(
            outcome.headline.contains("30s") && outcome.headline.contains("upload still in progress"),
            "headline is self-contained: ${outcome.headline}",
        )
        assertEquals(0, gateway.executedRequests.size, "a suspended turn executes nothing")
    }

    @Test
    fun `A18-resumeSuspended re-enters the loop over the retained goal`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        // First compile defers; the resume compile returns a real plan.
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"defer","reason":"still downloading","delay_seconds":60}""",
                """{"type":"invoke","command":"photo.enhance","args":{}}""",
            ),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        val first = agent.runTurn("s18", "enhance when the download finishes").toList().single()
        assertTrue(first is AgentTurnResult.Suspended, "first turn suspends")

        val second = agent.resumeSuspended("s18").toList().single()
        assertTrue(second is AgentTurnResult.PlanReady, "resume produces the real plan, got $second")
        assertEquals(
            "photo.enhance",
            ((second as AgentTurnResult.PlanReady).ir as ExecutionIr.Invoke).invoke.id,
        )

        // Suspension is one-shot: a second resume finds nothing pending.
        val third = agent.resumeSuspended("s18").toList().single()
        assertTrue(third is AgentTurnResult.Declined, "second resume declines: $third")
        assertEquals("no_suspended_turn", (third as AgentTurnResult.Declined).reason)
    }

    @Test
    fun `A19-suspension survives a fresh store via the persistence seam`() = runBlocking {
        // A simple in-memory persistence standing in for the host's durable KV.
        val saved = mutableMapOf<String, AgentSessionStore.Snapshot>()
        val persistence = object : AgentSessionStore.Persistence {
            override fun save(snapshot: AgentSessionStore.Snapshot) { saved[snapshot.sessionId] = snapshot }
            override fun delete(sessionId: String) { saved.remove(sessionId) }
            override fun loadAll(): List<AgentSessionStore.Snapshot> = saved.values.toList()
        }

        // Process 1: a probe records an observation, then the turn defers.
        val store1 = AgentSessionStore(persistence)
        val provider1 = FakeConstrainedProvider(
            listOf(
                """{"type":"invoke","command":"photo.search","args":{}}""",     // read prefix → probe
                """{"type":"defer","reason":"waiting on server","delay_seconds":120}""",
            ),
        )
        val agent1 = McosAgent(LlmPlanner(provider1, registry), FakeRuntimeGateway(), registry, sessions = store1)
        val out1 = agent1.runTurn("s19", "find and enhance my best photo").toList().last()
        assertTrue(out1 is AgentTurnResult.Suspended, "process-1 turn suspends: $out1")
        assertTrue(saved.containsKey("s19"), "suspension was persisted")
        assertTrue(saved.getValue("s19").observationLog.isNotEmpty(), "the probe observation was snapshotted")

        // Process 2: brand-new store over the same durable KV; restore, then resume.
        val store2 = AgentSessionStore(persistence)
        assertEquals(listOf("s19"), store2.restore(), "restore rehydrates the suspended session")
        val provider2 = FakeConstrainedProvider(
            listOf("""{"type":"invoke","command":"photo.enhance","args":{}}"""),
        )
        val agent2 = McosAgent(LlmPlanner(provider2, registry), FakeRuntimeGateway(), registry, sessions = store2)
        val out2 = agent2.resumeSuspended("s19").toList().single()
        assertTrue(out2 is AgentTurnResult.PlanReady, "process-2 resume produces the plan: $out2")
        assertFalse(saved.containsKey("s19"), "consuming the suspension clears the durable snapshot")
    }

    // ═══════════════════════════════════════════════════════════════
    // A20/A21: parallel plans compile to a fan-out workflow (06 §11.5)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A20-parallel plan stages a fan-out workflow IR without probing`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        // Two independent reads as a parallel plan: normally a read prefix would
        // auto-run, but a parallel plan stages whole — nothing is probed.
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"parallel","steps":[
                    {"command":"photo.search","args":{}},
                    {"command":"weather.today","args":{}}
                ]}""",
            ),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        val outcome = agent.runTurn("s20", "check my photos and the weather at once").toList().single()

        assertTrue(outcome is AgentTurnResult.PlanReady, "parallel plan stages for approval, got $outcome")
        outcome as AgentTurnResult.PlanReady
        assertEquals(0, gateway.probeCalls.size, "a parallel plan never auto-runs a read prefix")
        val workflow = outcome.ir as? ExecutionIr.Workflow
            ?: error("expected a fan-out Workflow IR, got ${outcome.ir}")
        val body = workflow.body.toString()
        assertTrue(body.contains("\"parallel\""), "IR body is a parallel workflow step: $body")
        assertTrue(body.contains("photo.search") && body.contains("weather.today"), "both steps present: $body")
        assertTrue(outcome.headline.contains("Parallel plan"), "headline flags parallelism: ${outcome.headline}")
    }

    @Test
    fun `A21-approved parallel plan submits the workflow IR to the runtime`() = runBlocking {
        val gateway = FakeRuntimeGateway()
        val provider = FakeConstrainedProvider(
            listOf(
                """{"type":"parallel","steps":[
                    {"command":"photo.enhance","args":{}},
                    {"command":"mail.send","args":{}}
                ]}""",
            ),
        )
        val agent = McosAgent(LlmPlanner(provider, registry), gateway, registry)

        agent.runTurn("s21", "enhance and mail in parallel").toList()
        val done = agent.resume("s21", approved = true).toList().single()

        assertTrue(done is AgentTurnResult.Done, "approval executes to Done, got $done")
        assertEquals(1, gateway.executedRequests.size, "one run submitted")
        val payload = gateway.executedRequests[0].payload as Payload.IrJson
        assertTrue(
            payload.json.toString().contains("\"parallel\""),
            "the submitted payload is the parallel workflow IR: ${payload.json}",
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** Poll until [condition] holds; fails the test after [timeoutMs]. */
    private suspend fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                error("condition not met within ${timeoutMs}ms")
            }
            delay(20)
        }
    }

    /** Register a command with the given id and side-effect class. */
    private fun registerCommand(id: String, sideEffectClass: SideEffectClass) {
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = "agent-test-$id",
                name = "Agent Test Plugin",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Agent loop test plugin for $id",
                provider = ProviderInfo("TestOrg", "https://example.com"),
                entry = "com.morainet.mcos.plugin.test.AgentTest",
                commands = listOf(
                    CommandManifestEntry(
                        id = id,
                        version = "1.0",
                        title = id,
                        description = "test command $id",
                        sideEffectClass = sideEffectClass,
                        inputSchema = JsonObject(emptyMap()),
                    )
                ),
            )

            override fun handlers(): Map<String, CommandHandler> = emptyMap()
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }
}

/**
 * CONSTRAINED-capable replay provider: feeds IR JSON responses in order and
 * captures every message list so tests can assert observation folding.
 */
private class FakeConstrainedProvider(
    private val responses: List<String>,
    private val delayMs: Long = 0L,
) : LlmProvider {
    override val id = "fake-constrained"
    override val capabilities = setOf(Capability.CHAT, Capability.CONSTRAINED)

    val capturedMessages = mutableListOf<List<ChatMessage>>()
    private var index = 0

    override suspend fun constrainedChat(messages: List<ChatMessage>, grammar: LlmGrammar): LlmResponse {
        capturedMessages += messages
        if (delayMs > 0) delay(delayMs)
        val content = responses.getOrNull(index)
            ?: """{"type":"refuse","reason":"no more canned responses","category":"TEST"}"""
        index++
        return LlmResponse.Ok(content)
    }

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse =
        LlmResponse.Err("UNSUPPORTED", "fake is CONSTRAINED-only", false)
}

/** Records probe/execute calls; observe() immediately reports success. */
private class FakeRuntimeGateway(
    private val probeResults: (List<Command>) -> List<CommandResult> =
        { cmds -> cmds.map { CommandResult.Ok(buildJsonObject { put("ok", true) }) } },
    private val probeDelayMs: Long = 0L,
) : RuntimeGateway {
    val probeCalls = mutableListOf<List<String>>()
    val executedRequests = mutableListOf<ExecuteRequest>()

    override suspend fun execute(request: ExecuteRequest): ExecuteHandle {
        executedRequests += request
        return ExecuteHandle(runId = "run-${executedRequests.size}", status = ExecutionStatus.RUNNING)
    }

    override fun observe(runId: String): Flow<RuntimeEvent> = flow {
        emit(RuntimeEvent.RunStarted(runId, null, 0L))
        emit(RuntimeEvent.StepSucceeded(runId, 0, "step", 5L))
        emit(RuntimeEvent.RunSucceeded(runId, 12L))
    }

    override suspend fun executeProbe(steps: List<Command>): List<CommandResult> {
        probeCalls += steps.map { it.id }
        if (probeDelayMs > 0) delay(probeDelayMs)
        return probeResults(steps)
    }
}
