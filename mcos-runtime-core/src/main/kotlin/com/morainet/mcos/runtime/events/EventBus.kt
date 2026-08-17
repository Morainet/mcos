package com.morainet.mcos.runtime.events

import com.morainet.mcos.runtime.api.RuntimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Built-in event source type constants, per [03-runtime.md 11.3].
 */
object EventTypes {
    const val BATTERY_LOW = "battery.low"
    const val BATTERY_CHARGING = "battery.charging"
    const val WIFI_CONNECTED = "wifi.connected"
    const val WIFI_DISCONNECTED = "wifi.disconnected"
    const val NOTIFY_POSTED = "notify.posted"
    const val LOCATION_SIGNIFICANT_CHANGE = "location.significant_change"
    const val TIME_SCHEDULE = "time.schedule"

    /** Plugin custom events are namespaced under the plugin id. */
    fun plugin(pluginId: String, eventName: String): String = "$pluginId.$eventName"
}

/**
 * An event on the system event bus, per [03-runtime.md 11.1].
 *
 * @property type Dotted event type, e.g. "connectivity.wifi.connected".
 * @property timestamp Epoch millis when the event occurred.
 * @property payload Structured event data.
 * @property source Producer identity, e.g. "sys.connectivity" or a plugin id.
 */
data class EventEnvelope(
    val type: String,
    val timestamp: Long,
    val payload: JsonObject = JsonObject(emptyMap()),
    val source: String,
)

/**
 * Subscription filter, per [03-runtime.md 11.2].
 *
 * `typePrefix` is a **string prefix** match on [EventEnvelope.type]
 * (e.g. `"connectivity.wifi."` matches `"connectivity.wifi.connected"`).
 * `where` is **deep equality** — every key in the filter must exist in the
 * envelope payload with an equal value; extra payload keys are ignored.
 * No wildcards, no regex, no JSONPath.
 */
data class EventFilter(
    val typePrefix: String? = null,
    val where: JsonObject? = null,
) {
    fun matches(envelope: EventEnvelope): Boolean {
        if (typePrefix != null && !envelope.type.startsWith(typePrefix)) return false
        val whereObj = where ?: return true
        return whereObj.all { (key, expected) ->
            val actual = envelope.payload[key] ?: return@all false
            deepEquals(actual, expected)
        }
    }

    private fun deepEquals(a: JsonElement, b: JsonElement): Boolean = when (b) {
        is JsonObject -> a is JsonObject && b.all { (k, v) ->
            val av = a[k] ?: return@all false
            deepEquals(av, v)
        }
        is JsonArray -> a is JsonArray && a.size == b.size && a.zip(b).all { (x, y) -> deepEquals(x, y) }
        else -> a == b
    }
}

/**
 * Handle for an active subscription. Pass to [EventBus.unsubscribe].
 *
 * The constructor is public so custom [EventBus] implementations (e.g. test
 * fakes outside this module) can mint handles; [id] is opaque to callers.
 */
class EventSubscription(val id: Long)

/**
 * Receives diagnostics from the event bus. Subscriber failures are isolated
 * and logged; backpressure drops are audited (both are warn-level, delivery
 * to other subscribers proceeds).
 */
interface EventAuditSink {
    /** A subscriber threw while handling an event. */
    fun onSubscriberError(eventType: String, subscriptionId: Long, error: Throwable)

    /** The subscriber's channel was full and the oldest undelivered event was dropped. */
    fun onBackpressureDrop(eventType: String, subscriptionId: Long)
}

/**
 * Full P2 event bus, per [03-runtime.md 11].
 *
 * Two independent channels:
 *  - **Run events** ([publish]/[observe]): the in-process stream consumed by
 *    [com.morainet.mcos.runtime.api.McosRuntime.observe]; kept for P1 compatibility.
 *  - **System events** ([publishEvent]/[subscribe]): typed envelopes matched
 *    against [EventFilter] subscriptions.
 *
 * Delivery semantics (11.4): at-most-once, no persistence; each subscriber
 * runs under its own child job of a [SupervisorJob] so a throwing subscriber
 * never terminates siblings or the bus. Events are buffered per subscriber
 * through a bounded [Channel]; when full, the oldest undelivered event is
 * dropped and [EventAuditSink.onBackpressureDrop] fires. The publisher is
 * never blocked.
 */
interface EventBus {
    /** Publish a run-scoped runtime event (P1 compatibility). */
    fun publish(runId: String, event: RuntimeEvent)

    /** Observe run events for a specific run as a cold [Flow]. */
    fun observe(runId: String): Flow<RuntimeEvent>

