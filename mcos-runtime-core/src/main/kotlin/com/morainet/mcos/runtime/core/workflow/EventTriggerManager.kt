package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.EventEnvelope
import com.morainet.mcos.runtime.core.events.EventFilter
import com.morainet.mcos.runtime.core.events.EventSubscription
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.security.NullAuditLog
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunOutcome
import com.morainet.mcos.security.audit.RunRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Background-fire budget per workflow (08-security.md §10.0).
 *
 * A sliding one-hour window caps how many times a single armed trigger may
 * launch its workflow; over-limit events are skipped and audited rather than
 * rejecting the arm. This bounds a pathological producer (event storm) to a
 * fixed background work rate.
 */
data class TriggerLimits(
    val maxBackgroundFiresPerHour: Int = DEFAULT_MAX_FIRES_PER_HOUR,
) {
    companion object {
        /** 08-security.md §10.0 default: 20 background fires per recipe per hour. */
        const val DEFAULT_MAX_FIRES_PER_HOUR = 20

        /** Sliding window length in milliseconds. */
        const val WINDOW_MS: Long = 60L * 60 * 1000
    }
}

/** Outcome of [EventTriggerManager.arm]. */
sealed class TriggerArmResult {
    /** The trigger is subscribed; matching events will invoke the launcher. */
    data class Armed(val workflowId: String) : TriggerArmResult()

    /** The trigger was refused; [reason] is a stable machine-readable code. */
    data class Rejected(val workflowId: String, val reason: String) : TriggerArmResult()
}

/**
 * Arms [Trigger.Event] workflows against the system [EventBus]
 * (05-workflow.md §9.2) — the runtime's first consumer of bus subscriptions.
 *
 * ## Matching semantics
 *
 * - `filter.type` becomes the subscription's `typePrefix` — a **prefix**
 *   match, the bus's native semantics (03-runtime.md §11.4).
 * - `filter.where` is evaluated by the manager (not the bus) so that a
 *   resolved **array** memory value means *membership*: the filter matches
 *   when the event's value equals any element (the canonical
 *   `places.office.wifiSsids` list case, 07 §13.1). Non-array values use
 *   deep equality with extra payload keys ignored.
 * - `$memory` references in `where` resolve per the trigger's
 *   [MemoryResolution]: once at arm time (`ARM`, the default) or per event
 *   (`FIRE`). A missing path is **not** an error (07 §13.1): the filter
 *   evaluates false (never-matches sentinel at arm time, skip-and-warn at
 *   fire time) and a warning is audited.
 *
 * ## Rate limiting (08 §10.0)
 *
 * Each armed workflow gets a sliding one-hour window of
 * [TriggerLimits.maxBackgroundFiresPerHour] fires; the (n+1)-th event within
 * the window is skipped and audited as `workflow.trigger_rate_limited`.
 *
 * ## Audit records
 *
 * Lifecycle records use the synthetic-runId convention (see MemorySync):
 * source `EVENT`, `commandId` carrying the event name — `workflow.trigger_fired`,
 * `workflow.trigger_rate_limited`, `workflow.trigger_memory_missing`.
 *
 * @param bus system event bus to subscribe on.
 * @param memory memory store backing `$memory` references.
 * @param auditLog audit sink for lifecycle records.
 * @param limits background-fire budget.
 * @param clock injectable clock (epoch ms) so rate-limit windows are testable.
 */
