package com.mcos.plugin.hello

import com.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Conformance tests for HelloPlugin — the reference sample.
 */
class HelloPluginTest {

    private lateinit var plugin: HelloPlugin

    @BeforeTest
    fun setUp() {
        plugin = HelloPlugin()
    }

    // ═══════════════════════════════════════════════════════════════
    // H1-H3: Manifest validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H1-manifest has correct plugin id`() {
        assertEquals("example.hello", plugin.manifest.id)
        assertEquals("Hello Plugin", plugin.manifest.name)
        assertEquals("1.0.0", plugin.manifest.version)
    }

    @Test
    fun `H2-handlers returns hello_world`() {
        val handlers = plugin.handlers()
        assertEquals(1, handlers.size)
        assertTrue(handlers.containsKey("hello.world"))
    }

    @Test
    fun `H3-hello_world with name parameter`() = runBlocking {
        val handler = plugin.handlers()["hello.world"]!!
        val args = buildJsonObject { put("name", JsonPrimitive("MCOS")) }
        val ctx = ExecutionContext(
            runId = "test-1",
            commandId = "hello.world",
            args = args,
            services = StubHelloHostServices(),
        )

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        val value = (result as CommandResult.Ok).value!!
        assertEquals("Hello, MCOS!", value.jsonPrimitive.content)
    }

    // ═══════════════════════════════════════════════════════════════
    // H4-H5: Default behavior
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `H4-hello_world with no args defaults to World`() = runBlocking {
        val handler = plugin.handlers()["hello.world"]!!
        val ctx = ExecutionContext(
            runId = "test-2",
            commandId = "hello.world",
            args = JsonObject(emptyMap()),
            services = StubHelloHostServices(),
        )

        val result = handler.invoke(ctx)

        assertTrue(result is CommandResult.Ok)
        assertEquals("Hello, World!", (result as CommandResult.Ok).value!!.jsonPrimitive.content)
    }

    @Test
    fun `H5-lifecycle load and unload`() = runBlocking {
        plugin.onLoad(StubHelloHostServices())
        plugin.onUnload()
    }
}

class StubHelloHostServices : HostServices {
    override val files: FileService get() = error("not available")
    override val net: NetService get() = error("not available")
    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? = null
        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = ResolveResult.NotFound()
    }
    override val ui: UiService = object : UiService {
        override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? = null
    }
    override val secureStore: SecureStore get() = error("not available")
    override val clock: Clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
    override val json: JsonService get() = error("not available")
}