    /**
     * Subscribe to system events matching [filter].
     *
     * @return an [EventSubscription] used with [unsubscribe].
     */
    fun subscribe(filter: EventFilter, handler: suspend (EventEnvelope) -> Unit): EventSubscription

    /** Remove a subscription; later events are no longer delivered to it. */
    fun unsubscribe(subscription: EventSubscription)

    /**
     * Publish a system event to all matching subscribers.
     * At-most-once; never blocks the caller.
     */
    fun publishEvent(envelope: EventEnvelope)
}

/**
 * Default [EventBus] implementation.
 *
 * @param auditSink optional sink for subscriber errors and backpressure drops.
 * @param externalScope optional scope for subscriber jobs; when omitted a
 *   private [SupervisorJob] scope on [Dispatchers.Default] is created and
 *   owned by this bus (released by [dispose]).
 * @param channelCapacity per-subscriber buffer size (default 64, per 11.4).
 */
class TypedEventBus(
    private val auditSink: EventAuditSink? = null,
    externalScope: CoroutineScope? = null,
    private val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
) : EventBus {

    private val ownsScope = externalScope == null
    private val scope: CoroutineScope =
        externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Run events are kept in a per-run SharedFlow instead of one global
    // stream. The previous design — a single MutableSharedFlow(replay = 256)
    // shared by every run, filtered per run in observe() — had a hang class
    // no buffer tuning could fix: a burst of events from OTHER runs could
    // evict a run's terminal event (RunSucceeded/RunFailed/RunCancelled)
    // from the global replay window, so a late observe() collector waiting
    // for that terminal event waited forever. Per-run isolation makes the
    // replay window belong to exactly one run, and [observe] completes the
    // returned flow at the terminal event so collectors have a bounded
    // lifecycle instead of collecting forever.
    private val runs = ConcurrentHashMap<String, RunState>()

    /**
     * Per-run event stream. [finished] marks that the terminal event was
     * published; finished runs are evicted FIFO once more than
     * [MAX_RETAINED_FINISHED_RUNS] have accumulated (replay history for
     * already-finished runs is diagnostic only).
     */
    private class RunState {
        val flow = MutableSharedFlow<RuntimeEvent>(
            replay = RUN_REPLAY,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        @Volatile
        var finished = false
    }

    private val finishedRunOrder = ArrayDeque<String>()

    // Run ids whose replay history was evicted. Observing an evicted run
    // completes empty immediately; observing a never-seen run WAITS (the
    // caller may legitimately subscribe before the run's first publish —
    // execute() returns its handle before the launched coroutine publishes
    // RunStarted, so observe() cannot treat "no state yet" as "nothing will
    // happen"). Tombstones distinguish the two; without them a late observe
    // of an evicted finished run would hang waiting for events that already
    // happened and were forgotten.
    private val evictedRunIds = HashSet<String>()
    private val evictedRunOrder = ArrayDeque<String>()

    private fun isTerminal(event: RuntimeEvent): Boolean = when (event) {
        is RuntimeEvent.RunSucceeded, is RuntimeEvent.RunFailed, is RuntimeEvent.RunCancelled -> true
        else -> false
    }
    private val subscriptions = ConcurrentHashMap<Long, Subscriber>()
    private val ids = AtomicLong(0)

    private inner class Subscriber(
        val id: Long,
        val filter: EventFilter,
        val handler: suspend (EventEnvelope) -> Unit,
    ) {
        val channel = Channel<EventEnvelope>(channelCapacity)

        // Per-subscriber monitor serialising the drop-oldest backpressure
        // sequence in [publishEvent]. Without it, two concurrent publishers
        // could each observe a full channel, each tryReceive() a distinct
        // oldest event, and then both trySend() — racing on the single freed
        // slot and leaving one event silently undelivered AND unaudited. The
        // lock is held only for the brief non-suspending drop+send, so it
        // never blocks the subscriber's draining coroutine for long.
        val publishLock = Any()

        // Isolation: a throwing handler is caught and audited; the loop keeps
        // draining so later events still reach this subscriber.
        val job: Job = scope.launch {
            for (envelope in channel) {
                try {
                    handler(envelope)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    auditSink?.onSubscriberError(envelope.type, id, e)
                }
            }
        }
    }

    override fun publish(runId: String, event: RuntimeEvent) {
        val state = runs.computeIfAbsent(runId) { RunState() }
        state.flow.tryEmit(event)
        if (isTerminal(event)) {
            state.finished = true
            // Bounded retention: keep replay history for the most recent
            // finished runs only, so long-lived processes don't accumulate
            // every run's events forever.
            synchronized(finishedRunOrder) {
                finishedRunOrder.addLast(runId)
                while (finishedRunOrder.size > MAX_RETAINED_FINISHED_RUNS) {
                    val evicted = finishedRunOrder.removeFirst()
                    runs.remove(evicted)
                    evictedRunIds.add(evicted)
                    evictedRunOrder.addLast(evicted)
                    while (evictedRunOrder.size > MAX_RETAINED_EVICTED_RUN_IDS) {
                        evictedRunIds.remove(evictedRunOrder.removeFirst())
                    }
                }
            }
        }
    }

    /**
     * Observe the events of one run. The returned flow:
     *  - replays the run's previously published events to late subscribers
     *    (isolation is per run — other runs' bursts cannot evict them),
     *  - completes normally after the run's terminal event
     *    (RunSucceeded/RunFailed/RunCancelled), giving collectors a bounded
     *    lifecycle, and
     *  - waits for the run's first event if the id was never published to
     *    (subscribing before execute's coroutine publishes RunStarted is a
     *    supported pattern), completing empty only for evicted finished runs.
     */
    override fun observe(runId: String): Flow<RuntimeEvent> {
        // Evicted finished run: its history is gone and nothing more will be
        // published — complete empty instead of waiting forever.
        if (runId in evictedRunIds) return emptyFlow()
        // Unknown id creates the state so an early subscriber attaches to the
        // run's stream and receives events once they are published. The state
        // is tiny (empty replay cache) and cleared by [dispose]; only ids that
        // are observed but never run anywhere linger.
        val state = runs.computeIfAbsent(runId) { RunState() }
        // Emit the terminal event, then complete: transformWhile's predicate
        // is evaluated AFTER the emit.
        return state.flow.transformWhile { event ->
            emit(event)
            !isTerminal(event)
        }
    }

    override fun subscribe(filter: EventFilter, handler: suspend (EventEnvelope) -> Unit): EventSubscription {
        val id = ids.incrementAndGet()
        subscriptions[id] = Subscriber(id, filter, handler)
        return EventSubscription(id)
    }

    override fun unsubscribe(subscription: EventSubscription) {
        subscriptions.remove(subscription.id)?.let { it.job.cancel() }
    }

    override fun publishEvent(envelope: EventEnvelope) {
        for (sub in subscriptions.values) {
            if (!sub.filter.matches(envelope)) continue
            // Serialize the drop+send under the subscriber's lock so concurrent
            // publishers cannot interleave: the tryReceive()+trySend() pair is
            // atomic, and a second send failure (still full after one drop) is
            // audited rather than silently swallowed (P1-E1).
            synchronized(sub.publishLock) {
                val result = sub.channel.trySend(envelope)
                if (result.isFailure) {
                    // Backpressure: buffer full → drop the oldest undelivered
                    // event, deliver the new one in its place, and audit the
                    // drop. The publisher is never blocked.
                    sub.channel.tryReceive().getOrNull()
                    val retry = sub.channel.trySend(envelope)
                    // Even after dropping one, a second failure is possible
                    // (e.g. capacity 0). Audit it so the drop is at least
                    // observable instead of silently lost.
                    auditSink?.onBackpressureDrop(envelope.type, sub.id)
                    @Suppress("UNUSED_VARIABLE")
                    val delivered = retry.isSuccess
                }
            }
        }
    }

    /**
     * Cancel all subscriber jobs, drop all run-event history, and release
     * the private scope (if owned). Idempotent; safe to call once the bus
     * is no longer needed.
     */
    fun dispose() {
        subscriptions.values.forEach { it.job.cancel() }
        subscriptions.clear()
        runs.clear()
        synchronized(finishedRunOrder) {
            finishedRunOrder.clear()
            evictedRunIds.clear()
            evictedRunOrder.clear()
        }
        if (ownsScope) scope.cancel()
    }

    companion object {
        /** Per-subscriber buffer capacity, per 11.4 (Channel.BUFFERED). */
        const val DEFAULT_CHANNEL_CAPACITY = 64

        /**
         * Replay window per run. Generous on purpose: a run's full event
         * history must survive for late subscribers (isolation is per run,
         * so this window is never contested by other runs).
         */
        const val RUN_REPLAY = 512

        /**
         * How many finished runs keep their replay history for late
         * observers. Older finished runs are evicted FIFO.
         */
        const val MAX_RETAINED_FINISHED_RUNS = 128

        /**
         * How many evicted run ids stay tombstoned. Beyond this window an
         * ancient id is indistinguishable from a not-yet-started run and
         * observe() waits again (same as the pre-isolation behavior).
         */
        const val MAX_RETAINED_EVICTED_RUN_IDS = 512
    }
}
