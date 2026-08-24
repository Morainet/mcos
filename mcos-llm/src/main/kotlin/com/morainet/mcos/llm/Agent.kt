package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.RuntimeGateway
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.EventEnvelope
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.ir.IrInvoke
import com.morainet.mcos.runtime.core.ir.IrSequence
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.registry.ResolveResult
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Reference [AgentBridge] implementation: the multi-turn Agent loop from
 * 06-agent.md §11.
 *
 * ```
 * compile → [injection check] → read-prefix probe → fold observations
 *        ↑                                              ↓
 *        └──────────── replan (≤ maxReplanRounds) ←──────┘
 *                                                       ↓
 *                          PlanReady → user approve/deny → execute → Done
 * ```
 *
 * ## Safety rules (enforced here, not delegated to the LLM)
 *
 * - **Read-prefix only**: the only steps that ever auto-run are a *leading*
 *   run of commands whose resolved `sideEffectClass` is `read`
 *   (§11.3). Write/network/destructive steps — and unresolvable commands —
 *   stop the prefix and wait for explicit user approval.
 * - **Caps** (§11.2): [AgentCaps] budgets per turn; any cap exceeded →
 *   `Refuse("QUOTA", "agent_cap_exceeded")`, never silent truncation.
 * - **§14.1 drift guard**: a replan that introduces a destructive/network
 *   command absent from the previous plan forces a [AgentTurnResult.Clarify]
 *   even when the planner is confident.
 * - **User cancel always wins**: cancelling the [runTurn] flow (or calling
 *   [cancel]) aborts the turn immediately.
 *
 * The loop drives the kernel exclusively through the [RuntimeGateway] port —
 * probes via [RuntimeGateway.executeProbe] (full Stage 3→10 pipeline, audited
 * `AGENT_PROBE`), final execution via [RuntimeGateway.execute] with
 * `Payload.IrJson` — so this module stays decoupled from the runtime facade
 * (01-architecture.md §3.2: llm and the facade are sibling clients).
 *
 * @param planner NL→IR compiler; replans are re-compiles with the folded
 *        observations passed as `extraContext` (06 §11.1).
 * @param runtime Kernel port for probes and final execution.
 * @param registry Command registry, used to resolve each step's
 *        `sideEffectClass` for the read-prefix rule.
 * @param injectionDetector Optional detector run on every compiled plan
 *        (08-security.md §11.3); a suspicion is surfaced as Clarify.
 * @param eventBus Optional bus for `agent.*` lifecycle events
 *        (`agent.plan_ready`, `agent.probe`, `agent.replan`, `agent.declined`,
 *        `agent.executed`) — dot.case types, `source = "agent"`.
 * @param caps Per-turn budgets (§11.2).
 * @param eventTimeoutMs Ceiling on waiting for a terminal runtime event after
 *        approval (P0-C4 pattern from ChatOrchestrator).
 */
