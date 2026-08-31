package com.morainet.mcos.android.host.isolation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Binder byte-wire protocol of isolation slice 3b-final
 * ([08-security.md §8.1]): how one [IsolationChannel] call becomes exactly
 * one Binder transaction.
 *
 * A **frame** is a flat JSON object `{"op": …, "payload": …}` carried as a
 * single Parcel string in each direction. Replies are framed with the same
 * op they served, so the wire is symmetric and the channel side simply
 * returns the payload. Transaction codes say which endpoint a frame is
 * addressed to — the framing itself carries the op vocabulary
 * ([IsolationOps]).
 *
 * Everything in this file is pure Kotlin and JVM-testable: the Android half
 * ([IsolationBinder.kt]) only shuttles the frame string through
 * [android.os.Parcel], and both endpoint `onTransact` bodies are one-line
 * delegations to [WireService] (same package) so no protocol logic lives in
 * untestable code.
 */
object BinderWire {

    /**
     * Main → plugin: one framed [IsolationOps.OP_INVOKE] exchange; the reply
     * frame carries the marshaled [com.morainet.mcos.sdk.CommandResult].
     */
    const val CODE_INVOKE = 1

    /**
     * Plugin → main: one framed facade-op exchange. The main-process
     * endpoint derives the caller identity from the Binder kernel
     * (`Binder.getCallingUid()`) for the §8.2 check-1 gate — identity never
     * travels inside the frame.
     */
    const val CODE_FACADE = 2

    /** Encode op + payload into one wire frame. */
    fun frame(op: String, payload: JsonObject): String =
        buildJsonObject {
            put("op", op)
            put("payload", payload)
        }.toString()

    /**
     * Decode a wire frame. Lenient like the codec below it: unknown fields
     * are ignored, and any malformed frame (bad JSON, missing/blank/non-
     * string op, non-object payload) maps to null — callers translate that
     * into an honest failure result, never a crash.
     */
    fun unframe(frame: String): Pair<String, JsonObject>? {
        val obj = runCatching { Json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
        val opPrimitive = obj["op"] as? JsonPrimitive ?: return null
        if (!opPrimitive.isString) return null
        val op = opPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val payload = obj["payload"] as? JsonObject ?: return null
        return op to payload
    }
}

/**
 * The one seam the Android Binder adapter implements: a raw synchronous
 * request/reply exchange. Tests wrap an in-memory function;
 * [BinderWirePipe][IsolationBinderKt] wraps one `IBinder.transact`.
 *
 * Implementations MUST throw (not return garbage) on transport failure —
 * dead object, dead process — so [TransportIsolationHost] can map that to
 * `PLUGIN_ERROR` / `isolation_transport_failure` instead of the runtime
 * crashing ([08-security.md §8.1]).
 */
fun interface WirePipe {
    fun exchange(code: Int, request: String): String
}

/** A reply that is not a valid wire frame — surfaces as `isolation_decode_failure`. */
class WireFormatException(message: String) : RuntimeException(message)

/**
 * [IsolationChannel] over any [WirePipe]: frame the call, exchange it on a
 * blocking dispatcher (Binder transacts are synchronous), unframe the reply.
 * This is the exact object the Binder endpoints sit behind, on both sides
 * of the boundary.
 *
 * A reply that is not a valid frame throws [WireFormatException]; at the
 * runtime-core seam any channel exception — pipe death or wire corruption
 * alike — maps to `PLUGIN_ERROR`/`isolation_transport_failure`
 * ([TransportIsolationHost]), which is honest: a corrupt pipe and a dead
 * pipe are equally unusable.
 *
 * @param pipe raw exchange seam.
 * @param code transaction code for [WirePipe.exchange] —
 *        [BinderWire.CODE_INVOKE] on the main side, [BinderWire.CODE_FACADE]
 *        on the plugin side.
 * @param dispatcher where the blocking exchange runs; tests inject
 *        [kotlinx.coroutines.Dispatchers.Unconfined] so the chain stays on
 *        the test scheduler (production keeps the IO default).
 */
class PipeIsolationChannel(
    private val pipe: WirePipe,
    private val code: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IsolationChannel {

    override suspend fun call(op: String, envelope: JsonObject): JsonObject = withContext(dispatcher) {
        val replyFrame = pipe.exchange(code, BinderWire.frame(op, envelope))
        val unframed = BinderWire.unframe(replyFrame)
            ?: throw WireFormatException("reply is not a valid isolation wire frame")
        unframed.second
    }
}

/** Reason strings for wire-level failures (surfaced through `details.reason`). */
object WireFailureReasons {
    const val FRAME_DECODE = "wire_frame_decode_failure"
    const val OP_MISMATCH = "wire_op_mismatch"
}
