package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.security.NullAuditLog
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunOutcome
import com.morainet.mcos.security.audit.RunRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Arms [Trigger.Schedule] workflows on cron schedules (05-workflow.md §9.3)
 * — the runtime-internal counterpart of [EventTriggerManager].
 *
 * ## Architecture: tick state machine + driver
 *
 * The firing logic is [tick] — a `suspend` function of `now` and nothing
 * else. It iterates the armed schedules, fires the ones whose boundary has
 * arrived, deduplicates via the strictly-after [CronExpression.nextFire], and
 * returns. A private driver coroutine (started by the first arm, cancelled by
 * [disarmAll]) wakes every [pollMs] — or just after the next minute boundary,
 * whichever is sooner — and calls `tick(clock())`. Tests pass `pollMs = null`
 * (manual mode) and drive [tick] with an injected clock: fully deterministic,
 * no real-time waits.
 *
 * This is a runtime-internal scheduler: it only runs while the process is
 * alive, at minute granularity in the trigger's own timezone. Durable host
 * scheduling (`AlarmManager`/`WorkManager`, Doze compliance, boot recovery)
 * remains V1 host work.
 *
 * ## Misfire semantics (05 §9.3)
 *
 * A boundary ticked ≤ [MISFIRE_TOLERANCE_MS] late is still on time. Beyond
 * that the missed boundary is a **misfire**, dispatched on the trigger's
 * `misfirePolicy`:
 *
 * - `skip` (default) — do not run; audit `workflow.trigger_misfire`.
 * - `fire-and-forget` — run immediately, once, coalesced: however many
 *   boundaries were missed, the recovery fires a single run.
 * - `fire-and-forget-if-window` — fire only while `now` is still before the
 *   **next** scheduled point after the missed boundary (same window); past
 *   that, behave like `skip`.
 *
 * The reserved `TRIGGER_MISFIRE` error code is intentionally unused here —
 * misfires are informational audit records, not run errors.
 *
 * ## Rate limiting (08 §10.0)
 *
 * Same sliding one-hour window as the event manager
 * ([TriggerLimits.maxBackgroundFiresPerHour]); over-limit boundaries are
 * consumed (not re-fired later) and audited as `workflow.trigger_rate_limited`.
 *
 * ## Launch path
 *
 * Fires launch **directly** through the launcher callback — deliberately not
 * via the EventBus, whose subscriptions are at-most-once with no redelivery
 * (03 §11.4): misfire recovery needs at-least the coalesced fire the
 * manager itself guarantees. The launcher receives `(workflowId, inputs,
 * preAuthorized)` with `inputs` always the **empty** object — schedule runs
 * have no `__input` payload (05 §6.2).
 *
 * ## Audit records
 *
 * Same synthetic-runId convention as the event manager, source `SCHEDULE`:
 * `workflow.trigger_fired`, `workflow.trigger_misfire`,
 * `workflow.trigger_rate_limited`. `trigger_fired`/`trigger_misfire` records
 * carry the scheduled boundary as `scheduledAt=<ISO-8601 instant>` (05 §7.5).
 *
 * @param auditLog audit sink for lifecycle records.
 * @param limits background-fire budget per workflow per hour.
 * @param clock injectable clock (epoch ms) so windows and boundaries are testable.
 * @param pollMs driver wake interval; `null` disables the driver (manual
 *        mode — the caller drives [tick], which is how tests stay deterministic).
 */
