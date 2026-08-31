package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.EventEnvelope
import com.morainet.mcos.runtime.core.events.EventFilter
import com.morainet.mcos.runtime.core.events.EventSubscription
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PermissionEntry
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * Unit tests for [ConfirmationCoordinator] — the extracted confirmation flow
 * (08-security.md §5). This is the area where the CI suite historically hung
 * when five packages ran together; these tests exercise the coordinator in
 * isolation so it no longer depends on the full facade to be covered.
 */
class ConfirmationCoordinatorTest {

    /** Records every published run event; ignores the system-event surface. */
    private class RecordingBus : EventBus {
        val runEvents = CopyOnWriteArrayList<Pair<String, RuntimeEvent>>()

        override fun publish(runId: String, event: RuntimeEvent) {
            runEvents += runId to event
        }

        override fun observe(runId: String): Flow<RuntimeEvent> =
            flow { runEvents.filter { it.first == runId }.forEach { emit(it.second) } }

        override fun subscribe(
            filter: EventFilter,
            handler: suspend (EventEnvelope) -> Unit,
        ): EventSubscription = EventSubscription(-1)

        override fun unsubscribe(subscription: EventSubscription) {}
        override fun publishEvent(envelope: EventEnvelope) {}
    }

    private val registry = CommandRegistry().apply {
        register(object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "test-plugin",
                name = "Test Plugin",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "confirmation test plugin",
                provider = ProviderInfo("TestOrg", "https://example.com"),
                entry = "com.morainet.mcos.plugin.test.TestPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = "test.write",
                        version = "1.0",
                        title = "test.write",
                        description = "network command requiring confirmation",
                        sideEffectClass = SideEffectClass.network,
                        permissions = listOf(PermissionEntry("mcos", "network.fetch")),
                        inputSchema = JsonObject(emptyMap()),
                    )
                ),
            )

            override fun handlers(): Map<String, CommandHandler> =
                mapOf(
                    "test.write" to object : CommandHandler {
                        override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                            CommandResult.Ok(JsonPrimitive("ok"))
                    }
                )

            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        })
    }

    private fun confirmationError() = CommandResult.Err(
        code = "CONFIRMATION_REQUIRED",
        message = "needs user confirmation",
        details = JsonObject(mapOf("sideEffectClass" to JsonPrimitive("network"))),
    )

    private fun command() = Command("test.write", JsonObject(emptyMap()))

    // ─── request / respond round-trips ──────────────────────────────────

    @Test
    fun `C1-requestConfirmation publishes ConfirmationNeeded then times out into Reject`() = runBlocking {
        val bus = RecordingBus()
        val coordinator = ConfirmationCoordinator(bus, HmacAuthStampSigner(), registry, timeoutMs = 100)

        val decision = coordinator.requestConfirmation("run-1", 0, command(), confirmationError())

        // Unanswered ⇒ the timeout is treated as a rejection (§6.3).
        assertIs<ConfirmationDecision.Reject>(decision)
        val event = bus.runEvents.singleOrNull()?.second
        val needed = assertIs<RuntimeEvent.ConfirmationNeeded>(event)
        assertEquals("run-1", needed.runId)
        assertEquals("test.write", needed.commandId)
        assertEquals("network", needed.sideEffectClass)
        assertEquals("needs user confirmation", needed.reason)
    }

    @Test
    fun `C2-approve resolves the suspended request`() = runBlocking {
        val coordinator = ConfirmationCoordinator(RecordingBus(), HmacAuthStampSigner(), registry, 5_000)

        val pending = async { coordinator.requestConfirmation("run-2", 0, command(), confirmationError()) }

        // Poll until the request is registered, then approve it. respond()
        // returns false while nothing is pending for that key.
        withTimeout(2_000) {
            while (!coordinator.respond("run-2", "test.write", ConfirmationDecision.Approve())) {
                delay(10)
            }
        }

        assertIs<ConfirmationDecision.Approve>(pending.await())
        Unit
    }

    @Test
    fun `C3-respond returns false for unknown or already-answered requests`() = runBlocking {
        val coordinator = ConfirmationCoordinator(RecordingBus(), HmacAuthStampSigner(), registry, 5_000)

        // Unknown run/command: nothing is pending.
        assertFalse(coordinator.respond("nope", "test.write", ConfirmationDecision.Reject))

        // Already answered: complete one request, let it finish, respond again.
        val pending = async { coordinator.requestConfirmation("run-3", 0, command(), confirmationError()) }
        withTimeout(2_000) {
            while (!coordinator.respond("run-3", "test.write", ConfirmationDecision.Reject)) {
                delay(10)
            }
        }
        pending.await()

        assertFalse(coordinator.respond("run-3", "test.write", ConfirmationDecision.Reject))
    }

    // ─── retry-stamp minting ────────────────────────────────────────────

    @Test
    fun `C4-minted stamp covers descriptor permissions and implicit scopes with a valid signature`() {
        val signer = HmacAuthStampSigner()
        val coordinator = ConfirmationCoordinator(RecordingBus(), signer, registry, 5_000)

        val stamp = coordinator.mintAuthStamp("run-4", command())

        assertEquals("run-4", stamp.runId)
        assertEquals("test.write", stamp.commandId)
        assertEquals("test-plugin", stamp.pluginId)
        // Explicit descriptor permissions plus the implicit network scope.
        assertEquals(setOf("network.fetch", "network.*"), stamp.grantsUsed)
        // Run-scoped, short-lived (08-security.md §5.2).
        assertEquals(30_000L, stamp.expiresAt - stamp.issuedAt)
        // The retry stamp must pass the signer's own verification.
        assertTrue(signer.verify(stamp))
    }
}
