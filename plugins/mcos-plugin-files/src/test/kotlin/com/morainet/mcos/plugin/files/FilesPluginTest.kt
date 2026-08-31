package com.morainet.mcos.plugin.files

import com.morainet.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant as KxInstant
import kotlinx.serialization.json.*
import kotlin.test.*
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

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
    fun `F2-manifest declares 8 file commands`() {
        val commands = plugin.manifest.commands.map { it.id }.toSet()
        assertEquals(8, commands.size)
        assertTrue(commands.contains("file.list"))
        assertTrue(commands.contains("file.search"))
        assertTrue(commands.contains("photo.search"))
        assertTrue(commands.contains("photo.compress"))
        assertTrue(commands.contains("file.write"))
        assertTrue(commands.contains("file.read"))
        assertTrue(commands.contains("file.stat"))
        assertTrue(commands.contains("file.delete"))
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
    fun `F4-handlers returns all 8 command handlers`() {
        val handlers = plugin.handlers()
        assertEquals(8, handlers.size)
        assertTrue(handlers.containsKey("file.list"))
        assertTrue(handlers.containsKey("file.search"))
        assertTrue(handlers.containsKey("photo.search"))
        assertTrue(handlers.containsKey("photo.compress"))
        assertTrue(handlers.containsKey("file.write"))
        assertTrue(handlers.containsKey("file.read"))
        assertTrue(handlers.containsKey("file.stat"))
        assertTrue(handlers.containsKey("file.delete"))
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

    @Test
    fun `F9-file_search glob wildcard matches correctly`() = runBlocking {
        // P1-F1 regression: `*.jpg` must match `file1.jpg` but not `file2.png`.
        // The previous implementation used Regex.escape(pattern) which wraps
        // the whole pattern in \Q...\E, so the subsequent .replace("\\*",".*")
        // never finds the escaped '*' — and the glob silently matches nothing.
        val handler = plugin.handlers()["file.search"]!!

        // *.jpg → should match only file1.jpg
        val jpgArgs = buildJsonObject { put("pattern", JsonPrimitive("*.jpg")) }
        val jpgCtx = execCtx("file.search", jpgArgs)
        val jpgResult = handler.invoke(jpgCtx)
        assertTrue(jpgResult is CommandResult.Ok)
        val jpgObj = (jpgResult as CommandResult.Ok).value!!.jsonObject
        val jpgCount = jpgObj["count"]!!.jsonPrimitive.content.toInt()
        assertEquals(1, jpgCount, "*.jpg should match exactly file1.jpg")

        // *.png → should match only file2.png
        val pngArgs = buildJsonObject { put("pattern", JsonPrimitive("*.png")) }
        val pngCtx = execCtx("file.search", pngArgs)
        val pngResult = handler.invoke(pngCtx)
        assertTrue(pngResult is CommandResult.Ok)
        val pngCount = (pngResult as CommandResult.Ok).value!!.jsonObject["count"]!!
            .jsonPrimitive.content.toInt()
        assertEquals(1, pngCount, "*.png should match exactly file2.png")

        // file?.jpg → '?' matches one char, so file1.jpg matches
        val qArgs = buildJsonObject { put("pattern", JsonPrimitive("file?.jpg")) }
        val qCtx = execCtx("file.search", qArgs)
        val qResult = handler.invoke(qCtx)
        assertTrue(qResult is CommandResult.Ok)
        val qCount = (qResult as CommandResult.Ok).value!!.jsonObject["count"]!!
            .jsonPrimitive.content.toInt()
        assertEquals(1, qCount, "file?.jpg should match file1.jpg")
        Unit
    }

    // ═══════════════════════════════════════════════════════════════
    // F10-F14: photo.search date filters + file.search media root
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `F10-photo_search date today resolves to local midnight lower bound`() = runBlocking {
        val handler = plugin.handlers()["photo.search"]!!
        val args = buildJsonObject { put("date", JsonPrimitive("today")) }
        val ctx = execCtx("photo.search", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val fs = stubServices.files as StubFileService
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        assertNotNull(fs.lastPhotoAfterMs, "date=today must push a lower bound")
        assertEquals(todayStart, fs.lastPhotoAfterMs)
        assertNull(fs.lastPhotoBeforeMs)
        assertEquals("image/*", fs.lastPhotoMimeType)
    }

    @Test
    fun `F11-photo_search iso date bounds forwarded to host`() = runBlocking {
        val handler = plugin.handlers()["photo.search"]!!
        val args = buildJsonObject {
            put("after", JsonPrimitive("2026-08-01"))
            put("before", JsonPrimitive("2026-08-15T23:59:59+08:00"))
        }
        val ctx = execCtx("photo.search", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val fs = stubServices.files as StubFileService
        val zone = ZoneId.systemDefault()
        val expectedAfter = LocalDate.parse("2026-08-01").atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedBefore = OffsetDateTime.parse("2026-08-15T23:59:59+08:00").toInstant().toEpochMilli()
        assertEquals(expectedAfter, fs.lastPhotoAfterMs)
        assertEquals(expectedBefore, fs.lastPhotoBeforeMs)
    }

    @Test
    fun `F12-photo_search passes limit through to host query`() = runBlocking {
        val handler = plugin.handlers()["photo.search"]!!
        val args = buildJsonObject { put("limit", JsonPrimitive(10)) }
        val ctx = execCtx("photo.search", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val fs = stubServices.files as StubFileService
        assertEquals(10, fs.lastPhotoLimit)
    }

    @Test
    fun `F13-file_search defaults to media store root`() = runBlocking {
        val handler = plugin.handlers()["file.search"]!!
        val args = buildJsonObject { put("pattern", JsonPrimitive("*")) }
        val ctx = execCtx("file.search", args)

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val fs = stubServices.files as StubFileService
        assertEquals("media://images", fs.lastListUri)
    }

    @Test
    fun `F14-resolveDateBounds shorthand and explicit override`() {
        val zone = ZoneId.systemDefault()
        val now = 1_758_000_000_000L
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        // yesterday → previous local midnight, no upper bound
        val yesterday = resolveDateBounds("yesterday", null, null, now)
        val expected = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, yesterday.afterMs)
        assertNull(yesterday.beforeMs)

        // explicit after overrides the shorthand lower bound
        val explicit = resolveDateBounds("today", "2026-08-01", null, now)
        val expectedAfter = LocalDate.parse("2026-08-01").atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expectedAfter, explicit.afterMs)
    }

    // ═══════════════════════════════════════════════════════════════
    // F15-F25: sandbox storage commands (04-plugin-sdk.md 6.1)
    //
    // The handlers read the sandbox from ctx.services (the Executor's
    // per-plugin namespaced view) — the onLoad stub has NO sandbox, so a
    // successful write here proves the ctx path is the one taken.
    // ═══════════════════════════════════════════════════════════════

    private fun sandboxCtx(commandId: String, args: JsonObject, sandbox: SandboxFileService): ExecutionContext =
        execCtx(commandId, args, SandboxedStubServices(sandbox))

    @Test
    fun `F15-file_write writes utf-8 text through the sandbox`() = runBlocking {
        val sandbox = RecordingSandbox()
        val result = plugin.handlers()["file.write"]!!.invoke(
            sandboxCtx("file.write", buildJsonObject {
                put("path", JsonPrimitive("logs/today.txt"))
                put("text", JsonPrimitive("battery 82%"))
            }, sandbox)
        )

        assertTrue(result is CommandResult.Ok, result.toString())
        assertEquals(1, sandbox.writes.size)
        val (path, bytes, append) = sandbox.writes[0]
        assertEquals("logs/today.txt", path)
        assertEquals("battery 82%", bytes.decodeToString())
        assertEquals(false, append)
        val value = (result as CommandResult.Ok).value.jsonObject
        assertEquals("logs/today.txt", value["path"]!!.jsonPrimitive.content)
        assertEquals(11L, value["size"]!!.jsonPrimitive.long)
    }

    @Test
    fun `F16-file_write forwards the append flag and reports grown size`() = runBlocking {
        val sandbox = RecordingSandbox()
        sandbox.files["log.txt"] = "old\n".toByteArray()
        val result = plugin.handlers()["file.write"]!!.invoke(
            sandboxCtx("file.write", buildJsonObject {
                put("path", JsonPrimitive("log.txt"))
                put("text", JsonPrimitive("new\n"))
                put("append", JsonPrimitive(true))
            }, sandbox)
        )

        assertTrue(result is CommandResult.Ok)
        assertEquals(true, sandbox.writes[0].third)
        assertEquals("old\nnew\n", sandbox.files["log.txt"]!!.decodeToString())
        assertEquals(8L, (result as CommandResult.Ok).value.jsonObject["size"]!!.jsonPrimitive.long)
    }

    @Test
    fun `F17-sandbox commands are UNAVAILABLE without the capability`() = runBlocking {
        // ctx carries the plain onLoad stub — no sandbox override anywhere.
        listOf(
            "file.write" to buildJsonObject { put("path", JsonPrimitive("a")); put("text", JsonPrimitive("x")) },
            "file.read" to buildJsonObject { put("path", JsonPrimitive("a")) },
            "file.stat" to buildJsonObject { put("path", JsonPrimitive("a")) },
            "file.delete" to buildJsonObject { put("path", JsonPrimitive("a")) },
        ).forEach { (commandId, args) ->
            val e = assertFailsWith<McosException> {
                plugin.handlers()[commandId]!!.invoke(execCtx(commandId, args))
            }
            assertEquals("UNAVAILABLE", e.code, commandId)
        }
    }

    @Test
    fun `F18-file_write over the 1 MiB limit is SCHEMA_VIOLATION file_too_large`() = runBlocking {
        val sandbox = RecordingSandbox()
        val oversized = "x".repeat(FilesPlugin.MAX_FILE_BYTES + 1)
        val e = assertFailsWith<McosException> {
            plugin.handlers()["file.write"]!!.invoke(
                sandboxCtx("file.write", buildJsonObject {
                    put("path", JsonPrimitive("big.txt"))
                    put("text", JsonPrimitive(oversized))
                }, sandbox)
            )
        }
        assertEquals("SCHEMA_VIOLATION", e.code)
        assertEquals("file_too_large", e.details["reason"]?.jsonPrimitive?.content)
        assertTrue(sandbox.writes.isEmpty(), "nothing may reach the sandbox")
    }

    @Test
    fun `F19-file_read returns path size text`() = runBlocking {
        val sandbox = RecordingSandbox()
        sandbox.files["note.txt"] = "hello sandbox".toByteArray()
        val result = plugin.handlers()["file.read"]!!.invoke(
            sandboxCtx("file.read", buildJsonObject { put("path", JsonPrimitive("note.txt")) }, sandbox)
        )

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value.jsonObject
        assertEquals("note.txt", value["path"]!!.jsonPrimitive.content)
        assertEquals(13L, value["size"]!!.jsonPrimitive.long)
        assertEquals("hello sandbox", value["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("note.txt"), sandbox.readPaths)
    }

    @Test
    fun `F20-file_read of an absent path is files not_found`() = runBlocking {
        val e = assertFailsWith<McosException> {
            plugin.handlers()["file.read"]!!.invoke(
                sandboxCtx("file.read", buildJsonObject { put("path", JsonPrimitive("gone.txt")) }, RecordingSandbox())
            )
        }
        assertEquals("files.not_found", e.code)
    }

    @Test
    fun `F21-file_read of an oversized file is files too_large`() = runBlocking {
        val sandbox = RecordingSandbox()
        sandbox.files["huge.txt"] = ByteArray(FilesPlugin.MAX_FILE_BYTES + 1)
        val e = assertFailsWith<McosException> {
            plugin.handlers()["file.read"]!!.invoke(
                sandboxCtx("file.read", buildJsonObject { put("path", JsonPrimitive("huge.txt")) }, sandbox)
            )
        }
        assertEquals("files.too_large", e.code)
    }

    @Test
    fun `F22-file_stat reports exists isDir size and the absent shape`() = runBlocking {
        val sandbox = RecordingSandbox()
        sandbox.files["f.txt"] = byteArrayOf(1, 2, 3)
        val handler = plugin.handlers()["file.stat"]!!

        val present = handler.invoke(
            sandboxCtx("file.stat", buildJsonObject { put("path", JsonPrimitive("f.txt")) }, sandbox)
        ) as CommandResult.Ok
        val pv = present.value.jsonObject
        assertEquals(true, pv["exists"]!!.jsonPrimitive.boolean)
        assertEquals(false, pv["isDir"]!!.jsonPrimitive.boolean)
        assertEquals(3L, pv["size"]!!.jsonPrimitive.long)

        val absent = handler.invoke(
            sandboxCtx("file.stat", buildJsonObject { put("path", JsonPrimitive("nope.txt")) }, sandbox)
        ) as CommandResult.Ok
        val av = absent.value.jsonObject
        assertEquals(false, av["exists"]!!.jsonPrimitive.boolean)
        assertNull(av["size"])
    }

    @Test
    fun `F23-file_delete is idempotent and forwards the path`() = runBlocking {
        val sandbox = RecordingSandbox()
        sandbox.files["temp.txt"] = "x".toByteArray()
        val handler = plugin.handlers()["file.delete"]!!

        val first = handler.invoke(
            sandboxCtx("file.delete", buildJsonObject { put("path", JsonPrimitive("temp.txt")) }, sandbox)
        ) as CommandResult.Ok
        assertEquals(true, first.value.jsonObject["deleted"]!!.jsonPrimitive.boolean)

        val second = handler.invoke(
            sandboxCtx("file.delete", buildJsonObject { put("path", JsonPrimitive("temp.txt")) }, sandbox)
        ) as CommandResult.Ok
        assertEquals(false, second.value.jsonObject["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("temp.txt", "temp.txt"), sandbox.deletes)
    }

    @Test
    fun `F24-sandbox path violations surface the service error untouched`() = runBlocking {
        val sandbox = RecordingSandbox().apply {
            failWith = McosException(
                code = "SCHEMA_VIOLATION",
                message = "Invalid sandbox path (dot segment)",
                details = JsonObject(mapOf("reason" to JsonPrimitive("sandbox_path_invalid"))),
            )
        }
        val e = assertFailsWith<McosException> {
            plugin.handlers()["file.write"]!!.invoke(
                sandboxCtx("file.write", buildJsonObject {
                    put("path", JsonPrimitive("../escape.txt"))
                    put("text", JsonPrimitive("no"))
                }, sandbox)
            )
        }
        // The Executor maps this to Err(SCHEMA_VIOLATION, details) — here we
        // only assert the handler does not swallow the sandbox's verdict.
        assertEquals("SCHEMA_VIOLATION", e.code)
        assertEquals("sandbox_path_invalid", e.details["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `F25-manifest marks write commands write-class and read commands read-class`() {
        val byId = plugin.manifest.commands.associateBy { it.id }
        assertEquals(SideEffectClass.write, byId.getValue("file.write").sideEffectClass)
        assertEquals(SideEffectClass.write, byId.getValue("file.delete").sideEffectClass)
        assertEquals(SideEffectClass.read, byId.getValue("file.read").sideEffectClass)
        assertEquals(SideEffectClass.read, byId.getValue("file.stat").sideEffectClass)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun execCtx(commandId: String, args: JsonObject, services: HostServices = stubServices): ExecutionContext {
        return ExecutionContext(
            runId = "test-run-1",
            commandId = commandId,
            args = args,
            services = services,
        )
    }
}

/** [HostServices] stub whose only addition is a sandbox capability. */
class SandboxedStubServices(private val sandboxService: SandboxFileService) : HostServices by StubFilesHostServices() {
    override val sandbox: SandboxFileService get() = sandboxService
}

/** Recording fake with an in-memory backing map (real-call assertions). */
class RecordingSandbox : SandboxFileService {
    val writes = mutableListOf<Triple<String, ByteArray, Boolean>>()
    val readPaths = mutableListOf<String>()
    val stats = mutableListOf<String>()
    val deletes = mutableListOf<String>()
    val lists = mutableListOf<String>()
    val tempFiles = mutableListOf<Pair<String, String>>()
    val files = mutableMapOf<String, ByteArray>()
    var failWith: McosException? = null

    private fun guard() {
        failWith?.let { throw it }
    }

    override suspend fun read(path: String): ByteArray? {
        guard()
        readPaths += path
        return files[path]
    }

    override suspend fun write(path: String, data: ByteArray, append: Boolean) {
        guard()
        writes += Triple(path, data, append)
        files[path] = if (append) (files[path] ?: ByteArray(0)) + data else data
    }

    override suspend fun stat(path: String): SandboxEntry? {
        guard()
        stats += path
        return files[path]?.let { SandboxEntry(path = path, isDir = false, size = it.size.toLong()) }
    }

    override suspend fun delete(path: String): Boolean {
        guard()
        deletes += path
        return files.remove(path) != null
    }

    override suspend fun list(dir: String): List<SandboxEntry> {
        guard()
        lists += dir
        return files.keys.filter { it.startsWith("$dir/") }
            .map { SandboxEntry(path = it, isDir = false, size = files[it]!!.size.toLong()) }
    }

    override suspend fun tempFile(prefix: String, suffix: String): String {
        guard()
        tempFiles += prefix to suffix
        val name = "${prefix}${tempFiles.size}${suffix}"
        files[name] = ByteArray(0)
        return name
    }
}

class StubFilesHostServices : HostServices {
    override val files: FileService = StubFileService()
    override val net: NetService get() = error("NetService not available")
    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? = null
        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound()
    }
    override val ui: UiService = object : UiService {
        override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? = null
    }
    override val secureStore: SecureStore get() = error("SecureStore not available")
    override val clock: Clock = object : Clock {
        override fun now(): KxInstant = KxInstant.fromEpochMilliseconds(System.currentTimeMillis())
        override fun monotonicMs(): Long = System.currentTimeMillis()
    }
    override val json: JsonService get() = error("JsonService not available")
}

class StubFileService : FileService {
    var lastListUri: String? = null
    var lastPhotoMimeType: String? = null
    var lastPhotoAfterMs: Long? = null
    var lastPhotoBeforeMs: Long? = null
    var lastPhotoLimit: Int = -1

    override suspend fun list(uri: String, mimeType: String?): List<FileEntry> {
        lastListUri = uri
        return listOf(
            FileEntry("$uri/file1.jpg", "file1.jpg", "image/jpeg", 1024),
            FileEntry("$uri/file2.png", "file2.png", "image/png", 2048),
        )
    }

    override suspend fun searchPhotos(
        mimeType: String,
        afterMs: Long?,
        beforeMs: Long?,
        limit: Int,
    ): List<FileEntry> {
        lastPhotoMimeType = mimeType
        lastPhotoAfterMs = afterMs
        lastPhotoBeforeMs = beforeMs
        lastPhotoLimit = limit
        return listOf(
            FileEntry("media://images/photo1.jpg", "photo1.jpg", "image/jpeg", 4096, dateModifiedMs = 1_700_000_000_000L),
            FileEntry("media://images/photo2.jpg", "photo2.jpg", "image/jpeg", 2048, dateModifiedMs = 1_700_000_500_000L),
        )
    }
}