class ScheduleTriggerManager(
    private val auditLog: AuditLog = NullAuditLog,
    private val limits: TriggerLimits = TriggerLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long? = DEFAULT_POLL_MS,
    private val wakeScheduler: WakeScheduler? = null,
) {

    /** One armed schedule and its fire bookkeeping. */
    private class ArmedSchedule(
        val workflowId: String,
        val trigger: Trigger.Schedule,
        val cron: CronExpression,
        val zone: ZoneId,
        val preAuthorized: Boolean,
        /** Fire timestamps inside the current 1h window (guarded by itself). */
        val fires: ArrayDeque<Long>,
        /** Next scheduled boundary (epoch ms); null = nothing more to fire. */
        var nextBoundary: Long?,
        val launcher: suspend (workflowId: String, inputs: JsonObject, preAuthorized: Boolean) -> Unit,
    )

    private val armedSchedules = ConcurrentHashMap<String, ArmedSchedule>()
    private val auditSeq = AtomicLong(0)

    /** Serializes [tick] (driver and any manual caller must not interleave). */
    private val tickMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var driverJob: Job? = null

    /**
     * Arm [trigger] for [workflowId]. Validates the cron syntax, the timezone,
     * and that the expression can fire at all (e.g. `0 0 31 2 *` never can);
     * on success the first boundary is computed from the current [clock]
     * reading and the driver coroutine is started (unless in manual mode).
     * Re-arming the same workflow replaces its schedule and resets its state.
     *
     * When a boundary arrives, [launcher] runs with
     * `(workflowId, empty inputs, preAuthorized)`.
     *
     * @return [TriggerArmResult.Armed], or [TriggerArmResult.Rejected] with a
     *   stable reason code: [REASON_CRON_INVALID], [REASON_TIMEZONE_INVALID],
     *   or [REASON_CRON_UNSATISFIABLE].
     */
    fun arm(
        workflowId: String,
        trigger: Trigger.Schedule,
        preAuthorized: Boolean = false,
        launcher: suspend (workflowId: String, inputs: JsonObject, preAuthorized: Boolean) -> Unit,
    ): TriggerArmResult {
        require(workflowId.isNotBlank()) { "workflowId must not be blank" }

        val cron = CronExpression.parse(trigger.cron)
            ?: return TriggerArmResult.Rejected(workflowId, REASON_CRON_INVALID)
        val zone = try {
            ZoneId.of(trigger.tz)
        } catch (_: DateTimeException) {
            return TriggerArmResult.Rejected(workflowId, REASON_TIMEZONE_INVALID)
        }
        val firstBoundary = cron.nextFire(clock(), zone)
            ?: return TriggerArmResult.Rejected(workflowId, REASON_CRON_UNSATISFIABLE)

        // Re-arm replaces: swap the entry atomically; the driver picks up the
        // new boundary on its next tick (old state — window, boundary — dies
        // with the old entry).
        armedSchedules[workflowId] = ArmedSchedule(
            workflowId = workflowId,
            trigger = trigger,
            cron = cron,
            zone = zone,
            preAuthorized = preAuthorized,
            fires = ArrayDeque(),
            nextBoundary = firstBoundary,
            launcher = launcher,
        )
        ensureDriver()
        rescheduleWake()
        return TriggerArmResult.Armed(workflowId)
    }

    /** Disarm [workflowId]; its boundaries no longer fire. `true` if it was armed. */
    fun disarm(workflowId: String): Boolean {
        val removed = armedSchedules.remove(workflowId) != null
        if (removed) rescheduleWake()
        return removed
    }

    /** Currently armed workflow ids (sorted for determinism). */
    fun armed(): List<String> = armedSchedules.keys.sorted()

    /** Disarm everything and stop the driver coroutine (runtime shutdown). */
    fun disarmAll() {
        driverJob?.cancel()
        driverJob = null
        armedSchedules.clear()
    }

    // ─── The state machine ──────────────────────────────────────────────

    /**
     * Advance every armed schedule to `now`, firing what is due. Pure with
     * respect to [now]: given the same sequence of tick timestamps, the same
     * fires and audits happen — this is the seam tests drive directly.
     */
    suspend fun tick(now: Long) {
        tickMutex.withLock {
            for (s in armedSchedules.values.sortedBy { it.workflowId }) {
                val boundary = s.nextBoundary ?: continue
                if (now < boundary) continue

                val lateness = now - boundary
                if (lateness <= MISFIRE_TOLERANCE_MS) {
                    // On time (including normal jitter from the poll cadence).
                    fire(s, boundary, now)
                    s.nextBoundary = s.cron.nextFire(boundary, s.zone)
                } else {
                    // Misfire — dispatch on the trigger's policy.
                    when (s.trigger.misfirePolicy) {
                        POLICY_FIRE_AND_FORGET -> fire(s, boundary, now)

                        POLICY_FIRE_AND_FORGET_IF_WINDOW -> {
                            // Still inside the missed boundary's window: no
                            // successor boundary has come due yet.
                            val nextAfter = s.cron.nextFire(boundary, s.zone)
                            if (nextAfter == null || now < nextAfter) {
                                fire(s, boundary, now)
                            } else {
                                auditMisfireSkipped(s, boundary, now)
                            }
                        }

                        else -> auditMisfireSkipped(s, boundary, now) // "skip"
                    }
                    // Recovery consumes every elapsed boundary: resume from
                    // the first one strictly after now.
                    s.nextBoundary = s.cron.nextFire(now, s.zone)
                }
            }
        }
        // Boundaries advanced — tell the host when to wake next (durable
        // scheduling, 10 §6). No-op when no WakeScheduler is wired.
        rescheduleWake()
    }

    /**
     * Ask the host to wake the process at the earliest armed boundary so a
     * killed/Doze'd app still fires its schedules ([WakeScheduler], 10 §6).
     */
    private fun rescheduleWake() {
        val ws = wakeScheduler ?: return
        val earliest = armedSchedules.values.mapNotNull { it.nextBoundary }.minOrNull() ?: return
        ws.scheduleWakeAt(earliest)
    }

    /** Rate-limit, audit, and launch one fire for [boundary]. */
    private suspend fun fire(s: ArmedSchedule, boundary: Long, now: Long) {
        // Sliding 1h window (08 §10.0). Skip + audit rather than fire.
        val skipped = synchronized(s.fires) {
            while (s.fires.isNotEmpty() && now - s.fires.first() >= TriggerLimits.WINDOW_MS) {
                s.fires.removeFirst()
            }
            if (s.fires.size >= limits.maxBackgroundFiresPerHour) {
                true
            } else {
                s.fires.addLast(now)
                false
            }
        }
        if (skipped) {
            auditScheduleEvent(
                commandId = "workflow.trigger_rate_limited",
                ir = "workflow=${s.workflowId} cron='${s.trigger.cron}' " +
                    "maxPerHour=${limits.maxBackgroundFiresPerHour}",
            )
            return
        }

        auditScheduleEvent(
            commandId = "workflow.trigger_fired",
            ir = "workflow=${s.workflowId} scheduledAt=${Instant.ofEpochMilli(boundary)} " +
                "latenessMs=${now - boundary}",
        )
        // Schedule runs carry no __input (05 §6.2) — the launcher gets the
        // empty object, matching the event payload contract's shape.
        s.launcher(s.workflowId, JsonObject(emptyMap()), s.preAuthorized)
    }

    private fun auditMisfireSkipped(s: ArmedSchedule, boundary: Long, now: Long) {
        auditScheduleEvent(
            commandId = "workflow.trigger_misfire",
            ir = "workflow=${s.workflowId} scheduledAt=${Instant.ofEpochMilli(boundary)} " +
                "policy=${s.trigger.misfirePolicy} latenessMs=${now - boundary}",
        )
    }

    // ─── Driver ─────────────────────────────────────────────────────────

    /** Start the polling driver if enabled and not already running. */
    private fun ensureDriver() {
        val poll = pollMs ?: return // manual mode: tests drive tick()
        if (driverJob?.isActive == true) return
        driverJob = scope.launch {
            while (isActive) {
                // The launcher contract (the runtime facade's
                // fireTriggeredWorkflow) never throws — it launches the run on
                // the run scope and catches inside. This guard is
                // belt-and-braces so one bad tick cannot silently kill every
                // schedule; the next poll proceeds regardless.
                try {
                    tick(clock())
                } catch (_: Exception) {
                }
                // Wake at the poll interval, or just past the next minute
                // boundary, whichever comes first — so an every-minute
                // schedule fires within ~250ms of its boundary while an idle
                // manager still only wakes every pollMs.
                val now = clock()
                val toBoundary = 60_000L - (now % 60_000L)
                delay(minOf(poll, toBoundary + 250L).coerceAtLeast(50L))
            }
        }
    }

    // ─── Audit ─────────────────────────────────────────────────────────

    private fun auditScheduleEvent(commandId: String, ir: String) {
        auditLog.append(
            RunRecord(
                runId = "trigger:${auditSeq.incrementAndGet()}",
                timestamp = clock(),
                source = SCHEDULE_SOURCE,
                commandId = commandId,
                ir = ir,
                outcome = RunOutcome.OK,
            )
        )
    }

    companion object {
        /** Driver default: poll at most every 10s (well inside the misfire tolerance). */
        const val DEFAULT_POLL_MS = 10_000L

        /**
         * How late a tick may arrive at a boundary and still fire normally
         * (05 §9.3: within the scheduled minute). Worse lateness is a misfire.
         */
        const val MISFIRE_TOLERANCE_MS = 60_000L

        /** Audit source label for schedule lifecycle records (08 §14). */
        const val SCHEDULE_SOURCE = "SCHEDULE"

        /** The cron expression failed to parse (includes blank cron). */
        const val REASON_CRON_INVALID = "schedule_cron_invalid"

        /** `tz` is not a valid IANA zone id. */
        const val REASON_TIMEZONE_INVALID = "schedule_timezone_invalid"

        /** Parses, but can never fire (e.g. Feb 31) — rejected, not silently dead. */
        const val REASON_CRON_UNSATISFIABLE = "schedule_cron_unsatisfiable"

        /** Misfire policy constants (05 §9.3 wire values). */
        const val POLICY_SKIP = "skip"
        const val POLICY_FIRE_AND_FORGET = "fire-and-forget"
        const val POLICY_FIRE_AND_FORGET_IF_WINDOW = "fire-and-forget-if-window"
    }
}
