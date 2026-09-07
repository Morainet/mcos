package com.morainet.mcos.android

import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.plugin.LoadResult
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.security.permission.PermissionKernel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One configured MCP server, in its persisted form (04 §10 per-server
 * enablement). The bearer token is deliberately **not** a field: it lives in
 * the [SecureStore] under `mcp.secret.<id>` and only the key *name* ever
 * reaches the bridged config (04 §11.1 — the raw token must never enter the
 * plugin manifest, the IR, or the audit trail).
 *
 * [pluginId] is the registry id of the synthesized plugin, learned from the
 * bridge at enable time and persisted so a later disable/remove can unregister
 * without re-running discovery. It is null before the first successful enable
 * (and in records persisted by pre-item-40 hosts, which carried only
 * id/endpoint/enabled — the parsing side treats it as absent).
 */
data class McpServerRecord(
    val id: String,
    val endpoint: String,
    val enabled: Boolean,
    val pluginId: String? = null,
    /**
     * The server's discovered tool catalog, cached after the first successful
     * enable so per-tool enable/disable and the UI list survive without a
     * reconnect. Empty until the first discovery. A tool with `enabled = false`
     * is not registered on the next enable (per-tool enablement); an unmapped
     * tool (`mapped = false`) can never be registered.
     */
    val tools: List<McpToolRecord> = emptyList(),
)

/**
 * One tool advertised by an MCP server, in persisted form. [enabled] is the
 * user's per-tool choice (defaults to on); [mapped] records whether its schema
 * was convertible at discovery — an unmapped tool stays visible in the UI but
 * can never be registered (§12.4 fail-closed).
 */
data class McpToolRecord(
    val name: String,
    val description: String? = null,
    val commandId: String,
    val enabled: Boolean = true,
    val mapped: Boolean = true,
)

/**
 * Host seam for bridging one configured MCP server into a plugin. The SDK
 * controller drives the *lifecycle* (list, secrets, enable/disable, restore);
 * the *discovery* is host-supplied so the library stays free of any MCP
 * client dependency — the demo shell wires this to `McpAdapter.discover`
 * (plugins:mcos-plugin-mcp), an integrating host may wire its own bridge.
 */
interface McpServerBridge {

    /**
     * Discover the server's tools and return the synthesized plugin. Throws
     * on transport/protocol failure — the controller maps that to an honest
     * [McpEnableResult.Error] and leaves the record disabled.
     *
     * @param secretKey SecureStore key holding the bearer token, or null when
     *        no token was configured; discovery authenticates with it, the
     *        synthesized handlers reference it as a `{{secret.*}}` template.
     * @param enabledTools When non-null, only these tool names are registered
     *        (per-tool enablement); the returned [BridgedMcpServer.tools] still
     *        reports the full catalog. Null registers every mappable tool.
     */
    suspend fun discover(record: McpServerRecord, secretKey: String?, enabledTools: Set<String>?): BridgedMcpServer

    /**
     * Registry id for [serverId] *without* running discovery — used when a
     * legacy record (persisted before the controller learned plugin ids) is
     * disabled or removed while enabled. Bridging adapters derive it
     * deterministically; hosts without such a contract return null and the
     * unregister is skipped.
     */
    fun pluginIdFor(serverId: String): String? = null
}

/** One bridged server: the ready-to-register plugin, the tools it had to drop, and the full catalog. */
data class BridgedMcpServer(
    val plugin: McosPlugin,
    val skippedTools: List<SkippedBridgedTool> = emptyList(),
    /**
     * The server's full advertised tool catalog (mapped + unmapped, registered
     * or filtered out). The controller caches this into
     * [McpServerRecord.tools]; empty means the bridge didn't report one (a
     * legacy bridge), in which case the controller leaves the record's tools
     * untouched.
     */
    val tools: List<BridgedMcpTool> = emptyList(),
)

/** A discovered tool that was not bridged because its schema is unmappable (02 §12.4). */
data class SkippedBridgedTool(val toolName: String, val unmappedType: String, val reason: String)

/** One advertised tool, as reported by the bridge (host-neutral — no MCP client types leak in). */
data class BridgedMcpTool(
    val name: String,
    val description: String?,
    val commandId: String,
    val mapped: Boolean,
)

/** Outcome of [McpServerController.addServer]. */
sealed interface McpAddResult {
    data class Added(val record: McpServerRecord) : McpAddResult
    data object Duplicate : McpAddResult
    data object Invalid : McpAddResult
}

/** Outcome of [McpServerController.setEnabled] / [McpServerController.reconnectEnabled]. */
sealed interface McpEnableResult {
    /** Registered at builtin trust; permissions granted and onLoad ran. */
    data class Enabled(
        val pluginId: String,
        val commandsRegistered: Int,
        val skipped: List<SkippedBridgedTool>,
    ) : McpEnableResult

