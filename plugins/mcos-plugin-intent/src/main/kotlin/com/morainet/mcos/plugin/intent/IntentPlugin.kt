package com.morainet.mcos.plugin.intent

import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.IntentRequest
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Intent / Deep Link / App Functions bridge — the plugin slice of
 * [10-roadmap.md §5.4] and [02-command-protocol.md §12.2/§12.3/§12.5/§12.6].
 *
 * Three commands:
 *
 * | Command | Spec | Notes |
 * |---|---|---|
 * | `intent.start` | 02 §12.3, §12.6 | extras are **schema-constrained** — a declared `extrasSchema` is mandatory unless the action is a published [WellKnownIntents] entry |
 * | `deeplink.open` | 02 §12.3 | an `ACTION_VIEW` intent whose payload rides the URI, so the §12.6 extras hazard does not apply |
 * | `appfn.invoke` | 02 §12.2, §12.5 | invokes a function published by another app package; [AppFunctionIds] is the §12.5 id encoding |
 *
 * **Safety posture:** the plugin never launches anything itself — every
 * command composes an [IntentRequest] and hands it to the host's optional
 * `HostServices.intents` / `HostServices.appFunctions` capability. A host
 * without one surfaces `UNAVAILABLE` (never a fake success, the P0-F1 policy
 * every optional capability follows). The §12.6 rejection is emitted with the
 * spec-named code/path/reason (`SCHEMA_VIOLATION`, `/args/extras`,
 * `extras_schema_required`) from the handler rather than from the Executor's
 * Stage-5 pass: the schema that governs extras is supplied **per invoke**,
 * so it cannot be expressed statically in the descriptor's `inputSchema`.
 *
 * **Honest boundary:** the bridge cannot know the target function's impact
 * for `appfn.invoke`, so `write` is the declared floor — the runtime still
 * requires write authorization and shows the DSL preview (02 §9.3/06 §8). A
 * publisher whose function is destructive should ship a wrapper descriptor
 * carrying `destructive`; the bridge does not guess.
 */
class IntentPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "mcos.plugin.intent",
        name = "Intent Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "Intent, deep-link, and App Functions bridge (02 §12)",
        provider = ProviderInfo("MCOS", "https://github.com/Morainet"),
        entry = "com.morainet.mcos.plugin.intent.IntentPlugin",
        commands = listOf(
            CommandManifestEntry(
                id = "intent.start",
                version = "1.0.0",
                title = "Start Intent",
                description = "Start an Intent with schema-constrained extras (02 §12.6)",
                sideEffectClass = SideEffectClass.write,
                examples = listOf(
                    """intent.start(action="android.intent.action.VIEW", dataUri="https://example.com")""",
                    """intent.start(action="com.example.app.OPEN", extrasSchema={type:"object"}, extras={})""",
                ),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("action")) })
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Platform action, e.g. android.intent.action.SEND"))
                        })
                        put("dataUri", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("The intent's data URI, when any"))
                        })
                        put("package", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Explicit target package"))
                        })
                        put("categories", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                        })
                        put("extras", buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("description", JsonPrimitive("Typed extras; must be declared by extrasSchema (02 §12.6)"))
                        })
                        put("extrasSchema", buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Schema for 'extras'. Required unless action is a published " +
                                        "well-known intent (02 §12.6)",
                                ),
                            )
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "deeplink.open",
                version = "1.0.0",
                title = "Open Deep Link",
                description = "Open a deep link URI (ACTION_VIEW) in its registered app",
                sideEffectClass = SideEffectClass.write,
                examples = listOf("""deeplink.open(uri="myapp://profile/42")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("uri")) })
                    put("properties", buildJsonObject {
                        put("uri", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Deep link URI including its scheme"))
                        })
                        put("package", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Explicit target package"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "appfn.invoke",
                version = "1.0.0",
                title = "Invoke App Function",
                description = "Invoke a function published by another app package (02 §12.2/§12.5)",
                sideEffectClass = SideEffectClass.write,
                examples = listOf(
                    """appfn.invoke(package="com.example.notes", function="createNote", args={title: "Hi"})""",
                ),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray {
                        add(JsonPrimitive("package"))
                        add(JsonPrimitive("function"))
                    })
                    put("properties", buildJsonObject {
                        put("package", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Publishing app's package name, e.g. com.example.notes"))
                        })
                        put("function", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Function name, e.g. createNote (a single segment)"))
                        })
                        put("args", buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("description", JsonPrimitive("Function arguments; validated by the publishing app"))
                        })
                    })
                }
            ),
        ),
        namespaces = listOf("intent", "deeplink", "appfn"),
        threadHint = "main",
    )

    private var services: HostServices? = null

    override suspend fun onLoad(services: HostServices) {
        this.services = services
    }

    override suspend fun onUnload() {
        this.services = null
    }

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "intent.start" to IntentStartHandler(),
        "deeplink.open" to DeeplinkOpenHandler(),
        "appfn.invoke" to AppFunctionInvokeHandler(),
    )

    // ─── Handlers ────────────────────────────────────────────────────────

    inner class IntentStartHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val action = args.stringArg("action")
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: action")

            val extras = when (val raw = args["extras"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> throw schemaViolation(
                    ExtrasSchema.ROOT_PATH,
                    ExtrasSchema.Reason.NOT_AN_OBJECT,
                    "'extras' must be an object",
                )
            }

            // 02 §12.6 — the extras schema is mandatory unless the action is a
            // published well-known intent. The rejection carries the spec's
            // path/reason so a Planner can self-correct on the next turn.
            val declared = args["extrasSchema"]
            val schema = when {
                declared is JsonObject -> declared
                declared != null && declared != JsonNull -> throw schemaViolation(
                    ExtrasSchema.ROOT_PATH,
                    ExtrasSchema.Reason.SCHEMA_UNSUPPORTED,
                    "'extrasSchema' must be an object",
                )
                else -> WellKnownIntents.schemaFor(action) ?: throw schemaViolation(
                    ExtrasSchema.ROOT_PATH,
                    ExtrasSchema.Reason.SCHEMA_REQUIRED,
                    "extrasSchema is required for action '$action' (02 §12.6)",
                )
            }

            when (val validation = ExtrasSchema.validate(schema, extras)) {
                is ExtrasSchema.Result.Valid -> Unit
                is ExtrasSchema.Result.Invalid -> throw schemaViolation(
                    validation.path,
                    validation.reason,
                    validation.message,
                )
            }

            val request = IntentRequest(
                action = action,
                dataUri = args.stringArg("dataUri"),
                packageName = args.stringArg("package"),
                categories = (args["categories"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) },
                extras = extras,
            )

            val intents = services?.intents
                // No fake success (P0-F1 policy): a host without an intent
                // platform reports UNAVAILABLE. `started=false` is a separate,
                // honest outcome — nothing on the device resolved the intent.
                ?: throw McosException("UNAVAILABLE", "Intent launching is not available on this host")
            val outcome = intents.start(request)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive(if (outcome.started) "started" else "not_handled"))
                    put("action", JsonPrimitive(action))
                    outcome.resolvedPackage?.let { put("resolvedPackage", JsonPrimitive(it)) }
                }
            )
        }
    }

    inner class DeeplinkOpenHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val uri = args.stringArg("uri")
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: uri")

            // A deep link without a scheme cannot be routed by the platform —
            // refuse instead of handing it an unroutable string.
            val scheme = runCatching { URI(uri).scheme }.getOrNull()
            if (scheme.isNullOrBlank()) {
                throw schemaViolation(
                    "/args/uri",
                    "deeplink_uri_invalid",
                    "uri must carry a scheme: '$uri'",
                )
            }

            val intents = services?.intents
                ?: throw McosException("UNAVAILABLE", "Deep links are not available on this host")
            val outcome = intents.start(
                IntentRequest(
                    action = "android.intent.action.VIEW",
                    dataUri = uri,
                    packageName = args.stringArg("package"),
                )
            )

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("status", JsonPrimitive(if (outcome.started) "opened" else "not_handled"))
                    put("uri", JsonPrimitive(uri))
                    outcome.resolvedPackage?.let { put("resolvedPackage", JsonPrimitive(it)) }
                }
            )
        }
    }

    inner class AppFunctionInvokeHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val packageName = args.stringArg("package")
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: package")
            val function = args.stringArg("function")
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: function")
            if ('.' in function) {
                throw schemaViolation(
                    "/args/function",
                    "appfn_target_invalid",
                    "function must be a single segment: '$function'",
                )
            }

            val functionArgs = when (val raw = args["args"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> throw schemaViolation(
                    "/args/args",
                    "appfn_args_not_an_object",
                    "'args' must be an object",
                )
            }

            val bridge = services?.appFunctions
                ?: throw McosException("UNAVAILABLE", "App Functions are not available on this host")
            val result = bridge.invoke(packageName, function, functionArgs)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("package", JsonPrimitive(packageName))
                    put("function", JsonPrimitive(function))
                    // The §12.5 id form, for audit/telemetry correlation.
                    put("commandId", JsonPrimitive(AppFunctionIds.encode(packageName, function)))
                    put("result", result)
                }
            )
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** A non-blank string argument, or null when absent/blank/wrong type. */
    private fun JsonObject.stringArg(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)

    private fun schemaViolation(path: String, reason: String, message: String) = McosException(
        code = "SCHEMA_VIOLATION",
        message = message,
        retryable = false,
        details = buildJsonObject {
            put("path", JsonPrimitive(path))
            put("reason", JsonPrimitive(reason))
        },
    )
}
