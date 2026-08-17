package com.morainet.mcos.plugin.camera

import com.morainet.mcos.sdk.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Camera plugin — camera.capture, camera.scan.
 * Matches [04-plugin-sdk.md 17].
 */
class CameraPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "mcos.plugin.camera",
        name = "Camera Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "Capture and scan using device cameras",
        provider = ProviderInfo("MCOS", "https://github.com/mcos-org"),
        entry = "com.morainet.mcos.plugin.camera.CameraPlugin",
        permissions = listOf(
            PermissionEntry("android", "android.permission.CAMERA", "Take photos and scan codes")
        ),
        commands = listOf(
            CommandManifestEntry(
                id = "camera.capture", version = "1.0.0",
                title = "Capture Photo",
                description = "Takes a photo using the default rear camera",
                sideEffectClass = SideEffectClass.write,
                permissions = listOf(
                    PermissionEntry("android", "android.permission.CAMERA", "Take photos")
                ),
                timeoutMs = 30000,
                examples = listOf("camera.capture()", """camera.capture(facing="front")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("facing", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("rear"))
                                add(JsonPrimitive("front"))
                            })
                            put("description", JsonPrimitive("Camera facing direction (default: rear)"))
                        })
                        put("flash", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("auto"))
                                add(JsonPrimitive("on"))
                                add(JsonPrimitive("off"))
                            })
                            put("description", JsonPrimitive("Flash mode (default: auto)"))
                        })
                        put("quality", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(100))
                            put("description", JsonPrimitive("JPEG quality 1-100 (default: 90)"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "camera.scan", version = "1.0.0",
                title = "Scan Code",
                description = "Scan QR/barcode and return decoded content",
                sideEffectClass = SideEffectClass.read,
                permissions = listOf(
                    PermissionEntry("android", "android.permission.CAMERA", "Scan codes")
                ),
                timeoutMs = 30000,
                examples = listOf("camera.scan()", """camera.scan(format="qr")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("format", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("auto"))
                                add(JsonPrimitive("qr"))
                                add(JsonPrimitive("barcode"))
                                add(JsonPrimitive("ean13"))
                                add(JsonPrimitive("ean8"))
                                add(JsonPrimitive("code128"))
                                add(JsonPrimitive("datamatrix"))
                            })
                            put("description", JsonPrimitive("Code format to scan (default: auto)"))
                        })
                    })
                }
            )
        ),
        namespaces = listOf("camera")
    )

    private var services: HostServices? = null

    override suspend fun onLoad(services: HostServices) {
        this.services = services
    }

    override suspend fun onUnload() {
        this.services = null
    }

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "camera.capture" to CaptureHandler(),
        "camera.scan" to ScanHandler()
    )

    // ─── Handlers ────────────────────────────────────────────────────────

    inner class CaptureHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val facing = args["facing"]?.jsonPrimitive?.content ?: "rear"
            val flash = args["flash"]?.jsonPrimitive?.content ?: "auto"
            val quality = args["quality"]?.jsonPrimitive?.intOrNull ?: 90

            // Validate enum values
            if (facing !in setOf("rear", "front")) {
                throw McosException(
                    "SCHEMA_VIOLATION",
                    "facing must be 'rear' or 'front', got '$facing'"
                )
            }
            if (flash !in setOf("auto", "on", "off")) {
                throw McosException(
                    "SCHEMA_VIOLATION",
                    "flash must be 'auto', 'on', or 'off', got '$flash'"
                )
            }

            // On Android, this would use CameraX/ACTION_IMAGE_CAPTURE via HostServices.
            // For JVM MVP, return a structured result representing the capture request.
            val s = services
            if (s != null) {
                val result = s.ui.startActivityForResult(
                    mapOf(
                        "action" to "ACTION_IMAGE_CAPTURE",
                        "facing" to facing,
                        "flash" to flash,
                        "quality" to quality.toString()
                    )
                )
                if (result != null) {
                    return CommandResult.Ok(
                        value = buildJsonObject {
                            put("status", JsonPrimitive("captured"))
                            put("uri", JsonPrimitive(result["uri"] ?: "content://mcos/camera/captured"))
                            put("facing", JsonPrimitive(facing))
                            put("flash", JsonPrimitive(flash))
                        },
                        artifacts = listOf(
                            Artifact(
                                type = "image",
                                uri = result["uri"] ?: "content://mcos/camera/captured",
                                mimeType = "image/jpeg"
                            )
                        )
                    )
                }
                // User cancelled
                return CommandResult.Err(
                    code = "CANCELLED",
                    message = "Photo capture was cancelled by user",
                    retryable = false
                )
            }

            // No host services — JVM MVP stub
            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("captured"))
                    put("uri", JsonPrimitive("content://mcos/camera/captured"))
                    put("facing", JsonPrimitive(facing))
                    put("flash", JsonPrimitive(flash))
                },
                artifacts = listOf(
                    Artifact(
                        type = "image",
                        uri = "content://mcos/camera/captured",
                        mimeType = "image/jpeg"
                    )
                )
            )
        }
    }

    inner class ScanHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val format = args["format"]?.jsonPrimitive?.content ?: "auto"

            val validFormats = setOf("auto", "qr", "barcode", "ean13", "ean8", "code128", "datamatrix")
            if (format !in validFormats) {
                throw McosException(
                    "SCHEMA_VIOLATION",
                    "format must be one of ${validFormats.joinToString(", ")}, got '$format'"
                )
            }

            // On Android, this would use ML Kit Barcode Scanning via HostServices.
            val s = services
            if (s != null) {
                val result = s.ui.startActivityForResult(
                    mapOf("action" to "ACTION_SCAN_BARCODE", "format" to format)
                )
                if (result != null) {
                    return CommandResult.Ok(
                        value = buildJsonObject {
                            put("status", JsonPrimitive("scanned"))
                            put("content", JsonPrimitive(result["content"] ?: ""))
                            put("format", JsonPrimitive(result["format"] ?: format))
                        }
                    )
                }
                return CommandResult.Err(
                    code = "CANCELLED",
                    message = "Barcode scanning was cancelled by user",
                    retryable = false
                )
            }

            // JVM MVP stub
            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("scanned"))
                    put("content", JsonPrimitive("stub-scan-result"))
                    put("format", JsonPrimitive(format))
                }
            )
        }
    }
}
