package com.morainet.mcos.plugin.iot

import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.HttpRequest
import com.morainet.mcos.sdk.HttpResponse
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.NetService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Conformance tests for [IotPlugin] against a recording fake
 * [NetService]: every hub interaction is asserted as an exact
 * method/URL/body triple, plus the validation and error-mapping matrix
 * (04-plugin-sdk.md 9: the runtime only sees command IDs; the hub
 * protocol stays inside the plugin).
 */
class IotPluginTest {

    /** Captures every request; answers from a scripted handler. */
    private class FakeNet(
        private val respond: (method: String, url: String, body: String?) -> HttpResponse,
    ) : NetService {
        data class Call(val method: String, val url: String, val body: String?, val headers: Map<String, String>)

        val calls = mutableListOf<Call>()

        override suspend fun request(req: HttpRequest): HttpResponse {
            val body = req.body?.decodeToString()
            calls.add(Call(req.method, req.url, body, req.headers))
            return respond(req.method, req.url, body)
        }
    }

    /** HostServices with only the surfaces the IoT plugin touches. */
    private class FakeHostServices(override val net: NetService) : HostServices {
        override val files get() = error("FileService not available")
        override val ui get() = error("UiService not available")
        override val secureStore get() = error("SecureStore not available")
        override val clock get() = error("Clock not available")
        override val json get() = error("JsonService not available")
        override val memory get() = error("MemoryFacade not available")
    }

    private val haStatesBody = """
        [
          {"entity_id":"light.living_room","state":"on",
           "attributes":{"friendly_name":"Living Room"}},
          {"entity_id":"climate.air_condition","state":"cool",
           "attributes":{"friendly_name":"Air Condition"}},
          {"entity_id":"scene.movie","state":"scening",
           "attributes":{"friendly_name":"Movie"}}
        ]
    """.trimIndent()

    private lateinit var net: FakeNet
    private lateinit var services: FakeHostServices
    private lateinit var plugin: IotPlugin

    @BeforeTest
    fun setUp() = runBlocking {
        net = FakeNet { _, _, _ -> HttpResponse(200, body = "[]".encodeToByteArray()) }
        services = FakeHostServices(net)
        plugin = IotPlugin(
            HomeAssistantConfig(
                baseUrl = "https://ha.example.com/",
                tokenSecretKey = "mcos.iot.ha.token",
            ),
        )
        plugin.onLoad(services)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        plugin.onUnload()
    }

    /** Point the plugin at a fresh scripted transport (re-loads it). */
    private suspend fun reloadWith(fresh: FakeNet) {
        net = fresh
        plugin.onLoad(FakeHostServices(fresh))
    }

    private fun ctx(commandId: String, args: JsonObject = JsonObject(emptyMap())): ExecutionContext =
        ExecutionContext(runId = "run_test", commandId = commandId, args = args, services = services)