    /** The trust gate refused the load. */
    data class Denied(val code: String, val reason: String?) : McpEnableResult

    /** The load pipeline failed. */
    data class Failed(val message: String?) : McpEnableResult

    /** Discovery threw (transport/protocol). */
    data class Error(val message: String?) : McpEnableResult

    /** Unregistered; the count is the commands removed (0 for a never-enabled legacy record). */
    data class Disabled(val commandsUnregistered: Int) : McpEnableResult
}

/** Outcome of [McpServerController.removeServer]. */
sealed interface McpRemoveResult {
    data class Removed(val commandsUnregistered: Int) : McpRemoveResult
    data object Unknown : McpRemoveResult
}

/**
 * Outcome of [McpServerController.importJsonConfig] — a tally of what an
 * `mcpServers` config produced. [skippedStdio] counts stdio (command-based)
 * entries this HTTP-only host cannot bridge; [invalid] counts malformed
 * entries and a malformed top-level document (invalid = 1, all others 0).
 */
data class McpImportResult(
    val added: Int = 0,
    val duplicates: Int = 0,
    val skippedStdio: Int = 0,
    val invalid: Int = 0,
    val addedIds: List<String> = emptyList(),
)

/**
 * Owns the MCP server list for a host process (04 §10): persistence (JSON
 * under the `mcp_servers` SecureStore key — tokens stay under
 * `mcp.secret.<id>`), the enable/disable lifecycle through the runtime
 * install pipeline at builtin trust, and the best-effort restore of servers
 * the user left enabled. Extracted from the demo shell's ViewModel (item 40)
 * so any integrating app gets the same management semantics behind its own UI.
 *
 * The controller is the single writer of the list; callers read state back
 * via [servers] after each operation. Failures never throw — they return
 * honest results so the UI layer can surface them.
 */
