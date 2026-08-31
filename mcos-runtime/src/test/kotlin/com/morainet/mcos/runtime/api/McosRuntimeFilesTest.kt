package com.morainet.mcos.runtime.api

import com.morainet.mcos.plugin.files.FilesPlugin
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.DirectorySandbox
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SandboxFileService
import com.morainet.mcos.sdk.SideEffectClass
import com.morainet.mcos.security.audit.RunOutcome
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for sandboxed file storage (04-plugin-sdk §6.1) through
 * the full [McosRuntime] stack: DSL text → parser → facade → Executor →
 * per-plugin namespaced sandbox → [DirectorySandbox] bytes on disk.
 *
 * The host injects a [DirectorySandbox] over a temp directory exactly like a
 * production host would (mcos-android points it at filesDir). The real
 * [FilesPlugin] provides the file.* commands, so these tests pin the whole
 * chain the docs promise: writes land under `<root>/<pluginId>/`, a second
 * plugin cannot see the first one's namespace, and a host that provides no
 * sandbox gets an honest UNAVAILABLE failure — never a fake success.
 */
class McosRuntimeFilesTest {

    private lateinit var registry: CommandRegistry
    private lateinit var memory: MemoryStore
    private lateinit var audit: InMemoryAuditLog
    private lateinit var runtime: McosRuntime
    private lateinit var sandboxRoot: Path
    private var observeJob: Job? = null

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        memory = MemoryStore()
        audit = InMemoryAuditLog().also { it.start() }
        sandboxRoot = Files.createTempDirectory("mcos-files-e2e-")
        registry.register(FilesPlugin())

