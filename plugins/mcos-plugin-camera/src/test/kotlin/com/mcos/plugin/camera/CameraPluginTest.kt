package com.mcos.plugin.camera

import com.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Conformance tests for CameraPlugin.
 * Matches [04-plugin-sdk.md 17].
 */
class CameraPluginTest {

    private lateinit var plugin: CameraPlugin
    private lateinit var stubServices: HostServices

    @BeforeTest
    fun setUp() = runBlocking {
        plugin = CameraPlugin()
        stubServices = StubHostServices()
        plugin.onLoad(stubServices)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        plugin.onUnload()
    }

    // ═══════════════════════════════════════════════════════════════
    // M1-M3: Manifest validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M1-manifest has correct plugin id`() {
        assertEquals("mcos.plugin.camera", plugin.manifest.id)
        assertEquals("Camera Plugin", plugin.manifest.name)
        assertEquals("1.0.0", plugin.manifest.version)
    }

    @Test
    fun `M2-manifest declares camera commands`() {
        val commands = plugin.manifest.commands.map { it.id }.toSet()
        assertTrue(commands.contains("camera.capture"))
        assertTrue(commands.contains("camera.scan"))
    }

    @Test
    fun `M3-manifest requires CAMERA permission`() {
        val perms = plugin.manifest.permissions.map { it.name }.toSet()
        assertTrue(perms.any { it.contains("CAMERA") })
    }

    // ═══════════════════════════════════════════════════════════════
    // M4-M6: Handlers registration
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M4-handlers returns both command handlers`() {
        val handlers = plugin.handlers()
        assertEquals(2, handlers.size)
        assertTrue(handlers.containsKey("camera.capture"))
        assertTrue(handlers.containsKey("camera.scan"))
    }

    @Test
    fun `M5-capture handler works with default args`() = runBlocking {
        val handler = plugin.handlers()["camera.capture"]!!
        val ctx = executionContext("camera.capture", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("captured", value["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `M6-capture handler works with explicit facing and flash`() = runBlocking {
        val handler = plugin.handlers()["camera.capture"]!!
        val args = buildJsonObject {
            put("facing", JsonPrimitive("front"))
            put("flash", JsonPrimitive("on"))
            put("quality", JsonPrimitive(50))
        }
        val ctx = executionContext("camera.capture", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("front", value["facing"]!!.jsonPrimitive.content)
        assertEquals("on", value["flash"]!!.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // M7-M9: Input validation — camera.capture
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M7-capture rejects invalid facing value`() = runBlocking {
        val handler = plugin.handlers()["camera.capture"]!!
        val args = buildJsonObject { put("facing", JsonPrimitive("side")) }
        val ctx = executionContext("camera.capture", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
        Unit
    }

    @Test
    fun `M8-capture rejects invalid flash value`() = runBlocking {
        val handler = plugin.handlers()["camera.capture"]!!
        val args = buildJsonObject { put("flash", JsonPrimitive("strobe")) }
        val ctx = executionContext("camera.capture", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
        Unit
    }

    @Test
    fun `M9-capture accepts all valid flash values`() {
        val handler = plugin.handlers()["camera.capture"]!!
        val validFlash = listOf("auto", "on", "off")

        for (flash in validFlash) {
            runBlocking {
                val args = buildJsonObject { put("flash", JsonPrimitive(flash)) }
                val ctx = executionContext("camera.capture", args)
                val result = handler.invoke(ctx)
                assertTrue(result is CommandResult.Ok, "flash=$flash should be accepted")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // M10-M12: camera.scan handler
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M10-scan handler works with default args`() = runBlocking {
        val handler = plugin.handlers()["camera.scan"]!!
        val ctx = executionContext("camera.scan", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("scanned", value["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `M11-scan handler works with explicit format`() = runBlocking {
        val handler = plugin.handlers()["camera.scan"]!!
        val args = buildJsonObject { put("format", JsonPrimitive("qr")) }
        val ctx = executionContext("camera.scan", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("qr", value["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `M12-scan rejects invalid format`() = runBlocking {
        val handler = plugin.handlers()["camera.scan"]!!
        val args = buildJsonObject { put("format", JsonPrimitive("invalid_format")) }
        val ctx = executionContext("camera.scan", args)

        assertFailsWith<McosException> {
            handler.invoke(ctx)
        }
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // M13: Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M13-load and unload lifecycle`() = runBlocking {
        val p = CameraPlugin()
        p.onLoad(stubServices)
        // Should not throw
        p.onUnload()
        p.onUnload() // idempotent
    }

    // ═══════════════════════════════════════════════════════════════
    // M14: Artifacts
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `M14-capture produces image artifact`() = runBlocking {
        val handler = plugin.handlers()["camera.capture"]!!
        val ctx = executionContext("camera.capture", JsonObject(emptyMap()))

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val ok = result as CommandResult.Ok
        assertTrue(ok.artifacts.isNotEmpty(), "should produce at least one artifact")
        assertEquals("image", ok.artifacts[0].type)
        assertEquals("image/jpeg", ok.artifacts[0].mimeType)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun executionContext(commandId: String, args: JsonObject): ExecutionContext {
        return ExecutionContext(
            runId = "test-run-1",
            commandId = commandId,
            args = args,
            services = stubServices,
        )
    }
}

/**
 * Minimal HostServices stub for plugin tests.
 */
class StubHostServices : HostServices {
    override val files: FileService get() = error("FileService not available")
    override val net: NetService get() = error("NetService not available")
    override val memory: MemoryFacade = StubMemoryFacade()

    override val ui: UiService = object : UiService {
        override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? {
            // Return a simulated result for tests that need UI interaction
            return mapOf("uri" to "content://test/camera/1", "content" to "test-barcode-content")
        }
    }

    override val secureStore: SecureStore get() = error("SecureStore not available")
    override val clock: Clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
    override val json: JsonService get() = error("JsonService not available")
}

class StubMemoryFacade : MemoryFacade {
    override suspend fun get(path: String): JsonElement? = null
    override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound()
}
