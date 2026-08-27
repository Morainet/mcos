package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.runtime.core.workflow.ArmedScheduleStore
import com.morainet.mcos.runtime.core.workflow.EventTriggerManager
import com.morainet.mcos.runtime.core.workflow.NullArmedScheduleStore
import com.morainet.mcos.runtime.core.workflow.PersistedSchedule
import com.morainet.mcos.runtime.core.workflow.ScheduleTriggerManager
import com.morainet.mcos.runtime.core.workflow.Trigger
import com.morainet.mcos.runtime.core.workflow.TriggerArmResult
import com.morainet.mcos.runtime.core.workflow.WakeScheduler
import com.morainet.mcos.runtime.core.workflow.WorkflowStore
import com.morainet.mcos.security.audit.AuditLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject

/**
 * Owns the two trigger families and their arm/disarm lifecycle, keeping that
 * coordination out of the [McosRuntime] facade.
 *
 *  - **Event triggers** (05-workflow.md §9.2) subscribe to the system event
 *    bus; each matching event launches the workflow with the event payload as
 *    `__input` and per-step audit source `EVENT`.
 *  - **Schedule triggers** (05-workflow.md §9.3) launch their workflow when a
 *    cron boundary arrives — directly, not via the bus (whose subscriptions
 *    are at-most-once with no redelivery, 03 §11.4, and thus incompatible with
 *    misfire recovery) — with empty inputs and audit source `SCHEDULE`.
 *
 * Firing itself is delegated back to the facade via [fire]: launching a run
 * and driving the workflow needs the facade's owned run scope and execution
 * pipeline. The coordinator only knows *when* to fire and with *which* family.
 */
internal class TriggerCoordinator(
    eventBus: EventBus,
    memory: MemoryStore,
    auditLog: AuditLog,
    private val workflowStore: WorkflowStore,
    private val scheduleStore: ArmedScheduleStore = NullArmedScheduleStore,
    wakeScheduler: WakeScheduler? = null,
    private val fire: (workflowId: String, inputs: JsonObject, preAuthorized: Boolean, stepSource: String) -> Unit,
) {
    private val eventTriggers = EventTriggerManager(
        bus = eventBus,
        memory = memory,
        auditLog = auditLog,
    )
    private val scheduleTriggers = ScheduleTriggerManager(
        auditLog = auditLog,
        wakeScheduler = wakeScheduler,
    )

    // Which workflows have an armed *schedule*, and whether the user
    // pre-authorized them — the durable subset persisted to [scheduleStore] so
    // a fresh process can re-arm them ([rehydrate]). Event triggers are not
    // persisted: their arming is re-driven by re-subscription, not a clock.
    private val scheduledPreAuth = ConcurrentHashMap<String, Boolean>()

    // Suppresses per-arm persistence while [rehydrate] replays the store, so a
    // batch re-arm writes the file once at the end, not once per schedule.
    private var rehydrating = false

    /**
     * Arm the registered workflow's trigger. Schedule specs route to the
     * schedule manager, everything else (Event, Manual) to the event manager
     * (which arms Event and rejects Manual). Re-arming across families first
     * disarms the other family so a re-registered spec never leaves a stale
     * entry live.
     *
     * @param preAuthorized `true` when the user pre-authorized the recipe at
     *        install time (05 §10) — carried to the launcher for the
     *        pre-authorization stamp flow (08 §4.1).
     */
    suspend fun arm(workflowId: String, preAuthorized: Boolean): TriggerArmResult {
        val spec = workflowStore.spec(workflowId)
            ?: return TriggerArmResult.Rejected(workflowId, "workflow_not_found")
        val trigger = spec.trigger
            ?: return TriggerArmResult.Rejected(workflowId, "workflow_has_no_trigger")
        return when (trigger) {
            is Trigger.Schedule -> {
                // Cross-family hygiene: a spec re-registered with a different
                // trigger type must not leave the other family's entry live.
                eventTriggers.disarm(workflowId)
                val result = scheduleTriggers.arm(workflowId, trigger, preAuthorized) { id, inputs, pre ->
                    fire(id, inputs, pre, Source.SCHEDULE.name)
                }
                if (result is TriggerArmResult.Armed) {
                    scheduledPreAuth[workflowId] = preAuthorized
                    persistSchedules()
                }
                result
            }
            // Event and Manual both route to the event manager: Event arms,
            // Manual is rejected there (manual_triggers_cannot_be_armed).
            else -> {
                scheduleTriggers.disarm(workflowId)
                // Re-registered as a non-schedule: drop it from the durable set.
                if (scheduledPreAuth.remove(workflowId) != null) persistSchedules()
                eventTriggers.arm(workflowId, trigger, preAuthorized) { id, inputs, pre ->
                    fire(id, inputs, pre, Source.EVENT.name)
                }
            }
        }
    }

    /** Disarm a previously armed trigger (either family). `true` if [workflowId] was armed. */
    fun disarm(workflowId: String): Boolean {
        val wasArmed = scheduleTriggers.disarm(workflowId) || eventTriggers.disarm(workflowId)
        if (scheduledPreAuth.remove(workflowId) != null) persistSchedules()
        return wasArmed
    }

    /**
     * Re-arm the schedules a previous process persisted ([ArmedScheduleStore]).
     * Each record's workflow must already be registered (e.g. rehydrated by the
     * marketplace) — a record that no longer resolves is dropped. Returns the
     * number re-armed. Call once at startup, after workflows are re-registered.
     */
    suspend fun rehydrate(): Int {
        rehydrating = true
        var armedCount = 0
        try {
            for (record in scheduleStore.load()) {
                if (arm(record.workflowId, record.preAuthorized) is TriggerArmResult.Armed) armedCount++
            }
        } finally {
            rehydrating = false
        }
        // Persist the survivors once (this also prunes records that no longer resolve).
        persistSchedules()
        return armedCount
    }

    private fun persistSchedules() {
        if (rehydrating) return
        scheduleStore.save(scheduledPreAuth.map { PersistedSchedule(it.key, it.value) })
    }

    /** Currently armed trigger workflow ids across both families (05 §9.2-§9.3). */
    fun armed(): List<String> =
        (scheduleTriggers.armed() + eventTriggers.armed()).distinct().sorted()

    /**
     * Drive one schedule tick now — the entry point for a host wake
     * ([WakeScheduler]): an `AlarmManager` alarm fires, the host calls this, due
     * boundaries run and the next wake is re-scheduled. Safe to call any time;
     * with nothing due it is a no-op that just re-arms the next wake.
     */
    suspend fun driveScheduleTick() = scheduleTriggers.tick(System.currentTimeMillis())

    /**
     * Release every armed trigger. Schedules first — disarming them cancels
     * the driver coroutine so no boundary tick can fire a run mid-shutdown —
     * then event subscriptions.
     */
    fun disarmAll() {
        scheduleTriggers.disarmAll()
        eventTriggers.disarmAll()
    }
}
