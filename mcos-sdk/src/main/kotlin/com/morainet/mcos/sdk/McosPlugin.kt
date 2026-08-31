package com.morainet.mcos.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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

    /**
     * Optional device-information service (battery / Wi-Fi / screen /
     * volume / location / brightness). Null on hosts without a device
     * platform (e.g. plain JVM). Plugins must surface UNAVAILABLE —
     * never fabricated telemetry — when null.
     */
    val deviceInfo: DeviceInfoService? get() = null

    /**
     * Optional system clipboard. Null on hosts without a clipboard;
     * a host whose clipboard is restricted (Android background limits)
     * returns null from [ClipboardService.get] rather than fabricating
     * text. Clipboard text is untrusted input (08-security.md 11.1).
     */
    val clipboard: ClipboardService? get() = null

    /**
     * Optional haptics (vibration). Null on hosts without a vibrator —
     * plugins must surface UNAVAILABLE rather than a fake success.
     */
    val haptics: HapticsService? get() = null

    /**
     * Optional system event bus publisher (03-runtime.md §11). Null on hosts
     * without an event bus; plugins must surface UNAVAILABLE rather than a
     * fake success. Publishing is a write-class side effect — an emitted
     * event can trigger privileged automations (armed recipes, 05 §9.2).
     */
    val events: EventPublisher? get() = null

    /**
     * Optional per-plugin sandbox storage (04-plugin-sdk.md 6.1). All paths
     * are relative to the calling plugin's namespaced directory — the
     * runtime namespaces the view per execution, and implementations reject
     * any path that would escape the sandbox root. Null on hosts without
     * local storage; plugins must surface UNAVAILABLE rather than a fake
     * success. Secrets MUST NOT be stored here (08-security.md 9) — use
     * [SecureStore].
     */
    val sandbox: SandboxFileService? get() = null
}

/**
 * Plugin-namespaced sandbox storage (04-plugin-sdk.md 6.1). Every path is
 * relative to the calling plugin's own sandbox directory; escaping it is a
 * hard error, not a fallback. The as-built API is byte-based rather than
 * the spec's streaming flows — the drift is recorded in the spec.
 */
interface SandboxFileService {
    /**
     * Read a sandbox-relative file.
     * @return the file's bytes, or null when it does not exist.
     */
    suspend fun read(path: String): ByteArray?

    /**
     * Write bytes to a sandbox-relative path, creating parent directories
     * as needed. [append] concatenates instead of overwriting.
     */
    suspend fun write(path: String, data: ByteArray, append: Boolean = false)

    /**
     * Stat a sandbox-relative path.
     * @return the entry, or null when it does not exist.
     */
    suspend fun stat(path: String): SandboxEntry?

    /**
     * Delete a sandbox-relative file or empty directory.
     * @return true when something was deleted, false when absent.
     */
    suspend fun delete(path: String): Boolean

    /** Non-recursive listing of a sandbox-relative directory (empty [dir] = the sandbox root). */
    suspend fun list(dir: String): List<SandboxEntry>

    /**
     * Create a unique temporary file inside the sandbox.
     * @return the temp file's sandbox-relative path.
     */
    suspend fun tempFile(prefix: String = "mcos", suffix: String = ".tmp"): String
}

/** A sandbox entry: [size] is null for directories. */
data class SandboxEntry(val path: String, val isDir: Boolean, val size: Long?)

/**
 * Publishes an event onto the system event bus (03-runtime.md §11.1).
 * The event is delivered to every matching subscriber (at-most-once,
 * no persistence).
 */
interface EventPublisher {
    /**
     * @param type dotted event type, e.g. `"wifi.connected"`.
     * @param payload structured event data.
     */
    suspend fun publish(type: String, payload: JsonObject)
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

/**
 * Device telemetry + brightness control. Implementations report the REAL
 * host state — a datum the host cannot determine (e.g. Wi-Fi SSID without
 * the location permission) is returned as null, never guessed.
 */
interface DeviceInfoService {
    suspend fun battery(): BatteryInfo
    suspend fun wifi(): WifiInfo
    suspend fun screen(): ScreenInfo
    suspend fun volume(): VolumeInfo

