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
 * A user-configured MCP server ([04-plugin-sdk.md §10]). [token], when
 * present, is sent as an `Authorization: Bearer` header — the P2 spike keeps
 * secrets in config; per-server `SecureStore` wiring is P3 ([10-roadmap.md
 * §5.7]).
 */
data class McpServerConfig(
    val id: String,
    val endpoint: String,
    val token: String? = null,
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

    /** Connect via the host [net], discover tools, and synthesize a plugin. */
    suspend fun discover(net: NetService, config: McpServerConfig): McpDiscovery {
        val headers = config.token
            ?.let { mapOf("Authorization" to "Bearer $it") }
            ?: emptyMap()
        return discover(McpClient(net, config.endpoint, headers), config)
    }

    /** Discovery against an injected [client] (used by tests). */
    suspend fun discover(client: McpClient, config: McpServerConfig): McpDiscovery {
        val tools = client.listTools()
        val commands = mutableListOf<CommandManifestEntry>()
        val handlers = mutableMapOf<String, CommandHandler>()
        val skipped = mutableListOf<SkippedTool>()

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
                    handlers[commandId] = McpProxyHandler(client, tool.name)
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
}

/**
 * The synthesized plugin for one bridged MCP server. Its handlers were built
 * at discovery time and already hold a live [McpClient], so [onLoad] has no
 * work to do — the connection is established before registration.
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
 */
class McpProxyHandler(
    private val client: McpClient,
    private val toolName: String,
) : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val args = ctx.args as? JsonObject ?: JsonObject(emptyMap())
        val result = try {
            client.callTool(toolName, args)
        } catch (e: McpException) {
            return CommandResult.Err(e.code, e.message, e.retryable)
        }

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
