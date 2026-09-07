package com.morainet.mcos.runtime.core.config

import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.EventEnvelope
import com.morainet.mcos.runtime.core.events.EventFilter
import com.morainet.mcos.runtime.core.events.EventSubscription
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.scheduler.RunScheduler
import com.morainet.mcos.runtime.core.scheduler.SchedulerConfig
import com.morainet.mcos.runtime.core.workflow.EventTriggerManager
import com.morainet.mcos.runtime.core.workflow.ScheduleTriggerManager
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import com.morainet.mcos.security.RateLimits
import com.morainet.mcos.security.RedactionLevel
import com.morainet.mcos.security.TokenBucketRateLimiter
import com.morainet.mcos.security.UserPolicy
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CF1-CF12 — [RuntimeConfigManager] apply / merge / fan-out / ConfigChanged
 * audit (03-runtime.md §19). Uses real-but-tiny scheduler + trigger managers
 * and a recording audit sink.
 */
class RuntimeConfigManagerTest {

    private class RecordingAudit : AuditLog {
        val records = CopyOnWriteArrayList<RunRecord>()
        override fun append(record: RunRecord) { records.add(record) }
        override suspend fun flush() {}
        override fun start() {}
        override fun stop() {}
        override fun getRuns() = records.toList()
        override fun getRun(runId: String) = records.find { it.runId == runId }
        override fun getRecent(limit: Int) = records.takeLast(limit)
        override fun count() = records.size
        override fun export() = ""
        override fun clear() { records.clear() }
    }

    private class NoopBus : EventBus {
        override fun publish(runId: String, event: RuntimeEvent) {}
        override fun observe(runId: String): Flow<RuntimeEvent> = emptyFlow()
        override fun subscribe(filter: EventFilter, handler: suspend (EventEnvelope) -> Unit): EventSubscription {
            val n = counter.incrementAndGet()
            return EventSubscription(n)
        }
        override fun unsubscribe(subscription: EventSubscription) {}
        override fun publishEvent(envelope: EventEnvelope) {}
        private val counter = AtomicLong(0)
    }

    private val registry = CommandRegistry()
    private val scheduler = RunScheduler(SchedulerConfig())
    private val limiter = TokenBucketRateLimiter()
    private val eventTriggers = EventTriggerManager(NoopBus(), MemoryStore())
    private val scheduleTriggers = ScheduleTriggerManager(pollMs = null)
    private val audit = RecordingAudit()

    @AfterTest fun tearDown() { registry.clear(); scheduler.shutdown() }

    private fun manager(
        mdm: () -> RuntimeConfig? = { null },
        user: () -> RuntimeConfig? = { null },
        initial: RuntimeConfig = RuntimeConfig(),
    ) = RuntimeConfigManager(
        registry, scheduler, limiter, eventTriggers, scheduleTriggers, audit,
        mdm = mdm, user = user, initial = initial, clock = { 42L },
    )

    // ─── apply fan-out ──────────────────────────────────────────────────────

    @Test
    fun `CF1-apply retunes the scheduler, limiter and trigger managers`() {
        val mgr = manager()
        val applied = mgr.apply(
            RuntimeConfig(
                maxParallel = 8,
                scheduler = SchedulerConfig(maxConcurrentInvokes = 8),
                rateLimits = RateLimits(120, 9, 33),
            )
        )
        assertEquals(8, applied.maxParallel)
        assertEquals(8, scheduler.currentConfig().maxConcurrentInvokes)
        // The limiter took the new rates — reconfigure returns the CURRENT
        // (just-applied) snapshot as its "previous", proving apply pushed them.
        val prevLimits = limiter.reconfigure(120, 9)
        assertEquals(120, prevLimits.maxInvokesPerMinute)
        assertEquals(9, prevLimits.maxDestructivePerHour)
        // Trigger managers took the background-fire budget (reconfigure returns
        // the value we just set, proving it landed).
        val prevEvent = eventTriggers.reconfigure(com.morainet.mcos.runtime.core.workflow.TriggerLimits(33))
        assertEquals(33, prevEvent.maxBackgroundFiresPerHour)
    }

    // ─── ConfigChanged audit ─────────────────────────────────────────────────

    @Test
    fun `CF2-apply always appends a ConfigChanged record with the diff`() {
        val mgr = manager()
        mgr.apply(RuntimeConfig(auditRedaction = RedactionLevel.STRICT))
        val rec = audit.records.single { it.commandId == "config.changed" }
        assertEquals("config", rec.runId)
        assertEquals("SYSTEM", rec.source)
        assertEquals(42L, rec.timestamp)
        assertNotNull(rec.ir)
        assertTrue(rec.ir!!.contains("auditRedaction"))
        assertTrue(rec.ir!!.contains("STRICT"))
    }