        // The demo-shell shape: an injected Executor whose host services
        // carry the sandbox capability. SecurityConfig.permissive() keeps the
        // FE tests off the confirmation flow (that path has its own suite in
        // McosRuntimeConfirmationTest); the audit log is still wired so the
        // sandboxed write is provably audited.
        runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withMemory(memory)
            .withAuditLog(audit)
            .withExecutor(
                Executor(
                    registry = registry,
                    hostServices = object : HostServices by StubHostServices(memory) {
                        override val sandbox: SandboxFileService = DirectorySandbox(sandboxRoot)
                    },
                    security = SecurityConfig.permissive().copy(auditLog = audit),
                )
            )
            .build()
    }

    @AfterTest
    fun tearDown() {
        observeJob?.cancel()
        runtime.shutdown()
        audit.stop()
        registry.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // FE1: file.write → physical bytes under <root>/<pluginId>/
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `FE1-file_write round-trips through the runtime into the plugin namespace`() = runBlocking<Unit> {
        val handle = runtime.execute(
            ExecuteRequest(Source.CLI, Payload.DslText("""file.write(path="logs/e2e.txt", text="hello sandbox")"""))
        )
        val events = observe(handle.runId)
        assertNotNull(awaitEvent(events) { it is RuntimeEvent.RunSucceeded }, "file.write run should succeed")

        // The whole point of the feature: the DSL path "logs/e2e.txt" must
        // land at <root>/mcos.plugin.files/logs/e2e.txt — the Executor's
        // Stage-4 facade scoped it to the owning plugin's namespace.
        val physical = sandboxRoot.resolve("mcos.plugin.files/logs/e2e.txt")
        assertTrue(Files.exists(physical), "file must exist at $physical")
        assertEquals("hello sandbox", Files.readString(physical))

        // read round-trip through the same chain (observe swaps in the new
        // run's collector, cancelling the previous one)
        val readHandle = runtime.execute(
            ExecuteRequest(Source.CLI, Payload.DslText("""file.read(path="logs/e2e.txt")"""))
        )
        val readEvents = observe(readHandle.runId)
        assertNotNull(awaitEvent(readEvents) { it is RuntimeEvent.RunSucceeded }, "file.read run should succeed")

        audit.flush()
        assertTrue(
            audit.getRuns().any { it.commandId == "file.write" && it.outcome == RunOutcome.OK },
            "the sandboxed write must land in the audit log",
        )
        stopObserving()
    }

    // ═══════════════════════════════════════════════════════════════
    // FE2: two plugins, one root, zero visibility
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `FE2-a second plugin cannot read the files plugin's namespace`() = runBlocking<Unit> {
        val handle = runtime.execute(
            ExecuteRequest(Source.CLI, Payload.DslText("""file.write(path="shared.txt", text="mine")"""))
        )
        assertNotNull(awaitEvent(observe(handle.runId)) { it is RuntimeEvent.RunSucceeded })
        assertTrue(Files.exists(sandboxRoot.resolve("mcos.plugin.files/shared.txt")))

        // A distinct plugin reading the SAME plugin-relative path through
        // ctx.services must see its own (empty) namespace, not the files
        // plugin's bytes.
        var invoked = false
        var seen: ByteArray? = ByteArray(0) // non-null sentinel: handler must overwrite
        registerPlugin("test.spy32", "spy.read") { ctx ->
            invoked = true
            seen = ctx.services.sandbox?.read("shared.txt")
            CommandResult.Ok(JsonPrimitive("done"))
        }
        val spyHandle = runtime.execute(ExecuteRequest(Source.CLI, Payload.DslText("""spy.read(x="1")""")))
        assertNotNull(awaitEvent(observe(spyHandle.runId)) { it is RuntimeEvent.RunSucceeded }, "spy.read should run")

        assertTrue(invoked, "spy handler must have been invoked")
        assertNull(seen, "spy must see null — its namespace is empty, not the files plugin's")
        assertFalse(Files.exists(sandboxRoot.resolve("test.spy32")), "spy namespace directory must not even exist")
        stopObserving()
    }

    // ═══════════════════════════════════════════════════════════════
    // FE3: no sandbox on the host → honest UNAVAILABLE, no fake success
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `FE3-file commands report UNAVAILABLE when the host provides no sandbox`() = runBlocking<Unit> {
        // Default-built runtime: its host services carry no sandbox, so the
        // optional capability surfaces honestly (04 §6.7). The plugin's
        // media-store permissions are granted up front so authorization
        // reaches the handler — the point here is the sandbox capability's
        // own degradation, not the permission gate. file.stat is a
        // read-class command, which keeps the default (non-permissive)
        // posture out of the confirmation flow.
        val kernel = DefaultPermissionKernel()
        kernel.grant("mcos.plugin.files", "android.permission.READ_MEDIA_IMAGES")
        kernel.grant("mcos.plugin.files", "android.permission.READ_EXTERNAL_STORAGE")
        val bareRuntime = McosRuntime.Builder()
            .withRegistry(registry)
            .withMemory(MemoryStore())
            .withPermissionKernel(kernel)
            .build()
        try {
            val handle = bareRuntime.execute(
                ExecuteRequest(Source.CLI, Payload.DslText("""file.stat(path="anything.txt")"""))
            )
            val failed = awaitEvent(observe(handle.runId, bareRuntime)) { it is RuntimeEvent.RunFailed }
            assertNotNull(failed, "file.stat must fail without a host sandbox")
            assertTrue(
                (failed as RuntimeEvent.RunFailed).error.contains("Sandbox storage is not available"),
                "failure must be the honest UNAVAILABLE message, got: ${failed.error}",
            )
        } finally {
            stopObserving()
            bareRuntime.shutdown()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FE4: file.delete removes the physical namespaced file
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `FE4-file_delete removes the physical file from the namespace`() = runBlocking<Unit> {
        val write = runtime.execute(
            ExecuteRequest(Source.CLI, Payload.DslText("""file.write(path="tmp/scratch.txt", text="bye")"""))
        )
        assertNotNull(awaitEvent(observe(write.runId)) { it is RuntimeEvent.RunSucceeded })
        val physical = sandboxRoot.resolve("mcos.plugin.files/tmp/scratch.txt")
        assertTrue(Files.exists(physical))

        val del = runtime.execute(
            ExecuteRequest(Source.CLI, Payload.DslText("""file.delete(path="tmp/scratch.txt")"""))
        )
        assertNotNull(awaitEvent(observe(del.runId)) { it is RuntimeEvent.RunSucceeded })
        assertFalse(Files.exists(physical), "delete must remove the physical file")
        stopObserving()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /** Registers a one-command plugin whose handler body is [block]. */
    private fun registerPlugin(
        id: String,
        commandId: String,
        block: suspend (ctx: ExecutionContext) -> CommandResult,
    ) {
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = id, name = id, version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Test plugin for $id",
                provider = ProviderInfo("TestOrg", "https://example.com"),
                entry = "com.morainet.mcos.plugin.test.TestPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = commandId,
                        version = "1.0",
                        title = commandId,
                        description = "Test command $commandId",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = JsonObject(emptyMap()),
                    )
                ),
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(
                commandId to object : CommandHandler {
                    override suspend fun invoke(ctx: ExecutionContext): CommandResult = block(ctx)
                },
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }

    /**
     * Collects the run's event stream into a list. The collector is a child
     * of the test's runBlocking scope; the underlying SharedFlow never
     * completes, so the caller MUST [stopObserving] before the test body
     * returns (runBlocking would otherwise wait forever on the child job).
     */
    private fun CoroutineScope.observe(
        runId: String,
        rt: McosRuntime = runtime,
    ): MutableList<RuntimeEvent> {
        val events = CopyOnWriteArrayList<RuntimeEvent>()
        observeJob?.cancel()
        observeJob = launch { rt.observe(runId).collect { events.add(it) } }
        return events
    }

    /** Cancels (and joins) the current observer so runBlocking can return. */
    private suspend fun stopObserving() {
        observeJob?.cancel()
        observeJob?.join()
    }

    private suspend fun awaitEvent(
        events: List<RuntimeEvent>,
        timeoutMs: Long = 5_000,
        predicate: (RuntimeEvent) -> Boolean,
    ): RuntimeEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            events.firstOrNull { predicate(it) }?.let { return it }
            delay(20)
        }
        return events.firstOrNull { predicate(it) }
    }
}
