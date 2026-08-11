package com.mcos.plugin.files

import com.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Conformance tests for FilesPlugin.
 */
class FilesPluginTest {

    private lateinit var plugin: FilesPlugin
    private lateinit var stubServices: HostServices

    @BeforeTest
    fun setUp() = runBlocking {
        plugin = FilesPlugin()
        stubServices = StubFilesHostServices()
        plugin.onLoad(stubServices)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        plugin.onUnload()
    }

    // ═══════════════════════════════════════════════════════════════
    // F1-F3: Manifest validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F1-manifest has correct plugin id`() {
        assertEquals("mcos.plugin.files", plugin.manifest.id)
        assertEquals("1.0.0", plugin.manifest.version)
    }

    @Test
    fun `F2-manifest declares 4 file commands`() {
        val commands = plugin.manifest.commands.map { it.id }.toSet()
        assertEquals(4, commands.size)
        assertTrue(commands.contains("file.list"))
        assertTrue(commands.contains("file.search"))
        assertTrue(commands.contains("photo.search"))
        assertTrue(commands.contains("photo.compress"))
    }

    @Test
    fun `F3-manifest requires storage permissions`() {
        val perms = plugin.manifest.permissions.map { it.name }.toSet()
        assertTrue(perms.any { it.contains("READ_MEDIA_IMAGES") || it.contains("READ_EXTERNAL_STORAGE") })
    }

    // ═══════════════════════════════════════════════════════════════
    // F4-F7: Handlers
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F4-handlers returns all 4 command handlers`() {
        val handlers = plugin.handlers()
        assertEquals(4, handlers.size)
        assertTrue(handlers.containsKey("file.list"))
        assertTrue(handlers.containsKey("file.search"))
        assertTrue(handlers.containsKey("photo.search"))
        assertTrue(handlers.containsKey("photo.compress"))
    }

    @Test
    fun `F5-file_list returns file entries`() = runBlocking {
        val handler = plugin.handlers()["file.list"]!!
        val args = buildJsonObject { put("path", JsonPrimitive("content://test/")) }
        val ctx = execCtx("file.list", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertTrue(value.containsKey("entries"))
    }

    @Test
    fun `F6-photo_search accepts date filter`() = runBlocking {
        val handler = plugin.handlers()["photo.search"]!!
        val args = buildJsonObject { put("date", JsonPrimitive("today")) }
        val ctx = execCtx("photo.search", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertTrue(value.containsKey("photos"))
    }

    @Test
    fun `F7-photo_compress returns compressed result`() = runBlocking {
        val handler = plugin.handlers()["photo.compress"]!!
        val args = buildJsonObject {
            put("uris", buildJsonArray { add(JsonPrimitive("content://test/photo.jpg")) })
            put("quality", JsonPrimitive(80))
        }
        val ctx = execCtx("photo.compress", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals(1, value["count"]!!.jsonPrimitive.int)
    }

    // ═══════════════════════════════════════════════════════════════
    // F8: Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F8-load and unload lifecycle`() = runBlocking {
        val p = FilesPlugin()
        p.onLoad(stubServices)
        p.onUnload()
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

class StubFilesHostServices : HostServices {
    override val files: FileService = StubFileService()
    override val net: NetService get() = error("NetService not available")
    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? = null
        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound
    }
    override val ui: UiService = object : UiService {
        override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? = null
    }
    override val secureStore: SecureStore get() = error("SecureStore not available")
    override val clock: Clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
    override val json: JsonService get() = error("JsonService not available")
}

class StubFileService : FileService {
    override suspend fun list(uri: String, mimeType: String?): List<FileEntry> {
        return listOf(
            FileEntry("$uri/file1.jpg", "file1.jpg", "image/jpeg", 1024),
            FileEntry("$uri/file2.png", "file2.png", "image/png", 2048),
        )
    }
}
