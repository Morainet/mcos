package com.morainet.mcos.plugin.iot

import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PermissionEntry
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Hub connection for [IotPlugin]. The bearer token lives in `SecureStore`
 * under [tokenSecretKey] (P3 path — the raw token never enters plugin
 * config; handlers carry a `{{secret.<key>}}` reference the executor
 * resolves per call, [08-security.md 9.2]). Local unauthenticated hubs
 * omit the key.
 */
data class HomeAssistantConfig(
    val baseUrl: String,
    val tokenSecretKey: String? = null,
)

/**
 * IoT / smart-home plugin — `home.*` + `iot.*` ([04-plugin-sdk.md 9]).
 *
 * Talks to a Home Assistant hub over the host [NetService] only: every
 * request passes the kernel's per-call egress scope check, and the
 * manifest declares the concrete `network.<hub-host>` scope so an
 * unconfigured hub grants nothing. All control commands are write-class
 * (they change physical state); `home.device.list` is the spec-mandated
 * read-only discovery surface (04 §9: "SHOULD expose a read command
 * rather than inventing out-of-band channels").
 *
 * Constructed per host with its own hub config — like the MCP adapter it
 * is deliberately not part of the android-sdk default built-in set:
 *
 * ```kotlin
 * val iot = IotPlugin(HomeAssistantConfig(
 *     baseUrl = "https://ha.example.com",
 *     tokenSecretKey = "mcos.iot.ha.token",   // SecureStore key
 * ))
 * ```
 *
 * A null [config] keeps the plugin loadable (manifest, empty state) while
 * every command surfaces an honest UNAVAILABLE — never a fabricated
 * success.
 */
class IotPlugin(private val config: HomeAssistantConfig? = null) : McosPlugin {

    override val manifest: PluginManifest = buildManifest()

    private var services: HostServices? = null

    override suspend fun onLoad(services: HostServices) {
        this.services = services
    }