    private fun argsOf(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

    private fun okValue(result: CommandResult.Ok): JsonObject = result.value.jsonObject

    private fun requestBody(call: FakeNet.Call): JsonObject =
        Json.parseToJsonElement(call.body!!).jsonObject

    // ═══════════════════════════════════════════════════════════════
    // I1-I3: Manifest
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `I1-manifest has correct id, namespaces and command surface`() {
        assertEquals("mcos.plugin.iot", plugin.manifest.id)
        assertEquals(setOf("home", "iot"), plugin.manifest.namespaces.toSet())
        assertEquals(
            setOf(
                "home.device.list", "home.light.on", "home.light.off", "home.light.set",
                "home.scene.apply", "home.scene.movie", "home.scene.sleep", "iot.ac.set",
            ),
            plugin.manifest.commands.map { it.id }.toSet(),
        )
        assertEquals(plugin.manifest.commands.size, plugin.handlers().size)
    }

    @Test
    fun `I2-manifest declares the concrete hub network scope`() {
        val scopes = plugin.manifest.permissions.map { it.name }
        assertEquals(listOf("network.ha.example.com"), scopes)
    }

    @Test
    fun `I3-unconfigured plugin declares no network scope and fails honest`() = runBlocking {
        val bare = IotPlugin()
        assertTrue(bare.manifest.permissions.isEmpty())
        bare.onLoad(FakeHostServices(net))
        val e = assertFailsWith<McosException> {
            bare.handlers()["home.device.list"]!!.invoke(ctx("home.device.list"))
        }
        assertEquals("UNAVAILABLE", e.code)
        assertTrue(net.calls.isEmpty(), "no hub → zero egress")
    }

    // ═══════════════════════════════════════════════════════════════
    // I4-I7: home.device.list
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `I4-device list normalizes hub states`() = runBlocking {
        reloadWith(FakeNet { _, _, _ -> HttpResponse(200, body = haStatesBody.encodeToByteArray()) })

        val result = plugin.handlers()["home.device.list"]!!.invoke(ctx("home.device.list")) as CommandResult.Ok

        assertEquals("GET", net.calls.single().method)
        assertEquals("https://ha.example.com/api/states", net.calls.single().url)
        assertEquals(
            "Bearer {{secret.mcos.iot.ha.token}}",
            net.calls.single().headers["Authorization"],
        )
        val value = okValue(result)
        assertEquals(3, value["count"]!!.jsonPrimitive.int)
        val first = value["devices"]!!.jsonArray[0].jsonObject
        assertEquals("light.living_room", first["id"]!!.jsonPrimitive.content)
        assertEquals("light", first["domain"]!!.jsonPrimitive.content)
        assertEquals("Living Room", first["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `I5-device list domain filter keeps only matching domain`() = runBlocking {
        reloadWith(FakeNet { _, _, _ -> HttpResponse(200, body = haStatesBody.encodeToByteArray()) })

        val result = plugin.handlers()["home.device.list"]!!
            .invoke(ctx("home.device.list", argsOf("domain" to JsonPrimitive("climate")))) as CommandResult.Ok

        val value = okValue(result)
        assertEquals(1, value["count"]!!.jsonPrimitive.int)
        assertEquals(
            "climate.air_condition",
            value["devices"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `I6-device list hub 401 maps to PERMISSION_DENIED`() = runBlocking {
        reloadWith(
            FakeNet { _, _, _ -> HttpResponse(401, body = "{\"message\":\"Unauthorized\"}".encodeToByteArray()) },
        )

        val e = assertFailsWith<McosException> {
            plugin.handlers()["home.device.list"]!!.invoke(ctx("home.device.list"))
        }
        assertEquals("PERMISSION_DENIED", e.code)
        assertEquals(false, e.retryable)
    }

    @Test
    fun `I7-device list hub 503 maps to retryable UNAVAILABLE`() = runBlocking {
        reloadWith(FakeNet { _, _, _ -> HttpResponse(503, body = "upstream down".encodeToByteArray()) })

        val e = assertFailsWith<McosException> {
            plugin.handlers()["home.device.list"]!!.invoke(ctx("home.device.list"))
        }
        assertEquals("UNAVAILABLE", e.code)
        assertEquals(true, e.retryable)
    }

    // ═══════════════════════════════════════════════════════════════
    // I8-I12: lights
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `I8-light on posts turn_on with entity`() = runBlocking {
        val result = plugin.handlers()["home.light.on"]!!
            .invoke(ctx("home.light.on", argsOf("id" to JsonPrimitive("living-room")))) as CommandResult.Ok

        val call = net.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://ha.example.com/api/services/light/turn_on", call.url)
        assertEquals("living-room", requestBody(call)["entity_id"]!!.jsonPrimitive.content)
        assertEquals("light.turn_on", okValue(result)["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `I9-light off posts turn_off`() = runBlocking {
        plugin.handlers()["home.light.off"]!!
            .invoke(ctx("home.light.off", argsOf("id" to JsonPrimitive("living-room"))))

        assertEquals(
            "https://ha.example.com/api/services/light/turn_off",
            net.calls.single().url,
        )
    }

    @Test
    fun `I10-light set maps 0_8 brightness to hub 0_255 scale`() = runBlocking {
        val result = plugin.handlers()["home.light.set"]!!.invoke(
            ctx(
                "home.light.set",
                argsOf(
                    "id" to JsonPrimitive("living-room"),
                    "on" to JsonPrimitive(true),
                    "brightness" to JsonPrimitive(0.8),
                    "meta" to JsonNull,
                ),
            ),
        ) as CommandResult.Ok

        val call = net.calls.single()
        assertEquals("https://ha.example.com/api/services/light/turn_on", call.url)
        assertEquals(204, requestBody(call)["brightness"]!!.jsonPrimitive.doubleOrNull!!.toInt())
        assertEquals("living-room", okValue(result)["entity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `I11-light set on=false posts turn_off regardless of brightness`() = runBlocking {
        plugin.handlers()["home.light.set"]!!.invoke(
            ctx(
                "home.light.set",
                argsOf(
                    "id" to JsonPrimitive("living-room"),
                    "on" to JsonPrimitive(false),
                    "brightness" to JsonPrimitive(1.0),
                ),
            ),
        )

        assertEquals(
            "https://ha.example.com/api/services/light/turn_off",
            net.calls.single().url,
        )
    }

    @Test
    fun `I12-light set rejects out-of-range brightness and missing id`() = runBlocking {
        val e1 = assertFailsWith<McosException> {
            plugin.handlers()["home.light.set"]!!.invoke(
                ctx(
                    "home.light.set",
                    argsOf(
                        "id" to JsonPrimitive("living-room"),
                        "brightness" to JsonPrimitive(1.5),
                    ),
                ),
            )
        }
        assertEquals("SCHEMA_VIOLATION", e1.code)

        val e2 = assertFailsWith<McosException> {
            plugin.handlers()["home.light.set"]!!.invoke(ctx("home.light.set"))
        }
        assertEquals("SCHEMA_VIOLATION", e2.code)
        assertTrue(net.calls.isEmpty(), "validation failures never reach the hub")
    }

    // ═══════════════════════════════════════════════════════════════
    // I13-I15: scenes
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `I13-scene apply activates scene entity`() = runBlocking {
        val result = plugin.handlers()["home.scene.apply"]!!
            .invoke(ctx("home.scene.apply", argsOf("name" to JsonPrimitive("focus")))) as CommandResult.Ok

        val call = net.calls.single()
        assertEquals("https://ha.example.com/api/services/scene/turn_on", call.url)
        assertEquals("scene.focus", requestBody(call)["entity_id"]!!.jsonPrimitive.content)
        assertEquals("scene.focus", okValue(result)["entity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `I14-scene movie and sleep hit their doc-named entities`() = runBlocking {
        plugin.handlers()["home.scene.movie"]!!.invoke(ctx("home.scene.movie"))
        plugin.handlers()["home.scene.sleep"]!!.invoke(ctx("home.scene.sleep"))

        assertEquals(
            listOf("scene.movie", "scene.sleep"),
            net.calls.map { requestBody(it)["entity_id"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `I15-scene apply blank name is a schema violation`() = runBlocking {
        val e = assertFailsWith<McosException> {
            plugin.handlers()["home.scene.apply"]!!
                .invoke(ctx("home.scene.apply", argsOf("name" to JsonPrimitive(" "))))
        }
        assertEquals("SCHEMA_VIOLATION", e.code)
    }

    // ═══════════════════════════════════════════════════════════════
    // I16-I18: iot.ac.set
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `I16-ac set powers on then sets temperature`() = runBlocking {
        val result = plugin.handlers()["iot.ac.set"]!!.invoke(
            ctx(
                "iot.ac.set",
                argsOf(
                    "name" to JsonPrimitive("air-condition"),
                    "power" to JsonPrimitive(true),
                    "tempC" to JsonPrimitive(26),
                ),
            ),
        ) as CommandResult.Ok

        assertEquals(2, net.calls.size)
        assertEquals("https://ha.example.com/api/services/climate/turn_on", net.calls[0].url)
        assertEquals("https://ha.example.com/api/services/climate/set_temperature", net.calls[1].url)
        assertEquals(
            "climate.air-condition",
            requestBody(net.calls[1])["entity_id"]!!.jsonPrimitive.content,
        )
        assertEquals(26.0, requestBody(net.calls[1])["temperature"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(
            "climate.turn_on+set_temperature",
            okValue(result)["action"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `I17-ac set power false turns off without temperature call`() = runBlocking {
        val result = plugin.handlers()["iot.ac.set"]!!.invoke(
            ctx(
                "iot.ac.set",
                argsOf(
                    "name" to JsonPrimitive("air-condition"),
                    "power" to JsonPrimitive(false),
                    "tempC" to JsonPrimitive(26),
                ),
            ),
        ) as CommandResult.Ok

        assertEquals(1, net.calls.size)
        assertEquals("https://ha.example.com/api/services/climate/turn_off", net.calls[0].url)
        assertEquals("climate.turn_off", okValue(result)["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `I18-ac set validates tempC range and unicode names pass through`() = runBlocking {
        val e = assertFailsWith<McosException> {
            plugin.handlers()["iot.ac.set"]!!.invoke(
                ctx(
                    "iot.ac.set",
                    argsOf(
                        "name" to JsonPrimitive("air-condition"),
                        "tempC" to JsonPrimitive(40),
                    ),
                ),
            )
        }
        assertEquals("SCHEMA_VIOLATION", e.code)

        // 02-command-protocol.md: DSL string literals carry raw UTF-8 —
        // `iot.ac.set(name="空调")` must reach the hub unharmed.
        plugin.handlers()["iot.ac.set"]!!.invoke(
            ctx("iot.ac.set", argsOf("name" to JsonPrimitive("空调")),
            ),
        )
        assertEquals("climate.空调", requestBody(net.calls.single())["entity_id"]!!.jsonPrimitive.content)
    }
}
