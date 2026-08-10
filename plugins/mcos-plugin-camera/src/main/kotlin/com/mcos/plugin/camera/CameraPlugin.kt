package com.mcos.plugin.camera

import com.mcos.sdk.*
import kotlinx.serialization.json.JsonPrimitive

/**
 * Camera plugin: camera.capture, camera.scan.
 * Matches [04-plugin-sdk.md §17].
 */
class CameraPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "mcos.plugin.camera",
        name = "Camera Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "Capture and scan using device cameras",
        provider = ProviderInfo("MCOS", "https://github.com/mcos-org"),
        entry = "com.mcos.plugin.camera.CameraPlugin",
        commands = listOf(
            CommandManifestEntry(
                id = "camera.capture", version = "1.0.0",
                title = "Capture photo", description = "Takes a photo using the default rear camera",
                sideEffectClass = SideEffectClass.write,
                examples = listOf("camera.capture()", """camera.capture(facing="front")""")
            ),
            CommandManifestEntry(
                id = "camera.scan", version = "1.0.0",
                title = "Scan code", description = "Scan QR/barcode",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("camera.scan()", """camera.scan(format="qr")""")
            )
        ),
        namespaces = listOf("camera")
    )

    override suspend fun onLoad(services: HostServices) {}
    override suspend fun onUnload() {}

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "camera.capture" to CaptureHandler,
        "camera.scan" to ScanHandler
    )
}

object CaptureHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: photo captured"))
    }
}

object ScanHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        return CommandResult.Ok(JsonPrimitive("stub: scanned"))
    }
}
