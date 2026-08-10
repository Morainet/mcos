package com.mcos.plugin.system

import com.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Conformance tests for SystemPlugin.
 * Matches [04-plugin-sdk.md §17].
 */
class SystemPluginTest {

    private lateinit var plugin: SystemPlugin
    private lateinit var stubServices: HostServices

    @BeforeTest
    fun setUp() = runBlocking {
        plugin = SystemPlugin()
        stubServices = StubSystemHostServices()
        plugin.onLoad(stubServices)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        plugin.onUnload()
    }

    // ═══════════════════════════════════════════════════════════════
    // S1-S3: Manifest validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S1-manifest has correct plugin id`() {
        assertEquals("mcos.plugin.system", plugin.manifest.id)
        assertEquals("System Plugin", plugin.manifest.name)
        assertEquals("1.0.0", plugin.manifest.version)
    }

    @Test
    fun `S2-manifest declares all 6 system commands`() {
        val commands = plugin.manifest.commands.map { it.id }.toSet()
        assertEquals(6, commands.size)
        assertTrue(commands.contains("sys.notify"))
        assertTrue(commands.contains("sys.share"))
        assertTrue(commands.contains("sys.clipboard"))
        assertTrue(commands.contains("sys.openUrl"))
        assertTrue(commands.contains("sys.intent.start"))
        assertTrue(commands.contains("sys.vibrate"))
    }

    @Test
    fun `S3-manifest requires VIBRATE and POST_NOTIFICATIONS`() {
        val perms = plugin.manifest.permissions.map { it.name }.toSet()
        assertTrue(perms.any { it.contains("VIBRATE") })
        assertTrue(perms.any { it.contains("POST_NOTIFICATIONS") })
    }

    // ═══════════════════════════════════════════════════════════════
    // S4-S6: Handlers registration
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S4-handlers returns all 6 command handlers`() {
        val handlers = plugin.handlers()
        assertEquals(6, handlers.size)
        assertTrue(handlers.containsKey("sys.notify"))
        assertTrue(handlers.containsKey("sys.share"))
        assertTrue(handlers.containsKey("sys.clipboard"))
        assertTrue(handlers.containsKey("sys.openUrl"))
        assertTrue(handlers.containsKey("sys.intent.start"))
        assertTrue(handlers.containsKey("sys.vibrate"))
    }

    @Test
    fun `S5-each handler is a unique instance`() {
        val handlers = plugin.handlers()
        val instances = handlers.values.toSet()
        assertEquals(6, instances.size, "each handler should be a distinct instance")
    }

    // ═══════════════════════════════════════════════════════════════
    // S7-S9: sys.notify
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S7-sys_notify succeeds with valid args`() = runBlocking {
        val handler = plugin.handlers()["sys.notify"]!!
        val args = buildJsonObject {
            put("title", JsonPrimitive("Hello"))
            put("text", JsonPrimitive("World"))
        }
        val ctx = execCtx("sys.notify", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("notified", value["status"]!!.jsonPrimitive.content)
        assertEquals("Hello", value["title"]!!.jsonPrimitive.content)
        assertEquals("World", value["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S8-sys_notify fails with missing title`() = runBlocking {
        val handler = plugin.handlers()["sys.notify"]!!
        val args = buildJsonObject { put("text", JsonPrimitive("World")) }
        val ctx = execCtx("sys.notify", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
    }

    @Test
    fun `S9-sys_notify fails with missing text`() = runBlocking {
        val handler = plugin.handlers()["sys.notify"]!!
        val args = buildJsonObject { put("title", JsonPrimitive("Hello")) }
        val ctx = execCtx("sys.notify", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // S10-S12: sys.share
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S10-sys_share succeeds with text`() = runBlocking {
        val handler = plugin.handlers()["sys.share"]!!
        val args = buildJsonObject { put("text", JsonPrimitive("Share this")) }
        val ctx = execCtx("sys.share", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("shared", value["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S11-sys_share succeeds with uri`() = runBlocking {
        val handler = plugin.handlers()["sys.share"]!!
        val args = buildJsonObject { put("uri", JsonPrimitive("content://test/file")) }
        val ctx = execCtx("sys.share", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
    }

    @Test
    fun `S12-sys_share fails with neither text nor uri`() = runBlocking {
        val handler = plugin.handlers()["sys.share"]!!
        val ctx = execCtx("sys.share", JsonObject(emptyMap()))

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // S13-S15: sys.clipboard
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S13-sys_clipboard write mode`() = runBlocking {
        val handler = plugin.handlers()["sys.clipboard"]!!
        val args = buildJsonObject { put("text", JsonPrimitive("Copy me")) }
        val ctx = execCtx("sys.clipboard", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("write", value["operation"]!!.jsonPrimitive.content)
        assertEquals("Copy me", value["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S14-sys_clipboard read mode`() = runBlocking {
        val handler = plugin.handlers()["sys.clipboard"]!!
        val ctx = execCtx("sys.clipboard", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("read", value["operation"]!!.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // S16-S18: sys.openUrl
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S16-sys_openUrl succeeds with valid url`() = runBlocking {
        val handler = plugin.handlers()["sys.openUrl"]!!
        val args = buildJsonObject { put("url", JsonPrimitive("https://example.com")) }
        val ctx = execCtx("sys.openUrl", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("opened", value["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S17-sys_openUrl fails with empty url`() = runBlocking {
        val handler = plugin.handlers()["sys.openUrl"]!!
        val args = buildJsonObject { put("url", JsonPrimitive("   ")) }
        val ctx = execCtx("sys.openUrl", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
    }

    @Test
    fun `S18-sys_openUrl fails with missing url`() = runBlocking {
        val handler = plugin.handlers()["sys.openUrl"]!!
        val ctx = execCtx("sys.openUrl", JsonObject(emptyMap()))

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // S19-S20: sys.vibrate
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S19-sys_vibrate succeeds with default duration`() = runBlocking {
        val handler = plugin.handlers()["sys.vibrate"]!!
        val ctx = execCtx("sys.vibrate", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("vibrated", value["status"]!!.jsonPrimitive.content)
        assertEquals(500, value["durationMs"]!!.jsonPrimitive.int)
    }

    @Test
    fun `S20-sys_vibrate succeeds with custom duration`() = runBlocking {
        val handler = plugin.handlers()["sys.vibrate"]!!
        val args = buildJsonObject { put("duration", JsonPrimitive(2000)) }
        val ctx = execCtx("sys.vibrate", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(2000, value["durationMs"]!!.jsonPrimitive.int)
    }

    // ═══════════════════════════════════════════════════════════════
    // S21-S22: sys.intent.start
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S21-sys_intent_start succeeds with action`() = runBlocking {
        val handler = plugin.handlers()["sys.intent.start"]!!
        val args = buildJsonObject { put("action", JsonPrimitive("android.intent.action.VIEW")) }
        val ctx = execCtx("sys.intent.start", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("started", value["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S22-sys_intent_start fails with missing action`() = runBlocking {
        val handler = plugin.handlers()["sys.intent.start"]!!
        val ctx = execCtx("sys.intent.start", JsonObject(emptyMap()))

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // S23-S24: Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S23-load and unload lifecycle`() = runBlocking {
        val p = SystemPlugin()
        p.onLoad(stubServices)
        p.onUnload()
        p.onUnload() // idempotent
    }

    @Test
    fun `S24-thread hint is main`() {
        assertEquals("main", plugin.manifest.threadHint)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun execCtx(commandId: String, args: JsonObject): ExecutionContext {
        return ExecutionContext(
            runId = "test-run-1",
            commandId = commandId,
            args = args,
            services = stubServices,
        )
    }
}

/**
 * Minimal HostServices stub for system plugin tests.
 */
class StubSystemHostServices : HostServices {
    override val files: FileService get() = error("FileService not available")
    override val net: NetService get() = error("NetService not available")
    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? = null
        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound
    }

    override val ui: UiService = object : UiService {
        override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? {
            // Simulate user accepting the share/open intent
            return mapOf("status" to "completed")
        }
    }

    override val secureStore: SecureStore get() = error("SecureStore not available")
    override val clock: Clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
    override val json: JsonService get() = error("JsonService not available")
}
