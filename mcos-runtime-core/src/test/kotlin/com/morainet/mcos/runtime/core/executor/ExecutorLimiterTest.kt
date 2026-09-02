package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.runtime.core.scheduler.InvocationLimiter
import com.morainet.mcos.runtime.core.scheduler.SchedulerConfig
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.sdk.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/**
 * Integration tests: [InvocationLimiter] wired into the Executor's Stage-8
 * pre-dispatch (03-runtime.md §8.2) — destructive commands serialize globally,
 * per-plugin caps hold, and a `null` limiter keeps the historical behavior.
 */
class ExecutorLimiterTest {

    private lateinit var registry: CommandRegistry
    private val services = ExecutorTest.StubHostServices()

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
    }

    @AfterTest
    fun tearDown() {
        registry.clear()
    }

    /** A handler that reports entry, tracks peak concurrency, then parks on [gate]. */
    private class GatedHandler(
        private val inFlight: AtomicInteger,
        private val maxInFlight: AtomicInteger,
        private val entered: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { cur, new -> maxOf(cur, new) }
            entered.complete(Unit)
            try {
                gate.await()
            } finally {
                inFlight.decrementAndGet()
            }
            return CommandResult.Ok(JsonPrimitive("ok"))
        }
    }

    private fun plugin(id: String, commandId: String, sideEffectClass: SideEffectClass, handler: CommandHandler): McosPlugin =
        object : McosPlugin {
            override val manifest = PluginManifest(
                id = id, name = id, version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "Limiter test plugin",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.plugin.test.TestPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = commandId,
                        version = "1.0.0",
                        title = commandId,
                        description = "Gated command",
                        sideEffectClass = sideEffectClass,
                    )
                )
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
            override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
        }

    @Test
    fun `EL1-destructive commands serialize globally across plugins`() = runBlocking<Unit> {
        val executor = Executor(registry, services, SecurityConfig.permissive(), invocationLimiter = InvocationLimiter())
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        registry.register(plugin("plugin.a", "a.destroy", SideEffectClass.destructive, GatedHandler(inFlight, maxInFlight, first, gate)))
        registry.register(plugin("plugin.b", "b.destroy", SideEffectClass.destructive, GatedHandler(inFlight, maxInFlight, second, gate)))

        val jobA = async { executor.execute("a.destroy") }
        withTimeout(5_000) { first.await() }
        val jobB = async { executor.execute("b.destroy") }
        assertNull(withTimeoutOrNull(200) { second.await() }) // §8.2: destructive cap 1

        gate.complete(Unit)
        withTimeout(5_000) { awaitAll(jobA, jobB) }
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun `EL2-per-plugin cap admits two and queues the third`() = runBlocking<Unit> {
        val executor = Executor(registry, services, SecurityConfig.permissive(), invocationLimiter = InvocationLimiter())
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val two = CompletableDeferred<Unit>()
        val third = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        registry.register(
            plugin(
                "plugin.a", "a.read",
                SideEffectClass.read,
                object : CommandHandler {
                    var n = 0
                    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                        val now = inFlight.incrementAndGet()
                        maxInFlight.accumulateAndGet(now) { cur, new -> maxOf(cur, new) }
                        if (++n == 2) two.complete(Unit)
                        if (n == 3) third.complete(Unit)
                        try {
                            gate.await()
                        } finally {
                            inFlight.decrementAndGet()
                        }
                        return CommandResult.Ok(JsonPrimitive("ok"))
                    }
                }
            )
        )

        val jobs = (1..3).map { async { executor.execute("a.read") } }
        withTimeout(5_000) { two.await() }
        assertNull(withTimeoutOrNull(200) { third.await() }) // §8.2: per-plugin cap 2

        gate.complete(Unit)
        withTimeout(5_000) { jobs.awaitAll() }
        assertEquals(2, maxInFlight.get())
    }

    @Test
    fun `EL3-null limiter preserves the historical uncapped behavior`() = runBlocking<Unit> {
        val executor = Executor(registry, services, SecurityConfig.permissive()) // limiter omitted
        val inFlight = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        registry.register(plugin("plugin.a", "a.destroy", SideEffectClass.destructive, GatedHandler(inFlight, AtomicInteger(0), CompletableDeferred(), gate)))
        registry.register(plugin("plugin.b", "b.destroy", SideEffectClass.destructive, GatedHandler(inFlight, AtomicInteger(0), CompletableDeferred(), gate)))

        // Both are destructive on different plugins — under the limiter they
        // would serialize; with no limiter both dispatch concurrently.
        val jobs = listOf("a.destroy", "b.destroy").map { id -> async { executor.execute(id) } }
        withTimeout(1_000) { while (inFlight.get() < 2) delay(10) } // no cap → both in flight

        gate.complete(Unit)
        withTimeout(5_000) { jobs.awaitAll() }
    }
}
