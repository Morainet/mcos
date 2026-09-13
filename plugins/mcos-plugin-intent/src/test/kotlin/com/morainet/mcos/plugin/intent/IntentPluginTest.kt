package com.morainet.mcos.plugin.intent

import com.morainet.mcos.sdk.AppFunctionService
import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.FileService
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.IntentRequest
import com.morainet.mcos.sdk.IntentResult
import com.morainet.mcos.sdk.IntentService
import com.morainet.mcos.sdk.JsonService
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.ResolveResult
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.UiService
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Deep-link / App Functions bridge tests
 * (02-command-protocol.md §12.2/§12.3/§12.5, 10-roadmap.md §5.4).
 *
 * **Note on the id gaps:** P4-P14 used to cover an `intent.start` command in
 * this plugin. That command was removed — §12.3 represents Intents as
 * `sys.intent.start` and §12.6 publishes the extras allowlist in the `sys`
 * plugin — so its coverage now lives in `SystemPluginTest` and the tests keep
 * their historical ids only for the cases that remain here.
 */
class IntentPluginTest {

    private lateinit var plugin: IntentPlugin

    @BeforeTest
    fun setUp() {
        plugin = IntentPlugin()
    }

    // ═══════════════════════════════════════════════════════════════
    // P1-P3: manifest
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P1-manifest identifies the bridge plugin`() {
        assertEquals("mcos.plugin.intent", plugin.manifest.id)
        assertEquals("Intent Plugin", plugin.manifest.name)
        assertEquals("1.0.0", plugin.manifest.version)
        assertEquals(listOf("deeplink", "appfn"), plugin.manifest.namespaces)
    }

    @Test
    fun `P2-handlers expose the two bridge commands`() {
        assertEquals(
            setOf("deeplink.open", "appfn.invoke"),
            plugin.handlers().keys,
        )
    }

    @Test
    fun `P3-manifest declares every command, and no Intent command`() {
        val commands = plugin.manifest.commands
        assertEquals(
            listOf("deeplink.open", "appfn.invoke"),
            commands.map { it.id },
        )
        commands.forEach { entry ->
            assertTrue(entry.inputSchema.isNotEmpty(), "${entry.id} must declare an input schema")
        }
        // The Intent command is the `sys` plugin's (§12.3/§12.6); carrying a
        // second copy here would mean two implementations of one protocol rule.
        assertTrue(
            commands.none { it.id == "intent.start" },
            "the Intent command belongs to mcos.plugin.system (02 §12.3/§12.6)",
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // P15-P18: deeplink.open
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P15-a deep link becomes an ACTION_VIEW intent`() = runBlocking {
        val services = RecordingIntentServices()
        val value = okValue(
            invoke(
                "deeplink.open",
                buildJsonObject {
                    put("uri", JsonPrimitive("myapp://profile/42"))
                    put("package", JsonPrimitive("com.myapp"))
                },
                services,
            )
        )
        assertEquals("opened", value["status"]!!.jsonPrimitive.content)

        val request = services.requests.single()
        assertEquals("android.intent.action.VIEW", request.action)
        assertEquals("myapp://profile/42", request.dataUri)
        assertEquals("com.myapp", request.packageName)
    }

    @Test
    fun `P16-missing uri is a schema violation`() = runBlocking {
        val ex = expectMcosException {
            invoke("deeplink.open", buildJsonObject { }, RecordingIntentServices())
        }
        assertEquals("SCHEMA_VIOLATION", ex.code)
    }

    @Test
    fun `P17-a uri without a scheme is refused before reaching the host`() = runBlocking {
        val services = RecordingIntentServices()
        val ex = expectMcosException {
            invoke(
                "deeplink.open",
                buildJsonObject { put("uri", JsonPrimitive("profile/42")) },
                services,
            )
        }
        assertEquals("SCHEMA_VIOLATION", ex.code)
        assertEquals("deeplink_uri_invalid", reasonOf(ex))
        assertEquals("/args/uri", pathOf(ex))
        assertTrue(services.requests.isEmpty(), "an unroutable uri must not reach the host")
    }

    @Test
    fun `P18-a host without an intent platform reports UNAVAILABLE`() = runBlocking {
        val ex = expectMcosException {
            invoke(
                "deeplink.open",
                buildJsonObject { put("uri", JsonPrimitive("myapp://x")) },
                BareHostServices(),
            )
        }
        assertEquals("UNAVAILABLE", ex.code)
    }