class McosAgent(
    private val planner: LlmPlanner,
    private val runtime: RuntimeGateway,
    private val registry: CommandRegistry,
    private val injectionDetector: PromptInjectionDetector? = null,
    private val eventBus: EventBus? = null,
    private val caps: AgentCaps = AgentCaps(),
    private val eventTimeoutMs: Long = 30_000L,
    private val sessions: AgentSessionStore = AgentSessionStore(),
) : AgentBridge {

    override fun runTurn(sessionId: String, userMessage: String): Flow<AgentTurnResult> = flow {
        sessions.begin(sessionId, userMessage)
        sessions.setActiveJob(sessionId, currentCoroutineContext().job)
        try {
            // Per-turn budgets (§11.2): reset on every runTurn, consumed
            // across replans within the turn.
            var probeCount = 0
            var replanCount = 0
            var lastProbedPlanIds: Set<String>? = null
            val seenCommandIds = mutableSetOf<String>()

            val terminal = withTimeoutOrNull(caps.maxWallClockMs) {
                while (true) {
                    // 1. Compile — folds this session's observations into the
                    //    prompt as extraContext (06 §11.1).
                    val context = sessions.observations(sessionId)
                    val plan = planner.plan(userMessage, extraContext = context.ifBlank { null })
                    currentCoroutineContext().ensureActive()

                    // 2. Forward structured planner outcomes verbatim.
                    plan.clarify?.let { return@withTimeoutOrNull AgentTurnResult.Clarify(it) }
                    plan.refuse?.let {
                        return@withTimeoutOrNull AgentTurnResult.Refuse(it.category ?: "POLICY", it.reason)
                    }
                    if (!plan.isSuccess) {
                        return@withTimeoutOrNull AgentTurnResult.Refuse(
                            category = "COMPILE_FAILED",
                            reason = plan.error?.message ?: plan.thoughts ?: "planning failed",
                        )
                    }

                    // 3. Injection detection on every compile (08 §11.3).
                    val detector = injectionDetector
                    if (detector != null) {
                        val detection = detector.detect(utterance = userMessage, commands = plan.commands)
                        if (detection is InjectionDetection.Suspected) {
                            return@withTimeoutOrNull AgentTurnResult.Clarify(
                                "Plan flagged by the injection detector (${detection.reason}): " +
                                    "${detection.evidence} — confirm you really want to run " +
                                    plan.commands.joinToString { it.id },
                            )
                        }
                    }

                    val commandIds = plan.commands.map { it.id }.toSet()

                    // §14.1 drift guard: from the first replan on, a plan may not
                    // introduce a destructive/network (or unresolvable) command
                    // absent from every earlier plan of this turn — force a
                    // Clarify even if the planner is confident.
                    if (seenCommandIds.isNotEmpty()) {
                        val novelHighRisk = plan.commands
                            .filter { it.id !in seenCommandIds && isHighRisk(it.id) }
                            .map { it.id }
                        if (novelHighRisk.isNotEmpty()) {
                            return@withTimeoutOrNull AgentTurnResult.Clarify(
                                "Replan introduced high-risk command(s) " +
                                    "${novelHighRisk.joinToString()} not present in earlier plans — " +
                                    "confirm this is intended (§14.1)",
                            )
                        }
                    }
                    seenCommandIds += commandIds

                    // 4. Read-prefix extraction (§11.3): leading `read` steps only.
                    val prefix = readPrefix(plan.commands)

                    if (prefix.isEmpty()) {
                        // First step is beyond `read` (or unresolvable): stage the
                        // plan for approval instead of executing anything.
                        return@withTimeoutOrNull stagePlan(sessionId, plan.commands)
                    }

                    // Converged replan: the planner re-proposed exactly what was
                    // just probed — no new information, stop looping and ask
                    // (pure-read goals terminate here with needsConfirmation=false).
                    if (commandIds == lastProbedPlanIds) {
                        return@withTimeoutOrNull stagePlan(sessionId, plan.commands)
                    }

                    // 5. Caps (§11.2) — any exceeded cap is a QUOTA refusal.
                    if (probeCount + prefix.size > caps.maxProbeSteps ||
                        replanCount >= caps.maxReplanRounds
                    ) {
                        return@withTimeoutOrNull AgentTurnResult.Refuse("QUOTA", "agent_cap_exceeded")
                    }

                    // 6. Probe: auto-run the read prefix through the kernel.
                    val results = runtime.executeProbe(prefix)
                    currentCoroutineContext().ensureActive()

                    val observation = formatObservation(prefix, results)
                    sessions.recordProbe(sessionId, observation)
                    probeCount += prefix.size
                    replanCount += 1

                    publish(
                        "agent.probe",
                        buildJsonObject {
                            put("session_id", sessionId)
                            put("steps", JsonArray(prefix.map { JsonPrimitive(it.id) }))
                            put("observation", observation)
                        },
                    )
                    publish(
                        "agent.replan",
                        buildJsonObject {
                            put("session_id", sessionId)
                            put("round", replanCount)
                        },
                    )

                    emit(AgentTurnResult.Probing(observation, "Replanning with observations…"))

                    lastProbedPlanIds = commandIds
                    // Loop back to step 1 with the folded observations.
                }
                @Suppress("UNREACHABLE_CODE")
                null // while(true) only exits via return@withTimeoutOrNull
            }

            // null ⇒ wall-clock cap exceeded; the loop never truncates silently.
            emit(terminal ?: AgentTurnResult.Refuse("QUOTA", "agent_cap_exceeded"))
        } finally {
            sessions.setActiveJob(sessionId, null)
        }
    }

    override suspend fun resume(sessionId: String, approved: Boolean): Flow<AgentTurnResult> = flow {
        val pending = sessions.takePending(sessionId)
        if (pending == null) {
            emit(AgentTurnResult.Declined("no_pending_plan"))
            return@flow
        }
        if (!approved) {
            publish(
                "agent.declined",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("command_ids", JsonArray(pending.commandIds.map { JsonPrimitive(it) }))
                },
            )
            emit(AgentTurnResult.Declined("user_declined"))
            return@flow
        }

        sessions.setActiveJob(sessionId, currentCoroutineContext().job)
        try {
            val request = ExecuteRequest(
                source = Source.CHAT,
                payload = Payload.IrJson(irToJsonElement(pending.ir)),
            )
            val handle = runtime.execute(request)

            // Observe until a terminal event or eventTimeoutMs (P0-C4):
            // the runtime's SharedFlow never completes on its own.
            val events = mutableListOf<RuntimeEvent>()
            val timedOut = withTimeoutOrNull(eventTimeoutMs) {
                coroutineScope {
                    val collector = launch {
                        runtime.observe(handle.runId).collect { event ->
                            events += event
                            when (event) {
                                is RuntimeEvent.RunSucceeded,
                                is RuntimeEvent.RunFailed,
                                is RuntimeEvent.RunCancelled -> cancel() // terminal: stop collecting
                                else -> { /* progress — keep collecting */ }
                            }
                        }
                    }
                    collector.join()
                }
            } == null

            val outcome = when {
                timedOut -> AgentTurnResult.Refuse(
                    "EXECUTION_TIMEOUT",
                    "no terminal event within ${eventTimeoutMs}ms",
                )
                events.any { it is RuntimeEvent.RunSucceeded } -> AgentTurnResult.Done(
                    "Executed ${pending.commandIds.size} command(s) successfully",
                )
                events.any { it is RuntimeEvent.RunCancelled } -> AgentTurnResult.Declined("run_cancelled")
                else -> {
                    val failed = events.filterIsInstance<RuntimeEvent.RunFailed>().firstOrNull()
                    AgentTurnResult.Refuse("EXECUTION_FAILED", failed?.error ?: "execution failed")
                }
            }
            publish(
                "agent.executed",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("command_ids", JsonArray(pending.commandIds.map { JsonPrimitive(it) }))
                    put("outcome", outcome::class.simpleName ?: "unknown")
                },
            )
            emit(outcome)
        } finally {
            sessions.setActiveJob(sessionId, null)
        }
    }

    override suspend fun cancel(sessionId: String) {
        sessions.cancelActive(sessionId)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Stage [commands] as the session's pending plan and build the matching
     * `PlanReady` state. `needsConfirmation` mirrors whether the runtime's
     * PermissionKernel will challenge any step (i.e. any non-`read` step).
     */
    private suspend fun stagePlan(
        sessionId: String,
        commands: List<Command>,
    ): AgentTurnResult.PlanReady {
        val ir = toExecutionIr(commands)
        val commandIds = commands.map { it.id }
        sessions.setPending(sessionId, AgentSessionStore.PendingPlan(ir, commandIds))
        publish(
            "agent.plan_ready",
            buildJsonObject {
                put("session_id", sessionId)
                put("command_ids", JsonArray(commandIds.map { JsonPrimitive(it) }))
                put(
                    "needs_confirmation",
                    commands.any { sideEffectOf(it.id) != SideEffectClass.read },
                )
            },
        )
        return AgentTurnResult.PlanReady(
            ir = ir,
            needsConfirmation = commands.any { sideEffectOf(it.id) != SideEffectClass.read },
        )
    }

    /**
     * Leading run of steps whose resolved [SideEffectClass] is `read`
     * (§11.3). Unresolvable commands resolve to `null` ≠ `read`, so they
     * stop the prefix — the Agent never auto-runs what it can't classify.
     */
    private fun readPrefix(commands: List<Command>): List<Command> {
        val prefix = ArrayList<Command>()
        for (command in commands) {
            if (sideEffectOf(command.id) != SideEffectClass.read) break
            prefix += command
        }
        return prefix
    }

    private fun sideEffectOf(commandId: String): SideEffectClass? =
        when (val resolved = registry.resolve(commandId)) {
            is ResolveResult.Found -> resolved.entry.descriptor.sideEffectClass
            else -> null
        }

    /** §14.1 high-risk classes: steps a replan may not introduce silently. */
    private fun isHighRisk(commandId: String): Boolean {
        val sideEffect = sideEffectOf(commandId) ?: return true // unresolvable = high risk
        return sideEffect == SideEffectClass.network || sideEffect == SideEffectClass.destructive
    }

    /**
     * Compact per-step observation lines: ``photo.search → {"count":47}``.
     * Errors become ``cmd → ERROR(CODE): message`` so a replan can route
     * around the failure. Truncated to keep the folded context within the
     * §4.0 prompt budget.
     */
    private fun formatObservation(commands: List<Command>, results: List<CommandResult>): String {
        val joined = commands.mapIndexed { index, command ->
            val result = results.getOrNull(index)
            val body = when (result) {
                is CommandResult.Ok -> result.value.toString()
                is CommandResult.Err -> "ERROR(${result.code}): ${result.message}"
                null -> "no result"
            }
            "${command.id} → $body"
        }.joinToString("\n")
        return if (joined.length <= OBSERVATION_MAX_CHARS) joined
        else joined.take(OBSERVATION_MAX_CHARS) + "…"
    }

    private fun toExecutionIr(commands: List<Command>): ExecutionIr {
        val invokes = commands.map { IrInvoke(id = it.id, args = it.args) }
        return if (invokes.size == 1) {
            ExecutionIr.Invoke(invokes.first())
        } else {
            ExecutionIr.Sequence(IrSequence(steps = invokes))
        }
    }

    private fun irToJsonElement(ir: ExecutionIr): JsonObject = when (ir) {
        is ExecutionIr.Invoke ->
            Json.encodeToJsonElement(IrInvoke.serializer(), ir.invoke).jsonObject
        is ExecutionIr.Sequence ->
            Json.encodeToJsonElement(IrSequence.serializer(), ir.sequence).jsonObject
        is ExecutionIr.Workflow -> ir.body.jsonObject
    }

    private fun publish(type: String, payload: JsonObject) {
        eventBus?.publishEvent(
            EventEnvelope(
                type = type,
                timestamp = System.currentTimeMillis(),
                payload = payload,
                source = "agent",
            ),
        )
    }

    companion object {
        /** Folded-observation truncation bound (06 §4.0 prompt budget). */
        internal const val OBSERVATION_MAX_CHARS = 2_000
    }
}
