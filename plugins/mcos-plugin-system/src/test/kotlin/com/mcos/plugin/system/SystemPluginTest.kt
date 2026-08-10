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
    fun `S2-manifest declares all 12 system commands`() {
        val commands = plugin.manifest.commands.map { it.id }.toSet()
        assertEquals(12, commands.size)
        assertTrue(commands.contains("sys.notify"))
        assertTrue(commands.contains("sys.share"))
        assertTrue(commands.contains("sys.clipboard"))
        assertTrue(commands.contains("sys.openUrl"))
        assertTrue(commands.contains("sys.intent.start"))
        assertTrue(commands.contains("sys.vibrate"))
        assertTrue(commands.contains("sys.device.battery"))
        assertTrue(commands.contains("sys.device.wifi"))
        assertTrue(commands.contains("sys.device.screen"))
        assertTrue(commands.contains("sys.device.volume"))
        assertTrue(commands.contains("sys.device.location"))
        assertTrue(commands.contains("sys.device.brightness"))
    }

    @Test
    fun `S3-manifest declares required system permissions`() {
        val perms = plugin.manifest.permissions.map { it.name }.toSet()
        assertTrue(perms.any { it.contains("VIBRATE") })
        assertTrue(perms.any { it.contains("POST_NOTIFICATIONS") })
        assertTrue(perms.any { it.contains("ACCESS_FINE_LOCATION") })
        assertTrue(perms.any { it.contains("WRITE_SETTINGS") })
    }

    // ═══════════════════════════════════════════════════════════════
    // S4-S6: Handlers registration
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S4-handlers returns all 12 command handlers`() {
        val handlers = plugin.handlers()
        assertEquals(12, handlers.size)
        assertTrue(handlers.containsKey("sys.notify"))
        assertTrue(handlers.containsKey("sys.share"))
        assertTrue(handlers.containsKey("sys.clipboard"))
        assertTrue(handlers.containsKey("sys.openUrl"))
        assertTrue(handlers.containsKey("sys.intent.start"))
        assertTrue(handlers.containsKey("sys.vibrate"))
        assertTrue(handlers.containsKey("sys.device.battery"))
        assertTrue(handlers.containsKey("sys.device.wifi"))
        assertTrue(handlers.containsKey("sys.device.screen"))
        assertTrue(handlers.containsKey("sys.device.volume"))
        assertTrue(handlers.containsKey("sys.device.location"))
        assertTrue(handlers.containsKey("sys.device.brightness"))
    }

    @Test
    fun `S5-each handler is a unique instance`() {
        val handlers = plugin.handlers()
        val instances = handlers.values.toSet()
        assertEquals(12, instances.size, "each handler should be a distinct instance")
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

    // ═══════════════════════════════════════════════════════════════
    // S25-S26: sys.device.battery
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S25-sys_device_battery returns battery info`() = runBlocking {
        val handler = plugin.handlers()["sys.device.battery"]!!
        val ctx = execCtx("sys.device.battery", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertTrue(value.containsKey("level"))
        assertTrue(value.containsKey("charging"))
        assertTrue(value.containsKey("temperature"))
        assertEquals(true, value["charging"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `S26-sys_device_battery has read sideEffectClass`() {
        val cmd = plugin.manifest.commands.find { it.id == "sys.device.battery" }!!
        assertEquals(SideEffectClass.read, cmd.sideEffectClass)
    }

    // ═══════════════════════════════════════════════════════════════
    // S27-S28: sys.device.wifi
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S27-sys_device_wifi returns wifi info`() = runBlocking {
        val handler = plugin.handlers()["sys.device.wifi"]!!
        val ctx = execCtx("sys.device.wifi", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertTrue(value.containsKey("connected"))
        assertTrue(value.containsKey("ssid"))
        assertTrue(value.containsKey("signalStrength"))
        assertEquals("MCOS-Network", value["ssid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S28-sys_device_wifi has read sideEffectClass`() {
        val cmd = plugin.manifest.commands.find { it.id == "sys.device.wifi" }!!
        assertEquals(SideEffectClass.read, cmd.sideEffectClass)
    }

    // ═══════════════════════════════════════════════════════════════
    // S29-S30: sys.device.screen
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S29-sys_device_screen returns display metrics`() = runBlocking {
        val handler = plugin.handlers()["sys.device.screen"]!!
        val ctx = execCtx("sys.device.screen", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(1080, value["width"]!!.jsonPrimitive.int)
        assertEquals(2400, value["height"]!!.jsonPrimitive.int)
        assertEquals("portrait", value["orientation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S30-sys_device_screen has read sideEffectClass`() {
        val cmd = plugin.manifest.commands.find { it.id == "sys.device.screen" }!!
        assertEquals(SideEffectClass.read, cmd.sideEffectClass)
    }

    // ═══════════════════════════════════════════════════════════════
    // S31-S32: sys.device.volume
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S31-sys_device_volume returns volume levels`() = runBlocking {
        val handler = plugin.handlers()["sys.device.volume"]!!
        val ctx = execCtx("sys.device.volume", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertTrue(value.containsKey("media"))
        assertTrue(value.containsKey("ring"))
        assertTrue(value.containsKey("alarm"))
        assertEquals(10, value["media"]!!.jsonPrimitive.int)
    }

    @Test
    fun `S32-sys_device_volume has read sideEffectClass`() {
        val cmd = plugin.manifest.commands.find { it.id == "sys.device.volume" }!!
        assertEquals(SideEffectClass.read, cmd.sideEffectClass)
    }

    // ═══════════════════════════════════════════════════════════════
    // S33-S34: sys.device.location
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S33-sys_device_location returns location data`() = runBlocking {
        val handler = plugin.handlers()["sys.device.location"]!!
        val ctx = execCtx("sys.device.location", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertTrue(value.containsKey("latitude"))
        assertTrue(value.containsKey("longitude"))
        assertTrue(value.containsKey("accuracy"))
        assertTrue(value.containsKey("provider"))
        assertEquals("gps", value["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S34-sys_device_location manifest declares fine location permission`() {
        val cmd = plugin.manifest.commands.find { it.id == "sys.device.location" }!!
        assertTrue(cmd.permissions.isNotEmpty(), "should declare permissions")
        assertTrue(cmd.permissions.any { it.name.contains("ACCESS_FINE_LOCATION") })
    }

    // ═══════════════════════════════════════════════════════════════
    // S35-S36: sys.device.brightness
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S35-sys_device_brightness query mode returns current level`() = runBlocking {
        val handler = plugin.handlers()["sys.device.brightness"]!!
        val ctx = execCtx("sys.device.brightness", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("query", value["mode"]!!.jsonPrimitive.content)
        assertEquals(128, value["level"]!!.jsonPrimitive.int)
    }

    @Test
    fun `S36-sys_device_brightness set mode updates level`() = runBlocking {
        val handler = plugin.handlers()["sys.device.brightness"]!!
        val args = buildJsonObject { put("level", JsonPrimitive(200)) }
        val ctx = execCtx("sys.device.brightness", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("set", value["mode"]!!.jsonPrimitive.content)
        assertEquals(200, value["level"]!!.jsonPrimitive.int)
    }

    @Test
    fun `S37-sys_device_brightness set mode rejects out-of-range level`() = runBlocking {
        val handler = plugin.handlers()["sys.device.brightness"]!!
        val args = buildJsonObject { put("level", JsonPrimitive(300)) }
        val ctx = execCtx("sys.device.brightness", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
    }

    @Test
    fun `S38-sys_device_brightness manifest declares write settings permission`() {
        val cmd = plugin.manifest.commands.find { it.id == "sys.device.brightness" }!!
        assertTrue(cmd.permissions.isNotEmpty(), "should declare permissions")
        assertTrue(cmd.permissions.any { it.name.contains("WRITE_SETTINGS") })
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
