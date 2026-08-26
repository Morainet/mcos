package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.runtime.core.workflow.EventTriggerManager
import com.morainet.mcos.runtime.core.workflow.ScheduleTriggerManager
import com.morainet.mcos.runtime.core.workflow.Trigger
import com.morainet.mcos.runtime.core.workflow.TriggerArmResult
import com.morainet.mcos.runtime.core.workflow.WorkflowStore
import com.morainet.mcos.security.audit.AuditLog
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
    private val fire: (workflowId: String, inputs: JsonObject, preAuthorized: Boolean, stepSource: String) -> Unit,
) {
    private val eventTriggers = EventTriggerManager(
        bus = eventBus,
        memory = memory,
        auditLog = auditLog,
    )
    private val scheduleTriggers = ScheduleTriggerManager(
        auditLog = auditLog,
    )

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
                scheduleTriggers.arm(workflowId, trigger, preAuthorized) { id, inputs, pre ->
                    fire(id, inputs, pre, Source.SCHEDULE.name)
                }
            }
            // Event and Manual both route to the event manager: Event arms,
            // Manual is rejected there (manual_triggers_cannot_be_armed).
            else -> {
                scheduleTriggers.disarm(workflowId)
                eventTriggers.arm(workflowId, trigger, preAuthorized) { id, inputs, pre ->
                    fire(id, inputs, pre, Source.EVENT.name)
                }
            }
        }
    }

    /** Disarm a previously armed trigger (either family). `true` if [workflowId] was armed. */
    fun disarm(workflowId: String): Boolean =
        scheduleTriggers.disarm(workflowId) || eventTriggers.disarm(workflowId)

    /** Currently armed trigger workflow ids across both families (05 §9.2-§9.3). */
    fun armed(): List<String> =
        (scheduleTriggers.armed() + eventTriggers.armed()).distinct().sorted()

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
