package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [DeviceSemantics] — the `x-mcos-semantic: "device"` schema scan
 * (02 §5.3 / 04 §4.5) supplying the args-driven half of 03 §8.5 device
 * resolution, plus the registry-backed [Executor.deviceSemanticIds] query.
 */
class DeviceSemanticsTest {

    private fun prop(semantic: String? = null): JsonObject = buildJsonObject {
        put("type", "string")
        semantic?.let { put("x-mcos-semantic", it) }
    }

    private fun schema(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        putJsonObject("properties") {
            for ((name, p) in props) put(name, p)
        }
    }

    // ─── Pure scan ───────────────────────────────────────────────────────

    @Test
    fun `DS1-device-semantic property extracted from args`() {
        val schema = schema("deviceId" to prop("device"))
        val args = JsonObject(mapOf("deviceId" to JsonPrimitive("living-room")))

        assertEquals(listOf("living-room"), DeviceSemantics.deviceIds(schema, args))
    }

    @Test
    fun `DS2-other semantics ignored`() {
        val schema = schema(
            "who" to prop("contact"),
            "when" to prop("date-or-relative"),
            "deviceId" to prop("device"),
        )
        val args = JsonObject(
            mapOf(
                "who" to JsonPrimitive("alice"),
                "when" to JsonPrimitive("today"),
                "deviceId" to JsonPrimitive("ac-1"),
            )
        )

        assertEquals(listOf("ac-1"), DeviceSemantics.deviceIds(schema, args))
    }

    @Test
    fun `DS3-missing blank or non-string args ignored`() {
        val schema = schema(
            "a" to prop("device"),
            "b" to prop("device"),
            "c" to prop("device"),
        )
        val args = JsonObject(
            mapOf(
                "b" to JsonPrimitive("   "),
                "c" to JsonPrimitive(7),
            )
        )

        assertTrue(DeviceSemantics.deviceIds(schema, args).isEmpty())
    }

    @Test
    fun `DS4-multiple device fields all collected`() {
        val schema = schema("left" to prop("device"), "right" to prop("device"))
        val args = JsonObject(
            mapOf("left" to JsonPrimitive("lamp-1"), "right" to JsonPrimitive("lamp-2"))
        )

        val ids = DeviceSemantics.deviceIds(schema, args)
        assertEquals(setOf("lamp-1", "lamp-2"), ids.toSet())
    }

    @Test
    fun `DS5-schema without properties yields empty`() {
        val ids = DeviceSemantics.deviceIds(
            buildJsonObject { put("type", "object") },
            JsonObject(mapOf("deviceId" to JsonPrimitive("x"))),
        )
        assertTrue(ids.isEmpty())
    }

    // ─── Registry-backed query ───────────────────────────────────────────

    @Test
    fun `DS6-executor query resolves schema from the registry`() {
        val registry = CommandRegistry()
        val deviceSchema = schema("id" to prop("device"))
        registry.register(
            object : McosPlugin {
                override val manifest = PluginManifest(
                    id = "home", name = "home", version = "1.0.0",
                    minRuntimeVersion = "0.1.0",
                    description = "Test plugin",
                    provider = ProviderInfo("Test", "https://test.local"),
                    entry = "com.morainet.mcos.plugin.test.TestPlugin",
                    commands = listOf(
                        CommandManifestEntry(
                            id = "home.light.set", version = "1.0.0",
                            title = "set", description = "set",
                            sideEffectClass = SideEffectClass.read,
                            inputSchema = deviceSchema,
                        )
                    )
                )
                override suspend fun onLoad(services: HostServices) {}
                override suspend fun onUnload() {}
                override fun handlers(): Map<String, CommandHandler> =
                    mapOf(
                        "home.light.set" to object : CommandHandler {
                            override suspend fun invoke(ctx: ExecutionContext) =
                                CommandResult.Ok(JsonPrimitive("ok"))
                        }
                    )
            }
        )
        val executor = Executor(registry, ExecutorTest.StubHostServices(), SecurityConfig.permissive())

        assertEquals(
            listOf("living-room"),
            executor.deviceSemanticIds(
                "home.light.set",
                JsonObject(mapOf("id" to JsonPrimitive("living-room")))
            )
        )
    }

    @Test
    fun `DS7-executor query on unknown command yields empty`() {
        val executor = Executor(
            CommandRegistry(),
            ExecutorTest.StubHostServices(),
            SecurityConfig.permissive()
        )

        assertTrue(
            executor.deviceSemanticIds(
                "nope.missing",
                JsonObject(mapOf("id" to JsonPrimitive("x")))
            ).isEmpty()
        )
    }
}
