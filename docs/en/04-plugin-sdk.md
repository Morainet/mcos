# MCOS Plugin SDK Design

> **Status:** Draft  
> **Version:** 0.1.0  
> **Last Updated:** 2026-08-06  
> **Package:** `mcos-sdk`  
> **Depends on:** [02-command-protocol.md](./02-command-protocol.md), [03-runtime.md](./03-runtime.md)

---

## 1. Purpose

The Plugin SDK is how the ecosystem grows **without forking Runtime**.

A plugin packages:

- Manifest (`plugin.json`)  
- Command handlers  
- Permission declarations  
- Optional assets (icons, localized strings)  
- Optional MCP/HTTP bridges  

```text
plugin.json + handlers
        │
        ▼
  Plugin Loader
        │
        ▼
  Command Registry
        │
        ▼
     Executor
```

---

## 2. Design Goals

1. **One day to first plugin** for an Android engineer  
2. **Declarative safety** — permissions & schemas in manifest, not tribal knowledge  
3. **Versioned contracts** — command SemVer enforced  
4. **Test without device farm** for pure logic handlers  
5. **Forward-compatible** host APIs with deprecation windows  

---

## 3. Plugin Package Layout

```text
my-plugin/
├── plugin.json              # required manifest
├── README.md
├── src/main/kotlin/...      # handlers
├── src/main/res/            # icons / strings (Android)
├── schemas/                 # optional externalized JSON Schemas
│   ├── camera.capture.in.json
│   └── camera.capture.out.json
└── proguard-rules.pro       # if shipping AAR
```

Marketplace artifacts may be:

- AAR / APK feature module  
- Signed zip for dynamic load (policy-dependent)  
- Separate companion app exposing a Bound Service  

SDK docs focus on **logical** shape; packaging flavors are host concerns.

---

## 4. Manifest (`plugin.json`)

### 4.1 Example

```json
{
  "id": "mcos.plugin.camera",
  "name": "Camera",
  "version": "1.0.0",
  "minRuntimeVersion": "0.1.0",
  "description": "Capture and scan using device cameras",
  "provider": {
    "name": "MCOS",
    "url": "https://github.com/mcos-org"
  },
  "entry": "com.morainet.mcos.plugin.camera.CameraPlugin",
  "permissions": [
    {
      "type": "android",
      "name": "android.permission.CAMERA",
      "reason": "Take photos and scan codes"
    }
  ],
  "commands": [
    {
      "id": "camera.capture",
      "version": "1.0.0",
      "title": "Capture photo",
      "description": "Capture a still image",
      "sideEffectClass": "write",
      "idempotent": false,
      "timeoutMs": 60000,
      "permissions": [],
      "inputSchema": { "$ref": "schemas/camera.capture.in.json" },
      "outputSchema": { "$ref": "schemas/camera.capture.out.json" },
      "examples": ["camera.capture()", "camera.capture(facing=\"front\")"]
    },
    {
      "id": "camera.scan",
      "version": "1.0.0",
      "title": "Scan code",
      "sideEffectClass": "read",
      "inputSchema": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "format": {
            "type": "string",
            "enum": ["qr", "barcode", "any"],
            "default": "any"
          }
        }
      },
      "outputSchema": {
        "type": "object",
        "required": ["text"],
        "properties": {
          "text": { "type": "string" },
          "format": { "type": "string" }
        }
      }
    }
  ],
  "eventsEmitted": ["camera.capture.completed"],
  "eventsConsumed": [],
  "tags": ["media", "camera"]
}
```

### 4.2 Required Fields

| Field | Rule |
|-------|------|
| `id` | Reverse-DNS unique |
| `version` | SemVer |
| `minRuntimeVersion` | SemVer |
| `entry` | Fully qualified plugin class |
| `commands` | Non-empty for capability plugins |

### 4.3 Command Field Inheritance

Command `permissions` are **additive** to plugin-level permissions.

### 4.4 Plugin-Level Field Reference

The runtime-side `CommandDescriptor` produced from the manifest is normative in [01 §10](./01-architecture.md) (15-field table) and [02 §8](./02-command-protocol.md). The table below covers the **plugin-manifest-level** fields the author writes in `plugin.json`; runtime-only fields (`pluginId`, resolved `version`) are injected by the Loader and are not author-facing.

| Field | Type | Required | Default | Constraint |
|-------|------|----------|---------|------------|
| `id` | string | yes | — | Reverse-DNS, unique; matches `^[a-z][a-z0-9.]*$` |
| `name` | string | yes | — | Human-readable; localizable (§12) |
| `version` | string (SemVer) | yes | — | `MAJOR.MINOR.PATCH`; monotonic across publishes |
| `minRuntimeVersion` | string (SemVer) | yes | — | Must rise when using new Host APIs |
| `description` | string | yes | — | One-line; localizable |
| `provider` | `{ name, url }` | yes | — | `url` is the publisher's homepage |
| `entry` | string (FQ class) | yes | — | Implements `McosPlugin` |
| `permissions` | array | no | `[]` | Each `{ type: "android"|"mcos", name, reason }`; additive with per-command perms |
| `commands` | array | yes (capability plugin) | — | Non-empty; each entry per §4.4 command sub-table below |
| `namespaces` | array | no | derived from commands | Explicit namespace roots claimed (e.g. `["camera"]`); used in conflict arbitration ([02 §4.4](./02-command-protocol.md)) |
| `eventsEmitted` | array | no | `[]` | Event type prefixes this plugin publishes (e.g. `["camera.capture.completed"]`) |
| `eventsConsumed` | array | no | `[]` | Event type prefixes this plugin subscribes to; each requires `event.subscribe.<type>` scope |
| `tags` | array | no | `[]` | Free-form labels for marketplace filtering; `"cpu-bound"` is a reserved tag the Executor honors for dispatch ([01 §8](./01-architecture.md)) |
| `threadHint` | `"io"` \| `"cpu"` \| `"main"` | no | `"io"` | Plugin-wide dispatch hint; `"main"` restricted to `control`-class plugins needing UI-thread APIs ([01 §8](./01-architecture.md)) |
| `i18n` | object | no | — | Per-locale overrides; see §12 |
| `http` (per-command) | object | no | — | Declarative webhook; see §11 |

**Command-level fields** (inside `commands[]`). These map 1:1 to the `CommandDescriptor` fields in [01 §10](./01-architecture.md) / [02 §8](./02-command-protocol.md); the runtime-side table is authoritative on conflict.

