package com.morainet.mcos.android

import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import com.morainet.mcos.security.audit.InMemoryAuditLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedRuntimeTest {
    private val services = StubHostServices(MemoryStore())

    @Test fun injectedServicesReachHandlersThroughTheDefaultExecutor() = runBlocking {
        var observedClock: Long? = null
        val custom = object : HostServices by services {
            override val clock = object : Clock by services.clock {
                override fun monotonicMs() = 123L
            }
        }
        val plugin = TestPlugin("one", mutableListOf(), handler = { ctx ->
            observedClock = ctx.services.clock.monotonicMs()
        })
        val audit = InMemoryAuditLog().apply { start() }
        val host = EmbeddedRuntime.create(custom, listOf(plugin), audit)
        try {
            val handle = host.runtime.execute(ExecuteRequest(Source.API, Payload.DslText("test.read()")))
            val events = mutableListOf<RuntimeEvent>()
            withTimeout(5_000) { host.runtime.observe(handle.runId).collect { events += it } }
            assertEquals(123L, observedClock)
            assertTrue(events.any { it is RuntimeEvent.RunSucceeded })
        } finally {
            host.runtime.shutdown()
            audit.stop()
        }
    }

    @Test fun conflictingCatalogDoesNotStartAnyPlugin() = runBlocking {
        val calls = mutableListOf<String>()
        val audit = InMemoryAuditLog().apply { start() }
        try {
            val failure = runCatching {
                EmbeddedRuntime.create(services, listOf(TestPlugin("one", calls), TestPlugin("two", calls)), audit)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(calls.isEmpty())
        } finally { audit.stop() }
    }

    @Test fun startupFailureUnloadsInReverseOrder() = runBlocking {
        val calls = mutableListOf<String>()
        val audit = InMemoryAuditLog().apply { start() }
        try {
            val failure = runCatching {
                EmbeddedRuntime.create(services, listOf(
                    TestPlugin("one", calls), TestPlugin("two", calls, failLoad = true, command = "test.other"),
                ), audit)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(listOf("load:one", "load:two", "unload:two", "unload:one"), calls)
        } finally { audit.stop() }
    }

    private class TestPlugin(
        private val id: String,
        private val calls: MutableList<String>,
        private val failLoad: Boolean = false,
        private val command: String = "test.read",
        private val handler: (ExecutionContext) -> Unit = {},
    ) : McosPlugin {
        override val manifest = PluginManifest(
            id, id, "1.0.0", "0.0.6", "test", ProviderInfo("test", "https://example.com"), "test.Plugin",
            commands = listOf(CommandManifestEntry(command, "1.0.0", command, "test", SideEffectClass.read)),
        )
        override suspend fun onLoad(services: HostServices) {
            calls += "load:$id"
            check(!failLoad) { "startup failed" }
        }
        override suspend fun onUnload() { calls += "unload:$id" }
        override fun handlers() = mapOf(command to object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                handler(ctx)
                return CommandResult.Ok(JsonPrimitive("ok"))
            }
        })
    }
}
