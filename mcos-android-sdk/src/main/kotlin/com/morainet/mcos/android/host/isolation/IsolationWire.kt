package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.executor.IsolatedInvocation
import com.morainet.mcos.sdk.Artifact
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The process-boundary wire contract for isolation slice 3
 * ([08-security.md §8.1]): one channel abstraction, one op vocabulary, one
 * codec. Everything here is pure Kotlin so the whole RPC layer is
 * JVM-unit-testable; the Binder transport is a thin adapter over
 * [IsolationChannel] and is the only on-device-verified part.
 *
 * Direction 1 (main → plugin process): `OP_INVOKE` carries a marshaled
 * [IsolatedInvocation]; the reply is a marshaled [CommandResult].
 *
 * Direction 2 (plugin → main process): `OP_NET_REQUEST`, `OP_SECURE_*`,
 * `OP_SANDBOX_*`, `OP_CLOCK_NOW`, `OP_MEMORY_*` carry a
 * [call envelope][encodeCall] with the run's [AuthStamp]; the reply is either
 * an op-specific success payload or a shared [error envelope][encodeError].
 */
object IsolationOps {
    /** Main → plugin: execute one [IsolatedInvocation]; reply = [CommandResult]. */
    const val OP_INVOKE = "invoke"

    /** Plugin → main: [com.morainet.mcos.sdk.NetService.request]. */
    const val OP_NET_REQUEST = "net.request"

    const val OP_SECURE_GET = "secureStore.get"
    const val OP_SECURE_PUT = "secureStore.put"
    const val OP_SECURE_REMOVE = "secureStore.remove"

    const val OP_SANDBOX_READ = "sandbox.read"
    const val OP_SANDBOX_WRITE = "sandbox.write"
    const val OP_SANDBOX_STAT = "sandbox.stat"
    const val OP_SANDBOX_DELETE = "sandbox.delete"
    const val OP_SANDBOX_LIST = "sandbox.list"
    const val OP_SANDBOX_TEMP = "sandbox.tempFile"

    const val OP_CLOCK_NOW = "clock.now"

    const val OP_MEMORY_GET = "memory.get"
    const val OP_MEMORY_RESOLVE = "memory.resolveRef"
}

/**
 * One marshalable request/reply pipe across the isolation boundary. The same
 * interface serves both directions — the Android implementation wraps a
 * Binder endpoint; tests wrap an in-memory fake.
 */
fun interface IsolationChannel {
    /**
     * Invoke [op] with [envelope] and return the reply object. Implementations
     * MUST map transport failure (process death, dead Binder) to an exception
     * — callers translate that into an honest `PLUGIN_ERROR`, so a remote
     * crash never takes down the runtime ([08-security.md §8.1]).
     */
    suspend fun call(op: String, envelope: JsonObject): JsonObject
}

/**
 * (De)serialization for the wire. Deliberately hand-rolled over the runtime
 * JSON API — android modules carry no serialization compiler plugin — and
 * lenient on decode: unknown fields are ignored so the two sides can be
 * upgraded independently.
 */
object IsolationCodec {

    // ── invocation / result (direction 1) ──────────────────────────────

    fun encodeInvocation(invocation: IsolatedInvocation): JsonObject = buildJsonObject {
        put("pluginId", invocation.pluginId)
        put("pluginVersion", invocation.pluginVersion)
        put("commandId", invocation.commandId)
        put("args", invocation.args)
        invocation.auth?.let { put("auth", encodeStamp(it)) }
        put("runId", invocation.runId)
        put("deadlineMs", invocation.deadlineMs)
        put("source", invocation.source)
    }

    fun decodeInvocation(json: JsonObject): IsolatedInvocation? = runCatching {
        IsolatedInvocation(
            pluginId = json.string("pluginId") ?: return null,
            pluginVersion = json.string("pluginVersion") ?: return null,
            commandId = json.string("commandId") ?: return null,
            args = json["args"]?.jsonObject ?: JsonObject(emptyMap()),
            auth = (json["auth"] as? JsonObject)?.let(::decodeStamp),
            runId = json.string("runId") ?: return null,
            deadlineMs = json["deadlineMs"]?.jsonPrimitive?.longOrNull ?: return null,
            source = json.string("source") ?: return null,
        )
    }.getOrNull()

    fun encodeResult(result: CommandResult): JsonObject = buildJsonObject {
        when (result) {
            is CommandResult.Ok -> {
                put("ok", true)
                put("value", result.value)
                if (result.artifacts.isNotEmpty()) put("artifacts", encodeArtifacts(result.artifacts))
            }
            is CommandResult.Err ->
                put("error", encodeErrorBody(result.code, result.message, result.retryable, result.details))
        }
    }

