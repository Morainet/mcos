package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.executor.IsolatedInvocation
import com.morainet.mcos.runtime.core.executor.IsolationHost
import com.morainet.mcos.sdk.CommandResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [IsolationHost] over any [IsolationChannel] (08-security.md §8.1): marshal
 * the invocation, send `OP_INVOKE`, unmarshal the reply. This is the last
 * pure-Kotlin piece of the isolation dispatch seam — the Android Binder
 * transport (slice 3b) is a thin `IsolationChannel` adapter plus a plugin
 * process that decodes the invocation, runs the handler against an
 * [IsolatedHostServicesProxy], and encodes the [CommandResult].
 *
 * Crash isolation ([08-security.md §8.1]): a channel failure — process
 * death, dead Binder, transport timeout — is mapped to an honest
 * `PLUGIN_ERROR` [CommandResult.Err] carrying `details.reason =
 * "isolation_transport_failure"`, never re-thrown, so a plugin process
 * death cannot take down the runtime. An unparseable reply (protocol
 * corruption) maps the same way with `"isolation_decode_failure"`.
 *
 * @param channel transport to the plugin process's invoke endpoint.
 */
class TransportIsolationHost(
    private val channel: IsolationChannel,
) : IsolationHost {

    override suspend fun invoke(request: IsolatedInvocation): CommandResult {
        val reply: JsonObject = try {
            channel.call(IsolationOps.OP_INVOKE, IsolationCodec.encodeInvocation(request))
        } catch (e: Exception) {
            // Cancellation must keep propagating — only transport failures
            // become command errors.
            if (e is kotlinx.coroutines.CancellationException) throw e
            return CommandResult.Err(
                code = McosErrorCode.PLUGIN_ERROR.name,
                message = "Isolated process call failed: ${e.message ?: e.javaClass.simpleName}",
                retryable = false,
                details = buildJsonObject { put("reason", TRANSPORT_FAILURE) },
            )
        }
        return IsolationCodec.decodeResult(reply) ?: CommandResult.Err(
            code = McosErrorCode.PLUGIN_ERROR.name,
            message = "Isolated process returned an unparseable result envelope",
            retryable = false,
            details = buildJsonObject { put("reason", DECODE_FAILURE) },
        )
    }

    companion object {
        const val TRANSPORT_FAILURE = "isolation_transport_failure"
        const val DECODE_FAILURE = "isolation_decode_failure"
    }
}