class McpServerController(
    private val secureStore: SecureStore,
    private val runtime: McosRuntime,
    private val registry: CommandRegistry,
    private val hostServices: HostServices,
    private val permissionKernel: PermissionKernel,
    private val bridge: McpServerBridge,
) {

    private var records: MutableList<McpServerRecord> = mutableListOf()
    private var loaded = false

    /** The configured servers (loads + migrates once per controller instance). */
    suspend fun servers(): List<McpServerRecord> {
        ensureLoaded()
        return records.toList()
    }

    /**
     * Add a server (disabled — connecting is an explicit [setEnabled]). The
     * token, if any, goes to the SecureStore before the record is persisted,
     * so an enabled record always finds its secret. Blank id/endpoint is
     * [McpAddResult.Invalid]; a duplicate id is [McpAddResult.Duplicate].
     */
    suspend fun addServer(id: String, endpoint: String, token: String?): McpAddResult {
        ensureLoaded()
        val cleanId = id.trim()
        val cleanEndpoint = endpoint.trim()
        if (cleanId.isBlank() || cleanEndpoint.isBlank()) return McpAddResult.Invalid
        if (records.any { it.id == cleanId }) return McpAddResult.Duplicate
        if (token != null) putText(secretKeyOf(cleanId), token)
        val record = McpServerRecord(cleanId, cleanEndpoint, enabled = false)
        records += record
        persist()
        return McpAddResult.Added(record)
    }

    /**
     * Remove a server: unregister its commands when it has a live plugin id,
     * drop its secret and its record. Never throws for an unknown id.
     */
    suspend fun removeServer(id: String): McpRemoveResult {
        ensureLoaded()
        val record = records.find { it.id == id } ?: return McpRemoveResult.Unknown
        val removed = unregisterOf(record)
        secureStore.remove(secretKeyOf(id))
        records.remove(record)
        persist()
        return McpRemoveResult.Removed(removed)
    }

    /**
     * Toggle a server on (discover + register at builtin trust, grant declared
     * permissions, run onLoad) or off (unregister its commands, live — no
     * restart). Returns null for an unknown id.
     */
    suspend fun setEnabled(id: String, enabled: Boolean): McpEnableResult? {
        ensureLoaded()
        val record = records.find { it.id == id } ?: return null
        return if (enabled) enable(record) else disable(record)
    }

    /**
     * Toggle one tool of a server on or off (per-tool enablement). Persists the
     * choice on the record; when the server is currently enabled it re-runs
     * discovery+registration so the change takes effect live (the enable path
     * re-registers the plugin with the new filter). Returns null for an unknown
     * server or tool; [McpEnableResult.Disabled] when the change applied while
     * the server was off (nothing to re-register).
     */
    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean): McpEnableResult? {
        ensureLoaded()
        val record = records.find { it.id == serverId } ?: return null
        if (record.tools.none { it.name == toolName }) return null
        update(serverId) { r ->
            r.copy(tools = r.tools.map { if (it.name == toolName) it.copy(enabled = enabled) else it })
        }
        val updated = records.find { it.id == serverId } ?: return null
        // Re-register with the new filter only when the server is live.
        return if (updated.enabled) enable(updated) else McpEnableResult.Disabled(0)
    }

    /**
     * Import servers from a standard MCP client config (the `mcpServers` map
     * used by Claude Desktop / Cursor et al.). Each entry with a `url` (or
     * `endpoint`) is added as a streamable-HTTP server (disabled — connecting
     * stays an explicit [setEnabled]); a bearer token is read from
     * `headers.Authorization` (the `Bearer ` prefix is stripped) and stored in
     * the SecureStore. Entries with a `command` (stdio transport) are counted
     * as [McpImportResult.skippedStdio] — this host bridges HTTP servers only.
     * A duplicate id is skipped and counted, never overwritten.
     */
    suspend fun importJsonConfig(text: String): McpImportResult {
        ensureLoaded()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return McpImportResult(invalid = 1)
        val servers = root["mcpServers"]?.let { it as? JsonObject }
            ?: return McpImportResult(invalid = 1)

        var added = 0
        var duplicates = 0
        var skippedStdio = 0
        var invalid = 0
        val addedIds = mutableListOf<String>()

        for ((id, elem) in servers) {
            val obj = elem as? JsonObject
            if (obj == null) { invalid++; continue }
            // stdio transport (command/args) is not bridgeable by this host.
            if (obj["command"] != null) { skippedStdio++; continue }
            val endpoint = (obj["url"] ?: obj["endpoint"])?.jsonPrimitive?.contentOrNull
            if (endpoint.isNullOrBlank()) { invalid++; continue }
            val token = obj["headers"]?.let { it as? JsonObject }
                ?.get("Authorization")?.jsonPrimitive?.contentOrNull
                ?.removePrefix("Bearer ")?.trim()?.ifBlank { null }
            when (addServer(id, endpoint, token)) {
                is McpAddResult.Added -> { added++; addedIds += id }
                McpAddResult.Duplicate -> duplicates++
                McpAddResult.Invalid -> invalid++
            }
        }
        return McpImportResult(added, duplicates, skippedStdio, invalid, addedIds)
    }

    /**
     * Best-effort restore of the servers the user left enabled (called on
     * host attach). A failing server is reported and left recorded for a
     * manual retry — one bad endpoint must not block the others.
     */
    suspend fun reconnectEnabled(): Map<String, McpEnableResult> {
        ensureLoaded()
        return records.filter { it.enabled }.associate { it.id to enable(it) }
    }

    // ── internals ──────────────────────────────────────────────────────

    private suspend fun enable(record: McpServerRecord): McpEnableResult {
        val secretKey = secretKeyOf(record.id).takeIf { secureStore.get(it) != null }
        // Per-tool enablement: pass the user's enabled tool names as the filter
        // once a catalog has been discovered. A first-time enable (no catalog
        // yet) passes null so every mappable tool registers, then we cache the
        // discovered catalog for the next time.
        val enabledFilter = record.tools
            .takeIf { it.isNotEmpty() }
            ?.filter { it.enabled && it.mapped }
            ?.map { it.name }
            ?.toSet()
        return try {
            val bridged = bridge.discover(record, secretKey, enabledFilter)
            val plugin = bridged.plugin
            when (val result = runtime.loadPlugin(
                packageId = plugin.manifest.id,
                version = plugin.manifest.version,
                builtin = true,
                plugin = plugin,
            )) {
                is LoadResult.Installed -> {
                    // First-party bridging code: grant the declared permissions
                    // so the commands clear the Stage-6 hard gate (the bridged
                    // tools keep their network/destructive side-effect class,
                    // so the kernel and egress policy still govern every call).
                    PluginPermissionBootstrap.grantAll(permissionKernel, plugin)
                    plugin.onLoad(hostServices)
                    update(record.id) {
                        it.copy(
                            enabled = true,
                            pluginId = plugin.manifest.id,
                            tools = mergeToolCatalog(it.tools, bridged.tools),
                        )
                    }
                    McpEnableResult.Enabled(plugin.manifest.id, result.commandsRegistered, bridged.skippedTools)
                }
                is LoadResult.Denied -> {
                    update(record.id) { it.copy(enabled = false) }
                    McpEnableResult.Denied(result.code, result.reason)
                }
                is LoadResult.Failed -> {
                    update(record.id) { it.copy(enabled = false) }
                    McpEnableResult.Failed(result.message)
                }
            }
        } catch (e: Exception) {
            update(record.id) { it.copy(enabled = false) }
            McpEnableResult.Error(e.message)
        }
    }

    private suspend fun disable(record: McpServerRecord): McpEnableResult {
        val removed = unregisterOf(record)
        update(record.id) { it.copy(enabled = false) }
        return McpEnableResult.Disabled(removed)
    }

    /** Unregister by the learned [McpServerRecord.pluginId], falling back to the bridge's legacy guess. */
    private fun unregisterOf(record: McpServerRecord): Int =
        (record.pluginId ?: bridge.pluginIdFor(record.id))?.let { registry.unregister(it) } ?: 0

    /**
     * Fold a fresh discovery catalog into the persisted one, preserving each
     * tool's user-chosen [McpToolRecord.enabled] flag across reconnects. New
     * tools default to enabled; tools no longer advertised drop off. An empty
     * [discovered] (a legacy bridge that reports no catalog) leaves [existing]
     * untouched.
     */
    private fun mergeToolCatalog(
        existing: List<McpToolRecord>,
        discovered: List<BridgedMcpTool>,
    ): List<McpToolRecord> {
        if (discovered.isEmpty()) return existing
        val prevEnabled = existing.associate { it.name to it.enabled }
        return discovered.map { t ->
            McpToolRecord(
                name = t.name,
                description = t.description,
                commandId = t.commandId,
                enabled = prevEnabled[t.name] ?: true,
                mapped = t.mapped,
            )
        }
    }

    private suspend fun update(id: String, transform: (McpServerRecord) -> McpServerRecord) {
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) {
            records[index] = transform(records[index])
            persist()
        }
    }

    /** Load once, migrating the item-31 single-server keys into the list on first read. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        records = loadRecords().toMutableList()
        val legacyId = getText(LEGACY_SERVER_ID)
        if (legacyId != null) {
            val legacyEndpoint = getText(LEGACY_ENDPOINT)
            if (legacyEndpoint != null && records.none { it.id == legacyId }) {
                records += McpServerRecord(legacyId, legacyEndpoint, enabled = false)
            }
            secureStore.remove(LEGACY_SERVER_ID)
            secureStore.remove(LEGACY_ENDPOINT)
            persist()
        }
    }

    private suspend fun loadRecords(): List<McpServerRecord> {
        val raw = getText(SERVERS_KEY) ?: return emptyList()
        // Lenient parse (rule: hosts persist with the runtime JSON API — this
        // module has no serialization compiler plugin): a malformed entry is
        // skipped, never fatal; unknown fields (future) are ignored.
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val endpoint = o["endpoint"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val tools = o["tools"]?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { te ->
                        val to = te.jsonObject
                        val name = to["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        McpToolRecord(
                            name = name,
                            description = to["description"]?.jsonPrimitive?.contentOrNull,
                            commandId = to["commandId"]?.jsonPrimitive?.contentOrNull ?: "mcp.$id.$name",
                            enabled = to["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                            mapped = to["mapped"]?.jsonPrimitive?.booleanOrNull ?: true,
                        )
                    } ?: emptyList()
                McpServerRecord(
                    id = id,
                    endpoint = endpoint,
                    enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    pluginId = o["pluginId"]?.jsonPrimitive?.contentOrNull,
                    tools = tools,
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun persist() {
        val arr = buildJsonArray {
            records.forEach { r ->
                add(
                    buildJsonObject {
                        put("id", r.id)
                        put("endpoint", r.endpoint)
                        put("enabled", r.enabled)
                        r.pluginId?.let { put("pluginId", it) }
                        if (r.tools.isNotEmpty()) {
                            put("tools", buildJsonArray {
                                r.tools.forEach { t ->
                                    add(buildJsonObject {
                                        put("name", t.name)
                                        t.description?.let { put("description", it) }
                                        put("commandId", t.commandId)
                                        put("enabled", t.enabled)
                                        put("mapped", t.mapped)
                                    })
                                }
                            })
                        }
                    }
                )
            }
        }
        putText(SERVERS_KEY, arr.toString())
    }

    // [SecureStore] is byte-valued (04-plugin-sdk.md 6.4); every record this
    // controller persists is text, so the UTF-8 hop lives in these two seams.
    private suspend fun putText(key: String, value: String) =
        secureStore.put(key, value.encodeToByteArray())

    private suspend fun getText(key: String): String? = secureStore.get(key)?.decodeToString()

    companion object {
        /** SecureStore key holding the server list (id/endpoint/enabled/pluginId — never tokens). */
        const val SERVERS_KEY = "mcp_servers"

        private const val LEGACY_SERVER_ID = "mcp_server_id"
        private const val LEGACY_ENDPOINT = "mcp_endpoint"

        /** SecureStore key holding server `<id>`'s bearer token. */
        fun secretKeyOf(serverId: String): String = "mcp.secret.$serverId"
    }
}

private val json = Json { ignoreUnknownKeys = true }
