package com.mcos.runtime.executor

import com.mcos.runtime.error.McosErrorCode
import com.mcos.runtime.permission.PermissionKernel
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Conformance tests for Executor v0.1.
 * Matches [03-runtime.md §9].
 */
class ExecutorTest {

    private lateinit var registry: CommandRegistry
    private lateinit var executor: Executor
    private val services = StubHostServices()

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
        executor = Executor(registry, services)
    }

    @AfterTest
    fun tearDown() {
        registry.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // E1-E3: Basic execution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E1-execute simple command returns Ok`() = runBlocking {
        val plugin = createPlugin("test.basic", "1.0.0", mapOf(
            "cmd.ok" to EchoHandler("done")
        ))
        registry.register(plugin)

        val result = executor.execute("cmd.ok")
        assertIs<CommandResult.Ok>(result)
        assertEquals("done", result.value.jsonPrimitive.content)
        assertTrue(result.artifacts.isEmpty())
    }

    @Test
    fun `E2-args are passed through to handler`() = runBlocking {
        val plugin = createPlugin("test.args", "1.0.0", mapOf(
            "echo.args" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    val name = ctx.args.jsonObject["name"]!!.jsonPrimitive.content
                    return CommandResult.Ok(JsonPrimitive("Hello, $name"))
                }
            }
        ))
        registry.register(plugin)

        val result = executor.execute(
            commandId = "echo.args",
            args = buildJsonObject { put("name", JsonPrimitive("MCOS")) }
        )

        assertIs<CommandResult.Ok>(result)
        assertEquals("Hello, MCOS", result.value.jsonPrimitive.content)
    }

    @Test
    fun `E3-unknown command returns UNKNOWN_COMMAND`() = runBlocking {
        val result = executor.execute("not.registered")

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.UNKNOWN_COMMAND.name, result.code)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("not.registered"))
    }

    // ═══════════════════════════════════════════════════════════════
    // E4-E5: Error handling and exception mapping
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E4-handler throws generic exception maps to PLUGIN_ERROR`() = runBlocking {
        val plugin = createPlugin("test.err", "1.0.0", mapOf(
            "cmd.bomb" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    throw RuntimeException("something went wrong internally")
                }
            }
        ))
        registry.register(plugin)

        val result = executor.execute("cmd.bomb")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PLUGIN_ERROR.name, result.code)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("cmd.bomb"))
        // Stack trace should NOT be in the message
        assertFalse(result.message.contains(".kt:"))
    }

    @Test
    fun `E5-handler throws McosException maps directly`() = runBlocking {
        val details = buildJsonObject { put("hint", JsonPrimitive("camera busy")) }
        val plugin = createPlugin("test.mcoserr", "1.0.0", mapOf(
            "cmd.mcos" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    throw McosException(
                        code = McosErrorCode.UNAVAILABLE.name,
                        message = "Camera hardware is busy",
                        retryable = true,
                        details = details
                    )
                }
            }
        ))
        registry.register(plugin)

        val result = executor.execute("cmd.mcos")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.UNAVAILABLE.name, result.code)
        assertEquals("Camera hardware is busy", result.message)
        assertTrue(result.retryable)
        assertEquals("camera busy", result.details["hint"]?.jsonPrimitive?.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // E6-E7: Timeout and cancellation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E6-handler exceeds timeout returns TIMEOUT`() = runBlocking {
        val plugin = createPluginWithTimeout(
            id = "test.timeout",
            version = "1.0.0",
            commandId = "cmd.slow",
            timeoutMs = 100, // very short timeout
            handler = object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    delay(5000) // way longer than timeout
                    return CommandResult.Ok(JsonPrimitive("never"))
                }
            }
        )
        registry.register(plugin)

        val result = executor.execute("cmd.slow")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.TIMEOUT.name, result.code)
        assertTrue(result.retryable)
        assertTrue(result.message.contains("timed out"))
    }

    @Test
    fun `E7-cancellation returns CANCELLED`() = runBlocking {
        val plugin = createPlugin("test.cancel", "1.0.0", mapOf(
            "cmd.wait" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    delay(Long.MAX_VALUE) // wait forever
                    return CommandResult.Ok(JsonPrimitive("never"))
                }
            }
        ))
        registry.register(plugin)

        var result: CommandResult? = null
        val job = launch {
            result = executor.execute("cmd.wait")
        }

        delay(50) // let it start
        job.cancel()
        job.join()

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.CANCELLED.name, (result as CommandResult.Err).code)
    }

    // ═══════════════════════════════════════════════════════════════
    // E8: Sequence execution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E8-executeSequence runs all steps in order`() = runBlocking {
        val executed = mutableListOf<String>()
        val plugin = createPlugin("test.seq", "1.0.0", mapOf(
            "step.a" to TrackingHandler("step.a", executed, "A"),
            "step.b" to TrackingHandler("step.b", executed, "B"),
            "step.c" to TrackingHandler("step.c", executed, "C")
        ))
        registry.register(plugin)

        val results = executor.executeSequence(
            listOf(
                Command("step.a"),
                Command("step.b"),
                Command("step.c")
            )
        )

        assertEquals(3, results.size)
        assertEquals(listOf("A", "B", "C"), results.map { (it as CommandResult.Ok).value.jsonPrimitive.content })
        assertEquals(listOf("step.a", "step.b", "step.c"), executed)
    }

    @Test
    fun `E9-executeSequence stops on first error`() = runBlocking {
        val executed = mutableListOf<String>()
        val plugin = createPlugin("test.seqerr", "1.0.0", mapOf(
            "step.a" to TrackingHandler("step.a", executed, "A"),
            "step.b" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    executed.add("step.b")
                    throw RuntimeException("BOOM")
                }
            },
            "step.c" to TrackingHandler("step.c", executed, "C")
        ))
        registry.register(plugin)

        val results = executor.executeSequence(
            listOf(
                Command("step.a"),
                Command("step.b"),
                Command("step.c")
            )
        )

        assertEquals(2, results.size) // stops after step.b error
        assertIs<CommandResult.Ok>(results[0])
        assertIs<CommandResult.Err>(results[1])
        assertEquals(listOf("step.a", "step.b"), executed)
        // step.c should NOT have been executed
        assertFalse(executed.contains("step.c"))
    }

    // ═══════════════════════════════════════════════════════════════
    // E10-E11: Execution context integrity
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E10-ExecutionContext has correct commandId and args`() = runBlocking {
        var capturedCtx: ExecutionContext? = null
        val plugin = createPlugin("test.ctx", "1.0.0", mapOf(
            "ctx.check" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    capturedCtx = ctx
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        ))
        registry.register(plugin)

        val args = buildJsonObject { put("key", JsonPrimitive("value")) }
        executor.execute("ctx.check", args)

        assertEquals("ctx.check", capturedCtx!!.commandId)
        assertEquals("value", capturedCtx!!.args.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `E11-ExecutionContext deadline is set from descriptor timeout`() = runBlocking {
        var capturedCtx: ExecutionContext? = null
        val plugin = createPluginWithTimeout(
            id = "test.deadline",
            version = "1.0.0",
            commandId = "deadline.check",
            timeoutMs = 30000,
            handler = object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    capturedCtx = ctx
                    return CommandResult.Ok(JsonPrimitive("ok"))
                }
            }
        )
        registry.register(plugin)

        val beforeCall = System.currentTimeMillis()
        executor.execute("deadline.check")
        val afterCall = System.currentTimeMillis()

        assertNotNull(capturedCtx!!.deadline)
        // deadline should be roughly beforeCall + 30000
        val expectedDeadline = beforeCall + 30000
        assertTrue(
            capturedCtx!!.deadline!! >= expectedDeadline - 100,
            "deadline should be ≥ roughly ${expectedDeadline - 100}, was ${capturedCtx!!.deadline}"
        )
        assertTrue(
            capturedCtx!!.deadline!! <= afterCall + 30000,
            "deadline should be ≤ $afterCall + 30000, was ${capturedCtx!!.deadline}"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // E12-E13: Ok with artifacts
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E12-handler returns Ok with artifacts`() = runBlocking {
        val plugin = createPlugin("test.art", "1.0.0", mapOf(
            "art.produce" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    return CommandResult.Ok(
                        value = JsonPrimitive("created"),
                        artifacts = listOf(
                            Artifact("image", "file:///photo.jpg", "image/jpeg"),
                            Artifact("thumbnail", "file:///thumb.jpg", "image/jpeg")
                        )
                    )
                }
            }
        ))
        registry.register(plugin)

        val result = executor.execute("art.produce")
        assertIs<CommandResult.Ok>(result)
        assertEquals(2, result.artifacts.size)
        assertEquals("image", result.artifacts[0].type)
        assertEquals("file:///photo.jpg", result.artifacts[0].uri)
    }

    @Test
    fun `E13-handler executes with alias resolution`() = runBlocking {
        val plugin = createPluginWithAlias(
            id = "test.alias.exec",
            version = "1.0.0",
            commandId = "sys.notify",
            alias = "notify",
            handler = EchoHandler("notified")
        )
        registry.register(plugin)

        val result = executor.execute("notify")
        assertIs<CommandResult.Ok>(result)
        assertEquals("notified", result.value.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // E14-E15: Schema validation integration (Stage 5)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E14-schema violation returns SCHEMA_VIOLATION`() = runBlocking {
        val inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray {
                add(JsonPrimitive("url"))
            })
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("minLength", JsonPrimitive(1))
                })
            })
        }
        val plugin = createPluginWithSchema(
            id = "test.schema",
            version = "1.0.0",
            commandId = "net.fetch",
            inputSchema = inputSchema,
            handler = EchoHandler("ok")
        )
        registry.register(plugin)

        // Execute with missing required field "url"
        val result = executor.execute("net.fetch", buildJsonObject {
            put("other", JsonPrimitive("value"))
        })

        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.SCHEMA_VIOLATION.name, result.code)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("Schema validation failed"))
        // Details should contain errors array
        assertTrue(result.details.containsKey("errors"))
    }

    @Test
    fun `E15-valid args against schema execute successfully`() = runBlocking {
        val inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("url")) })
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("minLength", JsonPrimitive(1))
                })
            })
        }
        val plugin = createPluginWithSchema(
            id = "test.schema2",
            version = "1.0.0",
            commandId = "net.fetch2",
            inputSchema = inputSchema,
            handler = EchoHandler("fetched")
        )
        registry.register(plugin)

        val result = executor.execute("net.fetch2", buildJsonObject {
            put("url", JsonPrimitive("https://example.com"))
        })

        assertIs<CommandResult.Ok>(result)
        assertEquals("fetched", result.value.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // E16-E17: PermissionKernel integration (Stage 6)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E16-PERMISSION_DENIED when required permission missing`() = runBlocking {
        val permKernel = PermissionKernel()
        val executorWithPerm = Executor(registry, services, permKernel)

        val plugin = createPlugin("test.perm", "1.0.0", mapOf(
            "camera.capture" to EchoHandler("photo")
        ))
        // Plugin has no explicit permissions, but we need to test denial
        // Register a descriptor with a required permission
        registry.register(plugin)

        // Re-register with a perm-requiring descriptor by using manifest entries
        val permPlugin = createPluginWithPerms(
            id = "test.perm2",
            version = "1.0.0",
            commandId = "camera.capture",
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA")),
            handler = EchoHandler("photo")
        )
        registry.register(permPlugin)

        val result = executorWithPerm.execute("camera.capture")
        assertIs<CommandResult.Err>(result)
        assertEquals(McosErrorCode.PERMISSION_DENIED.name, result.code)
        assertTrue(result.retryable)
    }

    @Test
    fun `E17-permission granted allows execution`() = runBlocking {
        val permKernel = PermissionKernel()
        // Grant required permission
        permKernel.grant("test.perm3", "android.permission.CAMERA")

        val executorWithPerm = Executor(registry, services, permKernel)

        val plugin = createPluginWithPerms(
            id = "test.perm3",
            version = "1.0.0",
            commandId = "camera.capture",
            permissions = listOf(PermissionEntry("android", "android.permission.CAMERA")),
            handler = EchoHandler("photo taken")
        )
        registry.register(plugin)

        val result = executorWithPerm.execute("camera.capture")
        // AlwaysConfirm is off, but sideEffectClass=read → authorization passes
        assertIs<CommandResult.Ok>(result)
        assertEquals("photo taken", result.value.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun createPlugin(
        id: String,
        version: String,
        commands: Map<String, CommandHandler>
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.mcos.plugin.test.TestPlugin"
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = commands
    }

    private fun createPluginWithTimeout(
        id: String,
        version: String,
        commandId: String,
        timeoutMs: Long,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with timeout",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Timed command",
                    sideEffectClass = SideEffectClass.read,
                    timeoutMs = timeoutMs
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    private fun createPluginWithAlias(
        id: String,
        version: String,
        commandId: String,
        alias: String,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with alias",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Command with alias",
                    sideEffectClass = SideEffectClass.read,
                    aliases = listOf(alias)
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    private fun createPluginWithSchema(
        id: String,
        version: String,
        commandId: String,
        inputSchema: JsonObject,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with schema",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Command with input schema",
                    sideEffectClass = SideEffectClass.read
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    private fun createPluginWithPerms(
        id: String,
        version: String,
        commandId: String,
        permissions: List<PermissionEntry>,
        handler: CommandHandler
    ): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id, name = id, version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin with permissions",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.mcos.plugin.test.TestPlugin",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = version,
                    title = commandId,
                    description = "Command with permissions",
                    sideEffectClass = SideEffectClass.read,
                    permissions = permissions
                )
            )
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(commandId to handler)
    }

    class EchoHandler(private val response: String) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult =
            CommandResult.Ok(JsonPrimitive(response))
    }

    class TrackingHandler(
        private val name: String,
        private val tracker: MutableList<String>,
        private val response: String
    ) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            tracker.add(name)
            return CommandResult.Ok(JsonPrimitive(response))
        }
    }

    /**
     * Minimal HostServices stub for JVM testing.
     */
    class StubHostServices : HostServices {
        override val files = object : FileService {
            override suspend fun list(uri: String, mimeType: String?): List<FileEntry> = emptyList()
        }
        override val net = object : NetService {
            override suspend fun request(method: String, url: String, body: String?, headers: Map<String, String>): NetResponse =
                NetResponse(200, "{}")
        }
        override val ui = object : UiService {
            override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? = null
        }
        override val secureStore = object : SecureStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        override val clock = object : Clock {
            override fun nowMs(): Long = System.currentTimeMillis()
        }
        override val json = object : JsonService {
            override fun parse(json: String): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(json)
        }
        override val memory = object : MemoryFacade {
            override suspend fun get(path: String): JsonElement? = null
            override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound
        }
    }
}