class EventTriggerManager(
    private val bus: EventBus,
    private val memory: MemoryStore,
    private val auditLog: AuditLog = NullAuditLog,
    private val limits: TriggerLimits = TriggerLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One armed subscription and its per-workbook fire bookkeeping. */
    private class ArmedTrigger(
        val workflowId: String,
        val trigger: Trigger.Event,
        val preAuthorized: Boolean,
        val subscription: EventSubscription,
        /** `where` with `$memory` resolved (ARM mode); null = no where clause. */
        val armedWhere: JsonObject?,
        /** Fire timestamps inside the current 1h window (guarded by itself). */
        val fires: ArrayDeque<Long>,
        val launcher: suspend (String, JsonObject, Boolean) -> Unit,
    )

    private val armedTriggers = ConcurrentHashMap<String, ArmedTrigger>()
    private val auditSeq = AtomicLong(0)

    /**
     * Arm [trigger] for [workflowId]. Subscribes to the bus; matching events
     * invoke [launcher] with `(workflowId, event payload, preAuthorized)` —
     * the payload becomes the run's `__input` (05 §6.2). Re-arming the same
     * workflow replaces its subscription (the ARM-resolved `where` refreshes).
     *
     * `suspend` because ARM-mode triggers resolve `$memory` references now.
     *
     * @return [TriggerArmResult.Armed], or [TriggerArmResult.Rejected] with a
     *   stable reason code (schedule triggers belong to
     *   [ScheduleTriggerManager]; this manager still rejects them).
     */
    suspend fun arm(
        workflowId: String,
        trigger: Trigger,
        preAuthorized: Boolean = false,
        launcher: suspend (workflowId: String, inputs: JsonObject, preAuthorized: Boolean) -> Unit,
    ): TriggerArmResult {
        require(workflowId.isNotBlank()) { "workflowId must not be blank" }
        val event = trigger as? Trigger.Event
            ?: return TriggerArmResult.Rejected(
                workflowId,
                when (trigger) {
                    is Trigger.Schedule -> REASON_SCHEDULE_UNSUPPORTED
                    is Trigger.Manual -> REASON_MANUAL_NOT_ARMABLE
                    // Unreachable: event != null here means trigger IS a
                    // Trigger.Event; the cast above already handled it.
                    else -> "unsupported_trigger"
                },
            )

        val typeElement = event.filter["type"]
        val type = if (typeElement is JsonPrimitive && typeElement !is JsonNull && typeElement.content.isNotBlank()) {
            typeElement.content
        } else {
            return TriggerArmResult.Rejected(workflowId, REASON_FILTER_TYPE_REQUIRED)
        }

        // ARM resolution: read memory once, here (05 §9.2). Missing paths are
        // not errors (07 §13.1) — the reference becomes a never-match sentinel.
        val armedWhere: JsonObject? = if (event.resolveMemory == MemoryResolution.ARM) {
            resolveWhere(event, atArmTime = true, workflowId = workflowId)
        } else {
            event.filter["where"] as? JsonObject
        }

        // Re-arm replaces: unsubscribe first so the old handler never races
        // the new one, then subscribe with the freshly resolved filter.
        armedTriggers.remove(workflowId)?.let { bus.unsubscribe(it.subscription) }
        val subscription = bus.subscribe(EventFilter(typePrefix = type)) { envelope ->
            onEvent(workflowId, envelope)
        }
        armedTriggers[workflowId] = ArmedTrigger(
            workflowId = workflowId,
            trigger = event,
            preAuthorized = preAuthorized,
            subscription = subscription,
            armedWhere = armedWhere,
            fires = ArrayDeque(),
            launcher = launcher,
        )
        return TriggerArmResult.Armed(workflowId)
    }

    /** Disarm [workflowId]; later events no longer launch it. `true` if it was armed. */
    fun disarm(workflowId: String): Boolean {
        val removed = armedTriggers.remove(workflowId) ?: return false
        bus.unsubscribe(removed.subscription)
        return true
    }

    /** Currently armed workflow ids (sorted for determinism). */
    fun armed(): List<String> = armedTriggers.keys.sorted()

    /** Disarm everything (runtime shutdown). */
    fun disarmAll() {
        armedTriggers.values.forEach { bus.unsubscribe(it.subscription) }
        armedTriggers.clear()
    }

    // ─── Event delivery ────────────────────────────────────────────────

    private suspend fun onEvent(workflowId: String, envelope: EventEnvelope) {
        val armed = armedTriggers[workflowId] ?: return // disarmed in flight

        val where = when (armed.trigger.resolveMemory) {
            MemoryResolution.FIRE -> {
                // FIRE resolution: re-read memory per event; a missing path
                // makes the filter false for THIS event only (07 §13.1).
                val resolved = resolveWhere(armed.trigger, atArmTime = false, workflowId = workflowId)
                    ?: return // missing path → warn audited, no match
                resolved
            }
            MemoryResolution.ARM -> armed.armedWhere
        }
        if (where != null && !whereMatches(where, envelope.payload)) return

        // Sliding 1h window (08 §10.0). Skip + audit rather than fire.
        val now = clock()
        val skipped = synchronized(armed.fires) {
            while (armed.fires.isNotEmpty() && now - armed.fires.first() >= TriggerLimits.WINDOW_MS) {
                armed.fires.removeFirst()
            }
            if (armed.fires.size >= limits.maxBackgroundFiresPerHour) {
                true
            } else {
                armed.fires.addLast(now)
                false
            }
        }
        if (skipped) {
            auditTriggerEvent(
                commandId = "workflow.trigger_rate_limited",
                ir = "workflow=$workflowId event=${envelope.type} " +
                    "maxPerHour=${limits.maxBackgroundFiresPerHour}",
            )
            return
        }

        auditTriggerEvent(
            commandId = "workflow.trigger_fired",
            ir = "workflow=$workflowId event=${envelope.type}",
        )
        armed.launcher(workflowId, envelope.payload, armed.preAuthorized)
    }

    // ─── $memory resolution (07 §13.1) ─────────────────────────────────

    /**
     * Resolve the trigger's `where` clause. At arm time a missing path yields
     * the never-match sentinel (arm still succeeds — the workflow simply
     * won't match until re-armed after the value exists); at fire time it
     * yields null (skip this event). Both paths audit a warning.
     */
    private suspend fun resolveWhere(
        trigger: Trigger.Event,
        atArmTime: Boolean,
        workflowId: String,
    ): JsonObject? {
        val where = trigger.filter["where"] as? JsonObject ?: return null
        val missing = mutableSetOf<String>()
        val resolved = resolveMemoryRefs(where, missing) as? JsonObject ?: where
        if (missing.isNotEmpty()) {
            auditTriggerEvent(
                commandId = "workflow.trigger_memory_missing",
                ir = "workflow=$workflowId paths=${missing.sorted().joinToString(",")}" +
                    if (atArmTime) " resolvedAt=arm" else " resolvedAt=fire",
            )
            return if (atArmTime) resolved else null
        }
        return resolved
    }

    /**
     * Walk [element] replacing `{"$memory": path}` single-key objects with
     * the stored value. Unresolvable refs become [MISSING_MEMORY_SENTINEL]
     * and their paths are collected into [missing].
     */
    private suspend fun resolveMemoryRefs(element: JsonElement, missing: MutableSet<String>): JsonElement {
        if (element is JsonObject && element.size == 1 && element.containsKey(MEMORY_REF_KEY)) {
            val ref = element[MEMORY_REF_KEY]
            val path = (ref as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            return if (path.isNullOrBlank()) {
                missing.add(ref.toString())
                MISSING_MEMORY_SENTINEL
            } else {
                memory.get(path) ?: run {
                    missing.add(path)
                    MISSING_MEMORY_SENTINEL
                }
            }
        }
        return when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, v) -> resolveMemoryRefs(v, missing) })
            is JsonArray -> JsonArray(element.map { resolveMemoryRefs(it, missing) })
            else -> element
        }
    }

    // ─── where matching ────────────────────────────────────────────────

    /**
     * `where` match with array-membership semantics: a filter value that is
     * an array matches when the event value equals ANY member (deep); any
     * other value matches by deep equality. Extra payload keys are ignored,
     * missing keys never match.
     */
    internal fun whereMatches(where: JsonObject, payload: JsonObject): Boolean =
        where.all { (key, expected) ->
            val actual = payload[key] ?: return@all false
            valueMatches(actual, expected)
        }

    private fun valueMatches(actual: JsonElement, expected: JsonElement): Boolean = when (expected) {
        is JsonArray -> expected.any { valueMatches(actual, it) }
        is JsonObject -> actual is JsonObject && expected.all { (k, v) ->
            val av = actual[k] ?: return@all false
            valueMatches(av, v)
        }
        else -> actual == expected
    }

    // ─── Audit ─────────────────────────────────────────────────────────

    private fun auditTriggerEvent(commandId: String, ir: String) {
        auditLog.append(
            RunRecord(
                runId = "trigger:${auditSeq.incrementAndGet()}",
                timestamp = clock(),
                source = EVENT_SOURCE,
                commandId = commandId,
                ir = ir,
                outcome = RunOutcome.OK,
            )
        )
    }

    companion object {
        private const val MEMORY_REF_KEY = "\$memory"

        /** Audit source label for trigger lifecycle records (08 §14). */
        const val EVENT_SOURCE = "EVENT"

        /** Schedules are armed by [ScheduleTriggerManager]; this manager rejects them. */
        const val REASON_SCHEDULE_UNSUPPORTED = "schedule_triggers_unsupported"

        /** Manual triggers run via explicit execute(WorkflowRef), not the bus. */
        const val REASON_MANUAL_NOT_ARMABLE = "manual_triggers_cannot_be_armed"

        /** The event filter must carry a non-blank string `type`. */
        const val REASON_FILTER_TYPE_REQUIRED = "trigger_filter_type_required"

        /**
         * Stand-in for an unresolvable `$memory` reference at arm time. A
         * filter key comparing against this object is false for every
         * realistic payload, so the trigger arms but never fires until
         * re-armed once the memory path exists (07 §13.1: not an error).
         */
        private val MISSING_MEMORY_SENTINEL =
            JsonObject(mapOf("\$mcos.trigger" to JsonPrimitive("memory_missing")))
    }
}