    @Test
    fun `CF3-an unchanged apply still emits a ConfigChanged record`() {
        val mgr = manager()
        mgr.apply(RuntimeConfig())
        assertEquals(1, audit.records.count { it.commandId == "config.changed" })
    }

    // ─── read-through suppliers ──────────────────────────────────────────────

    @Test
    fun `CF4-suppliers reflect the applied config immediately`() {
        val mgr = manager()
        assertEquals(RedactionLevel.DEFAULT, mgr.redactionLevel())
        mgr.apply(RuntimeConfig(auditRedaction = RedactionLevel.STRICT, strictSchemaOutput = true))
        assertEquals(RedactionLevel.STRICT, mgr.redactionLevel())
        assertTrue(mgr.strictSchemaOutput())
    }

    @Test
    fun `CF5-apply returns the applied config and current reflects it`() {
        val mgr = manager()
        val applied = mgr.apply(RuntimeConfig(defaultTimeoutMs = 12_345))
        assertEquals(12_345, applied.defaultTimeoutMs)
        assertEquals(12_345, mgr.current().defaultTimeoutMs)
    }

    // ─── merge precedence ────────────────────────────────────────────────────

    @Test
    fun `CF6-MDM wins the non-security fields over user and submitted`() {
        val mgr = manager(
            mdm = { RuntimeConfig(defaultTimeoutMs = 5_000, maxParallel = 2, scheduler = SchedulerConfig(maxConcurrentInvokes = 2)) },
            user = { RuntimeConfig(defaultTimeoutMs = 9_000) },
        )
        val applied = mgr.apply(RuntimeConfig(defaultTimeoutMs = 60_000))
        assertEquals(5_000, applied.defaultTimeoutMs)
        assertEquals(2, applied.maxParallel)
    }

    @Test
    fun `CF7-auditRedaction takes the maximum across sources`() {
        val mgr = manager(user = { RuntimeConfig(auditRedaction = RedactionLevel.STRICT) })
        val applied = mgr.apply(RuntimeConfig(auditRedaction = RedactionLevel.OFF))
        assertEquals(RedactionLevel.STRICT, applied.auditRedaction)
    }

    @Test
    fun `CF8-eventTriggersEnabled ANDs across sources`() {
        val mgr = manager(user = { RuntimeConfig(eventTriggersEnabled = false) })
        val applied = mgr.apply(RuntimeConfig(eventTriggersEnabled = true))
        assertFalse(applied.eventTriggersEnabled)
    }

    @Test
    fun `CF9-allow-lists union across sources`() {
        registry.register(plugin("p", "camera.scan", "files.read"))
        val mgr = manager(user = { RuntimeConfig(enterpriseAllowlist = listOf("files.read")) })
        val applied = mgr.apply(RuntimeConfig(enterpriseAllowlist = listOf("camera.scan")))
        assertNotNull(applied.enterpriseAllowlist)
        assertTrue("camera.scan" in applied.enterpriseAllowlist!!)
        assertTrue("files.read" in applied.enterpriseAllowlist!!)
    }

    @Test
    fun `CF10-userPolicy flags OR-combine across sources`() {
        val mgr = manager(user = { RuntimeConfig(userPolicy = UserPolicy(disableSessionGrants = true)) })
        val applied = mgr.apply(RuntimeConfig(userPolicy = UserPolicy(confirmEveryNetwork = true)))
        assertTrue(applied.userPolicy.confirmEveryNetwork)
        assertTrue(applied.userPolicy.disableSessionGrants)
    }

    // ─── allow-list deferral (§19.1) ─────────────────────────────────────────

    @Test
    fun `CF11-an allow-list referencing no registered command is deferred`() {
        // Nothing registered → the allow-list matches nothing → deferred.
        val mgr = manager()
        val applied = mgr.apply(RuntimeConfig(enterpriseAllowlist = listOf("ghost.*")))
        assertNull(applied.enterpriseAllowlist) // prior (null) kept
        val rec = audit.records.single { it.commandId == "config.changed" }
        assertTrue(rec.ir!!.contains("\"allowlistDeferred\":true"))
    }

    @Test
    fun `CF12-an allow-list matching a registered command is applied`() {
        registry.register(plugin("p", "camera.scan"))
        val mgr = manager()
        val applied = mgr.apply(RuntimeConfig(enterpriseAllowlist = listOf("camera.*")))
        assertEquals(listOf("camera.*"), applied.enterpriseAllowlist)
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun plugin(pluginId: String, vararg commandIds: String): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = pluginId, name = pluginId, version = "1.0.0",
            minRuntimeVersion = "0.1.0", description = "test",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = commandIds.map {
                CommandManifestEntry(id = it, version = "1.0.0", title = it, description = it, sideEffectClass = SideEffectClass.read)
            },
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = commandIds.associateWith {
            object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext) = CommandResult.Ok(JsonPrimitive("ok"))
            }
        }
    }
}
