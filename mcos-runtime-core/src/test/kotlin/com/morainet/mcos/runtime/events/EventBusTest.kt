package com.morainet.mcos.runtime.events

import com.morainet.mcos.runtime.api.RuntimeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class EventBusTest {

    private fun envelope(
        type: String,
        source: String = "test",
        payload: JsonObject = JsonObject(emptyMap()),
    ): EventEnvelope = EventEnvelope(
        type = type,
        timestamp = System.currentTimeMillis(),
        payload = payload,
        source = source,
    )

    // ─── Delivery ────────────────────────────────────────────────────────

    @Test
    fun prefixFilterDeliversMatchingEventsOnly() = runBlocking {
        val bus = TypedEventBus()
        val received = CopyOnWriteArrayList<String>()
        bus.subscribe(EventFilter(typePrefix = "connectivity.wifi.")) { received.add(it.type) }

        bus.publishEvent(envelope("connectivity.wifi.connected"))
        bus.publishEvent(envelope("connectivity.wifi.disconnected"))
        bus.publishEvent(envelope("battery.low"))

        withTimeout(2_000) {
            while (received.size < 2) delay(10)
        }
        delay(100)
        assertEquals(
            listOf("connectivity.wifi.connected", "connectivity.wifi.disconnected"),
            received,
        )
        bus.dispose()
    }

    @Test
    fun whereFilterUsesDeepEqualityIgnoringExtraPayloadKeys() = runBlocking {
        val bus = TypedEventBus()
        val received = CopyOnWriteArrayList<String>()
        bus.subscribe(
            EventFilter(
                typePrefix = "loc.",
                where = buildJsonObject {
                    put("coarse", JsonPrimitive(true))
                    put("loc", buildJsonObject {
                        put("lat", JsonPrimitive(31.23))
                    })
                },
            )
        ) { received.add(it.type) }

        // Matches: nested values equal, extra "lon" key ignored.
        bus.publishEvent(
            envelope("loc.change", payload = buildJsonObject {
                put("coarse", JsonPrimitive(true))
                put("loc", buildJsonObject {
                    put("lat", JsonPrimitive(31.23))
                    put("lon", JsonPrimitive(121.47))
                })
            })
        )
        // Mismatch: nested lat differs.
        bus.publishEvent(
            envelope("loc.change", payload = buildJsonObject {
                put("coarse", JsonPrimitive(true))
                put("loc", buildJsonObject { put("lat", JsonPrimitive(31.99)) })
            })
        )
        // Mismatch: coarse differs.
        bus.publishEvent(
            envelope("loc.change", payload = buildJsonObject { put("coarse", JsonPrimitive(false)) })
        )
        // Mismatch: missing the "coarse" filter key entirely.
        bus.publishEvent(
            envelope("loc.change", payload = buildJsonObject { put("lat", JsonPrimitive(31.23)) })
        )

        withTimeout(2_000) {
            while (received.isEmpty()) delay(10)
        }
        delay(100)
        assertEquals(listOf("loc.change"), received)
        bus.dispose()
    }

    @Test
    fun multipleSubscribersEachReceiveTheirMatches() = runBlocking {
        val bus = TypedEventBus()
        val wifi = CopyOnWriteArrayList<String>()
        val battery = CopyOnWriteArrayList<String>()
        bus.subscribe(EventFilter(typePrefix = "connectivity.")) { wifi.add(it.type) }
        bus.subscribe(EventFilter(typePrefix = "battery.")) { battery.add(it.type) }

        bus.publishEvent(envelope("connectivity.wifi.connected"))
        bus.publishEvent(envelope("battery.low"))
        bus.publishEvent(envelope("battery.charging"))

        withTimeout(2_000) {
            while (wifi.size < 1 || battery.size < 2) delay(10)
        }
        assertEquals(listOf("connectivity.wifi.connected"), wifi)
        assertEquals(listOf("battery.low", "battery.charging"), battery)
        bus.dispose()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    @Test
    fun unsubscribeStopsFurtherDelivery() = runBlocking {
        val bus = TypedEventBus()
        val received = CopyOnWriteArrayList<String>()
        val sub = bus.subscribe(EventFilter(typePrefix = "t.")) { received.add(it.type) }

        bus.publishEvent(envelope("t.one"))
        withTimeout(2_000) {
            while (received.isEmpty()) delay(10)
        }

        bus.unsubscribe(sub)
        bus.publishEvent(envelope("t.two"))
        delay(100)
        assertEquals(listOf("t.one"), received)
        bus.dispose()
    }

    @Test
    fun unsubscribeIdempotent() = runBlocking {
        val bus = TypedEventBus()
        val sub = bus.subscribe(EventFilter(typePrefix = "t.")) { }
        bus.unsubscribe(sub)
        bus.unsubscribe(sub) // must not throw
        bus.dispose()
    }

    // ─── Isolation & audit ───────────────────────────────────────────────

    @Test
    fun throwingSubscriberIsIsolatedAndAudited() = runBlocking {
        val errors = CopyOnWriteArrayList<Pair<String, Long>>()
        val drops = AtomicInteger(0)
        val bus = TypedEventBus(auditSink = object : EventAuditSink {
            override fun onSubscriberError(eventType: String, subscriptionId: Long, error: Throwable) {
                errors.add(eventType to subscriptionId)
            }

            override fun onBackpressureDrop(eventType: String, subscriptionId: Long) {
                drops.incrementAndGet()
            }
        })

        val healthy = CopyOnWriteArrayList<String>()
        bus.subscribe(EventFilter(typePrefix = "s.")) { throw IllegalStateException("boom") }
        bus.subscribe(EventFilter(typePrefix = "s.")) { healthy.add(it.type) }

        bus.publishEvent(envelope("s.ev"))
        // Wait for BOTH the healthy subscriber to receive AND the throwing
        // subscriber's error to be audited. Both happen asynchronously on
        // Dispatchers.Default; checking only `healthy` first can race the
        // error-audit (P1-E1 timing: the per-subscriber publish lock slightly
        // reorders delivery vs. the error audit).
        withTimeout(2_000) {
            while (healthy.isEmpty() || errors.isEmpty()) delay(10)
        }

        assertEquals(listOf("s.ev"), healthy)
        assertEquals(1, errors.size)
        assertEquals("s.ev", errors[0].first)
        assertEquals(0, drops.get())
        bus.dispose()
    }

    @Test
    fun backpressureDropsOldestAndAudits() = runBlocking {
        val drops = AtomicInteger(0)
        val bus = TypedEventBus(
            auditSink = object : EventAuditSink {
                override fun onSubscriberError(eventType: String, subscriptionId: Long, error: Throwable) {
                    error("unexpected subscriber error")
                }

                override fun onBackpressureDrop(eventType: String, subscriptionId: Long) {
                    drops.incrementAndGet()
                }
            },
            channelCapacity = 2,
        )

        // Slow consumer: 30ms per event while 40 events arrive instantly.
        val received = AtomicInteger(0)
        bus.subscribe(EventFilter(typePrefix = "flood.")) {
            delay(30)
            received.incrementAndGet()
        }

        repeat(40) { bus.publishEvent(envelope("flood.$it")) }

        // Let the slow consumer drain everything. Every published event ends
        // up either delivered or dropped, never lost silently.
        withTimeout(5_000) {
            while (received.get() + drops.get() < 40) delay(10)
        }

        assertTrue("expected drops, got ${drops.get()}", drops.get() > 0)
        assertEquals(40, received.get() + drops.get())
        bus.dispose()
    }

    @Test
    fun concurrentPublishersNeverSilentlyLoseEvents() = runBlocking {
        // P1-E1 regression: with the per-subscriber lock, concurrent
        // publishers must not interleave their drop+send sequences in a way
        // that drops an event but never audits it. Every event ends up either
        // delivered or audited as a drop — none vanish silently.
        val drops = AtomicInteger(0)
        val bus = TypedEventBus(
            auditSink = object : EventAuditSink {
                override fun onSubscriberError(eventType: String, subscriptionId: Long, error: Throwable) {
                    error("unexpected subscriber error")
                }

                override fun onBackpressureDrop(eventType: String, subscriptionId: Long) {
                    drops.incrementAndGet()
                }
            },
            channelCapacity = 4, // small so we actually hit backpressure
        )

        val received = AtomicInteger(0)
        bus.subscribe(EventFilter(typePrefix = "race.")) {
            delay(20) // slow consumer → buffer fills
            received.incrementAndGet()
        }

        // Fire 100 events from 10 concurrent threads.
        val threads = (1..10).map { t ->
            Thread {
                repeat(10) { i -> bus.publishEvent(envelope("race.$t.$i")) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Drain: every event is accounted for (delivered + dropped == 100).
        withTimeout(10_000) {
            while (received.get() + drops.get() < 100) delay(10)
        }
        assertEquals(100, received.get() + drops.get())
        bus.dispose()
        Unit
    }

    // ─── Run-event channel (P1 compatibility) ────────────────────────────

    @Test
    fun runEventsDeliverPerRun() = runBlocking {
        val bus = TypedEventBus()
        val a = CopyOnWriteArrayList<RuntimeEvent>()
        val b = CopyOnWriteArrayList<RuntimeEvent>()
        val jobA = launch { bus.observe("runA").collect { a.add(it) } }
        val jobB = launch { bus.observe("runB").collect { b.add(it) } }
        delay(50) // let the collectors subscribe before publishing

        bus.publish("runA", RuntimeEvent.RunStarted("runA", "c1", 1))
        bus.publish("runA", RuntimeEvent.RunSucceeded("runA", 5))
        bus.publish("runB", RuntimeEvent.RunStarted("runB", "c1", 2))

        withTimeout(2_000) {
            while (a.size < 2 || b.size < 1) delay(10)
        }
        assertEquals(2, a.size)
        assertEquals(1, b.size)
        assertEquals(RuntimeEvent.RunSucceeded::class, a[1]::class)
        jobA.cancel()
        jobB.cancel()
        bus.dispose()
    }

    // ─── Run-event lifecycle (per-run isolation fix) ─────────────────────

    /**
     * The CI-hang class: a run's terminal event must stay observable for a
     * late subscriber even when OTHER runs publish more events than the
     * replay window. The old global SharedFlow(replay=256) mixed all runs,
     * so a burst on unrelated runs evicted the victim's terminal event from
     * the shared replay window and late collectors waited forever.
     */
    @Test
    fun lateSubscriberSeesTerminalEventDespiteOtherRunsBurst() = runBlocking {
        val bus = TypedEventBus()
        bus.publish("victim", RuntimeEvent.RunStarted("victim", "cmd", 1))
        bus.publish("victim", RuntimeEvent.RunFailed("victim", "boom"))

        // Burst on unrelated runs, well past any replay window size.
        repeat(20) { n ->
            val runId = "noise$n"
            repeat(50) { bus.publish(runId, RuntimeEvent.Progress(runId, null, null)) }
        }

        val received = mutableListOf<RuntimeEvent>()
        // The collect completes at the terminal event; if it never does the
        // withTimeout fails the test instead of hanging the suite.
        withTimeout(2_000) {
            bus.observe("victim").collect { received.add(it) }
        }
        assertEquals(2, received.size)
        assertTrue(received.last() is RuntimeEvent.RunFailed)
        bus.dispose()
        Unit
    }

    /**
     * execute() returns its handle before the launched coroutine publishes
     * RunStarted, so callers legitimately subscribe BEFORE the run's first
     * event (ChatOrchestrator and MainActivity both do this). observe() on a
     * not-yet-published id must wait for the events, not complete empty.
     */
    @Test
    fun earlySubscriberReceivesEventsForNotYetStartedRun() = runBlocking {
        val bus = TypedEventBus()
        val received = CopyOnWriteArrayList<RuntimeEvent>()

        // Subscribe first — no state for "future" exists yet.
        val job = launch { bus.observe("future").collect { received.add(it) } }
        delay(50) // subscriber is attached; still nothing published
        assertTrue(received.isEmpty())

        bus.publish("future", RuntimeEvent.RunStarted("future", "c", 1))
        bus.publish("future", RuntimeEvent.RunSucceeded("future", 7))

        // The collector completes on its own at the terminal event.
        withTimeout(2_000) { job.join() }
        assertEquals(2, received.size)
        assertTrue(received.last() is RuntimeEvent.RunSucceeded)
        bus.dispose()
        Unit
    }

    @Test
    fun finishedRunHistoryEvictedAfterCap() = runBlocking {
        val bus = TypedEventBus()
        // Finish more runs than the retention cap keeps.
        repeat(TypedEventBus.MAX_RETAINED_FINISHED_RUNS + 5) { n ->
            val runId = "fin$n"
            bus.publish(runId, RuntimeEvent.RunStarted(runId, "c", n.toLong()))
            bus.publish(runId, RuntimeEvent.RunSucceeded(runId, 1))
        }

        // The oldest finished runs were evicted — observing them completes
        // empty instead of hanging.
        val ancient = mutableListOf<RuntimeEvent>()
        withTimeout(1_000) { bus.observe("fin0").collect { ancient.add(it) } }
        assertTrue(ancient.isEmpty())

        // The newest finished run still replays its full history.
        val newest = "fin${TypedEventBus.MAX_RETAINED_FINISHED_RUNS + 4}"
        val recent = mutableListOf<RuntimeEvent>()
        withTimeout(1_000) { bus.observe(newest).collect { recent.add(it) } }
        assertEquals(2, recent.size)
        assertTrue(recent.last() is RuntimeEvent.RunSucceeded)
        bus.dispose()
        Unit
    }

    /**
     * Concurrency stress: many runs publish concurrently on real threads
     * while observers attach both BEFORE and AFTER the burst. Per-run
     * isolation must guarantee every observer sees its run's terminal event.
     * The old global SharedFlow(replay=256) design failed exactly here —
     * 50 runs × 41 events = 2050 events >> 256, so runs' terminals were
     * evicted from the shared replay window mid-flight.
     */
    @Test
    fun concurrentRunsKeepTerminalEventsObservable() = runBlocking {
        val bus = TypedEventBus()
        val runCount = 50
        val progressPerRun = 40
        val early = ConcurrentHashMap<String, List<RuntimeEvent>>()
        val errors = CopyOnWriteArrayList<String>()

        // Early observers attach before any publish (early-subscriber path).
        val earlyJobs = (0 until runCount).map { n ->
            val runId = "stress$n"
            launch(Dispatchers.Default) {
                early[runId] = bus.observe(runId).toList()
            }
        }
        delay(100) // let the collectors attach before the burst

        // Concurrent publishers on real threads.
        val publishers = (0 until runCount).map { n ->
            val runId = "stress$n"
            launch(Dispatchers.Default) {
                repeat(progressPerRun) {
                    bus.publish(runId, RuntimeEvent.Progress(runId, it, null))
                }
                bus.publish(runId, RuntimeEvent.RunSucceeded(runId, 1))
            }
        }
        publishers.forEach { it.join() }

        // Every early observer must have completed at its run's terminal.
        withTimeout(10_000) { earlyJobs.forEach { it.join() } }
        for (n in 0 until runCount) {
            val runId = "stress$n"
            val received = early[runId]
            if (received == null || received.lastOrNull() !is RuntimeEvent.RunSucceeded) {
                errors.add("early $runId: terminal=${received?.lastOrNull()}")
            }
        }

        // Late observers must replay the full history and complete at the
        // terminal — deterministic via the replay cache.
        withTimeout(10_000) {
            coroutineScope {
                (0 until runCount).map { n ->
                    val runId = "stress$n"
                    launch(Dispatchers.Default) {
                        val received = bus.observe(runId).toList()
                        if (received.size != progressPerRun + 1 ||
                            received.last() !is RuntimeEvent.RunSucceeded
                        ) {
                            errors.add("late $runId: size=${received.size}")
                        }
                    }
                }
            }
        }

        bus.dispose()
        assertTrue("violations:\n${errors.joinToString("\n")}", errors.isEmpty())
        Unit
    }
}
