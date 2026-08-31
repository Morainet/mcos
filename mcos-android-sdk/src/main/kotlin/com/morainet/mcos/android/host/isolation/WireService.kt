package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.error.McosErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The pure serving cores behind the two Binder endpoints (isolation slice
 * 3b-final): take one wire frame, serve it, return the framed reply. Both
 * functions NEVER throw — every failure (malformed frame, wrong direction,
 * plugin crash) comes back as a framed error envelope, so a bad caller
 * cannot crash the serving process and the reply shape is always a valid
 * frame ([08-security.md §8.1] crash isolation at the wire level).
 *
 * The Android `onTransact` bodies in [IsolationBinder.kt] are one-line
 * delegations to these functions — that is what keeps the Binder glue thin
 * enough to leave JVM-untested (its only behavior is Parcel shuttling and
 * the kernel-supplied caller UID).
 */
object WireService {

    /**
     * Plugin-process side ([BinderWire.CODE_INVOKE]): decode the frame, hand
     * the invocation envelope to [IsolatedPluginRunner.serveInvoke], frame
     * the result. A frame that is not an `OP_INVOKE` (or not a frame at all)
     * is a protocol violation → framed `PLUGIN_ERROR` envelope.
     */
    fun serveInvoke(frame: String, runner: IsolatedPluginRunner): String {
        val (op, envelope) = BinderWire.unframe(frame)
            ?: return BinderWire.frame("error", wireError(WireFailureReasons.FRAME_DECODE, "malformed isolation wire frame"))
        if (op != IsolationOps.OP_INVOKE) {
            return BinderWire.frame(
                op,
                wireError(WireFailureReasons.OP_MISMATCH, "invoke endpoint received op '$op'"),
            )
        }
        // serveInvoke itself never throws (item 42), but runBlocking guards
        // the suspension boundary for the blocking Binder thread anyway.
        val reply = runBlocking { runner.serveInvoke(envelope) }
        return BinderWire.frame(op, reply)
    }

    /**
     * Main-process side ([BinderWire.CODE_FACADE]): decode the frame, run it
     * through [IsolatedFacadeServer.handle] — which enforces §8.2 check 1
     * against [callingUid] FIRST and never throws — and frame the reply.
     */
    fun serveFacade(frame: String, server: IsolatedFacadeServer, callingUid: Int): String {
        val (op, envelope) = BinderWire.unframe(frame)
            ?: return BinderWire.frame("error", wireError(WireFailureReasons.FRAME_DECODE, "malformed isolation wire frame"))
        // handle() returns an error envelope (not an exception) for identity
        // mismatch, stamp failures, unknown ops — the §8.2/§8.3 semantics are
        // entirely the server's; this half only frames.
        val reply = runBlocking { server.handle(op, envelope, callingUid) }
        return BinderWire.frame(op, reply)
    }

    private fun wireError(reason: String, message: String): JsonObject =
        IsolationCodec.encodeError(
            code = McosErrorCode.PLUGIN_ERROR.name,
            message = message,
            retryable = false,
            details = buildJsonObject { put("reason", reason) },
        )
}
