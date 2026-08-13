package com.mcos.runtime.events

import com.mcos.runtime.api.RuntimeEvent
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
import kotlinx.coroutines.flow.filter
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
 */
class EventSubscription internal constructor(val id: Long)

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
 *    [com.mcos.runtime.api.McosRuntime.observe]; kept for P1 compatibility.
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

    // P2-F4: onBufferOverflow = DROP_OLDEST ensures that when the buffer
    // is full, a newly published event displaces the oldest buffered one
    // instead of being silently discarded by tryEmit. This matters most
    // for terminal events (RunSucceeded/RunFailed/RunCancelled): without
    // DROP_OLDEST a burst of progress events can fill the buffer and cause
    // the terminal event to be dropped, leaving observe() collectors
    // hanging forever.
    private val runEvents = MutableSharedFlow<RuntimeEvent>(
        replay = 256,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
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
        runEvents.tryEmit(event)
    }

    override fun observe(runId: String): Flow<RuntimeEvent> {
        return runEvents.filter { event ->
            when (event) {
                is RuntimeEvent.RunStarted -> event.runId == runId
                is RuntimeEvent.StepStarted -> event.runId == runId
                is RuntimeEvent.Progress -> event.runId == runId
                is RuntimeEvent.ArtifactEmitted -> event.runId == runId
                is RuntimeEvent.LogEmitted -> event.runId == runId
                is RuntimeEvent.ConfirmationNeeded -> event.runId == runId
                is RuntimeEvent.StepSucceeded -> event.runId == runId
                is RuntimeEvent.StepFailed -> event.runId == runId
                is RuntimeEvent.RunSucceeded -> event.runId == runId
                is RuntimeEvent.RunFailed -> event.runId == runId
                is RuntimeEvent.RunCancelled -> event.runId == runId
            }
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
     * Cancel all subscriber jobs and release the private scope (if owned).
     * Idempotent; safe to call once the bus is no longer needed.
     */
    fun dispose() {
        subscriptions.values.forEach { it.job.cancel() }
        subscriptions.clear()
        if (ownsScope) scope.cancel()
    }

    companion object {
        /** Per-subscriber buffer capacity, per 11.4 (Channel.BUFFERED). */
        const val DEFAULT_CHANNEL_CAPACITY = 64
    }
}
