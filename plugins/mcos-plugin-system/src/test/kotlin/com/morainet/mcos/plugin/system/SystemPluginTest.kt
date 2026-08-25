package com.morainet.mcos.plugin.system

import com.morainet.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * Conformance tests for SystemPlugin.
 * Matches [04-plugin-sdk.md 17].
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
    fun `S2-manifest declares all 13 system commands`() {
        val commands = plugin.manifest.commands.map { it.id }.toSet()
        assertEquals(13, commands.size)
        assertTrue(commands.contains("sys.notify"))
        assertTrue(commands.contains("sys.share"))
        assertTrue(commands.contains("sys.clipboard"))
        assertTrue(commands.contains("sys.openUrl"))
        assertTrue(commands.contains("sys.intent.start"))
        assertTrue(commands.contains("sys.vibrate"))
        assertTrue(commands.contains("sys.event.emit"))
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
    fun `S4-handlers returns all 13 command handlers`() {
        val handlers = plugin.handlers()
        assertEquals(13, handlers.size)
        assertTrue(handlers.containsKey("sys.notify"))
        assertTrue(handlers.containsKey("sys.share"))
        assertTrue(handlers.containsKey("sys.clipboard"))
        assertTrue(handlers.containsKey("sys.openUrl"))
        assertTrue(handlers.containsKey("sys.intent.start"))
        assertTrue(handlers.containsKey("sys.vibrate"))
        assertTrue(handlers.containsKey("sys.event.emit"))
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
        assertEquals(13, instances.size, "each handler should be a distinct instance")
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
        Unit
    }

    @Test
    fun `S9-sys_notify fails with missing text`() = runBlocking {
        val handler = plugin.handlers()["sys.notify"]!!
        val args = buildJsonObject { put("title", JsonPrimitive("Hello")) }
        val ctx = execCtx("sys.notify", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
        Unit
    }

    @Test
    fun `S9-1-sys_notify fails UNAVAILABLE when host has no notification service`() = runBlocking {
        // P0-F1 regression: a host without a NotificationService must NOT get
        // a fake "notified" success — the handler must surface UNAVAILABLE so
        // callers and the audit trail know the notification was never posted.
        val noNotifyPlugin = SystemPlugin()
        val noNotifyServices = object : HostServices {
            override val files get() = error("x")
            override val net get() = error("x")
            override val ui get() = error("x")
            override val secureStore get() = error("x")
            override val clock = object : Clock { override fun nowMs() = 0L }
            override val json get() = error("x")
            override val memory = object : MemoryFacade {
                override suspend fun get(path: String): JsonElement? = null
                override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound()
            }
            // notifications intentionally left null (default)
        }
        noNotifyPlugin.onLoad(noNotifyServices)

        val handler = noNotifyPlugin.handlers()["sys.notify"]!!
        val args = buildJsonObject {
            put("title", JsonPrimitive("Hi"))
            put("text", JsonPrimitive("Body"))
        }
        val ctx = ExecutionContext(
            runId = "r", commandId = "sys.notify", args = args, services = noNotifyServices,
        )
        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        noNotifyPlugin.onUnload()
        Unit
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
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // S13-S15: sys.clipboard
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S13-sys_clipboard write mode calls the host clipboard`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        val handler = capablePlugin.handlers()["sys.clipboard"]!!
        val args = buildJsonObject { put("text", JsonPrimitive("Copy me")) }
        val ctx = execCtx("sys.clipboard", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("write", value["operation"]!!.jsonPrimitive.content)
        assertEquals("Copy me", value["text"]!!.jsonPrimitive.content)
        assertEquals("Copy me", svc.fakeClipboard.lastSet, "write mode must reach the host clipboard")
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S14-sys_clipboard read mode throws UNAVAILABLE`() = runBlocking {
        val handler = plugin.handlers()["sys.clipboard"]!!
        val ctx = execCtx("sys.clipboard", JsonObject(emptyMap()))

        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
    }

    @Test
    fun `S14-1-sys_clipboard read mode returns text tagged untrusted`() = runBlocking {
        // 08-security.md 11.1: clipboard text is untrusted input (the user may
        // have copied adversarial text) — the read result must carry the tag.
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeClipboard.current = "user-copied text"
        val handler = capablePlugin.handlers()["sys.clipboard"]!!

        val result = handler.invoke(execCtx("sys.clipboard", JsonObject(emptyMap())))

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("read", value["operation"]!!.jsonPrimitive.content)
        assertEquals("user-copied text", value["text"]!!.jsonPrimitive.content)
        assertEquals(true, value["untrusted"]!!.jsonPrimitive.boolean)
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S14-2-sys_clipboard read of empty clipboard throws UNAVAILABLE`() = runBlocking {
        // Empty and unreadable are indistinguishable — never a fabricated Ok.
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeClipboard.current = null
        val handler = capablePlugin.handlers()["sys.clipboard"]!!

        val ex = assertFailsWith<McosException> {
            handler.invoke(execCtx("sys.clipboard", JsonObject(emptyMap())))
        }
        assertEquals("UNAVAILABLE", ex.code)
        capablePlugin.onUnload()
        Unit
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
        Unit
    }

    @Test
    fun `S18-sys_openUrl fails with missing url`() = runBlocking {
        val handler = plugin.handlers()["sys.openUrl"]!!
        val ctx = execCtx("sys.openUrl", JsonObject(emptyMap()))

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // S19-S20: sys.vibrate
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S19-sys_vibrate succeeds with default duration`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        val handler = capablePlugin.handlers()["sys.vibrate"]!!

        val result = handler.invoke(execCtx("sys.vibrate", JsonObject(emptyMap())))

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("vibrated", value["status"]!!.jsonPrimitive.content)
        assertEquals(500, value["durationMs"]!!.jsonPrimitive.int)
        assertEquals(500, svc.fakeHaptics.lastDurationMs, "default duration must reach the host haptics")
        assertEquals(1, svc.fakeHaptics.vibrateCount)
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S19-1-sys_vibrate without haptics capability throws UNAVAILABLE`() = runBlocking {
        val handler = plugin.handlers()["sys.vibrate"]!!

        val ex = assertFailsWith<McosException> {
            handler.invoke(execCtx("sys.vibrate", JsonObject(emptyMap())))
        }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
    }

    @Test
    fun `S20-sys_vibrate succeeds with custom duration`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        val handler = capablePlugin.handlers()["sys.vibrate"]!!
        val args = buildJsonObject { put("duration", JsonPrimitive(2000)) }

        val result = handler.invoke(execCtx("sys.vibrate", args))

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(2000, value["durationMs"]!!.jsonPrimitive.int)
        assertEquals(2000, svc.fakeHaptics.lastDurationMs, "custom duration must reach the host haptics")
        capablePlugin.onUnload()
        Unit
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
        Unit
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
    fun `S25-sys_device_battery throws UNAVAILABLE on this host`() = runBlocking {
        val handler = plugin.handlers()["sys.device.battery"]!!
        val ctx = execCtx("sys.device.battery", JsonObject(emptyMap()))

        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
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
    fun `S27-sys_device_wifi throws UNAVAILABLE on this host`() = runBlocking {
        val handler = plugin.handlers()["sys.device.wifi"]!!
        val ctx = execCtx("sys.device.wifi", JsonObject(emptyMap()))

        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
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
    fun `S29-sys_device_screen throws UNAVAILABLE on this host`() = runBlocking {
        val handler = plugin.handlers()["sys.device.screen"]!!
        val ctx = execCtx("sys.device.screen", JsonObject(emptyMap()))

        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
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
    fun `S31-sys_device_volume throws UNAVAILABLE on this host`() = runBlocking {
        val handler = plugin.handlers()["sys.device.volume"]!!
        val ctx = execCtx("sys.device.volume", JsonObject(emptyMap()))

        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
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
    fun `S33-sys_device_location throws UNAVAILABLE on this host`() = runBlocking {
        val handler = plugin.handlers()["sys.device.location"]!!
        val ctx = execCtx("sys.device.location", JsonObject(emptyMap()))

        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
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
    fun `S35-sys_device_brightness query mode throws UNAVAILABLE`() = runBlocking {
        val handler = plugin.handlers()["sys.device.brightness"]!!
        val ctx = execCtx("sys.device.brightness", JsonObject(emptyMap()))

        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
    }

    @Test
    fun `S36-sys_device_brightness set mode throws UNAVAILABLE after schema check`() = runBlocking {
        val handler = plugin.handlers()["sys.device.brightness"]!!
        val args = buildJsonObject { put("level", JsonPrimitive(200)) }
        val ctx = execCtx("sys.device.brightness", args)

        // Valid level passes schema validation but device capability is unavailable.
        val ex = assertFailsWith<McosException> { handler.invoke(ctx) }
        assertEquals("UNAVAILABLE", ex.code)
        Unit
    }

    @Test
    fun `S37-sys_device_brightness set mode rejects out-of-range level`() = runBlocking {
        val handler = plugin.handlers()["sys.device.brightness"]!!
        val args = buildJsonObject { put("level", JsonPrimitive(300)) }
        val ctx = execCtx("sys.device.brightness", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
        Unit
    }

    @Test
    fun `S38-sys_device_brightness manifest declares write settings permission`() {
        val cmd = plugin.manifest.commands.find { it.id == "sys.device.brightness" }!!
        assertTrue(cmd.permissions.isNotEmpty(), "should declare permissions")
        assertTrue(cmd.permissions.any { it.name.contains("WRITE_SETTINGS") })
    }

    // ═══════════════════════════════════════════════════════════════
    // S39-S46: device queries on a capable host (real capability wiring)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S39-sys_device_battery maps host battery info`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeDeviceInfo.batteryInfo = BatteryInfo(percent = 87, charging = true, temperatureC = 30)
        val handler = capablePlugin.handlers()["sys.device.battery"]!!

        val result = handler.invoke(execCtx("sys.device.battery", JsonObject(emptyMap())))

        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(87, value["percent"]!!.jsonPrimitive.int)
        assertEquals(true, value["charging"]!!.jsonPrimitive.boolean)
        assertEquals(30, value["temperatureC"]!!.jsonPrimitive.int)
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S40-sys_device_wifi maps host wifi info and keeps unknown ssid null`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeDeviceInfo.wifiInfo = WifiInfo(connected = false, ssid = null, strength = null)
        val handler = capablePlugin.handlers()["sys.device.wifi"]!!

        val result = handler.invoke(execCtx("sys.device.wifi", JsonObject(emptyMap())))

        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(false, value["connected"]!!.jsonPrimitive.boolean)
        assertTrue(value["ssid"] is JsonNull, "unknown SSID must surface as null, never a guess")
        assertTrue(value["strength"] is JsonNull)
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S41-sys_device_screen maps host screen info`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeDeviceInfo.screenInfo = ScreenInfo(1080, 2400, densityDpi = 420, rotation = 1, brightness = 128)
        val handler = capablePlugin.handlers()["sys.device.screen"]!!

        val result = handler.invoke(execCtx("sys.device.screen", JsonObject(emptyMap())))

        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(1080, value["widthPx"]!!.jsonPrimitive.int)
        assertEquals(2400, value["heightPx"]!!.jsonPrimitive.int)
        assertEquals(420, value["densityDpi"]!!.jsonPrimitive.int)
        assertEquals(1, value["rotation"]!!.jsonPrimitive.int)
        assertEquals(128, value["brightness"]!!.jsonPrimitive.int)
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S42-sys_device_volume maps host volume info`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeDeviceInfo.volumeInfo = VolumeInfo(musicPercent = 40, ringPercent = 60, alarmPercent = 80)
        val handler = capablePlugin.handlers()["sys.device.volume"]!!

        val result = handler.invoke(execCtx("sys.device.volume", JsonObject(emptyMap())))

        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(40, value["music"]!!.jsonPrimitive.int)
        assertEquals(60, value["ring"]!!.jsonPrimitive.int)
        assertEquals(80, value["alarm"]!!.jsonPrimitive.int)
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S43-sys_device_location maps a real fix`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeDeviceInfo.locationInfo = LocationInfo(22.5431, 114.0579, accuracyM = 25f, timestampMs = 1_700_000_000_000)
        val handler = capablePlugin.handlers()["sys.device.location"]!!

        val result = handler.invoke(execCtx("sys.device.location", JsonObject(emptyMap())))

        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("ok", value["status"]!!.jsonPrimitive.content)
        assertEquals(22.5431, value["lat"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(114.0579, value["lng"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(25f, value["accuracyM"]!!.jsonPrimitive.float, 1e-6f)
        assertEquals(1_700_000_000_000, value["timestampMs"]!!.jsonPrimitive.long)
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S44-sys_device_location without a fix returns no_fix not an error`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeDeviceInfo.locationInfo = null
        val handler = capablePlugin.handlers()["sys.device.location"]!!

        val result = handler.invoke(execCtx("sys.device.location", JsonObject(emptyMap())))

        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("no_fix", value["status"]!!.jsonPrimitive.content)
        assertTrue(value["location"] is JsonNull, "absence is data, never a fabricated coordinate")
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S45-sys_device_brightness query maps level and auto`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        svc.fakeDeviceInfo.brightnessInfo = BrightnessInfo(level = 128, auto = false)
        val handler = capablePlugin.handlers()["sys.device.brightness"]!!

        val result = handler.invoke(execCtx("sys.device.brightness", JsonObject(emptyMap())))

        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(128, value["level"]!!.jsonPrimitive.int)
        assertEquals(false, value["auto"]!!.jsonPrimitive.boolean)
        capablePlugin.onUnload()
        Unit
    }

    @Test
    fun `S46-sys_device_brightness set calls host and reports the level`() = runBlocking {
        val (capablePlugin, svc) = capablePlugin()
        val handler = capablePlugin.handlers()["sys.device.brightness"]!!
        val args = buildJsonObject { put("level", JsonPrimitive(200)) }

        val result = handler.invoke(execCtx("sys.device.brightness", args))

        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("set", value["status"]!!.jsonPrimitive.content)
        assertEquals(200, value["level"]!!.jsonPrimitive.int)
        assertEquals(200, svc.fakeDeviceInfo.lastSetBrightness, "set mode must reach the host brightness setter")
        capablePlugin.onUnload()
        Unit
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    // ═══════════════════════════════════════════════════════════════
    // S47-S49: sys.event.emit (03 §11 demo event source)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `S47-sys_event_emit publishes type and payload to the bus`() = runBlocking {
        val (capable, services) = capablePlugin()
        val handler = capable.handlers()["sys.event.emit"]!!
        val args = buildJsonObject {
            put("type", JsonPrimitive("wifi.connected"))
            put("payload", buildJsonObject { put("ssid", JsonPrimitive("Office")) })
        }

        val result = handler.invoke(execCtx("sys.event.emit", args))

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("emitted", value["status"]!!.jsonPrimitive.content)
        assertEquals("wifi.connected", value["type"]!!.jsonPrimitive.content)
        assertEquals(1, services.fakeEvents.published.size)
        assertEquals("wifi.connected", services.fakeEvents.published[0].first)
        assertEquals(
            "Office",
            services.fakeEvents.published[0].second["ssid"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `S48-sys_event_emit without the events capability surfaces UNAVAILABLE`() = runBlocking {
        // The base stub keeps events null (like the default HostServices).
        val handler = plugin.handlers()["sys.event.emit"]!!

        val ex = assertFailsWith<McosException> {
            handler.invoke(execCtx("sys.event.emit", buildJsonObject { put("type", JsonPrimitive("x.y")) }))
        }
        assertEquals("UNAVAILABLE", ex.code)
    }

    @Test
    fun `S49-sys_event_emit rejects a missing or blank type`() = runBlocking {
        val (capable, _) = capablePlugin()
        val handler = capable.handlers()["sys.event.emit"]!!

        val missing = assertFailsWith<McosException> {
            handler.invoke(execCtx("sys.event.emit", JsonObject(emptyMap())))
        }
        assertEquals("SCHEMA_VIOLATION", missing.code)

        val blank = assertFailsWith<McosException> {
            handler.invoke(
                execCtx(
                    "sys.event.emit",
                    buildJsonObject { put("type", JsonPrimitive("  ")) },
                )
            )
        }
        assertEquals("SCHEMA_VIOLATION", blank.code)
    }

    private fun execCtx(commandId: String, args: JsonObject): ExecutionContext {
        return ExecutionContext(
            runId = "test-run-1",
            commandId = commandId,
            args = args,
            services = stubServices,
        )
    }

    /**
     * A plugin loaded with [CapableSystemHostServices] for the success-path
     * tests. Handlers read capabilities from the services captured at
     * onLoad — the same path a real host takes.
     */
    private fun capablePlugin(): Pair<SystemPlugin, CapableSystemHostServices> {
        val services = CapableSystemHostServices()
        val capable = SystemPlugin()
        runBlocking { capable.onLoad(services) }
        return capable to services
    }
}

/**
 * Minimal HostServices stub for system plugin tests.
 */
open class StubSystemHostServices : HostServices {
    override val files: FileService get() = error("FileService not available")
    override val net: NetService get() = error("NetService not available")
    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? = null
        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound()
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
    override val notifications: NotificationService? = object : NotificationService {
        override suspend fun notify(title: String, text: String): String = "test-channel"
    }
}

/**
 * [StubSystemHostServices] plus recording fakes for the device-info /
 * clipboard / haptics capabilities, for the success-path tests. The base
 * stub keeps them null — the UNAVAILABLE regression tests rely on that.
 */
class CapableSystemHostServices : StubSystemHostServices() {
    val fakeDeviceInfo = FakeDeviceInfoService()
    val fakeClipboard = FakeClipboardService()
    val fakeHaptics = FakeHapticsService()
    val fakeEvents = FakeEventPublisher()
    override val deviceInfo: DeviceInfoService get() = fakeDeviceInfo
    override val clipboard: ClipboardService get() = fakeClipboard
    override val haptics: HapticsService get() = fakeHaptics
    override val events: EventPublisher get() = fakeEvents
}

class FakeEventPublisher : EventPublisher {
    val published = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    override suspend fun publish(type: String, payload: JsonObject) {
        published.add(type to payload)
    }
}

class FakeDeviceInfoService : DeviceInfoService {
    var batteryInfo = BatteryInfo(percent = 87, charging = true, temperatureC = 30)
    var wifiInfo = WifiInfo(connected = true, ssid = "HomeNet", strength = -55)
    var screenInfo = ScreenInfo(1080, 2400, densityDpi = 420, rotation = 1, brightness = 128)
    var volumeInfo = VolumeInfo(musicPercent = 40, ringPercent = 60, alarmPercent = 80)
    var locationInfo: LocationInfo? = LocationInfo(22.5431, 114.0579, accuracyM = 25f, timestampMs = 1_700_000_000_000)
    var brightnessInfo = BrightnessInfo(level = 128, auto = false)
    var lastSetBrightness: Int? = null

    override suspend fun battery() = batteryInfo
    override suspend fun wifi() = wifiInfo
    override suspend fun screen() = screenInfo
    override suspend fun volume() = volumeInfo
    override suspend fun location() = locationInfo
    override suspend fun brightness() = brightnessInfo
    override suspend fun setBrightness(level: Int) {
        lastSetBrightness = level
    }
}

class FakeClipboardService : ClipboardService {
    var current: String? = "copied text"
    var lastSet: String? = null

    override suspend fun set(text: String) {
        lastSet = text
    }

    override suspend fun get(): String? = current
}

class FakeHapticsService : HapticsService {
    var lastDurationMs: Int? = null
    var vibrateCount = 0

    override suspend fun vibrate(durationMs: Int) {
        lastDurationMs = durationMs
        vibrateCount++
    }
}
