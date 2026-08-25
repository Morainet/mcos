package com.morainet.mcos.runtime.core.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/**
 * Tests for trigger parsing (05-workflow.md §9) and spec-aware
 * [WorkflowStore] — slice 1 of the event-triggered recipes feature.
 */
class WorkflowTriggerParsingTest {

    private fun spec(text: String): WorkflowSpec? =
        WorkflowJson.specFromJson(Json.parseToJsonElement(text))

    // ─── Manual trigger (§9.1) ───────────────────────────────────────────

    @Test
    fun `TP1-manual trigger parses source and declared inputs`() {
        val parsed = spec(
            """{"type":"workflow","trigger":{"type":"manual","source":"chat","inputs":["recipient","message"]},
                "body":{"type":"command","commandId":"sys.notify"}}"""
        )

        val trigger = parsed?.trigger as? Trigger.Manual
        assertNotNull(trigger)
        assertEquals("chat", trigger.source)
        assertEquals(listOf("recipient", "message"), trigger.inputs)
        val step = parsed.step as? WorkflowStep.Command
        assertNotNull(step)
        assertEquals("sys.notify", step.commandId)
    }

    @Test
    fun `TP2-manual trigger defaults`() {
        val parsed = spec(
            """{"type":"workflow","trigger":{"type":"manual"},"body":{"type":"command","commandId":"sys.notify"}}"""
        )

        val trigger = parsed?.trigger as? Trigger.Manual
        assertNotNull(trigger)
        assertNull(trigger.source)
        assertTrue(trigger.inputs.isEmpty())
    }

    // ─── Event trigger (§9.2) ────────────────────────────────────────────

    @Test
    fun `TP3-event trigger parses filter with where`() {
        val parsed = spec(
            """{"type":"workflow","trigger":{"type":"event","filter":{"type":"wifi.connected","where":{"ssid":"Office"}}},
                "body":{"type":"command","commandId":"vpn.connect"}}"""
        )

        val trigger = parsed?.trigger as? Trigger.Event
        assertNotNull(trigger)
        assertEquals("wifi.connected", (trigger.filter["type"] as JsonPrimitive).content)
        assertEquals(
            """{"ssid":"Office"}""",
            trigger.filter["where"].toString()
        )
    }

    @Test
    fun `TP4-event resolveMemory defaults to ARM`() {
        val parsed = spec(
            """{"type":"workflow","trigger":{"type":"event","filter":{"type":"wifi.connected"}},
                "body":{"type":"command","commandId":"vpn.connect"}}"""
        )

        assertEquals(MemoryResolution.ARM, (parsed?.trigger as? Trigger.Event)?.resolveMemory)
    }

    @Test
    fun `TP5-event resolveMemory fire is honored`() {
        val parsed = spec(
            """{"type":"workflow","trigger":{"type":"event","filter":{"type":"battery.low"},"resolveMemory":"fire"},
                "body":{"type":"command","commandId":"sys.notify"}}"""
        )

        assertEquals(MemoryResolution.FIRE, (parsed?.trigger as? Trigger.Event)?.resolveMemory)
    }

    @Test
    fun `TP6-event invalid resolveMemory rejects the spec`() {
        assertNull(
            spec(
                """{"type":"workflow","trigger":{"type":"event","filter":{"type":"wifi.connected"},"resolveMemory":"sometimes"},
                    "body":{"type":"command","commandId":"vpn.connect"}}"""
            ),
            "an unparseable trigger must never silently degrade to manual-only"
        )
    }

    @Test
    fun `TP7-event trigger without filter object rejects`() {
        assertNull(
            spec(
                """{"type":"workflow","trigger":{"type":"event"},"body":{"type":"command","commandId":"vpn.connect"}}"""
            )
        )
    }

    @Test
    fun `TP8-dollar-memory where references pass through untouched`() {
        val parsed = spec(
            """{"type":"workflow","trigger":{"type":"event",
                "filter":{"type":"wifi.connected","where":{"ssid":{"${'$'}memory":"places.office.wifiSsids"}}}},
                "body":{"type":"command","commandId":"vpn.connect"}}"""
        )

        val where = (parsed?.trigger as? Trigger.Event)?.filter?.get("where")
        assertEquals("""{"ssid":{"${'$'}memory":"places.office.wifiSsids"}}""", where.toString())
    }

    @Test
    fun `TP9-trigger inside the body is also accepted (compile pass reads body-trigger)`() {
        val parsed = spec(
            """{"type":"workflow","body":{"type":"sequential","trigger":{"type":"event","filter":{"type":"wifi.connected"}},
                "steps":[{"type":"command","commandId":"vpn.connect"}]}}"""
        )

        assertNotNull(parsed?.trigger as? Trigger.Event)
        assertTrue(parsed!!.step is WorkflowStep.Sequential)
    }

