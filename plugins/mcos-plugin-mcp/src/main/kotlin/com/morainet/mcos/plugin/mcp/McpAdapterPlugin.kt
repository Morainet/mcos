package com.morainet.mcos.plugin.mcp

import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A user-configured MCP server ([04-plugin-sdk.md §10]).
 *
 * Auth precedence: [secretKey] is the P3 production path — the bearer token
 * lives in `SecureStore` under this key and the synthesized handlers carry a
 * `{{secret.<key>}}` reference ([04-plugin-sdk.md §11.1]) that the executor
 * resolves per call ([08-security.md §9.2]), so the raw secret never enters
 * the config, the IR, or the audit trail. [token] is the P2 spike fallback
 * (secret in config); if both are set, [secretKey] wins.
 */
data class McpServerConfig(
    val id: String,
    val endpoint: String,
    val token: String? = null,
    val secretKey: String? = null,
)

/** A tool that was discovered but not registered because its schema is unmappable (§12.4). */
data class SkippedTool(val toolName: String, val unmappedType: String, val reason: String)

/** The outcome of bridging one server: a ready-to-register plugin plus the tools it had to drop. */
data class McpDiscovery(val plugin: McpBridgedPlugin, val skipped: List<SkippedTool>)

/**
 * Bridges a single MCP server into the command bus. Discovery runs *before*
 * registration because MCP tools are enumerated at runtime — the synthesized
 * plugin's `manifest.commands` cannot be known statically. The caller
 * registers the returned [McpDiscovery.plugin] with the `CommandRegistry`
 * exactly like a built-in.
 */
object McpAdapter {

    /** Plugin id for a bridged server, e.g. `mcos.plugin.mcp.github`. */
    fun pluginId(serverId: String): String = "mcos.plugin.mcp.$serverId"

    /**
     * Connect via the host [net], discover tools, and synthesize a plugin.
     *
     * Discovery (`tools/list`) runs outside the executor's Stage-4 secret
     * pipeline, so a [config.secretKey] is resolved here via [secretLookup] to
     * authenticate the enumeration call. The synthesized *handlers*, by
     * contrast, carry the `{{secret.<key>}}` template — they run through the
     * executor, which resolves it per call (see [McpProxyHandler]).
     */
    suspend fun discover(
        net: NetService,
        config: McpServerConfig,
        secretLookup: suspend (String) -> String? = { null },
    ): McpDiscovery {
        val discoveryToken = config.secretKey?.let { secretLookup(it) } ?: config.token
        val discoveryHeaders = discoveryToken
            ?.let { mapOf("Authorization" to "Bearer $it") }
            ?: emptyMap()
        return discover(McpClient(net, config.endpoint, discoveryHeaders), config)
    }

    /**
     * Discovery against an injected [client] (used by tests). All synthesized
     * handlers share one [breaker] so a run of failures against the server
     * gates the whole `mcp.<server>.*` namespace, not one command at a time.
     */
    suspend fun discover(
        client: McpClient,
        config: McpServerConfig,
        breaker: McpCircuitBreaker = McpCircuitBreaker(),
    ): McpDiscovery {
        val tools = client.listTools()
        val commands = mutableListOf<CommandManifestEntry>()
        val handlers = mutableMapOf<String, CommandHandler>()
        val skipped = mutableListOf<SkippedTool>()
        val proxyHeaders = proxyAuthHeaders(config)

        for (tool in tools) {
            val commandId = "mcp.${config.id}.${sanitize(tool.name)}"
            when (val conv = McpSchemaConverter.convert(tool.inputSchema)) {
                is McpSchemaConverter.Result.Converted -> {
                    commands += CommandManifestEntry(
                        id = commandId,
                        version = "1.0.0",
                        title = tool.name,
                        description = tool.description ?: "MCP tool '${tool.name}' on ${config.id}",
                        sideEffectClass = sideEffectOf(tool),
                        timeoutMs = 30000,
                        inputSchema = conv.inputSchema,
                    )
                    handlers[commandId] = McpProxyHandler(config.endpoint, tool.name, proxyHeaders, breaker)
                }
                is McpSchemaConverter.Result.Unmapped ->
                    // Fail-closed: the tool is dropped, not silently degraded.
                    skipped += SkippedTool(tool.name, conv.unmappedType, conv.reason)
            }
        }

        val manifest = PluginManifest(
            id = pluginId(config.id),
            name = "MCP: ${config.id}",
            version = "1.0.0",
            minRuntimeVersion = "0.1.0",
            description = "Bridged MCP server '${config.id}' (${config.endpoint})",
            provider = ProviderInfo("MCP Adapter", "https://modelcontextprotocol.io"),
            entry = McpBridgedPlugin::class.qualifiedName!!,
            commands = commands,
            // Informational only — the registry derives the namespace from the
            // command-id prefix. Two bridged servers never collide because
            // their ids differ (mcp.<serverA>.* vs mcp.<serverB>.*).
            namespaces = listOf("mcp"),
        )
        return McpDiscovery(McpBridgedPlugin(manifest, handlers), skipped)
    }

