package com.mcos.runtime.api

import com.mcos.runtime.memory.MemoryStore
import com.mcos.runtime.permission.PermissionKernel
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.sdk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/**
 * Tests for the confirmation flow (08-security.md §5): a command whose
 * side-effect class requires confirmation returns CONFIRMATION_REQUIRED; the
 * run suspends, the host answers via [McosRuntime.respondConfirmation], and an
 * approved command is retried with a signed, run-scoped AuthStamp.
 */
class McosRuntimeConfirmationTest {

    private lateinit var runtime: McosRuntime
    private lateinit var registry: CommandRegistry
    private lateinit var permissions: PermissionKernel
    private var observeJob: Job? = null

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        permissions = PermissionKernel()
        runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(permissions)
            .withMemory(MemoryStore())
            .withConfirmationTimeoutMs(5_000)
            .build()
    }

    @AfterTest
    fun tearDown() {
        observeJob?.cancel()
    }

    @Test
    fun `approved write command succeeds after confirmation`() = runBlocking {
        registerCommand("test.write", SideEffectClass.write)

        val handle = runtime.execute(
            ExecuteRequest(Source.CHAT, Payload.DslText("test.write(x=\"1\")"))
        )
        val events = observe(this, handle.runId)

        val needed = awaitEvent(events) { it is RuntimeEvent.ConfirmationNeeded }
        assertNotNull(needed, "should emit ConfirmationNeeded for a write command")
        val confirmation = needed as RuntimeEvent.ConfirmationNeeded
        assertEquals("test.write", confirmation.commandId)
        assertEquals("write", confirmation.sideEffectClass)

        val responded = runtime.respondConfirmation(
            handle.runId, "test.write", ConfirmationDecision.Approve()
        )
        assertTrue(responded, "should answer the pending confirmation request")

        assertTrue(awaitEvent(events) { it is RuntimeEvent.RunSucceeded } != null, "run should succeed after approval")
        assertTrue(events.any { it is RuntimeEvent.StepSucceeded }, "should emit StepSucceeded")
    }

    @Test
    fun `rejected command fails the run`() = runBlocking {
        registerCommand("test.write", SideEffectClass.write)

        val handle = runtime.execute(
            ExecuteRequest(Source.CHAT, Payload.DslText("test.write(x=\"1\")"))
        )
        val events = observe(this, handle.runId)

        assertNotNull(awaitEvent(events) { it is RuntimeEvent.ConfirmationNeeded })
        runtime.respondConfirmation(handle.runId, "test.write", ConfirmationDecision.Reject)

        val failed = awaitEvent(events) { it is RuntimeEvent.RunFailed }
        assertNotNull(failed, "run should fail after rejection")
        assertTrue((failed as RuntimeEvent.RunFailed).error.contains("rejected", ignoreCase = true))
        assertTrue(events.any { it is RuntimeEvent.StepFailed }, "should emit StepFailed")
    }

    @Test
    fun `unanswered confirmation times out and fails the run`() = runBlocking {
        val shortRuntime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(permissions)
            .withMemory(MemoryStore())
            .withConfirmationTimeoutMs(100)
            .build()
        registerCommand("test.write", SideEffectClass.write)

        val handle = shortRuntime.execute(
            ExecuteRequest(Source.CHAT, Payload.DslText("test.write(x=\"1\")"))
        )
        val events = observe(this, handle.runId)

        assertNotNull(awaitEvent(events) { it is RuntimeEvent.ConfirmationNeeded })
        // Do not answer — let the confirmation timeout elapse.
        val failed = awaitEvent(events) { it is RuntimeEvent.RunFailed }
        assertTrue(failed != null, "timeout should reject the run")
    }

    @Test
    fun `multi-command run continues after confirmation`() = runBlocking {
        registerCommand("test.read", SideEffectClass.read)
        registerCommand("test.write", SideEffectClass.write)

        val handle = runtime.execute(
            ExecuteRequest(
                Source.CHAT,
                Payload.DslText(
                    """
                    test.read(a="1")
                    test.write(b="2")
                    test.read(c="3")
                    """.trimIndent()
                )
            )
        )
        val events = observe(this, handle.runId)

        assertNotNull(awaitEvent(events) { it is RuntimeEvent.ConfirmationNeeded })
        runtime.respondConfirmation(handle.runId, "test.write", ConfirmationDecision.Approve())

        assertNotNull(awaitEvent(events) { it is RuntimeEvent.RunSucceeded }, "run should succeed")
        val stepsSucceeded = events.filterIsInstance<RuntimeEvent.StepSucceeded>()
        assertEquals(3, stepsSucceeded.size, "all three steps should run")
    }

    @Test
    fun `respondConfirmation returns false when no request is pending`() = runBlocking {
        registerCommand("test.read", SideEffectClass.read)
        val handle = runtime.execute(
            ExecuteRequest(Source.CHAT, Payload.DslText("test.read(a=\"1\")"))
        )
        val events = observe(this, handle.runId)
        assertNotNull(awaitEvent(events) { it is RuntimeEvent.RunSucceeded })

        val responded = runtime.respondConfirmation(handle.runId, "test.read", ConfirmationDecision.Approve())
        assertFalse(responded, "no pending confirmation should exist for a read command")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun observe(scope: CoroutineScope, runId: String): MutableList<RuntimeEvent> {
        val events = mutableListOf<RuntimeEvent>()
        observeJob?.cancel()
        observeJob = scope.launch { runtime.observe(runId).collect { events.add(it) } }
        return events
    }

    private suspend fun awaitEvent(
        events: MutableList<RuntimeEvent>,
        timeoutMs: Long = 3_000,
        predicate: (RuntimeEvent) -> Boolean,
    ): RuntimeEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            events.firstOrNull { predicate(it) }?.let { return it }
            delay(20)
        }
        return events.firstOrNull { predicate(it) }
    }

    private fun registerCommand(id: String, sideEffectClass: SideEffectClass) {
        val provider = ProviderInfo("TestOrg", "https://example.com")
        val plugin = object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "test-plugin-$id",
                name = "Test Plugin",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Test plugin for $id",
                provider = provider,
                entry = "com.mcos.plugin.test.TestPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = id,
                        version = "1.0",
                        title = id,
                        description = "Test command $id",
                        sideEffectClass = sideEffectClass,
                        inputSchema = JsonObject(emptyMap())
                    )
                )
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(id to EchoCommandHandler(id))
            override suspend fun onLoad(services: HostServices) {
                permissions.grant(manifest.id, "mcos:all")
            }
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }
}