    fun decodeResult(json: JsonObject): CommandResult? = runCatching {
        if (json["ok"]?.jsonPrimitive?.contentOrNull == "true") {
            CommandResult.Ok(
                value = json["value"] ?: JsonNull,
                artifacts = json["artifacts"]?.jsonArray?.mapNotNull(::decodeArtifact) ?: emptyList(),
            )
        } else {
            val err = json["error"]?.jsonObject ?: return null
            CommandResult.Err(
                code = err.string("code") ?: return null,
                message = err.string("message") ?: return null,
                retryable = err["retryable"]?.jsonPrimitive?.contentOrNull == "true",
                details = err["details"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }.getOrNull()

    // ── call envelope (direction 2) ────────────────────────────────────

    /** Wrap a plugin→main call payload: the run's stamp rides every call ([08-security.md §8.2]). */
    fun encodeCall(args: JsonObject, stamp: AuthStamp?): JsonObject = buildJsonObject {
        put("args", args)
        stamp?.let { put("stamp", encodeStamp(it)) }
    }

    fun decodeCall(json: JsonObject): Pair<JsonObject, AuthStamp?> = Pair(
        json["args"]?.jsonObject ?: JsonObject(emptyMap()),
        (json["stamp"] as? JsonObject)?.let(::decodeStamp),
    )

    // ── shared error envelope ──────────────────────────────────────────

    fun encodeError(code: String, message: String, retryable: Boolean, details: JsonObject): JsonObject =
        buildJsonObject { put("error", encodeErrorBody(code, message, retryable, details)) }

    /** Extract the error envelope from a reply, or null when the reply is a success payload. */
    fun decodeError(json: JsonObject): CommandResult.Err? =
        (json["error"] as? JsonObject)?.let { err ->
            CommandResult.Err(
                code = err.string("code") ?: "PLUGIN_ERROR",
                message = err.string("message") ?: "isolated call failed",
                retryable = err["retryable"]?.jsonPrimitive?.contentOrNull == "true",
                details = err["details"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }

    // ── AuthStamp ──────────────────────────────────────────────────────

    fun encodeStamp(stamp: AuthStamp): JsonObject = buildJsonObject {
        put("runId", stamp.runId)
        put("commandId", stamp.commandId)
        put("pluginId", stamp.pluginId)
        put("grantsUsed", buildJsonArray { stamp.grantsUsed.sorted().forEach { add(JsonPrimitive(it)) } })
        put("issuedAt", stamp.issuedAt)
        put("expiresAt", stamp.expiresAt)
        put("signature", stamp.signature)
    }

    fun decodeStamp(json: JsonObject): AuthStamp? = runCatching {
        AuthStamp(
            runId = json.string("runId") ?: return null,
            commandId = json.string("commandId") ?: return null,
            pluginId = json.string("pluginId") ?: return null,
            grantsUsed = json["grantsUsed"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet(),
            issuedAt = json["issuedAt"]?.jsonPrimitive?.longOrNull ?: return null,
            expiresAt = json["expiresAt"]?.jsonPrimitive?.longOrNull ?: return null,
            signature = json.string("signature") ?: "",
        )
    }.getOrNull()

    // ── artifacts ──────────────────────────────────────────────────────

    private fun encodeArtifacts(artifacts: List<Artifact>): JsonArray = buildJsonArray {
        artifacts.forEach { a ->
            add(
                buildJsonObject {
                    put("type", a.type)
                    put("uri", a.uri)
                    a.mimeType?.let { put("mimeType", it) }
                    if (a.metadata.isNotEmpty()) {
                        put("metadata", buildJsonObject { a.metadata.forEach { (k, v) -> put(k, v) } })
                    }
                }
            )
        }
    }

    private fun decodeArtifact(element: JsonElement): Artifact? {
        val o = element as? JsonObject ?: return null
        return Artifact(
            type = o.string("type") ?: return null,
            uri = o.string("uri") ?: return null,
            mimeType = o.string("mimeType"),
            metadata = (o["metadata"] as? JsonObject)
                ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: it.value.toString() } ?: emptyMap(),
        )
    }

    private fun encodeErrorBody(code: String, message: String, retryable: Boolean, details: JsonObject): JsonObject =
        buildJsonObject {
            put("code", code)
            put("message", message)
            put("retryable", retryable)
            put("details", details)
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
