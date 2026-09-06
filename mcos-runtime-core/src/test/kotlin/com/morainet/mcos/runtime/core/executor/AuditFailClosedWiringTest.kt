package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.workflow.WorkflowEngine
import com.morainet.mcos.runtime.core.workflow.WorkflowOutcome
import com.morainet.mcos.runtime.core.workflow.WorkflowStep
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.FileAuditLog
import com.morainet.mcos.security.audit.InMemoryAuditLog
import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.FileService
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.JsonService
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.SideEffectClass
import com.morainet.mcos.sdk.UiService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fail-closed Stage-10 audit wiring ([03-runtime.md §13.3]) through the
 * [Executor] (per-command record) and [WorkflowEngine] (aggregate record):
 * with `auditFailClosed` enabled a run whose audit record cannot be durably
 * written fails with `INTERNAL` instead of silently dropping the record,
 * while the default best-effort posture leaves the outcome unchanged.
 */
class AuditFailClosedWiringTest {

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun newExecutor(
        auditLog: AuditLog = InMemoryAuditLog(),
        auditFailClosed: Boolean = false,
    ): Pair<Executor, CommandRegistry> {
        val registry = CommandRegistry()
        val executor = Executor(
            registry,
            NoServices,
            security = SecurityConfig.permissive().copy(
                auditLog = auditLog,
                auditFailClosed = auditFailClosed,
            ),
        )
        return executor to registry
    }

    private fun registerOk(registry: CommandRegistry, commandId: String) {
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = "audit-test-$commandId",
                name = "Audit Fail-Closed Test",
                version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "Returns Ok without touching any service",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.test.AuditFailClosedTest",
                commands = listOf(
                    CommandManifestEntry(
                        id = commandId,
                        version = "1.0.0",
                        title = commandId,
                        description = "Returns Ok",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = JsonObject(emptyMap()),
                    )
                ),
            )
            override fun handlers(): Map<String, CommandHandler> = mapOf(
                commandId to object : CommandHandler {
                    override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                        CommandResult.Ok(JsonPrimitive("ok"))
                }
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }

    private fun tempFile(): File =
        File.createTempFile("mcos-fc", ".jsonl").apply { delete() }

    /** An audit file whose parent is an existing FILE — it can never be opened. */
    private fun unwritableLog(): Pair<File, File> {
        val dir = File.createTempFile("mcos-fc-blocker", ".tmp").apply { delete(); mkdirs() }
        val blocker = File(dir, "blocker").apply { createNewFile() }
        return dir to File(blocker, "audit.jsonl")
    }

    // ─── Executor: per-command Stage-10 record ─────────────────────────

    @Test
    fun `fail closed turns a successful run into INTERNAL when the sink cannot persist`() = runBlocking {
        val (dir, file) = unwritableLog()
        try {
            val log = FileAuditLog(file).also { it.start() }
            val (executor, registry) = newExecutor(log, auditFailClosed = true)
            registerOk(registry, "fc.ok")

            val r = executor.execute("fc.ok")
            assertIs<CommandResult.Err>(r)
            assertEquals(McosErrorCode.INTERNAL.name, r.code)
            assertTrue(r.message!!.contains("auditFailClosed"), "message: ${r.message}")
            log.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `fail closed keeps a success when the sink persists`() = runBlocking {
        val file = tempFile()
        try {
            val log = FileAuditLog(file).also { it.start() }
            val (executor, registry) = newExecutor(log, auditFailClosed = true)
            registerOk(registry, "fc.persisted")

            val r = executor.execute("fc.persisted")
            assertIs<CommandResult.Ok>(r)
            log.flush()
            assertTrue(log.count() >= 1)
            log.stop()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `default best-effort audit keeps the success even when the sink cannot persist`() = runBlocking {
        val (dir, file) = unwritableLog()
        try {
            val log = FileAuditLog(file).also { it.start() }
            val (executor, registry) = newExecutor(log, auditFailClosed = false)
            registerOk(registry, "fc.best-effort")

            val r = executor.execute("fc.best-effort")
            assertIs<CommandResult.Ok>(r)
            log.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    // ─── WorkflowEngine: aggregate Stage-10 record ─────────────────────

    @Test
    fun `workflow with fail closed audit fails when the aggregate record cannot be written`() = runBlocking {
        // The Executor (healthy sink) runs the step fine; the engine's own
        // aggregate record goes to an unwritable sink under auditFailClosed.
        val (executor, registry) = newExecutor(InMemoryAuditLog(), auditFailClosed = false)
        registerOk(registry, "wf.echo")
        val (dir, file) = unwritableLog()
        try {
            val brokenLog = FileAuditLog(file).also { it.start() }
            val engine = WorkflowEngine(executor, brokenLog, auditFailClosed = true)

            val result = engine.execute(WorkflowStep.Command(commandId = "wf.echo"))
            assertEquals(WorkflowOutcome.FAILED, result.outcome)
            assertTrue(
                result.steps.any { it.code == McosErrorCode.INTERNAL.name },
                "expected an INTERNAL step in: ${result.steps}",
            )
            brokenLog.stop()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `workflow with default audit reports COMPLETED despite an unwritable sink`() = runBlocking {
        val (executor, registry) = newExecutor(InMemoryAuditLog(), auditFailClosed = false)
        registerOk(registry, "wf.echo-default")
        val (dir, file) = unwritableLog()
        try {
            val brokenLog = FileAuditLog(file).also { it.start() }
            val engine = WorkflowEngine(executor, brokenLog, auditFailClosed = false)

            val result = engine.execute(WorkflowStep.Command(commandId = "wf.echo-default"))
            assertEquals(WorkflowOutcome.COMPLETED, result.outcome)
            brokenLog.stop()
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** HostServices whose members throw only if a handler touches them. */
private object NoServices : HostServices {
    override val files: FileService get() = error("FileService not available in test")
    override val net: NetService get() = error("NetService not available in test")
    override val ui: UiService get() = error("UiService not available in test")
    override val secureStore: SecureStore get() = error("SecureStore not available in test")
    override val clock: Clock get() = error("Clock not available in test")
    override val json: JsonService get() = error("JsonService not available in test")
    override val memory: MemoryFacade get() = error("MemoryFacade not available in test")
}
