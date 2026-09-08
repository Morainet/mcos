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
 * ## Suspension persistence (06 §11.4)
 *
 * A session that ends a turn in [AgentTurnResult.Suspended] must survive
 * process death so a scheduled wake-up can resume it. The in-RAM map alone
 * cannot do that, so the store takes an optional [persistence] seam: on
 * [markSuspended] it snapshots the resumable session (goal + observation log)
 * out through the seam, and [restore] rehydrates snapshots on a fresh process.
 * A store built with the default no-op seam behaves exactly as before —
 * RAM-only — so nothing on the JVM path changes unless a host wires storage.
 */
class AgentSessionStore(
    private val persistence: Persistence = Persistence.None,
) {

    /**
     * Durable snapshot of a session that ended a turn suspended: the minimum
     * needed to re-enter [McosAgent.resumeSuspended] on a fresh process.
     */
    data class Snapshot(
        val sessionId: String,
        val goal: String,
        val observationLog: List<String>,
    )

    /**
     * Host seam for durable suspension state (06 §11.4). Implementations back
     * this with whatever the host already uses for durable KV — the Android
     * host wires a `SecureStore`-backed adapter. All methods are synchronous;
     * keep them cheap (the store calls them inside its monitor).
     */
    interface Persistence {
        fun save(snapshot: Snapshot)
        fun delete(sessionId: String)
        fun loadAll(): List<Snapshot>

        /** Default: no durability (RAM-only, the pre-§11.4 behaviour). */
        object None : Persistence {
            override fun save(snapshot: Snapshot) = Unit
            override fun delete(sessionId: String) = Unit
            override fun loadAll(): List<Snapshot> = emptyList()
        }
    }

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

        /** True between [markSuspended] and the matching [takeSuspended]. */
        @Volatile
        internal var suspended: Boolean = false

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

    /**
     * Mark [sessionId] suspended and snapshot it through [persistence] so a
     * scheduled wake-up can resume it after process death (06 §11.4).
     */
    fun markSuspended(sessionId: String) = synchronized(lock) {
        val session = sessions[sessionId] ?: return
        session.suspended = true
        persistence.save(
            Snapshot(sessionId, session.goal, session.observationLog.toList()),
        )
    }

    /**
     * Consume a pending suspension: returns the retained goal and clears the
     * suspended flag + durable snapshot, or `null` if the session was not
     * suspended. Idempotent — a second call returns `null`.
     */
    fun takeSuspended(sessionId: String): String? = synchronized(lock) {
        val session = sessions[sessionId] ?: return null
        if (!session.suspended) return null
        session.suspended = false
        persistence.delete(sessionId)
        session.goal
    }

    /**
     * Rehydrate suspended sessions from durable storage into the in-RAM map
     * (call once on process start, before any [resumeSuspended]). Existing
     * live sessions are never overwritten. Returns the restored session ids.
     */
    fun restore(): List<String> = synchronized(lock) {
        persistence.loadAll().mapNotNull { snap ->
            if (sessions.containsKey(snap.sessionId)) return@mapNotNull null
            val session = Session(snap.sessionId, snap.goal)
            session.observationLog += snap.observationLog
            session.suspended = true
            sessions[snap.sessionId] = session
            snap.sessionId
        }
    }

    companion object {
        private const val MAX_OBSERVATIONS = 20
    }
}