    // ═══════════════════════════════════════════════════════════════
    // P19-P24: appfn.invoke
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `P19-appfn_invoke passes the decoded target and args to the host`() = runBlocking {
        val services = RecordingIntentServices(appFnResult = buildJsonObject { put("id", JsonPrimitive("n-1")) })
        val value = okValue(
            invoke(
                "appfn.invoke",
                buildJsonObject {
                    put("package", JsonPrimitive("com.example.notes"))
                    put("function", JsonPrimitive("createNote"))
                    put("args", buildJsonObject { put("title", JsonPrimitive("Hi")) })
                },
                services,
            )
        )

        assertEquals("com.example.notes", value["package"]!!.jsonPrimitive.content)
        assertEquals("createNote", value["function"]!!.jsonPrimitive.content)
        assertEquals("n-1", value["result"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        // §12.5 id form travels with the result for audit correlation.
        assertEquals(
            "sys.appfn.com_example_notes.createNote",
            value["commandId"]!!.jsonPrimitive.content,
        )

        val (pkg, function, args) = services.appFnCalls.single()
        assertEquals("com.example.notes", pkg)
        assertEquals("createNote", function)
        assertEquals(JsonPrimitive("Hi"), args["title"])
    }

    @Test
    fun `P20-a dotted function name is rejected as a schema violation`() = runBlocking {
        val ex = expectMcosException {
            invoke(
                "appfn.invoke",
                buildJsonObject {
                    put("package", JsonPrimitive("com.example.notes"))
                    put("function", JsonPrimitive("notes.create"))
                },
                RecordingIntentServices(),
            )
        }
        assertEquals("SCHEMA_VIOLATION", ex.code)
        assertEquals("appfn_target_invalid", reasonOf(ex))
    }

    @Test
    fun `P21-missing package is a schema violation`() = runBlocking {
        val ex = expectMcosException {
            invoke(
                "appfn.invoke",
                buildJsonObject { put("function", JsonPrimitive("createNote")) },
                RecordingIntentServices(),
            )
        }
        assertEquals("SCHEMA_VIOLATION", ex.code)
    }

    @Test
    fun `P22-args that are not an object are rejected`() = runBlocking {
        val ex = expectMcosException {
            invoke(
                "appfn.invoke",
                buildJsonObject {
                    put("package", JsonPrimitive("com.example.notes"))
                    put("function", JsonPrimitive("createNote"))
                    put("args", JsonPrimitive("nope"))
                },
                RecordingIntentServices(),
            )
        }
        assertEquals("SCHEMA_VIOLATION", ex.code)
        assertEquals("appfn_args_not_an_object", reasonOf(ex))
    }

    @Test
    fun `P23-a host without App Functions reports UNAVAILABLE`() = runBlocking {
        val ex = expectMcosException {
            invoke(
                "appfn.invoke",
                buildJsonObject {
                    put("package", JsonPrimitive("com.example.notes"))
                    put("function", JsonPrimitive("createNote"))
                },
                BareHostServices(),
            )
        }
        assertEquals("UNAVAILABLE", ex.code)
    }

    @Test
    fun `P24-a bridge failure surfaces instead of a fabricated result`() = runBlocking {
        val services = RecordingIntentServices(
            appFnFailure = McosException("PERMISSION_DENIED", "unknown app function"),
        )
        val ex = expectMcosException {
            invoke(
                "appfn.invoke",
                buildJsonObject {
                    put("package", JsonPrimitive("com.example.notes"))
                    put("function", JsonPrimitive("createNote"))
                },
                services,
            )
        }
        assertEquals("PERMISSION_DENIED", ex.code)
    }

    @Test
    fun `P25-after unload the bridge reports UNAVAILABLE, never a fake success`() = runBlocking {
        val services = RecordingIntentServices()
        plugin.onLoad(services)
        plugin.onUnload()

        val handler = plugin.handlers()["deeplink.open"]!!
        val ex = expectMcosException {
            handler.invoke(
                ExecutionContext(
                    runId = "r",
                    commandId = "deeplink.open",
                    args = buildJsonObject { put("uri", JsonPrimitive("myapp://x")) },
                    services = services,
                )
            )
        }
        assertEquals("UNAVAILABLE", ex.code)
        assertTrue(services.requests.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════

    private suspend fun invoke(commandId: String, args: JsonObject, services: HostServices): CommandResult {
        plugin.onLoad(services)
        val handler = plugin.handlers()[commandId] ?: error("no handler for $commandId")
        return handler.invoke(
            ExecutionContext(
                runId = "test-$commandId",
                commandId = commandId,
                args = args,
                services = services,
            )
        )
    }

    private fun okValue(result: CommandResult): JsonObject =
        assertIs<CommandResult.Ok>(result).value.jsonObject

    private fun reasonOf(ex: McosException): String? =
        ex.details["reason"]?.jsonPrimitive?.content

    private fun pathOf(ex: McosException): String? =
        ex.details["path"]?.jsonPrimitive?.content

    private suspend fun expectMcosException(block: suspend () -> Unit): McosException = try {
        block()
        fail("expected McosException")
    } catch (e: McosException) {
        e
    }
}

/**
 * [HostServices] with the two launch bridges wired to recording fakes.
 * A `contract` failure lets one test prove a bridge error is surfaced rather
 * than swallowed into a fabricated Ok.
 */
private class RecordingIntentServices(
    private val outcome: IntentResult = IntentResult(started = true, resolvedPackage = "com.target.app"),
    private val appFnResult: JsonElement = JsonPrimitive("fn-result"),
    private val appFnFailure: McosException? = null,
) : HostServices {

    val requests = mutableListOf<IntentRequest>()
    val appFnCalls = mutableListOf<Triple<String, String, JsonObject>>()

    override val intents: IntentService? = object : IntentService {
        override suspend fun start(request: IntentRequest): IntentResult {
            requests += request
            return outcome
        }
    }

    override val appFunctions: AppFunctionService? = object : AppFunctionService {
        override suspend fun invoke(packageName: String, function: String, args: JsonObject): JsonElement {
            appFnCalls += Triple(packageName, function, args)
            appFnFailure?.let { throw it }
            return appFnResult
        }
    }

    override val files: FileService get() = error("not available")
    override val net: NetService get() = error("not available")
    override val ui: UiService get() = error("not available")
    override val secureStore: SecureStore get() = error("not available")
    override val clock: Clock get() = error("not available")
    override val json: JsonService get() = error("not available")
    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? = null
        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult =
            ResolveResult.NotFound()
    }
}

/** [HostServices] whose optional capabilities are all absent (plain-JVM host). */
private class BareHostServices : HostServices {
    override val files: FileService get() = error("not available")
    override val net: NetService get() = error("not available")
    override val ui: UiService get() = error("not available")
    override val secureStore: SecureStore get() = error("not available")
    override val clock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        override fun monotonicMs(): Long = System.currentTimeMillis()
    }
    override val json: JsonService get() = error("not available")
    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? = null
        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult =
            ResolveResult.NotFound()
    }
}
