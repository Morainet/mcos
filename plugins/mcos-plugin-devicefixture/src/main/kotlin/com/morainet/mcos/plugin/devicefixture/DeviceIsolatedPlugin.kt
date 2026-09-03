package com.morainet.mcos.plugin.devicefixture

import com.morainet.mcos.sdk.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-device process-isolation verification fixture (NOT a shipping plugin).
 *
 * `BinderIsolationDeviceTest` (mcos-android-sdk androidTest) packs this class,
 * dexed via the `deviceFixtureDex` gradle task, into a signed `.mcos` and
 * installs it through the production `PluginInstaller`. Under
 * `processIsolation = true` its code then runs only in `:mcos_plugin`
 * ([08-security.md §8]), which is exactly what the test asserts.
 *
 * The two commands are deliberately `read`-class (no confirmation flow, no
 * network) — this fixture exercises the transport, not the policy:
 * - `devicefixture.echo` — instant round-trip; the result carries the pid the
 *   handler observed, so the test can prove the command ran in the plugin
 *   process (a different pid than the host).
 * - `devicefixture.park` — writes a marker through `ctx.services.sandbox`
 *   FIRST (crossing the real Binder facade — §8.3 namespacing on real disk),
 *   then sleeps. That gives the kill-mid-run test a deterministic sync point:
 *   once the marker exists the plugin process is provably inside the handler.
 */
class DeviceIsolatedPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = ID,
        name = "Device Isolation Fixture",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "On-device Binder isolation verification fixture — never ship",
        provider = ProviderInfo("MCOS", "https://github.com/Morainet/mcos"),
        entry = "com.morainet.mcos.plugin.devicefixture.DeviceIsolatedPlugin",
        commands = listOf(
            CommandManifestEntry(
                id = "$ID.echo",
                version = "1.0.0",
                title = "Echo",
                description = "Echo the message back with the handler's pid",
                sideEffectClass = SideEffectClass.read,
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("message", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                    })
                },
            ),
            CommandManifestEntry(
                id = "$ID.park",
                version = "1.0.0",
                title = "Park",
                description = "Write the sandbox entry marker, then sleep — the kill-mid-run sync point",
                sideEffectClass = SideEffectClass.read,
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("seconds", buildJsonObject {
                            put("type", JsonPrimitive("number"))
                        })
                    })
                },
            ),
        ),
    )

    override suspend fun onLoad(services: HostServices) {}

    override suspend fun onUnload() {}

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "$ID.echo" to EchoHandler,
        "$ID.park" to ParkHandler,
    )

    companion object {
        const val ID = "mcos.plugin.devicefixture"

        /** The sandbox marker `park` writes before sleeping. */
        const val PARK_MARKER = "park-entered.txt"
    }
}

/** Best-effort own-pid: `/proc` on Android, null on a desktop JVM. */
internal fun selfPidOrNull(): Int? = runCatching {
    File("/proc/self/stat").readText().substringBefore(' ').toInt()
}.getOrNull()

object EchoHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val message = ctx.args.jsonObject["message"]?.jsonPrimitive?.content ?: ""
        return CommandResult.Ok(buildJsonObject {
            put("message", JsonPrimitive(message))
            put("pid", selfPidOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }
}

object ParkHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val seconds = ctx.args.jsonObject["seconds"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0
        // Facade hop first: the marker crossing the Binder boundary proves the
        // plugin-side HostServices proxy reaches the main-process sandbox
        // server (§8.3), and gives the test its deterministic sync point.
        ctx.services.sandbox?.write(
            DeviceIsolatedPlugin.PARK_MARKER,
            "pid=${selfPidOrNull()} seconds=$seconds".toByteArray(),
        )
        delay((seconds * 1000).toLong())
        return CommandResult.Ok(buildJsonObject {
            put("parked", JsonPrimitive(seconds))
            put("pid", selfPidOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }
}