| Field | Type | Required | Default | Constraint |
|-------|------|----------|---------|------------|
| `id` | string | yes | — | `namespace.name`, lowercase, ≤128 chars |
| `version` | string (SemVer) | yes | — | Command contract version; independent of plugin `version` |
| `title` | string | yes | — | Localizable |
| `description` | string | yes | — | Localizable |
| `sideEffectClass` | enum | yes | — | `read` \| `write` \| `network` \| `control` \| `destructive` ([01 §10.1](./01-architecture.md)) |
| `idempotent` | boolean | no | `false` | Gates Workflow auto-retry ([02 §9.4](./02-command-protocol.md)) |
| `timeoutMs` | integer | no | `60000` | Executor deadline; `∈ [1000, 600000]` |
| `permissions` | array | no | `[]` | Additive to plugin-level ([§4.3](#43-command-field-inheritance)) |
| `inputSchema` | JSON Schema | yes | — | Per §4.5; Draft 2020-12 |
| `outputSchema` | JSON Schema | yes | — | Draft 2020-12 |
| `aliases` | array | no | `[]` | Alternate command ids resolving to this one ([02 §4.5](./02-command-protocol.md)) |
| `examples` | array | no | `[]` | DSL strings; used for Planner few-shot + CLI help |
| `tags` | array | no | `[]` | Per-command tags; `"cpu-bound"` overrides plugin `threadHint` |
| `deprecated` | boolean | no | `false` | Hides from Planner suggestions; CLI warns |
| `replacedBy` | string \| null | no | `null` | Command id to migrate to; must point to a registered command at load |
| `http` | object | no | — | Declarative webhook (§11); if present, no Kotlin handler needed for this command |

### 4.5 Writing `inputSchema` (Author Guide)

`inputSchema` is JSON Schema Draft 2020-12. MCOS adds four `x-mcos-*` extensions (normative in [02 §5.3](./02-command-protocol.md)) that change how the Runtime processes arguments. Authors should know them:

| Extension | Effect | When to use |
|-----------|--------|-------------|
| `"x-mcos-secret": true` | Value is redacted in audit before storage (§[03 §13.3](./03-runtime.md)). | Passwords, tokens, credentials. |
| `"x-mcos-ref": true` | Value is treated as a natural-language reference; resolved to a concrete id at Stage 4 Expand via `MemoryFacade.resolveRef()`. | Device names (`"空调"`), place names, person names. |
| `"x-mcos-semantic": "device\|place\|person\|wifi\|..."` | Tells the ref resolver which Memory index to search. Pair with `x-mcos-ref`. | Improves disambiguation accuracy. |
| `"x-mcos-default-from-memory": "path.to.key"` | If the arg is absent, the Runtime injects the value from Memory at the given path (suspend, at Stage 4). | "default city", "default home scene". |

**Example — a command with a secret and a memory ref:**

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "deviceId": {
      "type": "string",
      "x-mcos-ref": true,
      "x-mcos-semantic": "device",
      "description": "Device name or label, e.g. \"空调\""
    },
    "apiToken": {
      "type": "string",
      "x-mcos-secret": true,
      "description": "Vendor API token"
    },
    "mode": {
      "type": "string",
      "enum": ["cool", "heat", "auto"],
      "default": "cool"
    }
  },
  "required": ["deviceId"]
}
```

**Type bounds cheat-sheet** (normative bounds in [02 §5.4](./02-command-protocol.md)): `string` ≤ 65536 code points; `integer` signed 64-bit; `bytes` (base64) ≤ 10 MiB; `duration` accepts ISO-8601 string OR integer ms. Use `additionalProperties: false` on object schemas unless you intentionally accept extra keys — the Planner will otherwise hallucinate fields.

**Authoring rules:**
- Always provide `description` on each property — the Planner reads these to decide which arg fills which field.
- Declare `required` explicitly; absence from `required` means optional.
- Use `enum` for closed value sets; the Runtime validates and rejects out-of-range values at Stage 5.
- `$ref` to external files (`schemas/*.in.json`) is supported and recommended for non-trivial schemas; the Loader resolves them at plugin load.

---

## 5. Core SDK Interfaces (Kotlin)

> ✅ **Implementation status:** `McosPlugin` / `CommandHandler` (including the `cancel()` hook) live in `mcos-sdk`, exercised by the four reference plugins and the full test suite. Known deltas from this spec: `CommandId` is a plain `String` typealias, and `retryable` is carried by the runtime's `McosException`→`CommandResult.Err` mapping. See [11-implementation-status.md](./11-implementation-status.md) §7.

```kotlin
interface McosPlugin {
    val manifest: PluginManifest
    suspend fun onLoad(services: HostServices)   // renamed from PluginHost per 01 §11.1
    suspend fun onUnload()
    fun handlers(): Map<CommandId, CommandHandler>
}

interface CommandHandler {
    suspend fun invoke(ctx: ExecutionContext): CommandResult
    suspend fun cancel(ctx: ExecutionContext) { /* optional; see §7.4 */ }
}

sealed class CommandResult {
    data class Ok(
        val value: JsonElement,
        val artifacts: List<Artifact> = emptyList(),   // Artifact: see 01 §11.3
    ) : CommandResult()

    data class Err(
        val code: String,                  // a McosErrorCode (01 §15.1) or plugin-namespaced code
        val message: String,
        val retryable: Boolean = false,
        val details: JsonObject = JsonObject(emptyMap()),  // adheres to 02 §8.3 shape B for the code
    ) : CommandResult()
}
```

> **`CommandId` / `Artifact`** are value classes defined in [01 §11.3–11.4](./01-architecture.md). **`HostServices`** is the unified plugin-facing facade ([01 §11.1](./01-architecture.md)); the historical `PluginHost` name is retired. **`McosException`** ([03 §9.5](./03-runtime.md)) is the sanctioned way for a handler to declare a specific error code by throwing — see §5.2 for when to use `CommandResult.Err` vs throwing `McosException`.

### 5.1 Progress

```kotlin
interface ProgressEmitter {
    suspend fun progress(percent: Int?, message: String? = null)
    suspend fun log(level: LogLevel, message: String)
}
```

### 5.2 Declaring Errors (`CommandResult.Err` vs `McosException`)

A handler has two ways to report a failure, and they are **not** interchangeable:

| Pattern | When | Mechanism |
|---------|------|-----------|
| Return `CommandResult.Err(code, message, retryable, details)` | **Expected** failure the handler detects and controls (e.g. "device offline", "invalid state"). The normal return path — no exception thrown. | Executor maps `Err` fields directly to the runtime `Failure` envelope. |
| Throw `McosException(code, message, retryable, details)` | An **exceptional** condition deep in a call stack where returning is awkward (e.g. a library you call throws, and you want to re-map it to a specific MCOS code without a try/catch wrapper). | Executor catches `McosException` and maps its fields directly — it does NOT pass through the generic `Throwable.toMcosError()` heuristic ([01 §10.3](./01-architecture.md)). |
| Throw any other `Throwable` | Unexpected crash. | Executor maps to `PLUGIN_ERROR` with a sanitized message; raw stack only in dev-mode audit. |

**Both channels produce the same runtime `Failure` envelope** ([02 §10.2](./02-command-protocol.md) shape B). The `code` MUST be a valid `McosErrorCode` ([01 §15.1](./01-architecture.md)) — e.g. `UNAVAILABLE`, `TIMEOUT`, `PERMISSION_DENIED`, `PLUGIN_ERROR` — or a plugin-namespaced code (e.g. `"camera.hardware_busy"`). The `details` object MUST conform to the per-code required-fields table in [02 §8.3](./02-command-protocol.md) shape B.

**`details` quick reference** (most common codes authors emit; full table in [02 §8.3](./02-command-protocol.md)):

| Code | `details` required |
|------|--------------------|
| `UNAVAILABLE` | `component: string` (e.g. `"camera"`) |
| `TIMEOUT` | (Executor fills `timeoutMs`/`elapsedMs`; plugins rarely emit this directly) |
| `PERMISSION_DENIED` | `permission: string`, `sideEffectClass: string` |
| `PLUGIN_ERROR` | none required; add plugin-specific context in optional fields |

**Prefer `CommandResult.Err` for expected failures** (cleaner, no exception overhead) and reserve `McosException` for cases where a dependency throws and you want to tag the failure without a wrapper. Example:

```kotlin
// Expected failure — return Err
override suspend fun invoke(ctx: ExecutionContext): CommandResult {
    val device = deviceRegistry.find(ctx.refOrNull("deviceId"))
        ?: return CommandResult.Err(
            code = "UNAVAILABLE",
            message = "Device not reachable",
            retryable = true,
            details = buildJsonObject { put("component", JsonPrimitive("iot")) },
        )
    ...
}

