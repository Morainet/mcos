package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.EventEnvelope
import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.security.permission.PermissionKernel
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.workflow.MemoryResolution
import com.morainet.mcos.runtime.core.workflow.Trigger
import com.morainet.mcos.runtime.core.workflow.TriggerArmResult
import com.morainet.mcos.runtime.core.workflow.WorkflowSpec
import com.morainet.mcos.runtime.core.workflow.WorkflowStep
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * End-to-end tests for event-triggered workflows through the [McosRuntime]
 * facade: register spec → armTrigger → publishEvent → run completes
 * (05-workflow.md §9.2, roadmap §5.6.1 exit criterion).
 */
class McosRuntimeTriggerTest {

    private lateinit var bus: TypedEventBus
    private lateinit var registry: CommandRegistry
    private lateinit var permissions: PermissionKernel
    private lateinit var memory: MemoryStore
    private lateinit var audit: InMemoryAuditLog
    private lateinit var runtime: McosRuntime

    /** (commandId, args) per invocation, recorded by the test plugin. */
    private val invocations = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    @BeforeTest
    fun setUp() {
        bus = TypedEventBus()
        registry = CommandRegistry()
        permissions = DefaultPermissionKernel()
        memory = MemoryStore()
        audit = InMemoryAuditLog()
        runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(permissions)
            .withMemory(memory)
            .withEventBus(bus)
            .withAuditLog(audit)
            .build()
        // InMemoryAuditLog buffers appends through a writer coroutine; start
        // it so records actually land (flush() then drains them in tests).
        audit.start()
    }

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
        bus.dispose()
        audit.stop()
        registry.clear()
    }

    private fun registerRecordingCommand(id: String, sideEffectClass: SideEffectClass = SideEffectClass.read) {
        val plugin = object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "test-plugin-$id",
                name = "Test Plugin",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Test plugin for $id",
                provider = ProviderInfo("TestOrg", "https://example.com"),
                entry = "com.morainet.mcos.plugin.test.TestPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = id,
                        version = "1.0",
                        title = id,
                        description = "Test command $id",
                        sideEffectClass = sideEffectClass,
                        inputSchema = JsonObject(emptyMap()),
                    )
                ),
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(
                id to object : CommandHandler {
                    override suspend fun invoke(ctx: com.morainet.mcos.sdk.ExecutionContext): CommandResult {
                        invocations.add(id to (ctx.args as? JsonObject ?: JsonObject(emptyMap())))
                        return CommandResult.Ok(value = buildJsonObject { put("echo", JsonPrimitive(id)) })
                    }
                },
            )
            override suspend fun onLoad(services: HostServices) {
                permissions.grant(manifest.id, "mcos:all")
            }
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }

    private fun wifiTriggerSpec(resolveMemory: MemoryResolution = MemoryResolution.ARM): WorkflowSpec =
        WorkflowSpec(
            trigger = Trigger.Event(
                filter = buildJsonObject {
                    put("type", "wifi.connected")
                    putJsonObject("where") { put("ssid", JsonPrimitive("Office")) }
                },
                resolveMemory = resolveMemory,
            ),
            step = WorkflowStep.Command(
                commandId = "net.notify",
                args = buildJsonObject {
                    put("network", buildJsonObject { put("\$ref", JsonPrimitive("__input.ssid")) })
                },
            ),
        )

    private fun publishWifiEvent(ssid: String) {
        bus.publishEvent(
            EventEnvelope(
                type = "wifi.connected",
                timestamp = System.currentTimeMillis(),
                payload = buildJsonObject { put("ssid", JsonPrimitive(ssid)) },
                source = "test",
            )
        )
    }

    private suspend fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TE1: full end-to-end fire
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TE1-wifi event fires armed workflow end-to-end with payload as input`() = runBlocking {
        registerRecordingCommand("net.notify")
        runtime.workflowStore().registerSpec("wifi-vpn", wifiTriggerSpec())

        val arm = runtime.armTrigger("wifi-vpn")
        assertIs<TriggerArmResult.Armed>(arm)
        assertEquals(listOf("wifi-vpn"), runtime.armedTriggers())

        publishWifiEvent("Office")
        awaitTrue { invocations.isNotEmpty() }

        assertEquals(1, invocations.size)
        assertEquals("net.notify", invocations[0].first)
        assertEquals(
            "Office",
            (invocations[0].second["network"] as? JsonPrimitive)?.content,
            "event payload must bind to __input.ssid",
        )

        // Non-matching where: no second run.
        publishWifiEvent("Cafe-Guest")
        delay(300)
        assertEquals(1, invocations.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // TE2-TE3: audit trail
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TE2-trigger fire writes trigger_fired audit record`() = runBlocking {
        registerRecordingCommand("net.notify")
        runtime.workflowStore().registerSpec("wifi-vpn", wifiTriggerSpec())
        runtime.armTrigger("wifi-vpn")

        publishWifiEvent("Office")
        awaitTrue { invocations.isNotEmpty() }
        audit.flush()

        val fired = audit.getRuns().single { it.commandId == "workflow.trigger_fired" }
        assertEquals("EVENT", fired.source)
        assertTrue(fired.ir!!.contains("workflow=wifi-vpn"))
        assertTrue(fired.ir!!.contains("event=wifi.connected"))
    }

    @Test
    fun `TE3-trigger-fired run audits its steps with source EVENT`() = runBlocking {
        registerRecordingCommand("net.notify")
        runtime.workflowStore().registerSpec("wifi-vpn", wifiTriggerSpec())
        runtime.armTrigger("wifi-vpn")

        publishWifiEvent("Office")
        awaitTrue { invocations.isNotEmpty() }
        // Give the executor's async audit append a moment to land.
        audit.flush()

        val stepRecord = audit.getRuns().single { it.source == "EVENT" && it.commandId == "net.notify" }
        assertTrue(stepRecord.steps.isNotEmpty())
        assertTrue(stepRecord.steps[0].ok)
    }

    // ═══════════════════════════════════════════════════════════════
    // TE4: rejection reasons
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TE4-armTrigger rejects unknown workflow, manual-only spec, schedule trigger`() = runBlocking {
        registerRecordingCommand("net.notify")

        val unknown = runtime.armTrigger("no.such.workflow")
        assertIs<TriggerArmResult.Rejected>(unknown)
        assertEquals("workflow_not_found", unknown.reason)

        runtime.workflowStore().registerSpec(
            "manual-only",
            WorkflowSpec(trigger = null, step = WorkflowStep.Command("net.notify")),
        )
        val manual = runtime.armTrigger("manual-only")
        assertIs<TriggerArmResult.Rejected>(manual)
        assertEquals("workflow_has_no_trigger", manual.reason)

        runtime.workflowStore().registerSpec(
            "nightly",
            WorkflowSpec(
                trigger = Trigger.Schedule(cron = "0 23 * * *", tz = "Asia/Shanghai"),
                step = WorkflowStep.Command("net.notify"),
            ),
        )
        val schedule = runtime.armTrigger("nightly")
        assertIs<TriggerArmResult.Rejected>(schedule)
        assertEquals("schedule_triggers_unsupported", schedule.reason)

        assertTrue(runtime.armedTriggers().isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // TE5: disarm + shutdown release
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TE5-disarm stops firing and shutdown disarms everything`() = runBlocking {
        registerRecordingCommand("net.notify")
        runtime.workflowStore().registerSpec("wifi-vpn", wifiTriggerSpec())

        runtime.armTrigger("wifi-vpn")
        assertTrue(runtime.disarmTrigger("wifi-vpn"))
        assertFalse(runtime.disarmTrigger("wifi-vpn"))

        publishWifiEvent("Office")
        delay(300)
        assertTrue(invocations.isEmpty(), "disarmed trigger must not fire")

        // Re-arm, then shutdown() releases every armed trigger.
        runtime.armTrigger("wifi-vpn")
        assertEquals(listOf("wifi-vpn"), runtime.armedTriggers())
        runtime.shutdown()
        assertTrue(runtime.armedTriggers().isEmpty(), "shutdown must disarm all triggers")
    }

    // ═══════════════════════════════════════════════════════════════
    // Regression: manual WorkflowRef execution still works (source CLI)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `TE6-manual workflow ref run still executes with CLI step source`() = runBlocking {
        registerRecordingCommand("plain.cmd")
        runtime.workflowStore().registerSpec(
            "plain",
            WorkflowSpec(trigger = null, step = WorkflowStep.Command("plain.cmd")),
        )

        runtime.execute(
            ExecuteRequest(source = Source.CLI, payload = Payload.WorkflowRef("plain"))
        )
        awaitTrue { invocations.isNotEmpty() }
        assertEquals(1, invocations.size)

        // Manual runs keep their CLI audit source (08 §14) — the EVENT label
        // is reserved for trigger-fired runs.
        audit.flush()
        assertTrue(
            audit.getRuns().any { it.commandId == "plain.cmd" && it.source == "CLI" },
            "manual workflow run should audit its step with source CLI",
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // TE7-TE10: pre-authorization stamps + EVENT confirmation rules
    // (05 §10, 08 §4.0 step 4 / §4.1)
    // ═══════════════════════════════════════════════════════════════

    /** Bus decorator recording run events so tests can capture trigger-run ids. */
    private class RecordingBus(private val delegate: TypedEventBus) : EventBus by delegate {
        val runEvents = CopyOnWriteArrayList<Pair<String, RuntimeEvent>>()
        override fun publish(runId: String, event: RuntimeEvent) {
            runEvents.add(runId to event)
            delegate.publish(runId, event)
        }
    }

    private fun buildRuntime(bus: EventBus): McosRuntime =
        McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(permissions)
            .withMemory(memory)
            .withEventBus(bus)
            .withAuditLog(audit)
            .build()

    @Test
    fun `TE7-pre-authorized write step runs silently via shared stamp`() = runBlocking {
        registerRecordingCommand("net.connect", SideEffectClass.write)
        permissions.grant("test-plugin-net.connect", "android.permission.CHANGE_NETWORK_STATE")
        val bus = RecordingBus(this@McosRuntimeTriggerTest.bus)
        runtime = buildRuntime(bus)

        runtime.workflowStore().registerSpec(
            "wifi-vpn",
            WorkflowSpec(
                trigger = Trigger.Event(
                    filter = buildJsonObject { put("type", "wifi.connected") },
                ),
                step = WorkflowStep.Command(
                    "net.connect",
                    args = buildJsonObject {
                        put("ssid", buildJsonObject { put("\$ref", JsonPrimitive("__input.ssid")) })
                    },
                ),
            ),
        )
        assertIs<TriggerArmResult.Armed>(runtime.armTrigger("wifi-vpn", preAuthorized = true))

        publishWifiEvent("Office")
        awaitTrue { invocations.isNotEmpty() }

        // The write ran with no confirmation surfaced and the event payload
        // bound through the pre-authorized run's __input.
        assertEquals(1, invocations.size)
        assertEquals("Office", (invocations[0].second["ssid"] as? JsonPrimitive)?.content)
        assertTrue(
            bus.runEvents.none { it.second is RuntimeEvent.ConfirmationNeeded },
            "pre-authorized write must not challenge",
        )
    }

    @Test
    fun `TE8-destructive step still challenges and rejects without approval`() = runBlocking {
        registerRecordingCommand("fs.wipe", SideEffectClass.destructive)
        permissions.grant("test-plugin-fs.wipe", "mcos:storage")
        permissions.setAutoApprove("fs.wipe", true) // even auto-approved…
        val bus = RecordingBus(this@McosRuntimeTriggerTest.bus)
        runtime = buildRuntime(bus)

        runtime.workflowStore().registerSpec(
            "danger",
            WorkflowSpec(
                trigger = Trigger.Event(filter = buildJsonObject { put("type", "wifi.connected") }),
                step = WorkflowStep.Command("fs.wipe"),
            ),
        )
        assertIs<TriggerArmResult.Armed>(runtime.armTrigger("danger", preAuthorized = true))

        publishWifiEvent("Office")

        // The challenge surfaces to the host…
        awaitTrue { bus.runEvents.any { it.second is RuntimeEvent.ConfirmationNeeded } }
        val confirmation = bus.runEvents.first { it.second is RuntimeEvent.ConfirmationNeeded }
        val needed = confirmation.second as RuntimeEvent.ConfirmationNeeded
        assertEquals("fs.wipe", needed.commandId)

        // …and an explicit Reject keeps the step unexecuted.
        assertTrue(runtime.respondConfirmation(confirmation.first, "fs.wipe", ConfirmationDecision.Reject))
        awaitTrue {
            bus.runEvents.any { it.second is RuntimeEvent.RunFailed }
        }
        assertTrue(invocations.isEmpty(), "rejected destructive step must never execute")
    }

    @Test
    fun `TE9-unpreauthorized write challenges and rejection keeps it unexecuted`() = runBlocking {
        registerRecordingCommand("net.connect", SideEffectClass.write)
        permissions.grant("test-plugin-net.connect", "android.permission.CHANGE_NETWORK_STATE")
        val bus = RecordingBus(this@McosRuntimeTriggerTest.bus)
        runtime = buildRuntime(bus)

        runtime.workflowStore().registerSpec(
            "wifi-vpn",
            WorkflowSpec(
                trigger = Trigger.Event(filter = buildJsonObject { put("type", "wifi.connected") }),
                step = WorkflowStep.Command("net.connect"),
            ),
        )
        assertIs<TriggerArmResult.Armed>(runtime.armTrigger("wifi-vpn", preAuthorized = false))

        publishWifiEvent("Office")
        awaitTrue { bus.runEvents.any { it.second is RuntimeEvent.ConfirmationNeeded } }
        val confirmation = bus.runEvents.first { it.second is RuntimeEvent.ConfirmationNeeded }

        assertTrue(
            runtime.respondConfirmation(confirmation.first, "net.connect", ConfirmationDecision.Reject)
        )
        awaitTrue { bus.runEvents.any { it.second is RuntimeEvent.RunFailed } }
        assertTrue(invocations.isEmpty())
    }

    @Test
    fun `TE10-approved destructive step retries and completes`() = runBlocking {
        registerRecordingCommand("fs.wipe", SideEffectClass.destructive)
        permissions.grant("test-plugin-fs.wipe", "mcos:storage")
        val bus = RecordingBus(this@McosRuntimeTriggerTest.bus)
        runtime = buildRuntime(bus)

        runtime.workflowStore().registerSpec(
            "danger",
            WorkflowSpec(
                trigger = Trigger.Event(filter = buildJsonObject { put("type", "wifi.connected") }),
                step = WorkflowStep.Command("fs.wipe"),
            ),
        )
        assertIs<TriggerArmResult.Armed>(runtime.armTrigger("danger", preAuthorized = true))

        publishWifiEvent("Office")
        awaitTrue { bus.runEvents.any { it.second is RuntimeEvent.ConfirmationNeeded } }
        val confirmation = bus.runEvents.first { it.second is RuntimeEvent.ConfirmationNeeded }

        assertTrue(
            runtime.respondConfirmation(confirmation.first, "fs.wipe", ConfirmationDecision.Approve())
        )

        // Approval mints the retry stamp and the step executes once.
        awaitTrue { invocations.isNotEmpty() }
        awaitTrue { bus.runEvents.any { it.second is RuntimeEvent.RunSucceeded } }
        assertEquals(1, invocations.size)
    }
}