    override suspend fun onUnload() {
        services = null
    }

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "home.device.list" to DeviceListHandler(),
        "home.light.on" to LightHandler(on = true),
        "home.light.off" to LightHandler(on = false),
        "home.light.set" to LightSetHandler(),
        "home.scene.apply" to SceneHandler(),
        "home.scene.movie" to FixedSceneHandler("movie"),
        "home.scene.sleep" to FixedSceneHandler("sleep"),
        "iot.ac.set" to AcSetHandler(),
    )

    // ─── shared plumbing ─────────────────────────────────────────────────

    /** Hub client, or an honest UNAVAILABLE when no hub is configured. */
    private fun hub(what: String): Pair<HostServices, HomeAssistantClient> {
        val s = services
            ?: throw McosException("UNAVAILABLE", "$what: plugin is not loaded", retryable = false)
        val cfg = config
            ?: throw McosException(
                "UNAVAILABLE",
                "$what: no smart-home hub configured — construct IotPlugin with HomeAssistantConfig",
                retryable = false,
            )
        return s to HomeAssistantClient(s.net, cfg)
    }

    private fun ok(entity: String, action: String) = CommandResult.Ok(
        value = buildJsonObject {
            put("status", JsonPrimitive("ok"))
            put("entity", JsonPrimitive(entity))
            put("action", JsonPrimitive(action))
        },
    )

    private fun args(ctx: ExecutionContext): JsonObject = ctx.args.jsonObject

    private fun requiredString(ctxArgs: JsonObject, key: String, command: String): String {
        val raw = ctxArgs[key]
        if (raw == null || raw is JsonNull) {
            throw McosException("SCHEMA_VIOLATION", "$command: missing required argument '$key'")
        }
        val value = raw.jsonPrimitive.content
        if (value.isBlank()) {
            throw McosException("SCHEMA_VIOLATION", "$command: '$key' must not be blank")
        }
        return value
    }

    // ─── handlers ────────────────────────────────────────────────────────

    /** `home.device.list` — read-only discovery over `/api/states`. */
    inner class DeviceListHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val (s, ha) = hub("home.device.list")
            val domain = args(ctx)["domain"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

            val response = ha.states()
            HomeAssistantClient.ensureSuccess(response, "home.device.list")
            val entities = HomeAssistantClient.parseArray(response.body)

            val devices = entities.mapNotNull { entry ->
                val obj = entry.jsonObject
                val id = obj["entity_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (domain != null && id.substringBefore('.') != domain) return@mapNotNull null
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("domain", JsonPrimitive(id.substringBefore('.')))
                    put("state", JsonPrimitive(obj["state"]?.jsonPrimitive?.content ?: "unknown"))
                    put(
                        "name",
                        obj["attributes"]?.jsonObject?.get("friendly_name")?.jsonPrimitive?.content ?: id,
                    )
                }
            }

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("count", JsonPrimitive(devices.size))
                    put("devices", buildJsonArray { devices.forEach { add(it) } })
                },
            )
        }
    }

    /** `home.light.on` / `home.light.off` — one HA light service call. */
    inner class LightHandler(private val on: Boolean) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val command = if (on) "home.light.on" else "home.light.off"
            val id = requiredString(args(ctx), "id", command)
            val (_, ha) = hub(command)

            val response = ha.service(
                "light",
                if (on) "turn_on" else "turn_off",
                buildJsonObject { put("entity_id", JsonPrimitive(id)) },
            )
            HomeAssistantClient.ensureSuccess(response, command)
            return ok(id, if (on) "light.turn_on" else "light.turn_off")
        }
    }

    /**
     * `home.light.set` — the golden-fixture signature
     * `home.light.set(id="living-room", on=true, brightness=0.8, meta=null)`
     * ([02-command-protocol.md P5]). `brightness` is a 0..1 float mapped to
     * HA's 0..255 scale; `meta` is accepted (fixture parity) and ignored.
     */
    inner class LightSetHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val command = "home.light.set"
            val ctxArgs = args(ctx)
            val id = requiredString(ctxArgs, "id", command)
            val on = ctxArgs["on"]?.jsonPrimitive?.booleanOrNull ?: true
            val brightness = ctxArgs["brightness"]?.jsonPrimitive?.doubleOrNull
            val (_, ha) = hub(command)

            if (brightness != null && (brightness < 0.0 || brightness > 1.0)) {
                throw McosException(
                    "SCHEMA_VIOLATION",
                    "$command: brightness must be within 0..1, got $brightness",
                )
            }

            return if (!on) {
                val response = ha.service("light", "turn_off", buildJsonObject {
                    put("entity_id", JsonPrimitive(id))
                })
                HomeAssistantClient.ensureSuccess(response, command)
                ok(id, "light.turn_off")
            } else {
                val response = ha.service("light", "turn_on", buildJsonObject {
                    put("entity_id", JsonPrimitive(id))
                    if (brightness != null) {
                        put("brightness", JsonPrimitive((brightness * 255).roundToInt().coerceIn(0, 255)))
                    }
                })
                HomeAssistantClient.ensureSuccess(response, command)
                ok(id, "light.turn_on")
            }
        }
    }

    /**
     * `home.scene.apply(name)` plus the two doc-named convenience commands
     * ([04-plugin-sdk.md 9] lists `home.scene.movie` / `home.scene.sleep`):
     * a scene named "movie" activates the hub's `scene.movie` entity.
     */
    private suspend fun applyScene(name: String, command: String): CommandResult {
        val (_, ha) = hub(command)
        val response = ha.service(
            "scene",
            "turn_on",
            buildJsonObject { put("entity_id", JsonPrimitive("scene.$name")) },
        )
        HomeAssistantClient.ensureSuccess(response, command)
        return ok("scene.$name", "scene.turn_on")
    }

    inner class SceneHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult =
            applyScene(requiredString(args(ctx), "name", "home.scene.apply"), "home.scene.apply")
    }

    inner class FixedSceneHandler(private val name: String) : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult =
            applyScene(name, "home.scene.$name")
    }

    /**
     * `iot.ac.set(name="air-condition", power=true, tempC=26)`
     * ([02-command-protocol.md examples]) — HA climate: `power=false` turns
     * the unit off; `power=true` turns it on and, with `tempC`, sets the
     * target temperature (validated to the 16..30 °C range).
     */
    inner class AcSetHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val command = "iot.ac.set"
            val ctxArgs = args(ctx)
            val name = requiredString(ctxArgs, "name", command)
            val power = ctxArgs["power"]?.jsonPrimitive?.booleanOrNull ?: true
            val tempC = ctxArgs["tempC"]?.jsonPrimitive?.doubleOrNull
            val (_, ha) = hub(command)
            val entityId = "climate.$name"

            if (tempC != null && (tempC < 16.0 || tempC > 30.0)) {
                throw McosException(
                    "SCHEMA_VIOLATION",
                    "$command: tempC must be within 16..30, got $tempC",
                )
            }

            if (!power) {
                val response = ha.service("climate", "turn_off", buildJsonObject {
                    put("entity_id", JsonPrimitive(entityId))
                })
                HomeAssistantClient.ensureSuccess(response, command)
                return ok(entityId, "climate.turn_off")
            }
            val on = ha.service("climate", "turn_on", buildJsonObject {
                put("entity_id", JsonPrimitive(entityId))
            })
            HomeAssistantClient.ensureSuccess(on, command)
            if (tempC != null) {
                val set = ha.service("climate", "set_temperature", buildJsonObject {
                    put("entity_id", JsonPrimitive(entityId))
                    put("temperature", JsonPrimitive(tempC))
                })
                HomeAssistantClient.ensureSuccess(set, command)
            }
            return ok(
                entityId,
                if (tempC != null) "climate.turn_on+set_temperature" else "climate.turn_on",
            )
        }
    }

    // ─── manifest ────────────────────────────────────────────────────────

    private fun buildManifest(): PluginManifest = PluginManifest(
        id = "mcos.plugin.iot",
        name = "IoT / Smart Home Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "Controls smart-home devices through a Home Assistant hub",
        provider = ProviderInfo("MCOS", "https://github.com/Morainet/mcos"),
        entry = "com.morainet.mcos.plugin.iot.IotPlugin",
        permissions = config?.let { cfg ->
            listOf(
                PermissionEntry(
                    "mcos",
                    "network.${hubHost(cfg.baseUrl)}",
                    "Home Assistant hub API (${cfg.baseUrl})",
                ),
            )
        } ?: emptyList(),
        commands = listOf(
            CommandManifestEntry(
                id = "home.device.list", version = "1.0.0",
                title = "List Devices",
                description = "Lists smart-home entities known to the hub (read-only discovery)",
                sideEffectClass = SideEffectClass.read,
                timeoutMs = 15000,
                examples = listOf("""home.device.list()""", """home.device.list(domain="light")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("domain", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Filter by hub domain (e.g. light, climate, scene); default: all"),
                            )
                        })
                    })
                },
            ),
            CommandManifestEntry(
                id = "home.light.on", version = "1.0.0",
                title = "Light On",
                description = "Turns a light on",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf("""home.light.on(id="living-room")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("id")) })
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Light entity id, e.g. 'living-room'"))
                        })
                    })
                },
            ),
            CommandManifestEntry(
                id = "home.light.off", version = "1.0.0",
                title = "Light Off",
                description = "Turns a light off",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf("""home.light.off(id="living-room")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("id")) })
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Light entity id, e.g. 'living-room'"))
                        })
                    })
                },
            ),
            CommandManifestEntry(
                id = "home.light.set", version = "1.0.0",
                title = "Set Light",
                description = "Sets a light's on/off state and brightness (0..1)",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf(
                    """home.light.set(id="living-room", on=true, brightness=0.8, meta=null)""",
                ),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("id")) })
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Light entity id, e.g. 'living-room'"))
                        })
                        put("on", buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Target power state (default: true)"))
                        })
                        put("brightness", buildJsonObject {
                            put("type", JsonPrimitive("number"))
                            put("minimum", JsonPrimitive(0))
                            put("maximum", JsonPrimitive(1))
                            put("description", JsonPrimitive("Brightness 0..1 (mapped to the hub's 0..255)"))
                        })
                        put("meta", buildJsonObject {
                            put("description", JsonPrimitive("Accepted for fixture parity; ignored"))
                        })
                    })
                },
            ),
            CommandManifestEntry(
                id = "home.scene.apply", version = "1.0.0",
                title = "Apply Scene",
                description = "Activates a named scene on the hub",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf("""home.scene.apply(name="movie")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("name")) })
                    put("properties", buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Scene name, e.g. 'movie' → scene.movie"))
                        })
                    })
                },
            ),
            CommandManifestEntry(
                id = "home.scene.movie", version = "1.0.0",
                title = "Movie Scene",
                description = "Activates the 'movie' scene (04-plugin-sdk.md 9)",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf("home.scene.movie()"),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                },
            ),
            CommandManifestEntry(
                id = "home.scene.sleep", version = "1.0.0",
                title = "Sleep Scene",
                description = "Activates the 'sleep' scene (04-plugin-sdk.md 9)",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf("home.scene.sleep()"),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                },
            ),
            CommandManifestEntry(
                id = "iot.ac.set", version = "1.0.0",
                title = "Set Air Conditioner",
                description = "Sets an AC unit's power and target temperature",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf("""iot.ac.set(name="air-condition", power=true, tempC=26)"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("name")) })
                    put("properties", buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Climate entity name, e.g. 'air-condition' → climate.air-condition"))
                        })
                        put("power", buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Power state (default: true)"))
                        })
                        put("tempC", buildJsonObject {
                            put("type", JsonPrimitive("number"))
                            put("minimum", JsonPrimitive(16))
                            put("maximum", JsonPrimitive(30))
                            put("description", JsonPrimitive("Target temperature in °C (16..30)"))
                        })
                    })
                },
            ),
        ),
        namespaces = listOf("home", "iot"),
    )

    private fun hubHost(baseUrl: String): String {
        val noScheme = baseUrl.substringAfter("://", baseUrl)
        return noScheme.substringBefore('/').substringBefore(':').ifBlank { baseUrl }
    }
}