// Exceptional — throw McosException from a catch
try { vendorSdk.actuate(device) }
catch (e: VendorBusyException) {
    throw McosException("camera.hardware_busy", "Camera busy", retryable = true)
}
```

### 5.3 `meta` Is Runtime-Owned (Author Warning)

The IR `meta` field ([02 §8.2](./02-command-protocol.md)) — `source`, `confidence`, `utteranceId`, `correlationId`, `traceId` — is injected by the Planner and Runtime. **Plugins MUST NOT read, write, or depend on `meta` contents.** It is not part of `ExecutionContext.args`. If a plugin needs provenance (e.g. "was this from an LLM?"), it should declare an explicit input arg rather than reaching into `meta`.

---

## 6. HostServices (Plugin-Facing Facade)

> ✅ **Implementation status:** `HostServices` and every §6.1–6.6 interface live in `mcos-sdk` (JVM stubs + real Android implementations in `mcos-android`). v0.x deltas: services are `val` properties (the spec sketch shows `fun`) and some signatures are leaner (`NetService.request(method, url, body, headers)`, `Clock.nowMs()`) — full-signature alignment is tracked as a 🟡 gap in [11-implementation-status.md](./11-implementation-status.md). §6.7–6.10 optional capabilities were added in v0.x, and §6.1's scoped storage shipped in v0.x as the optional `sandbox` capability (see §6.1's as-built note).

Plugins should depend on **facades**, not the entire Android framework.

```kotlin
interface HostServices {
    fun files(): FileService
    fun net(): NetService
    fun ui(): UiService
    fun secureStore(): SecureStore
    fun clock(): Clock
    fun json(): kotlinx.serialization.json.Json
    fun memory(): MemoryFacade   // read-only view for plugins; P2 (see 01 §11.1)

    // Optional platform capabilities (added in v0.x; interface default null):
    // hosts without the capability (e.g. plain JVM) simply do not override,
    // and plugins must surface UNAVAILABLE — never fabricated data or fake success.
    val notifications: NotificationService?   // §6.7
    val media: MediaService?                  // §6.7
    val deviceInfo: DeviceInfoService?        // §6.8
    val clipboard: ClipboardService?          // §6.9
    val haptics: HapticsService?              // §6.10
    val events: EventPublisher?               // §6.11
    val sandbox: SandboxFileService?          // §6.1 scoped storage (optional, v0.x)
}
```

Each service interface is specified in §6.1–6.6 below. MVP pragmatism: the Camera plugin may need CameraX directly. Guideline: **new code prefers facades**; document exceptions in plugin README.

### 6.1 `FileService` / `SandboxFileService` — Media Access & Scoped Storage

As built (v0.x), this section covers **two surfaces**:

- **`HostServices.files: FileService`** — the **media-store facade**: read-only queries over the device media library (`list(uri)`, `searchPhotos(mimeType, afterMs, beforeMs, limit)`), consumed by `file.list` / `file.search` / `photo.search` / `photo.compress`. It is *not* a general file API.
- **`HostServices.sandbox: SandboxFileService?`** — the **scoped storage** this section originally specified, shipped as an optional capability (the §6.7–6.11 pattern: interface default null — a host without storage simply does not override, and the sandbox commands surface `UNAVAILABLE`, never fake success). All paths are plugin-relative and resolve inside the plugin's **namespaced sandbox**; a plugin cannot read or write outside its own directory. The reference implementation is `DirectorySandbox(root)` — pure `java.nio`, so its JVM test suite covers the exact code the Android host runs (`filesDir/plugin-sandbox`).

```kotlin
interface SandboxFileService {
    suspend fun read(path: String): ByteArray?                     // null if absent
    suspend fun write(path: String, data: ByteArray, append: Boolean = false)
    suspend fun stat(path: String): SandboxEntry?                  // null if absent
    suspend fun delete(path: String): Boolean                      // false if absent; empty dirs only
    suspend fun list(dir: String): List<SandboxEntry>              // non-recursive
    suspend fun tempFile(prefix: String = "mcos", suffix: String = ".tmp"): String  // sandbox-relative
}

data class SandboxEntry(val path: String, val isDir: Boolean, val size: Long?)
```

**Namespacing** — the Executor's Stage-4 facade hands every command a per-plugin view: paths resolve under `<root>/<pluginId>/`, and one plugin can never see another's files. Handlers MUST use `ctx.services.sandbox` (the namespaced view), never an `onLoad`-captured host-wide facade.

**Path defense (two layers)** — syntax layer: blank/`.`/`..` segments, backslashes, NUL → `SCHEMA_VIOLATION` with `details.reason = "sandbox_path_invalid"`. Physical layer: lexical root containment plus a strict no-symlink walk of every existing component → `PERMISSION_DENIED` with `details.reason = "sandbox_escape"`.

**Command surface** — `mcos.plugin.files` exposes the sandbox as four commands (§17): `file.write {path, text, append?}` (write class; 1 MiB per write — over → `SCHEMA_VIOLATION "file_too_large"`), `file.read {path}` (read class; absent → `files.not_found`; oversize → `files.too_large`), `file.stat {path}` (read class; `Ok {path, exists, isDir, size?}`), `file.delete {path}` (write class — sandbox-local deletion is not device-level irreversible, so it is not `destructive`; idempotent `Ok {deleted}`). The command face is **text-only**; binary data stays in plugin code calling the SDK interface above.

Artifacts returned from handlers SHOULD use `tempFile(...)` or a stable sandbox path, then return the URI — never inline bytes in `CommandResult.Ok` (see §7.3). **Secrets MUST never live in the sandbox** — it is plaintext app-private storage; use `SecureStore` (§6.4, [08 §9](./08-security.md)).

> 🟡 **v0.x deltas (honest):** the byte API above is leaner than the original streaming sketch (`openInput`/`openOutput` → `InputFlow`/`OutputFlow` — same drift family as `NetService`/`Clock`, tracked in [11-implementation-status.md](./11-implementation-status.md)); the "user grants access outside the sandbox via a system picker" flow is V1 host work; per-plugin storage quotas beyond the 1 MiB-per-write cap are not implemented.

### 6.2 `NetService` — Policy-Aware HTTP

All network egress is **policy-gated**. The Runtime checks `network.<domain>` scopes ([08 §3](./08-security.md)) and the enterprise allowlist before connecting. A request to a disallowed domain fails with `PERMISSION_DENIED` (`details.permission = "network:<domain>"`).

```kotlin
interface NetService {
    suspend fun request(req: HttpRequest): HttpResponse
    suspend fun websocket(url: String): WebSocketSession   // P2
}