    /**
     * Every MCP call leaves the device boundary, so the floor is `network`
     * ([02-command-protocol.md §8.1]). A `destructiveHint` annotation upgrades
     * to `destructive` so deletes always challenge for confirmation.
     */
    private fun sideEffectOf(tool: McpTool): SideEffectClass {
        val destructive = tool.annotations
            ?.get("destructiveHint")?.jsonPrimitive?.booleanOrNull ?: false
        return if (destructive) SideEffectClass.destructive else SideEffectClass.network
    }

    /** Reduce an MCP tool name to a command-id-safe segment. */
    private fun sanitize(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

    /**
     * The `Authorization` header the synthesized handlers send per call.
     * A [config.secretKey] yields a `{{secret.<key>}}` reference the executor
     * resolves at Stage 4 ([08-security.md §9.2]) — the raw secret is never
     * baked into the handler. [config.token] (spike fallback) is sent inline.
     */
    private fun proxyAuthHeaders(config: McpServerConfig): Map<String, String> = when {
        config.secretKey != null -> mapOf("Authorization" to "Bearer {{secret.${config.secretKey}}}")
        config.token != null -> mapOf("Authorization" to "Bearer ${config.token}")
        else -> emptyMap()
    }
}

/**
 * The synthesized plugin for one bridged MCP server. Its handlers are bound to
 * the server endpoint at discovery time but open the transport per call over
 * [ExecutionContext.services].net (so egress policy + secret resolution apply),
 * so [onLoad] has no work to do — tools were enumerated before registration.
 */
class McpBridgedPlugin(
    override val manifest: PluginManifest,
    private val handlerMap: Map<String, CommandHandler>,
) : McosPlugin {
    override suspend fun onLoad(services: HostServices) {}
    override suspend fun onUnload() {}
    override fun handlers(): Map<String, CommandHandler> = handlerMap
}

/**
 * Proxies one MCOS command onto a `tools/call` against the bridged server,
 * translating the MCP result envelope back into a [CommandResult].
 *
 * The client is built per call over [ExecutionContext.services].net — the
 * executor's Stage-4 decorator ([08-security.md §9.2]), so any
 * `{{secret.<key>}}` in [headers] is resolved from the plugin's `SecureStore`
 * on the way out and the secret never lives on the handler.
 *
 * A shared [breaker] gates the server: when it is open the handler fast-fails
 * `UNAVAILABLE` without a request ([04-plugin-sdk.md §10]).
 */
class McpProxyHandler(
    private val endpoint: String,
    private val toolName: String,
    private val headers: Map<String, String> = emptyMap(),
    private val breaker: McpCircuitBreaker = McpCircuitBreaker(),
) : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        if (breaker.isOpen()) {
            return CommandResult.Err(
                code = "UNAVAILABLE",
                message = "MCP server is temporarily unavailable (circuit open); retry later",
                retryable = true,
            )
        }
        val args = ctx.args as? JsonObject ?: JsonObject(emptyMap())
        val client = McpClient(ctx.services.net, endpoint, headers)
        val result = try {
            client.callTool(toolName, args)
        } catch (e: McpException) {
            // A retryable fault (connection exhaustion or 5xx) is a health
            // signal; PERMISSION_DENIED/SCHEMA_VIOLATION are the caller's fault,
            // not the server's, so they never trip the breaker.
            if (e.retryable) breaker.recordFailure()
            return CommandResult.Err(e.code, e.message, e.retryable)
        }
        // The transport round-tripped — the server is healthy even if the tool
        // itself reports isError below.
        breaker.recordSuccess()

        val content = result["content"] ?: JsonNull
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (isError) {
            CommandResult.Err(
                code = "PLUGIN_ERROR",
                message = "MCP tool '$toolName' reported an error",
            )
        } else {
            CommandResult.Ok(value = buildJsonObject { put("content", content) })
        }
    }
}