    /** Last known location, or null when the host has no fix. */
    suspend fun location(): LocationInfo?
    suspend fun brightness(): BrightnessInfo

    /** Set screen brightness (0-255). Implementations surface permission
     *  failures as errors instead of pretending the level changed. */
    suspend fun setBrightness(level: Int)
}

data class BatteryInfo(val percent: Int, val charging: Boolean, val temperatureC: Int? = null)

/** [ssid]/[strength] are null when unavailable (e.g. no location permission on Android 10+). */
data class WifiInfo(val connected: Boolean, val ssid: String? = null, val strength: Int? = null)

data class ScreenInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotation: Int,
    val brightness: Int? = null,
)

data class VolumeInfo(
    val musicPercent: Int,
    val ringPercent: Int? = null,
    val alarmPercent: Int? = null,
)

data class LocationInfo(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val timestampMs: Long? = null,
)

data class BrightnessInfo(val level: Int, val auto: Boolean)

/**
 * System clipboard. [get] returns null when the clipboard is empty or the
 * host cannot read it (Android restricts background access) — implementations
 * must not fabricate text. Returned text is untrusted input (08-security.md 11.1).
 */
interface ClipboardService {
    suspend fun set(text: String)
    suspend fun get(): String?
}

/** Haptic feedback (vibration). */
interface HapticsService {
    suspend fun vibrate(durationMs: Int)
}

interface FileService {
    /** List files matching a content URI or directory. System-media queries only; no arbitrary filesystem access. */
    suspend fun list(uri: String, mimeType: String? = null): List<FileEntry>

    /**
     * Search photos in the system media store, optionally bounded by capture
     * time. Hosts with a real media store (Android MediaStore) override this
     * to push the filters into the native query; the default implementation
     * lists the images collection and filters client-side (best effort).
     *
     * @param mimeType MIME filter, e.g. image/jpeg
     * @param afterMs  only entries modified at/after this epoch millis
     * @param beforeMs only entries modified at/before this epoch millis
     * @param limit    maximum number of entries, newest first
     */
    suspend fun searchPhotos(
        mimeType: String = "image/*",
        afterMs: Long? = null,
        beforeMs: Long? = null,
        limit: Int = 200,
    ): List<FileEntry> = list("media://images", mimeType)
        .filter { entry ->
            val modified = entry.dateModifiedMs
            modified == null ||
                (afterMs == null || modified >= afterMs) &&
                (beforeMs == null || modified <= beforeMs)
        }
        .take(limit)
}

/**
 * Policy-aware HTTP egress (04-plugin-sdk.md 6.2). Every call passes the
 * kernel's per-call scope check before any connection is opened.
 *
 * `websocket()` from the spec sketch is P2 and deliberately absent — adding
 * an unimplementable abstract member would force hosts into fake success,
 * which this SDK never does (04-plugin-sdk.md 6 status note).
 */
interface NetService {
    /** Issue an HTTP request. Egress policy checked per-call. */
    suspend fun request(req: HttpRequest): HttpResponse
}

/**
 * One outbound HTTP request (04-plugin-sdk.md 6.2). [body] is raw bytes —
 * binary payloads ride unchanged; text protocols pass encoded UTF-8.
 * [timeoutMs] caps this single call (connect + read); the Executor's
 * command deadline still applies on top of it.
 */
data class HttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Long = 30_000,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return method == other.method && url == other.url && headers == other.headers &&
            body.contentEquals(other.body) && timeoutMs == other.timeoutMs
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + timeoutMs.hashCode()
        return result
    }
}

/**
 * An HTTP response (04-plugin-sdk.md 6.2). [headers] is a multi-map —
 * repeated headers (e.g. `Set-Cookie`) are preserved as separate values.
 * [body] is raw bytes (empty when the response carried none).
 */
data class HttpResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    /** UTF-8 view of [body] — the common case for MCOS's text protocols. */
    val bodyText: String get() = body.decodeToString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return status == other.status && headers == other.headers && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

interface UiService {
    /** Start an Android Activity for result. Returns result data or null. */
    suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>?

