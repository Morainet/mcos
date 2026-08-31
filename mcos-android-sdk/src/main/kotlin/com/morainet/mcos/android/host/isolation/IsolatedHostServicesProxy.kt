package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.ClipboardService
import com.morainet.mcos.sdk.DeviceInfoService
import com.morainet.mcos.sdk.EventPublisher
import com.morainet.mcos.sdk.FileEntry
import com.morainet.mcos.sdk.FileService
import com.morainet.mcos.sdk.HapticsService
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.HttpRequest
import com.morainet.mcos.sdk.HttpResponse
import com.morainet.mcos.sdk.JsonService
import com.morainet.mcos.sdk.MediaService
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.NotificationService
import com.morainet.mcos.sdk.ResolveResult
import com.morainet.mcos.sdk.SandboxEntry
import com.morainet.mcos.sdk.SandboxFileService
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.UiService
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * The plugin-process side of the isolation boundary (08-security.md §8.3):
 * a [HostServices] facade whose privileged members forward over an
 * [IsolationChannel] to the main process, where an [IsolatedFacadeServer]
 * — composing the *same* [com.morainet.mcos.runtime.core.executor.StampScopedNetService] /
 * [com.morainet.mcos.runtime.core.executor.SecretResolvingNetService] /
 * [com.morainet.mcos.runtime.core.executor.NamespacedSandbox] decorators the
 * in-process Executor uses — serves them. The plugin never holds a raw
 * `android.content.Context`, never sees the grant cache, and never sees the
 * AuthStamp signing key: exactly the §8.3 posture.
 *
 * Member mapping:
 * - `net` / `secureStore` / `sandbox` / `memory` / `clock` — forwarded; the
 *   run's [AuthStamp] rides every call envelope so the main-process side
 *   re-verifies signature, TTL, and scope *per call* (08-security.md §8.2).
 * - `json` — pure computation, served locally (no host access, no pointless
 *   round-trip).
 * - `ui` / `files` — interface members that would need an Activity or a
 *   system picker on the main side; they surface an honest `UNAVAILABLE`
 *   failure rather than a silent no-op.
 * - optional capabilities (`notifications`, `media`, `deviceInfo`,
 *   `clipboard`, `haptics`, `events`) — null, following the §6.7-§6.11
 *   optional-capability pattern: plugins degrade to `UNAVAILABLE`, never
 *   fabricate.
 *
 * A remote denial (the shared error envelope) is re-thrown as the original
 * [McosException] — code, message, retryable, and `details.reason` survive
 * the boundary, so the Stage-10 audit sees the true reason.
 *
 * @param channel transport to the main-process facade server.
 * @param stamp the run-bound AuthStamp of the invocation being served
 *        (captured per invocation, not per plugin).
 */