data class HttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Long = 30_000,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)
```

HTTPS is enforced in production builds; HTTP is allowed only in debug. Secrets for `Authorization` headers MUST come from `SecureStore`, never hardcoded.

### 6.3 `UiService` — Activity Bridging & Notifications

```kotlin
interface UiService {
    suspend fun startActivityForResult(intent: android.content.Intent): ActivityResult
    suspend fun postNotification(channel: String, title: String, body: String)
    suspend fun toast(message: String)
}

data class ActivityResult(val resultCode: Int, val data: android.content.Intent?)
```

`startActivityForResult` **suspends** the calling handler coroutine; the Runtime bridges the OS `onActivityResult` callback back to resume it ([03 §9.4](./03-runtime.md)). The handler's `timeoutMs` deadline continues to run across this suspension. `UiService` methods dispatch on `Dispatchers.Main` — plugin code need not switch dispatchers to call them.

`toast` was added in v0.x: the default implementation throws `UNAVAILABLE` (hosts without UI do not fake a popup); the Android host posts a real `Toast` via a main-thread `Handler`. There is **no `sys.toast` command** — toast is for a plugin's own lightweight in-flow feedback, not part of the command surface.

### 6.4 `SecureStore` — Keystore-Backed Secrets

Per-plugin namespaced key-value store backed by the Android Keystore ([08 §9](./08-security.md)). Keys are scoped to the plugin id; one plugin cannot read another's secrets.

```kotlin
interface SecureStore {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, value: ByteArray)
    suspend fun remove(key: String)
    suspend fun keys(): Set<String>
}
```

Use this for API tokens, OAuth refresh tokens, vendor credentials. **Never** store secrets in `FileService`, `Memory`, or the manifest. Secrets stored here are excluded from audit redaction walks (they never enter the IR) and from Memory sync.

### 6.5 `Clock` — Injectable Time

```kotlin
interface Clock {
    fun now(): kotlinx.datetime.Instant        // wall clock
    fun monotonicMs(): Long                     // monotonic, for elapsed-time measurement
}
```

Always use `Clock` instead of `System.currentTimeMillis()` / `Instant.now()` directly — this makes handlers deterministic and testable. `mcos-sdk-testing` injects a `FakeClock` ([§14.1](#141-full-mcos-sdk-testing-api)) so tests can advance time without `Thread.sleep`.

### 6.6 `MemoryFacade` — Read-Only Plugin View

Plugins receive a **read-only** view of the Memory engine ([07 §5](./07-memory.md)). The full `get`/`put`/`delete`/`search`/`resolve`/`export`/`import` surface is Runtime/Planner-owned; plugins get:

```kotlin
interface MemoryFacade {
    suspend fun get(path: String): JsonElement?
    suspend fun search(query: String, filter: MemoryFilter = MemoryFilter.ALL): List<MemoryHit>
}
```

`put` / `delete` / `import` are **not** available to plugins — writes go through the Planner with user confirmation ([07 §5.1](./07-memory.md)). If a handler needs to persist data, it uses `FileService` (device-local) or its own backend via `NetService`.

### 6.7 `NotificationService` / `MediaService` — Optional Platform Capabilities (v0.x spec backfill)

Implemented in earlier slices but never spec'd here — now recorded. Both follow the **optional-capability pattern**: interface default `null`, hosts without the platform do not override, and plugins surface `UNAVAILABLE` when they see `null` (the `sys.notify` P0-F1 semantics).

```kotlin
interface NotificationService {
    suspend fun notify(title: String, text: String): String   // returns channel/notification id
}