    /**
     * Show a transient toast (04-plugin-sdk.md 6.3). The default throws
     * [McosException] UNAVAILABLE so hosts without a UI surface an honest
     * failure instead of a silent no-op.
     */
    suspend fun toast(message: String) {
        throw McosException("UNAVAILABLE", "Toast is not available on this host")
    }
}

interface SecureStore {
    /** Get a stored secret by key. Never returned to Planner—only plugins see it. */
    suspend fun get(key: String): ByteArray?

    /** Store a secret by key. */
    suspend fun put(key: String, value: ByteArray)

    /** Remove a secret by key. */
    suspend fun remove(key: String)

    /**
     * Every key in this plugin's namespace (04-plugin-sdk.md 6.4) — the
     * enumeration secret hygiene needs: uninstall cleanup, rotation, and
     * "what does this plugin hold?" disclosure.
     */
    suspend fun keys(): Set<String>
}

interface Clock {
    /** Wall clock (04-plugin-sdk.md 6.5). */
    fun now(): Instant

    /**
     * Monotonic source for elapsed-time measurement — immune to NTP steps,
     * unlike [now]. Process-local by nature; never compared across processes.
     */
    fun monotonicMs(): Long

    /**
     * Epoch-millis convenience over [now] — epoch-ms remains the wire format
     * of the IR, audit trail, and schedule store, so internal call sites keep
     * this spelling instead of re-deriving it everywhere.
     */
    fun nowMs(): Long = now().toEpochMilliseconds()
}

interface JsonService {
    /** Parse JSON string to structured form. */
    fun parse(json: String): JsonElement
}

/**
 * Read-only Memory view for plugins — same as [03-runtime.md 12].
 */
interface MemoryFacade {
    suspend fun get(path: String): JsonElement?
    suspend fun resolveRef(ref: String, semanticType: String? = null): ResolveResult
}

/**
 * Result of reference resolution, per [07-memory.md 6.0].
 *
 * - [Resolved] carries the concrete id and a confidence score in `[0..1]`.
 * - [Ambiguous] lists candidate paths when the top two scores differ by less
 *   than the ambiguity threshold — the Planner should emit a `Clarify`.
 * - [NotFound] carries a machine-readable reason
 *   (e.g. `"ref_unresolvable"`, `"filtered_out"`).
 */
sealed class ResolveResult {
    data class Resolved(val id: String, val confidence: Float = 1.0f) : ResolveResult()
    data class Ambiguous(val candidates: List<String>) : ResolveResult()
    data class NotFound(val reason: String = "ref_unresolvable") : ResolveResult()
}

/**
 * Write outcome for [MemoryStore.put], per [07-memory.md 5.1].
 */
enum class WriteStatus { CREATED, UPDATED, CONFLICT, REJECTED }

/**
 * Risk level of a memory fact; drives the conflict confirmation policy
 * ([07-memory.md 5.2]).
 */
enum class MemoryCategory { PREFERENCE, PLACE, PERSON, DEVICE, PAYMENT, PERMISSION, OTHER }

/**
 * Details of a cross-path semantic conflict detected during a write.
 */
data class MemoryConflict(
    val existingPath: String,
    val existingValue: JsonElement,
    val similarity: Float,
    val category: MemoryCategory,
)

/**
 * Result of a memory write, per [07-memory.md 5.1].
 *
 * @property status CREATED (new fact), UPDATED (same-path overwrite, old value
 *   soft-deleted into history), CONFLICT (cross-path semantic duplicate, write
 *   withheld), or REJECTED (category policy refused the write).
 * @property supersededPath History key of the replaced value, e.g.
 *   `"people.tom.phone@2026-07-01T..."`, when [status] is UPDATED.
 * @property conflict Details when [status] is CONFLICT.
 */
data class MemoryWriteResult(
    val status: WriteStatus,
    val supersededPath: String? = null,
    val conflict: MemoryConflict? = null,
)

// ─── Supporting value types ──────────────────────────────────────────────

@Serializable
data class FileEntry(
    val uri: String,
    val name: String,
    val mimeType: String? = null,
    val size: Long? = null,
    /** Last-modified / capture time in epoch millis, when the host can determine it. */
    val dateModifiedMs: Long? = null
)