    // ─── Schedule trigger (§9.3 — parse-only this round) ─────────────────

    @Test
    fun `TP10-schedule trigger parses with misfirePolicy default`() {
        val parsed = spec(
            """{"type":"workflow","trigger":{"type":"schedule","cron":"0 9 * * 1-5","tz":"Asia/Shanghai"},
                "body":{"type":"command","commandId":"sys.notify"}}"""
        )

        val trigger = parsed?.trigger as? Trigger.Schedule
        assertNotNull(trigger)
        assertEquals("0 9 * * 1-5", trigger.cron)
        assertEquals("Asia/Shanghai", trigger.tz)
        assertEquals("skip", trigger.misfirePolicy)
    }

    @Test
    fun `TP11-schedule invalid misfirePolicy rejects`() {
        assertNull(
            spec(
                """{"type":"workflow","trigger":{"type":"schedule","cron":"0 9 * * *","tz":"UTC","misfirePolicy":"yolo"},
                    "body":{"type":"command","commandId":"sys.notify"}}"""
            )
        )
    }

    @Test
    fun `TP12-schedule missing tz rejects`() {
        assertNull(
            spec(
                """{"type":"workflow","trigger":{"type":"schedule","cron":"0 9 * * *"},
                    "body":{"type":"command","commandId":"sys.notify"}}"""
            )
        )
    }

    // ─── Absent / unknown triggers ───────────────────────────────────────

    @Test
    fun `TP13-no trigger yields a manual-only spec`() {
        val parsed = spec("""{"type":"workflow","body":{"type":"command","commandId":"sys.notify"}}""")

        assertNotNull(parsed)
        assertNull(parsed.trigger)
    }

    @Test
    fun `TP14-unknown trigger type rejects the spec`() {
        assertNull(
            spec(
                """{"type":"workflow","trigger":{"type":"telepathy"},"body":{"type":"command","commandId":"sys.notify"}}"""
            )
        )
    }

    @Test
    fun `TP15-unparseable step tree rejects even with a valid trigger`() {
        assertNull(
            spec(
                """{"type":"workflow","trigger":{"type":"event","filter":{"type":"wifi.connected"}},
                    "body":{"type":"nonexistent"}}"""
            )
        )
    }

    @Test
    fun `TP16-fromJson still drops the trigger (back-compat)`() {
        val step = WorkflowJson.fromJson(
            Json.parseToJsonElement(
                """{"type":"workflow","trigger":{"type":"event","filter":{"type":"wifi.connected"}},
                    "body":{"type":"command","commandId":"vpn.connect"}}"""
            )
        )

        val command = step as? WorkflowStep.Command
        assertNotNull(command)
        assertEquals("vpn.connect", command.commandId)
    }

    // ─── Spec-aware WorkflowStore ────────────────────────────────────────

    @Test
    fun `TP17-store round-trips a spec with trigger`() {
        val store = WorkflowStore()
        val spec = WorkflowSpec(
            trigger = Trigger.Event(filter = Json.parseToJsonElement("""{"type":"wifi.connected"}""") as kotlinx.serialization.json.JsonObject),
            step = WorkflowStep.Command("vpn.connect")
        )

        store.registerSpec("recipe.office.vpn", spec)

        assertEquals(spec, store.spec("recipe.office.vpn"))
        assertEquals(spec.step, store.get("recipe.office.vpn"))
    }

    @Test
    fun `TP18-legacy register stores a manual-only spec`() {
        val store = WorkflowStore()
        store.register("legacy", WorkflowStep.Command("sys.notify"))

        val spec = store.spec("legacy")
        assertNotNull(spec)
        assertNull(spec.trigger)
        assertEquals(WorkflowStep.Command("sys.notify"), store.get("legacy"))
    }

    @Test
    fun `TP19-registerSpec overwrites legacy register and remove clears both views`() {
        val store = WorkflowStore()
        store.register("wf", WorkflowStep.Command("a"))
        store.registerSpec("wf", WorkflowSpec(null, WorkflowStep.Command("b")))

        assertEquals("b", (store.get("wf") as WorkflowStep.Command).commandId)
        assertNotNull(store.remove("wf"))
        assertNull(store.get("wf"))
        assertNull(store.spec("wf"))
    }

    @Test
    fun `TP20-registerSpec blank id throws`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowStore().registerSpec(" ", WorkflowSpec(null, WorkflowStep.Command("a")))
        }
    }
}