interface MediaService {
    suspend fun compress(
        uris: List<String>, quality: Int,
        maxWidth: Int? = null, maxHeight: Int? = null,
    ): List<String>   // output URIs after JPEG compress/resize (order kept, nulls dropped)
}
```

`NotificationService` backs `sys.notify` (when the `POST_NOTIFICATIONS` runtime grant is absent the command reports a real `PERMISSION_DENIED`). `MediaService` serves the camera plugin's photo-compression pipeline.

### 6.8 `DeviceInfoService` — Device Info (added in v0.x)

Backs the six `sys.device.*` commands (battery/wifi/screen/volume/location/brightness query + set). Core contract: **report the truth** — a datum the host cannot determine is returned as `null`, never guessed (P2-F3 semantics: an earlier implementation returned hardcoded fake data and has been removed).

```kotlin
interface DeviceInfoService {
    suspend fun battery(): BatteryInfo        // percent, charging, temperatureC?
    suspend fun wifi(): WifiInfo              // connected; ssid/strength null without location grant (Android 9+)
    suspend fun screen(): ScreenInfo          // widthPx, heightPx, densityDpi, rotation, brightness?
    suspend fun volume(): VolumeInfo          // musicPercent, ringPercent?, alarmPercent?
    suspend fun location(): LocationInfo?     // null when no fix — the command emits no_fix, not an error
    suspend fun brightness(): BrightnessInfo  // level (0-255), auto
    suspend fun setBrightness(level: Int)     // throws PERMISSION_DENIED without WRITE_SETTINGS — never fakes success
}
```

Android permission constraints and degradation semantics: SSID/RSSI require the `ACCESS_FINE_LOCATION` runtime grant — without it `wifi()` returns `connected=true, ssid=null, strength=null`; `location()` prompts in-app for the grant on first miss (`RuntimePermissionBridge`, item 38) and throws an actionable `PERMISSION_DENIED` when the user denies or no Activity is registered (a headless schedule run), returning `null` (→ `{"status":"no_fix","location":null}`) when granted but no fix exists; `setBrightness` requires the WRITE_SETTINGS special access (there is no `requestPermissions` dialog for special-access grants) — on first miss it deep-links the app's "Modify system settings" screen via the activity-result bridge, re-checks on return, and throws `PERMISSION_DENIED` when still not writable or no Activity is registered. Ungranted states are always reported honestly; only headless runs point the user at system settings.

### 6.9 `ClipboardService` — Clipboard (added in v0.x)

Backs `sys.clipboard`'s read and write modes.

```kotlin
interface ClipboardService {
    suspend fun set(text: String)
    suspend fun get(): String?   // null when empty or unreadable (Android background restrictions)
}
```

A `null` from `get()` is indistinguishable from an empty clipboard — the command surfaces `UNAVAILABLE`, never fabricated text. **Clipboard text is untrusted input** ([08 §11.1](./08-security.md)): the user may have copied adversarial text, so `sys.clipboard` read results always carry `untrusted: true` for downstream prompt-injection defenses.

### 6.10 `HapticsService` — Haptic Feedback (added in v0.x)

Backs `sys.vibrate`.

```kotlin
interface HapticsService {
    suspend fun vibrate(durationMs: Int)
}
```

Hosts without a vibrator stay `null` → the command reports `UNAVAILABLE`. The former fake success (returning `vibrated` without touching hardware) has been removed — the audit trail must only record vibrations that actually happened. The Android implementation uses `VibratorManager` (API 31+) with a legacy `Vibrator` fallback.

### 6.11 `EventPublisher` — EventBus Publish Access (added in v0.x)

Backs `sys.event.emit` and lets plugins surface domain events (`wifi.connected`, `user.arrived.home`) that event-trigger recipes ([05 §9.2](./05-workflow.md)) subscribe to.

```kotlin
interface EventPublisher {
    suspend fun publish(type: String, payload: JsonObject)
}
```

The runtime wires this to the shared EventBus ([03 §11](./03-runtime.md)) when one exists; publish is **not** a privileged bus bypass — emitted envelopes carry the publishing context and consumers (triggers, the Agent loop) apply their own filters. A host without a bus stays `null` → `sys.event.emit` reports `UNAVAILABLE`, never fake success.

---

## 7. Handler Patterns

### 7.1 Pure Local

```kotlin
class WeatherTodayHandler(
    private val client: WeatherClient
) : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val city = ctx.stringOrNull("city") ?: ctx.memoryDefault("places.defaultCity")?.jsonPrimitive?.contentOrNull ?: "Beijing"
        val data = client.fetchToday(city)
        return CommandResult.Ok(data.toJson())
    }
}
```

### 7.2 User Activity Result

For flows needing Activity results (capture preview):

1. Handler calls `ctx.services.ui().startActivityForResult(intent)` which **suspends** the handler coroutine
2. Runtime bridges the Android `onActivityResult` callback back to resume the coroutine ([03 §9.4](./03-runtime.md))
3. Timeout (`timeoutMs`) continues to run across the suspension — a user who never returns trips `TIMEOUT`

### 7.3 Streaming Artifacts

Large media: return `content://` / `file://` URIs as artifacts; avoid base64 in IR results.

### 7.4 Cooperative Cancellation Obligation

The Executor enforces `timeoutMs` and external cancels cooperatively ([03 §9.4](./03-runtime.md)). When a cancel or timeout fires, the Executor calls `handler.cancel(ctx)` and waits up to `cancelGraceMs` (default 2000 ms) for the handler to unwind. **A handler that ignores cancellation is a defect** — after the grace period the Runtime force-cancels the coroutine, and repeated forced-cancels trip the circuit breaker ([01 §15.3](./01-architecture.md)): 3 strikes / 60 s → 30 s cooldown; sustained trips → auto-unload ([03 §16.5](./03-runtime.md)).

**Rules for authors:**

1. **Override `cancel(ctx)`** if your handler holds resources (open sockets, camera sessions, file locks) that need explicit release. The default no-op is acceptable only for pure-compute handlers.
2. **Check cancellation in long loops.** Use Kotlin's `ensureActive()` or `currentCoroutineContext().isActive` to bail early:
   ```kotlin
   override suspend fun invoke(ctx: ExecutionContext): CommandResult {
       val rows = ctx.services.files().list("inbox")
       for (row in rows) {
           currentCoroutineContext().ensureActive()   // throws CancellationException on cancel
           process(row)
       }
       return CommandResult.Ok(...)
   }
   ```
3. **Propagate `CancellationException`.** Never catch it as a generic exception — let it unwind the coroutine. If you must catch (e.g. in a `finally`), re-throw after cleanup.
4. **Respect `ctx.deadline`.** For operations without native timeout support, compare against `ctx.deadline` (an `Instant`) before starting each unit of work.

A handler that responds to cancellation promptly will never see the circuit breaker.

---

## 8. Permission Declaration Guidelines

| Do | Don't |
|----|-------|
| Declare least privilege | Request Accessibility "just in case" |
| Provide human `reason` strings | Hide network use under `read` |
| Mark destructive deletes correctly | Label delete as `read` |
| Use `network` class for egress | Ship secrets in manifests |

Runtime may **refuse to load** plugins that request forbidden combinations under enterprise policy.

### 8.1 MCOS scope vocabulary

Alongside Android permissions, MCOS defines its own scope vocabulary that plugins declare with `"type": "mcos"`. The full normative list lives in [08 §3](./08-security.md); the author-facing summary:

