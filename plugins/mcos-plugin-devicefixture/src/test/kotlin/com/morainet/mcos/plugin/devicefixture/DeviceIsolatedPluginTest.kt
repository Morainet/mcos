package com.morainet.mcos.plugin.devicefixture

import com.morainet.mcos.sdk.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * JVM semantics of the device fixture: command ids/shapes, echo round-trip,
 * and park's marker-before-sleep ordering (the kill-mid-run sync point the
 * on-device suite depends on). The transport itself is verified by
 * `BinderIsolationDeviceTest` on real hardware.
 */
class DeviceIsolatedPluginTest {

    private val plugin = DeviceIsolatedPlugin()

    private class FixtureHostServices(sandboxOverride: SandboxFileService? = null) : HostServices {
        override val sandbox: SandboxFileService? = sandboxOverride
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
            override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            override fun monotonicMs(): Long = System.currentTimeMillis()
        }
        override val json: JsonService get() = error("not available")
    }

    @Test
    fun `DF1-manifest declares echo and park, both read class`() {
        assertEquals(DeviceIsolatedPlugin.ID, plugin.manifest.id)
        assertEquals("com.morainet.mcos.plugin.devicefixture.DeviceIsolatedPlugin", plugin.manifest.entry)
        val commands = plugin.manifest.commands.map { it.id to it.sideEffectClass }
        assertEquals(
            listOf(
                "mcos.plugin.devicefixture.echo" to SideEffectClass.read,
                "mcos.plugin.devicefixture.park" to SideEffectClass.read,
            ),
            commands,
        )
    }

    @Test
    fun `DF2-echo round-trips the message`() = runBlocking {
        val result = plugin.handlers()["mcos.plugin.devicefixture.echo"]!!.invoke(
            ExecutionContext(
                runId = "df-1",
                commandId = "mcos.plugin.devicefixture.echo",
                args = buildJsonObject { put("message", JsonPrimitive("from-test")) },
                services = FixtureHostServices(DirectorySandbox(Files.createTempDirectory("df-sandbox"))),
            ),
        )
        val value = (result as CommandResult.Ok).value!!.jsonObject
        assertEquals("from-test", value["message"]!!.jsonPrimitive.content)
        // /proc does not exist on a desktop JVM — pid is honestly null there.
        assertTrue(value["pid"] is JsonNull)
    }

    @Test
    fun `DF3-park writes the marker before returning`() = runBlocking {
        val sandbox = DirectorySandbox(Files.createTempDirectory("df-sandbox"))
        val result = plugin.handlers()["mcos.plugin.devicefixture.park"]!!.invoke(
            ExecutionContext(
                runId = "df-2",
                commandId = "mcos.plugin.devicefixture.park",
                args = buildJsonObject { put("seconds", JsonPrimitive(0)) },
                services = FixtureHostServices(sandbox),
            ),
        )
        assertTrue(result is CommandResult.Ok)
        val marker = sandbox.read(DeviceIsolatedPlugin.PARK_MARKER)
        assertNotNull(marker)
        val text = String(marker)
        assertTrue(text.startsWith("pid="), "marker should carry the handler pid: $text")
        assertTrue("seconds=0.0" in text, "marker should carry the park duration: $text")
    }

    @Test
    fun `DF4-park without a sandbox capability still returns Ok`() = runBlocking {
        // A host with no sandbox degrades honestly — the marker hop is
        // skipped, the sleep and the result still happen.
        val result = plugin.handlers()["mcos.plugin.devicefixture.park"]!!.invoke(
            ExecutionContext(
                runId = "df-3",
                commandId = "mcos.plugin.devicefixture.park",
                args = JsonObject(emptyMap()),
                services = FixtureHostServices(),
            ),
        )
        assertTrue(result is CommandResult.Ok)
    }
}
