package com.mcos.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

// ─── Core Plugin & Handler interfaces ─────────────────────────────────────

/**
 * Plugin entry point. One instance per plugin process.
 * Matches [04-plugin-sdk.md 5].
 */
interface McosPlugin {
    val manifest: PluginManifest

    /** Called after plugin is loaded and registered. */
    suspend fun onLoad(services: HostServices)

    /** Called before plugin is unloaded. Drain in-flight work. */
    suspend fun onUnload()

    /** Returns all command handlers provided by this plugin. */
    fun handlers(): Map<String, CommandHandler>
}

/**
 * Handler for a single command. One instance per command per plugin.
 */
interface CommandHandler {
    /** Execute the command with the given context. */
    suspend fun invoke(ctx: ExecutionContext): CommandResult

    /** Optional cancellation hook. Called on timeout or user cancel. */
    suspend fun cancel(ctx: ExecutionContext) {}
}

// ─── Host Services — the plugin-facing Runtime facade ─────────────────────

/**
 * Facade providing controlled access to Runtime services.
 * Matches [01-architecture.md 11.1], [04-plugin-sdk.md 6].
 */
interface HostServices {
    val files: FileService
    val net: NetService
    val ui: UiService
    val secureStore: SecureStore
    val clock: Clock
    val json: JsonService
    val memory: MemoryFacade

    /**
     * Optional platform notification service. Null on hosts without
     * notification support (e.g. plain JVM). Plugins must fall back to
     * a stub result when null.
     */
    val notifications: NotificationService? get() = null

    /**
     * Optional platform media-processing service (compress/resize).
     * Null on hosts without media support. Plugins must fall back to
     * a stub result when null.
     */
    val media: MediaService? get() = null
}

/**
 * Posts system notifications. Provided by hosts with a notification
 * manager (Android NotificationManager, desktop toasts, etc.).
 */
interface NotificationService {
    /**
     * Post a notification with the given title and text.
     * @return an identifier for the posted notification (e.g. channel id)
     */
    suspend fun notify(title: String, text: String): String
}

/**
 * Media processing for image artifacts. Provided by hosts with
 * bitmap processing capability.
 */
interface MediaService {
    /**
     * Compress the given image content URIs to JPEG and return the
     * compressed output content URIs (same order, nulls dropped).
     *
     * @param uris source image content URIs
     * @param quality JPEG quality 1-100
     * @param maxWidth optional max output width (keeps aspect ratio)
     * @param maxHeight optional max output height (keeps aspect ratio)
     */
    suspend fun compress(
        uris: List<String>,
        quality: Int,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
    ): List<String>
}

interface FileService {
    /** List files matching a content URI or directory. System-media queries only; no arbitrary filesystem access. */
    suspend fun list(uri: String, mimeType: String? = null): List<FileEntry>
}

interface NetService {
    /** Issue an HTTP request. Egress policy checked per-call. */
    suspend fun request(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): NetResponse
}

interface UiService {
    /** Start an Android Activity for result. Returns result data or null. */
    suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>?
}

interface SecureStore {
    /** Get a stored secret by key. Never returned to Planner—only plugins see it. */
    suspend fun get(key: String): String?

    /** Store a secret by key. */
    suspend fun put(key: String, value: String)

    /** Remove a secret by key. */
    suspend fun remove(key: String)
}

interface Clock {
    /** Current time in epoch millis */
    fun nowMs(): Long
}

interface JsonService {
    /** Parse JSON string to structured form. */
    fun parse(json: String): kotlinx.serialization.json.JsonElement
}

/**
 * Read-only Memory view for plugins — same as [03-runtime.md 12].
 */
interface MemoryFacade {
    suspend fun get(path: String): kotlinx.serialization.json.JsonElement?
    suspend fun resolveRef(ref: String, semanticType: String? = null): ResolveResult
}

sealed class ResolveResult {
    data class Resolved(val id: String) : ResolveResult()
    data class Ambiguous(val candidates: List<String>) : ResolveResult()
    data object NotFound : ResolveResult()
}

// ─── Supporting value types ──────────────────────────────────────────────

@kotlinx.serialization.Serializable
data class FileEntry(
    val uri: String,
    val name: String,
    val mimeType: String? = null,
    val size: Long? = null
)

@kotlinx.serialization.Serializable
data class NetResponse(
    val status: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap()
)