| Scope | When to declare |
|-------|------------------|
| `command.<id>` | Auto-derived from each command your plugin owns — you do **not** declare these manually |
| `memory.read` | Required to call `services.memory().get(...)` or `services.memory().search(...)` |
| `memory.write` | Required to emit any event that another plugin consumes to write memory (rare — most plugins are read-only on memory) |
| `network.<domain>` | One per distinct eTLD+1 your plugin contacts (e.g. `network.api.openai.com`). The host portion of every `http.url` ([§11.1](#111-the-http-object--field-by-field-specification)) must be covered by a declared `network.*` scope |
| `mcp.server.<id>` | Required by the MCP adapter for each configured server; user-granted per server in Settings |
| `securestore.<keyprefix>` | Required for each `SecureStore` key prefix your plugin reads (e.g. `securestore.example_token` for the `auth.secretKey` example in §11.1) |

**Authoring rule.** Declare the scope in the command's `permissions[]` array with `type: "mcos"`, paired with a human `reason` string. Example:

```json
{
  "id": "weather.forecast",
  "permissions": [
    { "type": "android", "name": "INTERNET", "reason": "Fetch forecast data" },
    { "type": "mcos",    "name": "network.api.weather.example.com", "reason": "Forecast API endpoint" },
    { "type": "mcos",    "name": "memory.read", "reason": "Read user's default city" }
  ]
}
```

The Runtime's Authorize stage ([01 §5](./01-architecture.md) stage 6) refuses to execute a command whose declared `network.*` / `memory.*` / `securestore.*` scopes do not cover what the handler actually does at runtime — so over-declaring gains nothing and under-declaring fails fast.

---

## 9. IoT Plugin Pattern

```text
home/
  plugin.json
  commands:
    home.light.on
    home.light.off
    home.scene.movie
    home.scene.sleep
```

Implementation talks to Home Assistant / Tuya / Matter **inside** the plugin.  
Runtime only sees command IDs.

Device discovery **SHOULD** expose a `home.device.list` read command rather than inventing out-of-band channels.

### 9.1 Plugin lifecycle state machine

Every plugin instance moves through this state machine, owned by the Plugin Loader ([03 §16](./03-runtime.md)):

```text
        onLoad(services)              handlers() registered
loaded ─────────────────► ready ─────────────────────────► active
   │                         ▲                              │
   │ onLoad failed           │ re-enable                    │ auto-unload
   ▼                         │                              ▼
unloaded (registration     paused                        unloading
   rolled back)               │                              │
                             │ user disable / policy        │ onUnload()
                             └──────────────────────────────┘
                                                            ▼
                                                        unloaded
```

**Author-facing rules:**

1. **`onLoad(services: HostServices)` is the only place** to acquire long-lived resources (DB handles, MCP connections, listeners). Store them on the plugin instance. Anything you don't acquire here won't be available in `invoke()`.
2. **`onLoad` failure → registration rollback.** If `onLoad` throws, the Runtime unregisters every command descriptor the plugin would have published and records a `PluginLoadFailed` audit event ([03 §16.2](./03-runtime.md)). The plugin stays in the `unloaded` state and **does not** receive `onUnload()` — its partial work must clean itself up in a `finally` block. Throw a `McosException("UNAVAILABLE", ..., retryable = true)` if you want the Runtime to retry loading later.
3. **`onUnload()` MUST be idempotent and fast** (target < 1 s). It is called on auto-unload, user-disable, and Runtime shutdown. Release every resource acquired in `onLoad`; in-flight `invoke()` calls have already been cancelled before `onUnload` runs.
4. **Auto-unload (circuit breaker).** If your handlers trip the circuit breaker repeatedly (3 forced-cancels in 60 s, or sustained error rate), the Runtime calls `onUnload()` and parks the plugin in the `unloading` → `unloaded` state without consulting it ([01 §15.3](./01-architecture.md), [03 §16.5](./03-runtime.md)). Your only signal is that `onUnload()` runs unexpectedly — write it so that's always safe.
5. **Re-enable flow.** A user re-enabling a parked plugin causes a fresh `onLoad()` against a **new** plugin instance — your old instance state is gone. Do not rely on static singletons to persist across unload/reload; persist any user state to `SecureStore` or memory (07 §5) instead.

---

## 10. MCP Adapter Plugin

The special plugin `mcos.plugin.mcp` bridges external [MCP](https://modelcontextprotocol.io) servers into the MCOS command bus. It is a **P3** production target ([§17](#17-built-in-plugin-set-first-party)); a **P2 bridge spike** (user-configured trusted servers only) validates the schema conversion + ecosystem-adoption thesis early — see [10-roadmap.md §5.7](./10-roadmap.md) for the spike scope guardrails.

```text
Connect MCP server (user-configured)
  → list tools
  → synthesize Command Descriptors under mcp.<server>.*
  → handlers proxy invoke() to MCP tool calls
  → map MCP results back to CommandResult
```

**Schema conversion:** the field-by-field MCP JSON-Schema → MCOS `inputSchema` mapping (including the fail-closed rule for unmappable types like `oneOf`/`anyOf`) is normative in [02 §12.4](./02-command-protocol.md). The adapter does not invent its own mapping.

**Adapter responsibilities** (author-facing):

| Responsibility | Detail |
|----------------|--------|
| Connection management | Maintain a live connection per configured MCP server; reconnect with backoff; mark all `mcp.<server>.*` commands `UNAVAILABLE` when disconnected |
| Tool discovery | On connect, enumerate tools and register descriptors dynamically into the Registry ([03 §6.5](./03-runtime.md) hot-reload) |
| Handler proxy | Each synthesized handler's `invoke()` translates MCOS args → MCP tool-call params, awaits the MCP response, maps it to `CommandResult.Ok`/`Err` |
| Auth failure mapping | MCP auth errors → `PERMISSION_DENIED` (`details.permission = "mcp.server.<id>"`); connection errors → `UNAVAILABLE` |
| User control | Each server is user-enabled/disabled in Settings; disabled servers have their descriptors unregistered |

The marketplace catalog bridge (publishing MCP servers as discoverable MCOS plugins) is specified in [09 §10](./09-marketplace.md).

---

## 11. HTTP / Webhook Plugin Skeleton

For quick integrations without full native code:

```json
{
  "id": "webhook.example",
  "commands": [
    {
      "id": "hook.ping",
      "sideEffectClass": "network",
      "http": {
        "method": "POST",
        "url": "https://example.com/hook",
        "bodyTemplate": { "ok": true }
      }
    }
  ]
}
```

Declarative HTTP is optional sugar in SDK; security review still required for marketplace.

### 11.1 The `http` object — field-by-field specification

| Field | Type | Required | Default | Constraint |
|-------|------|----------|---------|------------|
| `method` | enum | yes | — | One of `GET`, `POST`, `PUT`, `PATCH`, `DELETE` |
| `url` | string (template) | yes | — | HTTPS required in production; supports `{{arg.<name>}}` interpolation from input args; query-string built from `query` field |
| `headers` | object | no | `{}` | Each value may be a `{{arg.x}}` template or a `{{secret.<key>}}` reference into `SecureStore` |
| `query` | object | no | `{}` | Map of query params; values may be `{{arg.x}}` templates |
| `bodyTemplate` | JSON object (template) | no | `null` | JSON body for `POST`/`PUT`/`PATCH`; any leaf value may be `{{arg.x}}`; **MUST NOT** reference any field marked `x-mcos-secret` |
| `auth` | object | no | `null` | Binds to a `SecureStore` key: `{ "type": "bearer", "secretKey": "<key>" }` or `{ "type": "basic", "secretKey": "<key>" }`. Runtime injects the resolved credential into the request; the value never appears in `bodyTemplate` or `url` |
| `timeoutMs` | integer | no | `15000` | Range `1000`–`120000` |
| `errorMapping` | object | no | `{}` | Maps HTTP status codes (or ranges `"4xx"`/`"5xx"`) to MCOS error codes; e.g. `{ "401": "PERMISSION_DENIED", "5xx": "UNAVAILABLE", "429": "RATE_LIMITED" }`. Unmapped statuses default to `INTERNAL` for 5xx and `SCHEMA_VIOLATION` for 4xx |
| `successCode` | integer | no | `200`–`299` | Treat only this/these status code(s) as success |

**Template interpolation.** Only `{{arg.<name>}}` (input args) and `{{secret.<key>}}` (for `headers` only) are recognised templates. Unknown `{{...}}` is a literal string. Missing `arg` references fail the call with `SCHEMA_VIOLATION` at execute time.

**Security constraints.**

1. `url` MUST be `https://` in production builds (`http://` allowed only under a developer flag).
2. `bodyTemplate` MUST NOT contain any `{{secret.*}}` or any value sourced from a field declared `x-mcos-secret` — secrets enter the request only via `auth` or `headers`. The `mcos-sdk-gradle` checker flags violations.
3. The command's `sideEffectClass` MUST be `network`, and the host portion of `url` MUST be covered by a `network.<domain>` permission scope ([08 §3](./08-security.md)).

**Example with `auth` + `errorMapping`:**

```json
{
  "http": {
    "method": "POST",
    "url": "https://api.example.com/v1/messages",
    "headers": { "Authorization": "Bearer {{secret.token}}" },
    "bodyTemplate": { "text": "{{arg.message}}" },
    "auth": { "type": "bearer", "secretKey": "example_token" },
    "timeoutMs": 30000,
    "errorMapping": {
      "401": "PERMISSION_DENIED",
      "429": "RATE_LIMITED",
      "5xx": "UNAVAILABLE"
    }
  }
}
```

The Runtime executes `http` plugins through the same `NetService` used by native plugins, so the enterprise domain whitelist and proxy settings apply uniformly.

---

## 12. Localization & UX Hints

Manifest may include:

```json
{
  "i18n": {
    "zh-CN": {
      "name": "相机",
      "commands": {
        "camera.capture": {
          "title": "拍照",
          "description": "使用相机拍摄一张照片"
        }
      }
    }
  }
}
```

CLI help and confirmation dialogs prefer localized titles.

### 12.1 Locale fallback chain

At resolve-time the Runtime looks up each localizable field in the following order, stopping at the first present key:

```text
<user-locale>            e.g. zh-CN
  → <language-only>      e.g. zh
    → manifest default (en if not declared)
```

**Localizable field list.** Only these fields are ever localised; all others (command IDs, error codes, schema field names, DSL keywords) stay English:

| Field | Owner | Example |
|-------|-------|---------|
| plugin `name` | manifest root | `"相机"` |
| command `title` | per-command | `"拍照"` |
| command `description` | per-command | `"使用相机拍摄一张照片"` |
| permission `reason` | per-permission | `"用于扫码"` |
| command `examples[].description` | per-example | `"扫码登录"` |

**Missing-key behavior.** A missing key falls back silently to the next locale in the chain, ultimately to the manifest's original (English) value. **No error is raised at runtime** for missing keys — runtime localisation is fail-soft by design.

**Marketplace CI completeness check.** Submission to the marketplace runs a CI gate that requires, for every published locale tag, the full set of `title` and `description` keys to be present (other keys like `reason`/`examples` are warned, not blocked). See [09 §5.1](./09-marketplace.md). The `mcos-sdk-gradle` checker ([§13.2](#132-mcos-sdk-gradle-validator)) reproduces this check locally so authors can fail fast before submission.

---

## 13. Versioning Rules for Plugin Authors

1. Changing output meaning → bump command **MAJOR**  
2. Adding optional input → **MINOR**  
3. Publishing replacement ID → deprecate old with `replacedBy`  
4. `minRuntimeVersion` must rise when using new Host APIs  

SDK ships a `mcos-sdk-gradle` checker (planned) to validate manifests in CI.

### 13.1 CI-checkable rules

These rules are machine-checkable and enforced by both `mcos-sdk-gradle` locally and the marketplace CI gate ([09 §5.1](./09-marketplace.md)):

| Rule | Check | Failure mode |
|------|-------|--------------|
| **SemVer regex** | plugin `version` and each command `version` match `^\d+\.\d+\.\d+$` | Build error |
| **Command version coupling** | a plugin `version` MAJOR bump MUST be accompanied by a MAJOR bump on at least one command it owns; new commands may start at `0.1.0` | Build error |
| **`minRuntimeVersion` monotonicity** | `minRuntimeVersion` of a new release MUST be ≥ the previous published release's `minRuntimeVersion` | Submission reject |
| **`replacedBy` resolution** | any command declaring `replacedBy: "<id>"` MUST reference a command ID that is registered either in this plugin or a declared dependency | Build error |
| **Deprecated without `replacedBy`** | commands with `deprecated: true` SHOULD declare `replacedBy`; CI warns (not blocks) if absent | Warning |
| **Namespace ownership** | every command ID's first segment must match one of the plugin's declared `namespaces[]` ([02 §4.4](./02-command-protocol.md)) | Build error |
| **Unique IDs** | no duplicate command IDs within a plugin, and no collision with a reserved namespace (`mcos.*`, `sys.*`, `mcp.*`, `std.*`) | Build error |

### 13.2 `mcos-sdk-gradle` validator

The Gradle plugin `mcos-sdk-gradle` exposes a single task that authors run before submission to mirror marketplace CI:

```bash
./gradlew mcosValidate
```

**Check list** (each maps to a CI gate):

1. **Manifest schema** — manifest parses against the JSON Schema derived from [01 §10](./01-architecture.md) (`CommandDescriptor` + manifest root).
2. **Reserved namespace check** — `mcos.*`, `sys.*`, `mcp.*`, `std.*` are rejected for third-party plugins ([02 §4.3](./02-command-protocol.md)).
3. **Duplicate ID check** — within the manifest and across declared dependencies ([02 §4.4](./02-command-protocol.md)).
4. **sideEffectClass honesty heuristics** — flags suspicious mismatches:
   - command declares `sideEffectClass: "read"` but manifest mentions `http`/`destructive` markers
   - command declares `write`/`destructive` but all branches of `bodyTemplate`/handler return only `read`-shaped artifacts
   - warnings, not hard errors; see [09 §5.1](./09-marketplace.md) for the marketplace's tolerance policy
5. **SemVer compliance** — rules in [§13.1](#131-ci-checkable-rules) above.
6. **i18n completeness** — title/description present for every declared locale tag ([§12.1](#121-locale-fallback-chain)).
7. **Secret containment** — no `{{secret.*}}` or `x-mcos-secret`-sourced value appears in `http.bodyTemplate` ([§11.1](#111-the-http-object--field-by-field-specification)).

The validator's report is the same JSON shape the marketplace returns, so fixing all local errors should yield a clean submission.

---

## 14. Testing Support

```kotlin
class CameraScanTest {
    @Test
    fun scansQr() = runBlocking {
        val rt = FakeRuntime.with(CameraPlugin())
        val result = rt.executeDsl("camera.scan(format=\"qr\")")
        assertTrue(result.ok)
    }
}
```

`mcos-sdk-testing` provides:

- Fake PermissionKernel (auto-grant / deny sets)  
- In-memory EventBus  
- Recorded Progress assertions  

### 14.1 Full `mcos-sdk-testing` API

**`FakeRuntime.Builder`** — configures the in-memory Runtime before each test:

```kotlin
val rt = FakeRuntime.Builder()
    .with(CameraPlugin())
    .with(WeatherPlugin())
    .grants("command.camera.scan", "command.hello.world")   // auto-approve these scopes
    .deny("command.camera.delete")                           // always reject
    .clock(FakeClock(start = "2026-08-06T10:00:00Z"))        // deterministic Clock
    .config(RuntimeConfig(networkAllowList = listOf("*.example.com")))
    .build()
```

| Method | Purpose |
|--------|---------|
| `with(plugin: McosPlugin)` | Register a plugin instance |
| `grants(vararg scopes: String)` | Scopes the fake PermissionKernel auto-approves |
| `deny(vararg scopes: String)` | Scopes the fake PermissionKernel always rejects |
| `clock(fake: FakeClock)` | Inject a controllable `Clock` for deterministic time |
| `config(cfg: RuntimeConfig)` | Override defaults (network allow-list, deadline, etc.) |
| `secureStoreFake(entries: Map<String, ByteArray>)` | Pre-seed the fake `SecureStore` |

**Execution** — two entry points mirroring production Runtime:

```kotlin
suspend fun executeDsl(dsl: String): FakeResult
suspend fun invoke(commandId: String, args: JsonObject): FakeResult
```

**`FakeResult`** — exposes everything the test cares about:

```kotlin
class FakeResult {
    val ok: Boolean
    val value: JsonObject                  // non-null when ok
    val error: CommandResult.Err?          // non-null when !ok
    val events: List<RuntimeEvent>         // every RuntimeEvent emitted during the run
    val progressLog: List<ProgressEntry>   // every progress() call
    val artifacts: List<Artifact>          // every emit() recorded
    val meta: JsonObject                   // the Runtime-owned meta (read-only)
}
```

**Assertion helpers** (extension functions on `FakeResult`):

```kotlin
result.assertEmitted<RuntimeEvent.RunStarted>()
result.assertEmitted("artifact.saved")                       // by event type tag
result.assertProgressContains("Scanning frame 3")
result.assertArtifactCount(2)
result.assertError("PERMISSION_DENIED")                      // code check
```

**Under the hood.** `FakeRuntime` wires the same 10-stage pipeline ([01 §5](./01-architecture.md)) to in-memory fakes — no Android dependencies, no real network, no real filesystem. It is JVM-runnable for plain JUnit/Kotest.

**Complete example — happy path + permission denial:**

```kotlin
class CameraScanTest {
    @Test
    fun happyPath() = runBlocking {
        val rt = FakeRuntime.Builder()
            .with(CameraPlugin())
            .grants("command.camera.scan")
            .build()

        val result = rt.executeDsl("camera.scan(format=\"qr\")")

        assertTrue(result.ok)
        assertEquals("qr", result.value["format"]!!.jsonPrimitive.content)
        result.assertEmitted<RuntimeEvent.StepStarted>()
        result.assertArtifactCount(1)
    }

    @Test
    fun deniedWithoutScope() = runBlocking {
        val rt = FakeRuntime.Builder()
            .with(CameraPlugin())
            // no grants
            .build()

        val result = rt.invoke("camera.scan", buildJsonObject { put("format", JsonPrimitive("qr")) })

        assertFalse(result.ok)
        result.assertError("PERMISSION_DENIED")
        // No StepStarted event because Authorize stage rejected before Execute
        result.assertProgressContains("Permission denied: command.camera.scan")
    }
}
```

---

## 15. Security Review Checklist (Marketplace)

Before listing a plugin:

- [ ] Manifest permissions match actual API usage  
- [ ] No plaintext secrets in package  
- [ ] `sideEffectClass` honest  
- [ ] Network domains documented  
- [ ] Signed build / reproducible where feasible  
- [ ] Privacy policy URL for plugins that sync PII  
- [ ] **Prompt-injection label for Planner-consumed output.** Plugins whose output text is consumed by the Planner (e.g. `camera.scan` returning OCR text, mail readers, web scrapers) SHOULD mark their results with a trust signal so the Planner can treat untrusted content as data, not instructions. The normative marking and the Planner's handling rule live in [08 §11](./08-security.md). Authors of such plugins should explicitly document, in the plugin README, which output fields may contain adversarial content.

---

## 16. Example: Minimal Hello Plugin

**plugin.json**

```json
{
  "id": "example.hello",
  "name": "Hello",
  "version": "1.0.0",
  "minRuntimeVersion": "0.1.0",
  "entry": "com.example.hello.HelloPlugin",
  "commands": [
    {
      "id": "hello.world",
      "version": "1.0.0",
      "title": "Hello World",
      "sideEffectClass": "read",
      "idempotent": true,
      "inputSchema": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "name": { "type": "string", "default": "MCOS" }
        }
      },
      "outputSchema": {
        "type": "object",
        "required": ["message"],
        "properties": {
          "message": { "type": "string" }
        }
      },
      "examples": ["hello.world()", "hello.world(name=\"Tom\")"]
    }
  ]
}
```

**Handler**

```kotlin
class HelloWorldHandler : CommandHandler {
    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
        val name = ctx.args["name"]?.jsonPrimitive?.contentOrNull ?: "MCOS"
        return CommandResult.Ok(buildJsonObject { put("message", "Hello, $name") })
    }
}
```

---

## 17. Built-in Plugin Set (First Party)

> ✅ **Implementation status:** the first four (hello / system / camera / files) are implemented in `plugins/` with conformance tests and are the marketplace's curated built-ins; the rest remain spec-only.
>
> **This table is the single source of truth for the built-in command surface.** All other documents (roadmap, repositories, vision) reference this table rather than maintaining independent command lists. If a command appears elsewhere but not here, it is undocumented and should be added here or removed from the referencing document. The `mcos.plugin.system` plugin owns both the `sys.*` and `sys.device.*` namespaces (device queries are system-API wrappers, kept under the reserved `sys` root rather than a separate `device` root — `device` is not a reserved namespace, see [02 §4.3](./02-command-protocol.md)).

| Plugin | Commands (illustrative) | Target phase |
|--------|-------------------------|--------------|
| `example.hello` | `hello.world` | P1 (reference) |
| `mcos.plugin.system` | `sys.notify`, `sys.share`, `sys.clipboard`, `sys.openUrl`, `sys.vibrate`, `sys.device.battery`, `sys.device.wifi`, `sys.device.screen`, `sys.device.volume`, `sys.device.location`, `sys.device.brightness`, `sys.event.emit` | P1 (+`sys.event.emit` P2) |
| `mcos.plugin.camera` | `camera.capture`, `camera.scan` | P1 |
| `mcos.plugin.files` | `file.list`, `file.search`, `photo.search`, `photo.compress`, `file.write`, `file.read`, `file.stat`, `file.delete` | P1 |
| `mcos.plugin.iot` | `home.*`, `iot.*` | P2 |
| `mcos.plugin.mcp` | dynamic `mcp.*` | P2 spike / P3 production |

---

## 18. Non-Goals for SDK v0.1

- Hot-patch native `.so` without restart guarantees  
- Running untrusted unsigned code in-process on production builds  
- Cross-language plugins (Swift/RN) — future bridges possible  
- Full OS privilege escalation APIs  

---

## 19. Summary

The SDK turns third-party capability into **registry-native commands**:

- Manifest declares **what** and **what permissions**  
- Handlers implement **how**  
- Runtime enforces **whether**  

Next: composing many commands into reliable graphs — [05-workflow.md](./05-workflow.md).
