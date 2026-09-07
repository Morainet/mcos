package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * EC1-EC8 — the §19 Executor read-through seams: `defaultTimeout`,
 * `strictSchemaOutput`, and the Stage-3 `commandAllowlist` visibility gate
 * (03-runtime.md §19).
 */
class ExecutorRuntimeConfigTest {

    private lateinit var registry: CommandRegistry
    private val services = ExecutorTest.StubHostServices()

    @BeforeTest fun setUp() { registry = CommandRegistry() }
    @AfterTest fun tearDown() { registry.clear() }

    // ─── defaultTimeout coalesce ────────────────────────────────────────────

    @Test
    fun `EC1-a default-timeout descriptor uses the global defaultTimeout`() = runBlocking {
        registry.register(
            plugin("p.slow", cmd("slow.op", timeoutMs = null, cls = SideEffectClass.read) {
                delay(200); CommandResult.Ok(JsonPrimitive("done"))
            })
        )
        // Global default lowered to 50ms → the un-set-timeout command times out.
        val exec = Executor(registry, services, SecurityConfig.permissive().copy(
            defaultTimeout = { 1_000L }, // clamped up to the 1000 minimum
        ))
        // A 1000ms budget easily covers the 200ms handler.
        val ok = exec.execute("slow.op")
        assertIs<CommandResult.Ok>(ok)
        Unit
    }

    @Test
    fun `EC2-an explicit descriptor timeout wins over the global default`() = runBlocking {
        // Descriptor pins 1000ms (the floor — anything below coerces up to it);
        // the handler runs 3000ms, so the descriptor budget bites while the
        // generous global default would not.
        registry.register(
            plugin("p.fast", cmd("fast.op", timeoutMs = 1000, cls = SideEffectClass.read) {
                delay(3000); CommandResult.Ok(JsonPrimitive("done"))
            })
        )
        val exec = Executor(registry, services, SecurityConfig.permissive().copy(
            defaultTimeout = { 600_000L },
        ))
        val res = exec.execute("fast.op")
        assertIs<CommandResult.Err>(res)
        assertEquals(McosErrorCode.TIMEOUT.name, res.code)
    }

    // ─── strictSchemaOutput ──────────────────────────────────────────────────

    @Test
    fun `EC3-strictSchemaOutput off leaves an output-schema violation as Ok`() = runBlocking {
        registry.register(
            plugin("p.out", cmdOut("out.bad", outputSchema = objectSchema()) {
                CommandResult.Ok(JsonPrimitive("not-an-object"))
            })
        )
        val exec = Executor(registry, services, SecurityConfig.permissive()) // flag defaults off
        assertIs<CommandResult.Ok>(exec.execute("out.bad"))
        Unit
    }

    @Test
    fun `EC4-strictSchemaOutput on turns an output-schema violation into SCHEMA_VIOLATION`() = runBlocking {
        registry.register(
            plugin("p.out2", cmdOut("out.bad2", outputSchema = objectSchema()) {
                CommandResult.Ok(JsonPrimitive("not-an-object"))
            })
        )
        val exec = Executor(registry, services, SecurityConfig.permissive().copy(
            strictSchemaOutput = { true },
        ))
        val res = exec.execute("out.bad2")
        assertIs<CommandResult.Err>(res)
        assertEquals(McosErrorCode.SCHEMA_VIOLATION.name, res.code)
    }

    @Test
    fun `EC5-strictSchemaOutput on passes a conforming object result`() = runBlocking {
        registry.register(
            plugin("p.out3", cmdOut("out.good", outputSchema = objectSchema()) {
                CommandResult.Ok(buildJsonObject { put("value", JsonPrimitive(1)) })
            })
        )
        val exec = Executor(registry, services, SecurityConfig.permissive().copy(
            strictSchemaOutput = { true },
        ))
        assertIs<CommandResult.Ok>(exec.execute("out.good"))
        Unit
    }

    // ─── Stage-3 command allow-list ──────────────────────────────────────────

    @Test
    fun `EC6-a null allow-list hides nothing`() = runBlocking {
        registry.register(plugin("p.a", cmd("camera.scan", cls = SideEffectClass.read) {
            CommandResult.Ok(JsonPrimitive("ok"))
        }))
        val exec = Executor(registry, services, SecurityConfig.permissive()) // null allow-list
        assertIs<CommandResult.Ok>(exec.execute("camera.scan"))
        Unit
    }

    @Test
    fun `EC7-a non-matching command is UNKNOWN_COMMAND under the allow-list`() = runBlocking {
        registry.register(plugin("p.b", cmd("vpn.connect", cls = SideEffectClass.read) {
            CommandResult.Ok(JsonPrimitive("ok"))
        }))
        val exec = Executor(registry, services, SecurityConfig.permissive().copy(
            commandAllowlist = { listOf("camera.*", "files.read") },
        ))
        val res = exec.execute("vpn.connect")
        assertIs<CommandResult.Err>(res)
        assertEquals(McosErrorCode.UNKNOWN_COMMAND.name, res.code)
    }

    @Test
    fun `EC8-the allow-list is read live so a re-apply is immediate`() = runBlocking {
        registry.register(plugin("p.c", cmd("files.read", cls = SideEffectClass.read) {
            CommandResult.Ok(JsonPrimitive("ok"))
        }))
        var allow: List<String>? = listOf("camera.*")
        val exec = Executor(registry, services, SecurityConfig.permissive().copy(
            commandAllowlist = { allow },
        ))
        // Not on the list yet → hidden.
        assertIs<CommandResult.Err>(exec.execute("files.read"))
        // Widen the list → visible on the next invoke, no rebuild.
        allow = listOf("camera.*", "files.*")
        assertIs<CommandResult.Ok>(exec.execute("files.read"))
        Unit
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun objectSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
    }

    private class Handler(val body: suspend (ExecutionContext) -> CommandResult) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult = body(ctx)
    }

    private fun cmd(
        id: String,
        timeoutMs: Long? = null,
        cls: SideEffectClass = SideEffectClass.read,
        body: suspend (ExecutionContext) -> CommandResult,
    ): Triple<String, CommandManifestEntry, CommandHandler> {
        val entry = CommandManifestEntry(
            id = id,
            version = "1.0.0",
            title = id,
            description = "test $id",
            sideEffectClass = cls,
            timeoutMs = timeoutMs ?: 60000,
        )
        return Triple(id, entry, Handler(body))
    }

    private fun cmdOut(
        id: String,
        outputSchema: JsonObject,
        body: suspend (ExecutionContext) -> CommandResult,
    ): Triple<String, CommandManifestEntry, CommandHandler> {
        val entry = CommandManifestEntry(
            id = id,
            version = "1.0.0",
            title = id,
            description = "test $id",
            sideEffectClass = SideEffectClass.read,
            outputSchema = outputSchema,
        )
        return Triple(id, entry, Handler(body))
    }

    private fun plugin(
        pluginId: String,
        vararg commands: Triple<String, CommandManifestEntry, CommandHandler>,
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = pluginId, name = pluginId, version = "1.0.0",
            minRuntimeVersion = "0.1.0",
            description = "test plugin",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
            commands = commands.map { it.second },
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = commands.associate { it.first to it.third }
    }
}
