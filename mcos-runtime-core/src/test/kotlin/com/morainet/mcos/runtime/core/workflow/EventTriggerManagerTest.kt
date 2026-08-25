package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.EventEnvelope
import com.morainet.mcos.runtime.core.events.EventFilter
import com.morainet.mcos.runtime.core.events.EventSubscription
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/**
 * Conformance tests for [EventTriggerManager] (05-workflow.md §9.2,
 * 07-memory.md §13.1, 08-security.md §10.0).
 *
 * Uses a synchronous fake bus (handlers run inline in publishEvent) so event
 * delivery, memory resolution and rate limiting are fully deterministic.
 */
class EventTriggerManagerTest {

    /** Synchronous in-process bus: publishEvent dispatches to matching handlers inline. */
    private class FakeBus : EventBus {
        private val nextId = AtomicLong(0)
        private val subs = ConcurrentHashMap<Long, Pair<EventFilter, suspend (EventEnvelope) -> Unit>>()
        val published = CopyOnWriteArrayList<EventEnvelope>()

        override fun publish(runId: String, event: RuntimeEvent) {}
        override fun observe(runId: String): Flow<RuntimeEvent> = emptyFlow()

        override fun subscribe(filter: EventFilter, handler: suspend (EventEnvelope) -> Unit): EventSubscription {
            val id = nextId.incrementAndGet()
            subs[id] = filter to handler
            return EventSubscription(id)
        }

        override fun unsubscribe(subscription: EventSubscription) {
            subs.remove(subscription.id)
        }

        override fun publishEvent(envelope: EventEnvelope) {
            published.add(envelope)
            // Inline (blocking) dispatch keeps tests deterministic: the handler
            // completes before publishEvent returns.
            runBlocking {
                subs.values.forEach { (filter, handler) ->
                    if (filter.matches(envelope)) handler(envelope)
                }
            }
        }

        fun activeSubscriptions(): Int = subs.size
    }

    private class RecordingAuditLog : AuditLog {
        val records = CopyOnWriteArrayList<RunRecord>()
        override fun append(record: RunRecord) { records.add(record) }
        override suspend fun flush() {}
        override fun start() {}
        override fun stop() {}
        override fun getRuns(): List<RunRecord> = records.reversed()
        override fun getRun(runId: String): RunRecord? = records.lastOrNull { it.runId == runId }
        override fun getRecent(limit: Int): List<RunRecord> = records.takeLast(limit).reversed()
        override fun count(): Int = records.size
        override fun export(): String = records.joinToString("\n") { it.toString() }
        override fun clear() { records.clear() }
    }

    private lateinit var bus: FakeBus
    private lateinit var memory: MemoryStore
    private lateinit var audit: RecordingAuditLog

    /** (workflowId, payload, preAuthorized) per launch. */
    private val launched = CopyOnWriteArrayList<Triple<String, JsonObject, Boolean>>()

    @BeforeTest
    fun setUp() {
        bus = FakeBus()
        memory = MemoryStore()
        audit = RecordingAuditLog()
    }

    private fun manager(limits: TriggerLimits = TriggerLimits(), clock: () -> Long = System::currentTimeMillis) =
        EventTriggerManager(bus, memory, audit, limits, clock)

    private fun eventTrigger(
        type: String,
        where: JsonObject? = null,
        resolveMemory: MemoryResolution = MemoryResolution.ARM,
    ): Trigger.Event = Trigger.Event(
        filter = buildJsonObject {
            put("type", type)
            if (where != null) put("where", where)
        },
        resolveMemory = resolveMemory,
    )

    private fun envelope(type: String, payload: JsonObject = JsonObject(emptyMap())) =
        EventEnvelope(type = type, timestamp = 0L, payload = payload, source = "test")

    private suspend fun arm(
        manager: EventTriggerManager,
        workflowId: String = "wf",
        trigger: Trigger.Event = eventTrigger("wifi.connected"),
        preAuthorized: Boolean = false,
    ): TriggerArmResult = manager.arm(workflowId, trigger, preAuthorized) { id, inputs, pre ->
        launched.add(Triple(id, inputs, pre))
    }

    private suspend fun putMemory(path: String, value: JsonElement) {
        memory.put(path, value, checkConflict = false)
    }