class IsolatedHostServicesProxy(
    private val channel: IsolationChannel,
    private val stamp: AuthStamp?,
) : HostServices {

    private fun unavailable(member: String): Nothing =
        throw McosException(
            code = McosErrorCode.UNAVAILABLE.name,
            message = "$member is not available across the isolation boundary (08 §8.3)",
        )

    /**
     * Send one op, unpack the shared error envelope into a real
     * [McosException] when the main side denied it, and hand back the
     * success payload otherwise.
     */
    private suspend fun call(op: String, args: JsonObject): JsonObject {
        val reply = channel.call(op, IsolationCodec.encodeCall(args, stamp))
        IsolationCodec.decodeError(reply)?.let { denial ->
            throw McosException(
                code = denial.code,
                message = denial.message,
                retryable = denial.retryable,
                details = denial.details,
            )
        }
        return reply
    }

    override val net: NetService = object : NetService {
        override suspend fun request(req: HttpRequest): HttpResponse {
            val reply = call(
                IsolationOps.OP_NET_REQUEST,
                buildJsonObject {
                    put("method", req.method)
                    put("url", req.url)
                    req.body?.let { put("bodyB64", Base64.getEncoder().encodeToString(it)) }
                    if (req.headers.isNotEmpty()) {
                        put("headers", buildJsonObject { req.headers.forEach { (k, v) -> put(k, v) } })
                    }
                    put("timeoutMs", req.timeoutMs)
                },
            )
            return HttpResponse(
                status = reply.intOrNull("status") ?: 0,
                headers = (reply["headers"] as? JsonObject)?.mapValues { (_, values) ->
                    (values as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                } ?: emptyMap(),
                body = reply.stringOrNull("bodyB64")?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0),
            )
        }
    }

    override val secureStore: SecureStore = object : SecureStore {
        override suspend fun get(key: String): ByteArray? =
            call(IsolationOps.OP_SECURE_GET, buildJsonObject { put("key", key) })
                .stringOrNull("valueB64")?.let { Base64.getDecoder().decode(it) }

        override suspend fun put(key: String, value: ByteArray) {
            call(
                IsolationOps.OP_SECURE_PUT,
                buildJsonObject {
                    put("key", key)
                    put("valueB64", Base64.getEncoder().encodeToString(value))
                },
            )
        }

        override suspend fun remove(key: String) {
            call(IsolationOps.OP_SECURE_REMOVE, buildJsonObject { put("key", key) })
        }

        override suspend fun keys(): Set<String> =
            call(IsolationOps.OP_SECURE_KEYS, JsonObject(emptyMap()))["keys"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                ?: emptySet()
    }

    override val sandbox: SandboxFileService = object : SandboxFileService {
        override suspend fun read(path: String): ByteArray? =
            call(IsolationOps.OP_SANDBOX_READ, buildJsonObject { put("path", path) })
                .stringOrNull("dataB64")?.let { Base64.getDecoder().decode(it) }

        override suspend fun write(path: String, data: ByteArray, append: Boolean) {
            call(
                IsolationOps.OP_SANDBOX_WRITE,
                buildJsonObject {
                    put("path", path)
                    put("dataB64", Base64.getEncoder().encodeToString(data))
                    put("append", append)
                },
            )
        }

        override suspend fun stat(path: String): SandboxEntry? =
            call(IsolationOps.OP_SANDBOX_STAT, buildJsonObject { put("path", path) })
                .get("entry")?.let { decodeEntry(it.jsonObject) }

        override suspend fun delete(path: String): Boolean =
            call(IsolationOps.OP_SANDBOX_DELETE, buildJsonObject { put("path", path) })
                .get("deleted") != null

        override suspend fun list(dir: String): List<SandboxEntry> =
            call(IsolationOps.OP_SANDBOX_LIST, buildJsonObject { put("dir", dir) })
                .get("entries")?.jsonArray?.map { decodeEntry(it.jsonObject) } ?: emptyList()

        override suspend fun tempFile(prefix: String, suffix: String): String =
            call(
                IsolationOps.OP_SANDBOX_TEMP,
                buildJsonObject { put("prefix", prefix); put("suffix", suffix) },
            ).stringOrNull("name") ?: unavailable("sandbox.tempFile")
    }

    override val clock: Clock = object : Clock {
        /**
         * The interface is blocking by contract (04 §6); the plugin runner
         * serves one invocation at a time, so a blocking bridge over the
         * suspend channel call is the honest implementation.
         */
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMsOverWire())

        /**
         * Elapsed-time measurement is process-local by nature — a monotonic
         * source from the main process says nothing about this process's
         * timeline, so it is read locally and never crosses the wire.
         */
        override fun monotonicMs(): Long = System.nanoTime() / 1_000_000

        private fun nowMsOverWire(): Long = runBlocking {
            call(IsolationOps.OP_CLOCK_NOW, JsonObject(emptyMap())).longOrNullField("nowMs") ?: 0L
        }
    }

    override val json: JsonService = object : JsonService {
        private val json = Json { ignoreUnknownKeys = true }
        override fun parse(jsonStr: String): JsonElement = json.parseToJsonElement(jsonStr)
    }

    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? =
            call(IsolationOps.OP_MEMORY_GET, buildJsonObject { put("path", path) })["value"]

        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult {
            val reply = call(
                IsolationOps.OP_MEMORY_RESOLVE,
                buildJsonObject {
                    put("ref", ref)
                    semanticType?.let { put("semanticType", it) }
                },
            )["resolved"]?.jsonObject ?: return ResolveResult.NotFound("isolation_decode")
            return when (reply.stringOrNull("kind")) {
                "resolved" -> ResolveResult.Resolved(
                    id = reply.stringOrNull("id") ?: "",
                    confidence = reply["confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1.0f,
                )
                "ambiguous" -> ResolveResult.Ambiguous(
                    candidates = reply["candidates"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                )
                else -> ResolveResult.NotFound(reply.stringOrNull("reason") ?: "ref_unresolvable")
            }
        }
    }

    // ── honestly unavailable / null members (08 §8.3) ────────────────────

    override val files: FileService = object : FileService {
        // searchPhotos defaults to list(...) and inherits the same honest denial.
        override suspend fun list(uri: String, mimeType: String?): List<FileEntry> =
            unavailable("files.list")
    }

    override val ui: UiService = object : UiService {
        override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? =
            unavailable("ui.startActivityForResult")
    }

    override val notifications: NotificationService? get() = null
    override val media: MediaService? get() = null
    override val deviceInfo: DeviceInfoService? get() = null
    override val clipboard: ClipboardService? get() = null
    override val haptics: HapticsService? get() = null
    override val events: EventPublisher? get() = null

    private fun decodeEntry(o: JsonObject) = SandboxEntry(
        path = o.stringOrNull("path") ?: "",
        isDir = o["isDir"]?.jsonPrimitive?.contentOrNull == "true",
        size = o.longOrNullField("size"),
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    longOrNullField(key)?.toInt()

private fun JsonObject.longOrNullField(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
