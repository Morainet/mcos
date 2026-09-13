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
 * Deep Link / App Functions bridge — the plugin slice of
 * [10-roadmap.md §5.4] and [02-command-protocol.md §12.2/§12.3/§12.5].
 *
 * **Why there is no `intent.start` here.** §12.3 represents Intents as
 * `sys.intent.start`, and §12.6 publishes the pre-declared extras allowlist
 * "in the `sys` plugin" — so the Intent command itself lives in
 * `mcos.plugin.system`, which owns the §12.6 checker and the published
 * allowlist. 10-roadmap §5.4's name for that capability (`intent.start`)
 * resolves to it as an **alias**, so this module carries no second copy of the
 * §12.6 rule.
 *
 * Two commands:
 *
 * | Command | Spec | Notes |
 * |---|---|---|
 * | `deeplink.open` | 02 §12.3 | an `ACTION_VIEW` intent whose payload rides the URI, so the §12.6 extras hazard does not apply |
 * | `appfn.invoke` | 02 §12.2, §12.5 | invokes a function published by another app package; [AppFunctionIds] is the §12.5 id encoding |
 *
 * **Safety posture:** the plugin never launches anything itself — every
 * command composes an [IntentRequest] and hands it to the host's optional
 * `HostServices.intents` / `HostServices.appFunctions` capability. A host
 * without one surfaces `UNAVAILABLE`, never a fake success (the P0-F1 policy
 * every optional capability follows).
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
        description = "Deep-link and App Functions bridge (02 §12)",
        provider = ProviderInfo("MCOS", "https://github.com/Morainet"),
        entry = "com.morainet.mcos.plugin.intent.IntentPlugin",
        commands = listOf(
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
        namespaces = listOf("deeplink", "appfn"),
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
        "deeplink.open" to DeeplinkOpenHandler(),
        "appfn.invoke" to AppFunctionInvokeHandler(),
    )

    // ─── Handlers ────────────────────────────────────────────────────────

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
