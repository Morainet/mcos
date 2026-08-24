package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.ir.ExecutionIr
import kotlinx.coroutines.flow.Flow

/**
 * Port for the multi-turn Agent loop (06-agent.md §11).
 *
 * A *turn* is one user message driving the loop
 * `compile → read-only probe → fold observations → replan → confirm → execute`.
 * The bridge streams intermediate states ([AgentTurnResult.Probing]) while the
 * loop is still working, then exactly one terminal state:
 *
 * - [AgentTurnResult.Clarify] — the planner needs more information, or a
 *   safety rule (§14.1) requires explicit user intent.
 * - [AgentTurnResult.Refuse] — the planner or the loop declined (never a
 *   crash; carries a machine-readable category).
 * - [AgentTurnResult.PlanReady] — the final plan is staged and awaiting the
 *   user's approve/deny decision via [resume].
 * - [AgentTurnResult.Declined] — the user (or a runtime cancel) said no.
 * - [AgentTurnResult.Done] — the approved plan executed to completion.
 *
 * The canonical implementation is [McosAgent]. Keeping the port separate from
 * the implementation lets the Android layer (and tests) substitute fakes the
 * same way they do for [RuntimeGateway] (01-architecture.md §3.2).
 */
interface AgentBridge {

    /**
     * Start (or restart) an Agent turn for [sessionId].
     *
     * Emits zero or more [AgentTurnResult.Probing] states, then exactly one
     * terminal state. Cancelling collection of the flow aborts the turn —
     * "user cancel always wins" (06 §11.2).
     *
     * @param sessionId Stable conversation/session key; observation history
     *        and pending plans are kept per session.
     * @param userMessage The user's goal for this turn, e.g.
     *        "find my best photo of Tom and clean it up".
     */
    fun runTurn(sessionId: String, userMessage: String): Flow<AgentTurnResult>

    /**
     * Resolve a plan previously surfaced as [AgentTurnResult.PlanReady].
     *
     * @param approved `true` executes the staged plan through the runtime
     *        (emitting [AgentTurnResult.Done] or a failure [AgentTurnResult.Refuse]
     *        on execution error); `false` emits [AgentTurnResult.Declined].
     * @throws IllegalStateException-free: a missing/already-consumed pending
     *         plan emits [AgentTurnResult.Declined] with reason
     *         `"no_pending_plan"` instead of throwing.
     */
    suspend fun resume(sessionId: String, approved: Boolean): Flow<AgentTurnResult>

    /**
     * Cooperatively cancel the turn currently active for [sessionId]
     * (06 §11.2: user cancel always wins). No-op if no turn is active.
     */
    suspend fun cancel(sessionId: String)
}

/**
 * One streamed state of an Agent turn. See [AgentBridge.runTurn].
 */
sealed class AgentTurnResult {

    /**
     * The loop staged a final plan and is waiting for approve/deny.
     *
     * @property ir The executable IR ([ExecutionIr.Invoke] for a single step,
     *        [ExecutionIr.Sequence] otherwise) — the UI can preview it and,
     *        on approval, the runtime receives it as `Payload.IrJson`.
     * @property needsConfirmation `true` when any step is beyond
     *        `sideEffectClass: read` — i.e. the runtime's PermissionKernel
     *        will challenge it and the user must approve here first.
     *        All-read plans also pass through here, but with
     *        `needsConfirmation = false`.
     */
    data class PlanReady(
        val ir: ExecutionIr,
        val needsConfirmation: Boolean,
    ) : AgentTurnResult()

    /**
     * A read-only probe batch executed; observations are folded into the next
     * compile (06 §11.1 "Replan with observations").
     *
     * @property observation Compact `commandId → result` lines from the probe.
     * @property nextAction What the loop does next, for UI progress display
     *        (e.g. `"Replanning with observations…"`).
     */
    data class Probing(
        val observation: String,
        val nextAction: String,
    ) : AgentTurnResult()

    /** The planner asked a clarifying question; answer with a new [AgentBridge.runTurn]. */
    data class Clarify(val question: String) : AgentTurnResult()

    /**
     * The loop declined. `category` is machine-readable:
     * `QUOTA` (agent cap exceeded), `POLICY` (planner refusal),
     * `COMPILE_FAILED` (no provider produced a plan),
     * `EXECUTION_FAILED` / `EXECUTION_TIMEOUT` (post-approval runtime errors).
     */
    data class Refuse(val category: String, val reason: String) : AgentTurnResult()

    /** The approved plan executed to completion; [summary] is human-readable. */
    data class Done(val summary: String) : AgentTurnResult()

    /** The user rejected the plan, the run was cancelled, or no plan was pending. */
    data class Declined(val reason: String) : AgentTurnResult()
}

/**
 * Per-turn Agent caps (06 §11.2). Exceeding any cap terminates the turn with
 * `Refuse("QUOTA", "agent_cap_exceeded")` — the loop never silently truncates.
 *
 * The budget is consumed across replans *within one turn* (compile → probe →
 * compile counts against the same budget; a new [AgentBridge.runTurn] resets
 * it). Cloud default 30s wall clock; on-device deployments should construct
 * 15s per spec.
 *
 * @property maxProbeSteps Total read-only steps that may auto-run per turn.
 * @property maxReplanRounds Maximum number of replans (extra compiles fed by
 *        probe observations) per turn.
 * @property maxWallClockMs Hard ceiling on the whole turn.
 */
data class AgentCaps(
    val maxProbeSteps: Int = 3,
    val maxReplanRounds: Int = 2,
    val maxWallClockMs: Long = 30_000L,
)