    // ═══════════════════════════════════════════════════════════════
    // TR1-TR3: subscription, type prefix, where literals
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TR1-matching event fires launcher with payload and preAuthorized flag`() = runBlocking {
        val manager = manager()
        val result = arm(manager, "wf", eventTrigger("wifi.connected"), preAuthorized = true)
        assertIs<TriggerArmResult.Armed>(result)

        bus.publishEvent(
            envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) })
        )

        assertEquals(1, launched.size)
        assertEquals("wf", launched[0].first)
        assertEquals("Office", launched[0].second["ssid"]?.jsonPrimitiveOrNull()?.content)
        assertTrue(launched[0].third, "preAuthorized flag must thread through to the launcher")
    }

    @Test
    fun `TR2-non-matching event type does not fire`() = runBlocking {
        val manager = manager()
        arm(manager, trigger = eventTrigger("wifi.connected"))

        bus.publishEvent(envelope("battery.low"))
        bus.publishEvent(envelope("wifiw.connected"))

        assertTrue(launched.isEmpty())
    }

    @Test
    fun `TR3-where literal deep equality, extra payload keys ignored`() = runBlocking {
        val manager = manager()
        arm(
            manager,
            trigger = eventTrigger(
                "wifi.connected",
                where = buildJsonObject {
                    put("ssid", JsonPrimitive("Office"))
                    putJsonObject("meta") { put("band", JsonPrimitive("5g")) }
                },
            ),
        )

        // Match: nested equal, extra top-level key ignored.
        bus.publishEvent(
            envelope(
                "wifi.connected",
                buildJsonObject {
                    put("ssid", JsonPrimitive("Office"))
                    putJsonObject("meta") { put("band", JsonPrimitive("5g")); put("ch", JsonPrimitive(36)) }
                    put("rssi", JsonPrimitive(-55))
                },
            )
        )
        // Mismatch: nested band differs.
        bus.publishEvent(
            envelope(
                "wifi.connected",
                buildJsonObject {
                    put("ssid", JsonPrimitive("Office"))
                    putJsonObject("meta") { put("band", JsonPrimitive("2.4g")) }
                },
            )
        )
        // Mismatch: key absent.
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("rssi", JsonPrimitive(-55)) }))

        assertEquals(1, launched.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // TR4-TR6: $memory arm vs fire resolution (07 §13.1)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TR4-ARM mode resolves memory at arm time`() = runBlocking {
        putMemory("places.office.ssid", JsonPrimitive("Office"))
        val manager = manager()
        arm(
            manager,
            trigger = eventTrigger(
                "wifi.connected",
                where = buildJsonObject {
                    put("ssid", buildJsonObject { put("\$memory", JsonPrimitive("places.office.ssid")) })
                },
                resolveMemory = MemoryResolution.ARM,
            ),
        )

        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Home")) }))
        assertEquals(1, launched.size)
    }

