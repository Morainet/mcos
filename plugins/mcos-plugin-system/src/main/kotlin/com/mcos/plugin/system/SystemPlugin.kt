package com.mcos.plugin.system

import com.mcos.sdk.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * System commands plugin — sys.notify, sys.share, sys.clipboard, sys.openUrl, sys.intent.start, sys.vibrate.
 * Matches [04-plugin-sdk.md §17].
 */
class SystemPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "mcos.plugin.system",
        name = "System Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "System-level commands: notify, share, clipboard, open URL, device info",
        provider = ProviderInfo("MCOS", "https://github.com/mcos-org"),
        entry = "com.mcos.plugin.system.SystemPlugin",
        permissions = listOf(
            PermissionEntry("android", "android.permission.VIBRATE", "Device vibration"),
            PermissionEntry("android", "android.permission.POST_NOTIFICATIONS", "System notifications")
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
        "sys.vibrate" to VibrateHandler()
    )

    // ─── Handlers ────────────────────────────────────────────────────────

    inner class NotifyHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val title = args["title"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: title")
            val text = args["text"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: text")

            // On Android, this would call NotificationManager via HostServices.
            // For JVM MVP, return a structured result.
            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("notified"))
                    put("title", JsonPrimitive(title))
                    put("text", JsonPrimitive(text))
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

            return if (text != null) {
                // Write mode
                CommandResult.Ok(
                    value = buildJsonObject {
                        put("operation", JsonPrimitive("write"))
                        put("text", JsonPrimitive(text))
                    }
                )
            } else {
                // Read mode (MVP: return stub since real clipboard needs Android)
                CommandResult.Ok(
                    value = buildJsonObject {
                        put("operation", JsonPrimitive("read"))
                        put("text", JsonPrimitive(""))
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

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive("vibrated"))
                    put("durationMs", JsonPrimitive(duration))
                }
            )
        }
    }
}
