package com.mcos.runtime.llm

import com.mcos.runtime.registry.CommandRegistry
import com.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class LlmPlannerTest {

    private lateinit var registry: CommandRegistry

    private val defaultProvider = ProviderInfo("TestOrg", "https://example.com")

    @BeforeTest
    fun setUp() {
        registry = CommandRegistry()
    }

    // ---- buildSystemPrompt -----------------------------------------------

    @Test
    fun `buildSystemPrompt with no commands`() = runBlocking {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val prompt = planner.buildSystemPrompt()

        assertTrue(prompt.contains("None registered yet"), "should mention no commands")
        assertTrue(prompt.contains("MCOS Agent"), "should include agent identity")
    }

    @Test
    fun `buildSystemPrompt with registered commands includes command info`() = runBlocking {
        registerCameraPlugin()

        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val prompt = planner.buildSystemPrompt()

        assertTrue(prompt.contains("camera.capture"), "should list camera.capture")
        assertTrue(prompt.contains("camera.scan"), "should list camera.scan")
        assertTrue(prompt.contains("facing"), "should list parameter name")
        assertTrue(prompt.contains("Parameters"), "should have parameters section")
    }

    @Test
    fun `buildSystemPrompt includes required markers`() = runBlocking {
        registerSystemPlugin()

        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val prompt = planner.buildSystemPrompt()

        // sys.notify has title + body as required
        assertTrue(prompt.contains("[required]"), "should mark required params")
    }

    @Test
    fun `buildSystemPrompt includes memory context when MemoryStore is provided`() = runBlocking {
        registerCameraPlugin()
        val memory = com.mcos.runtime.memory.MemoryStore()
        memory.putString("prefs.theme", "dark", tags = setOf("preference"))
        memory.putString("prefs.language", "zh-CN", tags = setOf("preference"))

        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry, memory)
        val prompt = planner.buildSystemPrompt()

        assertTrue(prompt.contains("Memory Context"), "should have memory section")
        assertTrue(prompt.contains("prefs.theme"), "should list prefs.theme")
        assertTrue(prompt.contains("dark"), "should show prefs value")
    }

    // ---- parseResponse: valid DSL ----------------------------------------

    @Test
    fun `parseResponse with valid single command`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val dsl = "camera.capture(quality=\"high\", flash=\"on\")"

        val plan = planner.parseResponse(dsl)

        assertTrue(plan.isSuccess, "should be successful")
        assertEquals(1, plan.commands.size)
        assertEquals("camera.capture", plan.commands[0].id)
        assertEquals("high", plan.commands[0].args["quality"]?.jsonPrimitive?.content)
        assertEquals("on", plan.commands[0].args["flash"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseResponse with valid sequence`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val dsl = """
            camera.capture(quality="high")
            sys.notify(title="Done", body="Captured")
        """.trimIndent()

        val plan = planner.parseResponse(dsl)

        assertTrue(plan.isSuccess)
        assertEquals(2, plan.commands.size)
        assertEquals("camera.capture", plan.commands[0].id)
        assertEquals("sys.notify", plan.commands[1].id)
    }

    // ---- parseResponse: markdown fences ----------------------------------

    @Test
    fun `parseResponse extracts DSL from mcos code fence`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val raw = """
            Here is the command:
            ```mcos
            camera.capture(quality="low")
            ```
        """.trimIndent()

        val plan = planner.parseResponse(raw)

        assertTrue(plan.isSuccess)
        assertEquals(1, plan.commands.size)
        assertEquals("camera.capture", plan.commands[0].id)
    }

    @Test
    fun `parseResponse extracts DSL from plain code fence`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val raw = """
            ``````
            camera.capture(quality="low")
            ```
        """.trimIndent()

        val plan = planner.parseResponse(raw)

        assertTrue(plan.isSuccess)
        assertEquals(1, plan.commands.size)
    }

    @Test
    fun `parseResponse extracts DSL from dsl code fence`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val raw = """
            ```dsl
            camera.capture(quality="low")
            ```
        """.trimIndent()

        val plan = planner.parseResponse(raw)

        assertTrue(plan.isSuccess)
        assertEquals(1, plan.commands.size)
    }

    // ---- parseResponse: edge cases ---------------------------------------

    @Test
    fun `parseResponse with empty string`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)

        val plan = planner.parseResponse("   ")

        assertFalse(plan.isSuccess)
        assertTrue(plan.commands.isEmpty())
        assertNotNull(plan.thoughts)
    }

    @Test
    fun `parseResponse with invalid DSL returns error`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)

        val plan = planner.parseResponse("this is not valid dsl at all")

        assertFalse(plan.isSuccess)
        assertNotNull(plan.error)
        assertEquals("LLM_PARSE_ERROR", plan.error!!.code)
    }

    @Test
    fun `parseResponse with unknown command parses but has empty commands`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)

        // DSL parser doesn't validate against registry -- that's the Executor's job
        val plan = planner.parseResponse("unknown.cmd(param=\"x\")")

        // Should parse successfully (structural parse only), then Executor handles unknown
        assertTrue(plan.isSuccess)
        assertEquals("unknown.cmd", plan.commands[0].id)
    }

    @Test
    fun `parseResponse with numeric and boolean args`() {
        val planner = LlmPlanner(FakeLlmProvider(emptyList()), registry)
        val dsl = "camera.capture(quality=100, flash=true)"

        val plan = planner.parseResponse(dsl)

        assertTrue(plan.isSuccess)
        assertEquals(100, plan.commands[0].args["quality"]?.jsonPrimitive?.int)
        assertTrue(plan.commands[0].args["flash"]?.jsonPrimitive?.boolean ?: false)
    }

    // ---- LlmPlan.isSuccess -----------------------------------------------

    @Test
    fun `LlmPlan isSuccess false when commands empty`() {
        val plan = LlmPlan(emptyList(), "", "no commands")
        assertFalse(plan.isSuccess)
    }

    @Test
    fun `LlmPlan isSuccess false when error present`() {
        val plan = LlmPlan(
            listOf(com.mcos.runtime.executor.Command("x", JsonObject(emptyMap()))),
            "",
            null,
            LlmResponse.Err("X", "msg")
        )
        assertFalse(plan.isSuccess)
    }

    @Test
    fun `LlmPlan isSuccess true when commands present and no error`() {
        val plan = LlmPlan(
            listOf(com.mcos.runtime.executor.Command("x", JsonObject(emptyMap()))),
            "",
            "ok"
        )
        assertTrue(plan.isSuccess)
    }

    // ---- plan() end-to-end with fake provider ----------------------------

    @Test
    fun `plan with successful LLM response`() = runBlocking {
        registerCameraPlugin()
        val fakeProvider = FakeLlmProvider(
            listOf(LlmResponse.Ok("camera.capture(quality=\"high\")"))
        )
        val planner = LlmPlanner(fakeProvider, registry)

        val plan = planner.plan("take a photo")

        assertTrue(plan.isSuccess)
        assertEquals(1, plan.commands.size)
        assertEquals("camera.capture", plan.commands[0].id)
    }

    @Test
    fun `plan with LLM error returns error plan`() = runBlocking {
        val fakeProvider = FakeLlmProvider(
            listOf(LlmResponse.Err("API_ERROR", "Service unavailable", true))
        )
        val planner = LlmPlanner(fakeProvider, registry)

        val plan = planner.plan("do something")

        assertFalse(plan.isSuccess)
        assertNotNull(plan.error)
        assertEquals("API_ERROR", plan.error!!.code)
    }

    // ---- Helpers ---------------------------------------------------------

    private fun registerCameraPlugin() {
        val plugin = object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "camera-plugin",
                name = "Camera",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Camera plugin",
                provider = defaultProvider,
                entry = "com.mcos.plugin.camera.CameraPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = "camera.capture",
                        version = "1.0",
                        title = "Capture Photo",
                        description = "Take a photo",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", buildJsonObject {
                                put("quality", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Photo quality"))
                                })
                                put("flash", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Flash mode"))
                                })
                                put("facing", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Camera direction"))
                                })
                            })
                        }
                    ),
                    CommandManifestEntry(
                        id = "camera.scan",
                        version = "1.0",
                        title = "Scan QR Code",
                        description = "Scan a QR/barcode",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = JsonObject(emptyMap())
                    )
                )
            )
            override fun handlers(): Map<String, CommandHandler> = emptyMap()
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }

    private fun registerSystemPlugin() {
        val plugin = object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "system-plugin",
                name = "System",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "System plugin",
                provider = defaultProvider,
                entry = "com.mcos.plugin.system.SystemPlugin",
                commands = listOf(
                    CommandManifestEntry(
                        id = "sys.notify",
                        version = "1.0",
                        title = "Notify",
                        description = "Show a notification",
                        sideEffectClass = SideEffectClass.control,
                        inputSchema = buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("required", buildJsonArray {
                                add(JsonPrimitive("title"))
                                add(JsonPrimitive("body"))
                            })
                            put("properties", buildJsonObject {
                                put("title", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                })
                                put("body", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                })
                            })
                        }
                    )
                )
            )
            override fun handlers(): Map<String, CommandHandler> = emptyMap()
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
        }
        registry.register(plugin)
    }
}

/**
 * Fake [LlmProvider] that returns pre-configured responses in order.
 */
class FakeLlmProvider(
    private val responses: List<LlmResponse>,
    private val defaultResponse: LlmResponse = LlmResponse.Err("NO_RESPONSE", "No responses configured", false)
) : LlmProvider {
    private var callIndex = 0

    override suspend fun chat(messages: List<ChatMessage>): LlmResponse {
        val response = if (callIndex < responses.size) {
            responses[callIndex]
        } else {
            defaultResponse
        }
        callIndex++
        return response
    }
}
