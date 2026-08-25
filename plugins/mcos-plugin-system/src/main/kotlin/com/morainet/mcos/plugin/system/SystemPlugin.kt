package com.morainet.mcos.plugin.system

import com.morainet.mcos.sdk.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * System commands plugin — sys.notify, sys.share, sys.clipboard, sys.openUrl, sys.intent.start, sys.vibrate,
 * sys.event.emit, plus sys.device.battery, sys.device.wifi, sys.device.screen, sys.device.volume,
 * sys.device.location, sys.device.brightness.
 * Matches [04-plugin-sdk.md 17].
 */
class SystemPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "mcos.plugin.system",
        name = "System Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "System-level commands: notify, share, clipboard, open URL, device info",
        provider = ProviderInfo("MCOS", "https://github.com/mcos-org"),
        entry = "com.morainet.mcos.plugin.system.SystemPlugin",
        permissions = listOf(
            PermissionEntry("android", "android.permission.VIBRATE", "Device vibration"),
            PermissionEntry("android", "android.permission.POST_NOTIFICATIONS", "System notifications"),
            PermissionEntry("android", "android.permission.ACCESS_FINE_LOCATION", "Device location"),
            PermissionEntry("android", "android.permission.WRITE_SETTINGS", "Modify system settings"),
        ),
        commands = listOf(
            CommandManifestEntry(
                id = "sys.notify", version = "1.0.0",
                title = "Send Notification",
                description = "Post a system notification with title and text",
                sideEffectClass = SideEffectClass.write,
                permissions = listOf(
                    PermissionEntry("android", "android.permission.POST_NOTIFICATIONS", "System notifications")
                ),
                examples = listOf("""sys.notify(title="MCOS", text="Done")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray {
                        add(JsonPrimitive("title"))
                        add(JsonPrimitive("text"))
                    })
                    put("properties", buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("maxLength", JsonPrimitive(100))
                        })
                        put("text", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("maxLength", JsonPrimitive(500))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "sys.share", version = "1.0.0",
                title = "Share Content",
                description = "Share text or URI via system share sheet",
                sideEffectClass = SideEffectClass.write,
                examples = listOf("""sys.share(text="Hello", uri="content://...")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(10000))
                        })
                        put("uri", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                        put("title", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(100))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "sys.clipboard", version = "1.0.0",
                title = "Clipboard",
                description = "Read or write system clipboard",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("""sys.clipboard(text="Copy this")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Text to copy. If omitted, reads clipboard."))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "sys.openUrl", version = "1.0.0",
                title = "Open URL",
                description = "Open a URL in default browser",
                sideEffectClass = SideEffectClass.network,
                examples = listOf("""sys.openUrl(url="https://example.com")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("url")) })
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "sys.intent.start", version = "1.0.0",
                title = "Start Intent",
                description = "Start a schematized Android Intent",
                sideEffectClass = SideEffectClass.write,
                examples = emptyList(),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("action")) })
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                        })
                        put("dataUri", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                        put("package", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "sys.vibrate", version = "1.0.0",
                title = "Vibrate",
                description = "Trigger device vibration for a duration",
                sideEffectClass = SideEffectClass.control,
                permissions = listOf(
                    PermissionEntry("android", "android.permission.VIBRATE", "Device vibration")
                ),
                examples = listOf("""sys.vibrate(duration=500)"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("duration", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(0))
                            put("maximum", JsonPrimitive(5000))
                            put("description", JsonPrimitive("Vibration duration in milliseconds (default 500)"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "sys.event.emit", version = "1.0.0",
                title = "Emit Event",
                description = "Publish an event onto the system event bus (03 §11); can trigger armed recipes (05 §9.2)",
                sideEffectClass = SideEffectClass.write,
                examples = listOf("""sys.event.emit(type="wifi.connected", payload={ssid: "Office"})"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("type")) })
                    put("properties", buildJsonObject {
                        put("type", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Dotted event type, e.g. wifi.connected"))
                        })
                        put("payload", buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("description", JsonPrimitive("Structured event payload delivered to subscribers"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "sys.device.battery", version = "1.0.0",
                title = "Battery Info",
                description = "Query device battery level, charging status, and temperature",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("sys.device.battery()"),
                inputSchema = JsonObject(emptyMap())
            ),
            CommandManifestEntry(
                id = "sys.device.wifi", version = "1.0.0",
                title = "Wi-Fi Info",
                description = "Query current Wi-Fi connection: SSID, signal strength, status",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("sys.device.wifi()"),
                inputSchema = JsonObject(emptyMap())
            ),
            CommandManifestEntry(
                id = "sys.device.screen", version = "1.0.0",
                title = "Screen Info",
                description = "Query display metrics: resolution, density, orientation",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("sys.device.screen()"),
                inputSchema = JsonObject(emptyMap())
            ),
            CommandManifestEntry(
                id = "sys.device.volume", version = "1.0.0",
                title = "Volume Info",
                description = "Query current volume levels for media, ring, and alarm streams",
                sideEffectClass = SideEffectClass.read,
                examples = listOf("sys.device.volume()"),
                inputSchema = JsonObject(emptyMap())
            ),
            CommandManifestEntry(
                id = "sys.device.location", version = "1.0.0",
                title = "Location Info",
                description = "Query last known device location (latitude, longitude, accuracy)",
                sideEffectClass = SideEffectClass.read,
                permissions = listOf(
                    PermissionEntry("android", "android.permission.ACCESS_FINE_LOCATION", "Device location")
                ),
                examples = listOf("sys.device.location()"),
                inputSchema = JsonObject(emptyMap())
            ),
            CommandManifestEntry(
                id = "sys.device.brightness", version = "1.0.0",
                title = "Screen Brightness",
                description = "Query or set screen brightness (0-255). Omitting 'level' queries, providing it sets.",
                sideEffectClass = SideEffectClass.control,
                permissions = listOf(
                    PermissionEntry("android", "android.permission.WRITE_SETTINGS", "Modify system settings")
                ),
                examples = listOf(
                    "sys.device.brightness()",
                    "sys.device.brightness(level=128)"
                ),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("level", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(0))
                            put("maximum", JsonPrimitive(255))
                            put("description", JsonPrimitive("Brightness level 0-255. Omit to query current level."))
                        })
                    })
                }
            )
        ),
        namespaces = listOf("sys"),
        threadHint = "main"
    )

    private var services: HostServices? = null

    override suspend fun onLoad(services: HostServices) {
        this.services = services
    }

    override suspend fun onUnload() {
        this.services = null
    }

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "sys.notify" to NotifyHandler(),
        "sys.share" to ShareHandler(),
        "sys.clipboard" to ClipboardHandler(),
        "sys.openUrl" to OpenUrlHandler(),
        "sys.intent.start" to IntentStartHandler(),
        "sys.vibrate" to VibrateHandler(),
        "sys.event.emit" to EmitEventHandler(),
        "sys.device.battery" to BatteryHandler(),
        "sys.device.wifi" to WifiHandler(),
        "sys.device.screen" to ScreenHandler(),
        "sys.device.volume" to VolumeHandler(),
        "sys.device.location" to LocationHandler(),
        "sys.device.brightness" to BrightnessHandler(),
    )

    // ─── Handlers ────────────────────────────────────────────────────────

    inner class NotifyHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val title = args["title"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: title")
            val text = args["text"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: text")

            val notifications = services?.notifications
                // P0-F1: when no notification service is available the command
                // must NOT report a fake success — callers (and the audit trail)
                // would believe the notification was delivered. Surface a clear
                // UNAVAILABLE error instead.
                ?: throw McosException("UNAVAILABLE", "Notification service is not available on this host")
            val channel = notifications.notify(title, text)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("notified"))
                    put("title", JsonPrimitive(title))
                    put("text", JsonPrimitive(text))
                    if (channel != null) put("channel", JsonPrimitive(channel))
                }
            )
        }
    }

    inner class ShareHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val text = args["text"]?.jsonPrimitive?.content
            val uri = args["uri"]?.jsonPrimitive?.content

            if (text == null && uri == null) {
                throw McosException("SCHEMA_VIOLATION", "At least one of 'text' or 'uri' is required")
            }

            val s = services ?: throw McosException("UNAVAILABLE", "System services not available")
            val result = s.ui.startActivityForResult(
                mapOf(
                    "action" to "ACTION_SEND",
                    "text" to (text ?: ""),
                    "uri" to (uri ?: "")
                )
            )

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive(if (result != null) "shared" else "cancelled"))
                    put("sharedText", if (text != null) JsonPrimitive(text) else JsonNull)
                    put("sharedUri", if (uri != null) JsonPrimitive(uri) else JsonNull)
                }
            )
        }
    }

    inner class ClipboardHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val text = args["text"]?.jsonPrimitive?.content
            val clipboard = services?.clipboard
                // No fake success: a host without a clipboard capability must
                // surface UNAVAILABLE (same policy as NotifyHandler P0-F1).
                ?: throw McosException("UNAVAILABLE", "Clipboard is not available on this host")

            return if (text != null) {
                // Write mode
                clipboard.set(text)
                CommandResult.Ok(
                    value = buildJsonObject {
                        put("operation", JsonPrimitive("write"))
                        put("text", JsonPrimitive(text))
                    }
                )
            } else {
                // Read mode — 08-security.md 11.1: clipboard text is untrusted
                // input (the user may have copied adversarial text), so the
                // result is tagged for downstream prompt-injection defenses.
                val current = clipboard.get()
                    // Empty and unreadable are indistinguishable on most
                    // hosts — an honest UNAVAILABLE, never a fabricated Ok.
                    ?: throw McosException("UNAVAILABLE", "Clipboard is empty or unreadable on this host")
                CommandResult.Ok(
                    value = buildJsonObject {
                        put("operation", JsonPrimitive("read"))
                        put("text", JsonPrimitive(current))
                        put("untrusted", JsonPrimitive(true))
                    }
                )
            }
        }
    }

    inner class OpenUrlHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val url = args["url"]?.jsonPrimitive?.content

            if (url.isNullOrBlank()) {
                throw McosException("SCHEMA_VIOLATION", "Missing required arg: url")
            }

            val s = services ?: throw McosException("UNAVAILABLE", "System services not available")
            s.ui.startActivityForResult(
                mapOf("action" to "ACTION_VIEW", "uri" to url)
            )

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("opened"))
                    put("url", JsonPrimitive(url))
                }
            )
        }
    }

    inner class IntentStartHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val action = args["action"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: action")
            val dataUri = args["dataUri"]?.jsonPrimitive?.content
            val pkg = args["package"]?.jsonPrimitive?.content

            val intent = mutableMapOf("action" to action)
            if (dataUri != null) intent["uri"] = dataUri
            if (pkg != null) intent["package"] = pkg

            val s = services ?: throw McosException("UNAVAILABLE", "System services not available")
            val result = s.ui.startActivityForResult(intent)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive(if (result != null) "started" else "cancelled"))
                    put("action", JsonPrimitive(action))
                }
            )
        }
    }

    inner class VibrateHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val duration = args["duration"]?.jsonPrimitive?.intOrNull ?: 500

            if (duration < 0 || duration > 5000) {
                throw McosException(
                    "SCHEMA_VIOLATION",
                    "duration must be between 0 and 5000 milliseconds",
                    details = buildJsonObject { put("duration", JsonPrimitive(duration)) }
                )
            }

            val haptics = services?.haptics
                // Previously this handler reported a fake "vibrated" Ok
                // without touching the host — the audit trail believed a
                // vibration happened. Real capability or UNAVAILABLE.
                ?: throw McosException("UNAVAILABLE", "Vibration is not available on this host")
            haptics.vibrate(duration)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("vibrated"))
                    put("durationMs", JsonPrimitive(duration))
                }
            )
        }
    }

    inner class EmitEventHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val type = args["type"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: type")
            val payload = args["payload"] as? JsonObject ?: JsonObject(emptyMap())

            val events = services?.events
                // No fake success: a host without the event-bus capability
                // must surface UNAVAILABLE (P0-F1 policy) — an Ok here would
                // make the audit trail believe subscribed automations ran.
                ?: throw McosException("UNAVAILABLE", "Event bus is not available on this host")
            events.publish(type, payload)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("emitted"))
                    put("type", JsonPrimitive(type))
                }
            )
        }
    }

    // ─── Device query handlers ────────────────────────────────────────────
    //
    // P2-F3 history: these handlers once returned hardcoded fake data
    // (battery=85%, ssid="MCOS-Network", lat=22.5431, ...) and reported Ok,
    // polluting the audit trail with fabricated telemetry. They now map the
    // host's real DeviceInfoService; a host without the capability (plain
    // JVM, StubHostServices) surfaces UNAVAILABLE — and individual data the
    // host cannot determine (SSID without the location permission, no
    // location fix) is reported as null/no_fix, never guessed.

    private fun deviceInfoOrUnavailable(): DeviceInfoService =
        services?.deviceInfo
            ?: throw McosException("UNAVAILABLE", "Device info is not available on this host")

    private fun JsonPrimitive?.orJsonNull(): JsonElement = this ?: JsonNull

    inner class BatteryHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val b = deviceInfoOrUnavailable().battery()
            return CommandResult.Ok(
                value = buildJsonObject {
                    put("percent", JsonPrimitive(b.percent))
                    put("charging", JsonPrimitive(b.charging))
                    put("temperatureC", b.temperatureC?.let { JsonPrimitive(it) }.orJsonNull())
                }
            )
        }
    }

    inner class WifiHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val w = deviceInfoOrUnavailable().wifi()
            return CommandResult.Ok(
                value = buildJsonObject {
                    put("connected", JsonPrimitive(w.connected))
                    put("ssid", w.ssid?.let { JsonPrimitive(it) }.orJsonNull())
                    put("strength", w.strength?.let { JsonPrimitive(it) }.orJsonNull())
                }
            )
        }
    }

    inner class ScreenHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val s = deviceInfoOrUnavailable().screen()
            return CommandResult.Ok(
                value = buildJsonObject {
                    put("widthPx", JsonPrimitive(s.widthPx))
                    put("heightPx", JsonPrimitive(s.heightPx))
                    put("densityDpi", JsonPrimitive(s.densityDpi))
                    put("rotation", JsonPrimitive(s.rotation))
                    put("brightness", s.brightness?.let { JsonPrimitive(it) }.orJsonNull())
                }
            )
        }
    }

    inner class VolumeHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val v = deviceInfoOrUnavailable().volume()
            return CommandResult.Ok(
                value = buildJsonObject {
                    put("music", JsonPrimitive(v.musicPercent))
                    put("ring", v.ringPercent?.let { JsonPrimitive(it) }.orJsonNull())
                    put("alarm", v.alarmPercent?.let { JsonPrimitive(it) }.orJsonNull())
                }
            )
        }
    }

    inner class LocationHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val loc = deviceInfoOrUnavailable().location()
                // "Last known location" with no fix is a legitimate device
                // state, not an error — report it honestly instead of a
                // fabricated coordinate.
                ?: return CommandResult.Ok(
                    value = buildJsonObject {
                        put("status", JsonPrimitive("no_fix"))
                        put("location", JsonNull)
                    }
                )
            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("lat", JsonPrimitive(loc.lat))
                    put("lng", JsonPrimitive(loc.lng))
                    put("accuracyM", loc.accuracyM?.let { JsonPrimitive(it) }.orJsonNull())
                    put("timestampMs", loc.timestampMs?.let { JsonPrimitive(it) }.orJsonNull())
                }
            )
        }
    }

    inner class BrightnessHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val level = args["level"]?.jsonPrimitive?.intOrNull

            return if (level != null) {
                // Set mode — schema check first so callers get a deterministic
                // validation error before any capability/host interaction.
                if (level < 0 || level > 255) {
                    throw McosException(
                        "SCHEMA_VIOLATION",
                        "level must be between 0 and 255",
                        details = buildJsonObject { put("level", JsonPrimitive(level)) }
                    )
                }
                deviceInfoOrUnavailable().setBrightness(level)
                CommandResult.Ok(
                    value = buildJsonObject {
                        put("status", JsonPrimitive("set"))
                        put("level", JsonPrimitive(level))
                    }
                )
            } else {
                val b = deviceInfoOrUnavailable().brightness()
                CommandResult.Ok(
                    value = buildJsonObject {
                        put("level", JsonPrimitive(b.level))
                        put("auto", JsonPrimitive(b.auto))
                    }
                )
            }
        }
    }
}