    @Test
    fun `TR5-ARM mode ignores memory changes after arming`() = runBlocking {
        putMemory("places.office.ssid", JsonPrimitive("Office"))
        val manager = manager()
        arm(
            manager,
            trigger = eventTrigger(
                "wifi.connected",
                where = buildJsonObject {
                    put("ssid", buildJsonObject { put("\$memory", JsonPrimitive("places.office.ssid")) })
                },
            ),
        )
        // User updates the office SSID after arming.
        putMemory("places.office.ssid", JsonPrimitive("NewOffice"))

        // Still matches the ARM-time value; the new value does NOT match
        // until re-arm (05 §9.2).
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("NewOffice")) }))
        assertEquals(listOf("Office"), launched.map { it.second["ssid"]!!.jsonPrimitiveOrNull()!!.content })
    }

    @Test
    fun `TR6-FIRE mode re-resolves memory per event`() = runBlocking {
        putMemory("places.office.ssid", JsonPrimitive("Office"))
        val manager = manager()
        arm(
            manager,
            trigger = eventTrigger(
                "wifi.connected",
                where = buildJsonObject {
                    put("ssid", buildJsonObject { put("\$memory", JsonPrimitive("places.office.ssid")) })
                },
                resolveMemory = MemoryResolution.FIRE,
            ),
        )

        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        putMemory("places.office.ssid", JsonPrimitive("NewOffice"))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("NewOffice")) }))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))

        assertEquals(
            listOf("Office", "NewOffice"),
            launched.map { it.second["ssid"]!!.jsonPrimitiveOrNull()!!.content },
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // TR7-TR8: missing memory path → filter false + audit warn (07 §13.1)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TR7-ARM mode missing path arms but never fires, warns once`() = runBlocking {
        val manager = manager()
        val result = arm(
            manager,
            trigger = eventTrigger(
                "wifi.connected",
                where = buildJsonObject {
                    put("ssid", buildJsonObject { put("\$memory", JsonPrimitive("places.office.ssid")) })
                },
            ),
        )
        assertIs<TriggerArmResult.Armed>(result)

        // No memory value exists: the filter is false for everything.
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        bus.publishEvent(envelope("wifi.connected"))
        assertTrue(launched.isEmpty())

        val warn = audit.records.filter { it.commandId == "workflow.trigger_memory_missing" }
        assertEquals(1, warn.size, "arm-time resolution warns exactly once")
        assertTrue(warn[0].ir!!.contains("places.office.ssid"))
        assertTrue(warn[0].ir!!.contains("resolvedAt=arm"))
        assertEquals("EVENT", warn[0].source)

        // Once the value exists, a re-arm picks it up and fires.
        putMemory("places.office.ssid", JsonPrimitive("Office"))
        arm(
            manager,
            trigger = eventTrigger(
                "wifi.connected",
                where = buildJsonObject {
                    put("ssid", buildJsonObject { put("\$memory", JsonPrimitive("places.office.ssid")) })
                },
            ),
        )
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        assertEquals(1, launched.size)
    }

    @Test
    fun `TR8-FIRE mode missing path skips event, matches once value appears`() = runBlocking {
        val manager = manager()
        arm(
            manager,
            trigger = eventTrigger(
                "wifi.connected",
                where = buildJsonObject {
                    put("ssid", buildJsonObject { put("\$memory", JsonPrimitive("places.office.ssid")) })
                },
                resolveMemory = MemoryResolution.FIRE,
            ),
        )

        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        assertTrue(launched.isEmpty())

        putMemory("places.office.ssid", JsonPrimitive("Office"))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        assertEquals(1, launched.size)

        val warns = audit.records.filter { it.commandId == "workflow.trigger_memory_missing" }
        assertEquals(1, warns.size)
        assertTrue(warns[0].ir!!.contains("resolvedAt=fire"))
    }

    // ═══════════════════════════════════════════════════════════════
    // TR9: array memory value = membership (07 §13.1 canonical case)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TR9-array memory value matches by membership`() = runBlocking {
        putMemory(
            "places.office.wifiSsids",
            JsonArray(listOf(JsonPrimitive("Office-5G"), JsonPrimitive("Office-24G"))),
        )
        val manager = manager()
        arm(
            manager,
            trigger = eventTrigger(
                "wifi.connected",
                where = buildJsonObject {
                    put("ssid", buildJsonObject { put("\$memory", JsonPrimitive("places.office.wifiSsids")) })
                },
            ),
        )

        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office-5G")) }))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office-24G")) }))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Cafe-Guest")) }))
        // An event whose ssid is itself an array equals no single member.
        bus.publishEvent(
            envelope(
                "wifi.connected",
                buildJsonObject {
                    put("ssid", JsonArray(listOf(JsonPrimitive("Office-5G"), JsonPrimitive("Office-24G"))))
                },
            )
        )

        assertEquals(
            listOf("Office-5G", "Office-24G"),
            launched.map { it.second["ssid"]!!.jsonPrimitiveOrNull()!!.content },
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // TR10: rate limit — sliding 1h window (08 §10.0)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TR10-over-limit fires are skipped and audited, window slides`() = runBlocking {
        var now = 1_000_000L
        val manager = manager(limits = TriggerLimits(maxBackgroundFiresPerHour = 3), clock = { now })
        arm(manager, trigger = eventTrigger("battery.low"))

        fun fire() = bus.publishEvent(envelope("battery.low"))

        fire(); fire(); fire()
        assertEquals(3, launched.size)

        // 4th event inside the window: skipped + audited, launcher not called.
        fire()
        assertEquals(3, launched.size)
        val limited = audit.records.filter { it.commandId == "workflow.trigger_rate_limited" }
        assertEquals(1, limited.size)
        assertTrue(limited[0].ir!!.contains("workflow=wf"))
        assertEquals("EVENT", limited[0].source)

        // Only max fires are audited as fired.
        assertEquals(3, audit.records.count { it.commandId == "workflow.trigger_fired" })

        // After the window slides, firing resumes.
        now += TriggerLimits.WINDOW_MS
        fire()
        assertEquals(4, launched.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // TR11-TR12: disarm / re-arm
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TR11-disarm stops firing and reports armed state`() = runBlocking {
        val manager = manager()
        arm(manager, "wf", eventTrigger("wifi.connected"))
        assertEquals(listOf("wf"), manager.armed())

        assertTrue(manager.disarm("wf"))
        assertFalse(manager.disarm("wf"), "second disarm of the same id is false")
        assertTrue(manager.armed().isEmpty())

        bus.publishEvent(envelope("wifi.connected"))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `TR12-re-arm replaces the subscription instead of doubling it`() = runBlocking {
        val manager = manager()
        arm(manager, trigger = eventTrigger("wifi.connected", where = buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        arm(manager, trigger = eventTrigger("wifi.connected", where = buildJsonObject { put("ssid", JsonPrimitive("Home")) }))
        assertEquals(1, bus.activeSubscriptions(), "re-arm must unsubscribe the previous subscription")

        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Home")) }))

        assertEquals(listOf("Home"), launched.map { it.second["ssid"]!!.jsonPrimitiveOrNull()!!.content })
    }

    // ═══════════════════════════════════════════════════════════════
    // TR13-TR14: rejections and invariants
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TR13-non-Event triggers and malformed filters are rejected`() = runBlocking {
        val manager = manager()

        val schedule = manager.arm("wf.sched", Trigger.Schedule(cron = "0 23 * * *", tz = "Asia/Shanghai")) { _, _, _ -> }
        assertIs<TriggerArmResult.Rejected>(schedule)
        assertEquals("schedule_triggers_unsupported", schedule.reason)

        val manual = manager.arm("wf.manual", Trigger.Manual(source = "voice")) { _, _, _ -> }
        assertIs<TriggerArmResult.Rejected>(manual)
        assertEquals("manual_triggers_cannot_be_armed", manual.reason)

        val noType = manager.arm(
            "wf.notype",
            Trigger.Event(filter = buildJsonObject { putJsonObject("where") { put("ssid", JsonPrimitive("Office")) } }),
        ) { _, _, _ -> }
        assertIs<TriggerArmResult.Rejected>(noType)
        assertEquals("trigger_filter_type_required", noType.reason)

        assertTrue(manager.armed().isEmpty(), "rejected arms must not leave subscriptions behind")
        assertEquals(0, bus.activeSubscriptions())
    }

    @Test
    fun `TR14-blank workflow id rejected and disarmAll clears state`() = runBlocking {
        val manager = manager()
        assertFailsWith<IllegalArgumentException> {
            runBlocking { manager.arm(" ", eventTrigger("wifi.connected")) { _, _, _ -> } }
        }

        arm(manager, "wf.a", eventTrigger("battery.low"))
        arm(manager, "wf.b", eventTrigger("wifi.connected"))
        assertEquals(listOf("wf.a", "wf.b"), manager.armed())

        manager.disarmAll()
        assertTrue(manager.armed().isEmpty())
        assertEquals(0, bus.activeSubscriptions())

        bus.publishEvent(envelope("wifi.connected"))
        bus.publishEvent(envelope("battery.low"))
        assertTrue(launched.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // Audit: workflow.trigger_fired record shape
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TR15-fired trigger writes its trigger_fired audit record`() = runBlocking {
        val manager = manager()
        arm(manager, "wifi-vpn", eventTrigger("wifi.connected"))
        bus.publishEvent(envelope("wifi.connected", buildJsonObject { put("ssid", JsonPrimitive("Office")) }))

        val fired = audit.records.single { it.commandId == "workflow.trigger_fired" }
        assertEquals("EVENT", fired.source)
        assertTrue(fired.ir!!.contains("workflow=wifi-vpn"))
        assertTrue(fired.ir!!.contains("event=wifi.connected"))
        assertTrue(fired.runId.startsWith("trigger:"))
    }

    private fun JsonElement?.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}
