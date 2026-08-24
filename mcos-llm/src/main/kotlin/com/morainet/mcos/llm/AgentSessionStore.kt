package com.morainet.mcos.llm

import com.morainet.mcos.runtime.core.ir.ExecutionIr
import kotlinx.coroutines.Job

/**
 * Per-session state store for the Agent loop (06 §11).
 *
 * Follows the MemoryStore convention: a single map guarded by a plain monitor
 * (all accessors are synchronous and non-suspending, so critical sections are
 * trivially short). One session object per `sessionId`; observation history
 * and the pending plan survive across turns within a session.
 *
 * Turn-scoped budgets (probe/replan counters) deliberately do NOT live here —
 * 06 §11.2 scopes caps per turn, so [McosAgent] keeps them as loop-locals.
 *
 * @param MAX_OBSERVATIONS Cap on the retained observation log; folding only
 *        ever needs recent probes, and the log feeds the next compile's
 *        `extraContext` (bounded to keep the prompt within §4.0 token limits).
 */
class AgentSessionStore {

    /**
     * A plan staged by `PlanReady`, awaiting the user's approve/deny.
     *
     * @property ir The executable IR submitted to the runtime on approval.
     * @property commandIds Ordered ids, for summaries and event payloads.
     */
    class PendingPlan(
        val ir: ExecutionIr,
        val commandIds: List<String>,
    )

    /**
     * Mutable per-session state. All field access goes through the store's
     * synchronized methods — never touch fields directly from the loop.
     */
    class Session internal constructor(
        val id: String,
        @Volatile var goal: String,
    ) {
        internal val observationLog = mutableListOf<String>()
        internal var pending: PendingPlan? = null

        @Volatile
        internal var activeJob: Job? = null
    }

    private val lock = Any()
    private val sessions = mutableMapOf<String, Session>()

    /** Start a turn: create the session or update its goal. */
    fun begin(sessionId: String, goal: String): Session = synchronized(lock) {
        val existing = sessions[sessionId]
        if (existing != null) {
            existing.goal = goal
            existing
        } else {
            Session(sessionId, goal).also { sessions[sessionId] = it }
        }
    }

    fun get(sessionId: String): Session? = synchronized(lock) { sessions[sessionId] }

    /** Append one probe observation line-block; trims the log to the cap. */
    fun recordProbe(sessionId: String, observation: String) = synchronized(lock) {
        val session = sessions[sessionId] ?: return
        session.observationLog += observation
        while (session.observationLog.size > MAX_OBSERVATIONS) {
            session.observationLog.removeAt(0)
        }
        Unit
    }

    /** Joined observation log (oldest first) — the next compile's `extraContext`. */
    fun observations(sessionId: String): String = synchronized(lock) {
        sessions[sessionId]?.observationLog?.joinToString("\n") ?: ""
    }

    /** Stage (or clear) the plan awaiting approval. */
    fun setPending(sessionId: String, pending: PendingPlan?) = synchronized(lock) {
        sessions[sessionId]?.pending = pending
        Unit
    }

    /** Consume the pending plan; `null` if absent or already consumed. */
    fun takePending(sessionId: String): PendingPlan? = synchronized(lock) {
        val session = sessions[sessionId] ?: return null
        val pending = session.pending
        session.pending = null
        pending
    }

    /** Track the coroutine running the session's active turn (for cancel). */
    fun setActiveJob(sessionId: String, job: Job?) = synchronized(lock) {
        val session = sessions[sessionId] ?: return
        session.activeJob = job
        Unit
    }

    /** Cancel the active turn's job, if any (06 §11.2 "user cancel always wins"). */
    fun cancelActive(sessionId: String) = synchronized(lock) {
        sessions[sessionId]?.activeJob?.cancel()
        Unit
    }

    companion object {
        private const val MAX_OBSERVATIONS = 20
    }
}
