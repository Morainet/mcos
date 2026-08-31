package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.McosPlugin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The plugin-process execution half of isolation slice 3b (08-security.md
 * §8.1): receives an `OP_INVOKE` envelope, decodes the
 * [IsolatedInvocation][com.morainet.mcos.runtime.core.executor.IsolatedInvocation],
 * and runs the loaded plugin's handler against an
 * [IsolatedHostServicesProxy] — so the plugin's code and its privileged
 * facade calls both live on the plugin-process side of the boundary, exactly
 * as they will behind the Binder transport.
 *
 * Together with item 41 this closes every JVM-provable link of the chain:
 * `Executor` (main) → [TransportIsolationHost] → channel → **this runner**
 * → handler → proxy → channel → [IsolatedFacadeServer] (main, identity +
 * §8.2 stamp gate + namespacing) → the real host facade. The remaining
 * slice-3b work is the Binder byte pipe (a thin `IsolationChannel` adapter)
 * and on-device verification — nothing in the protocol changes.
 *
 * Defense in depth beyond the wire:
 * - the invocation's `pluginId` must match the plugin this runner loaded
 *   (mismatch → `PERMISSION_DENIED` / `plugin.identity_mismatch`, handler
 *   never touched) — the runner never trusts the envelope's claim about
 *   *whose* code to run;
 * - an `AuthStamp` that does not describe *this* invocation (runId,
 *   commandId, or pluginId mismatch) is dropped before it reaches the
 *   proxy, so it can never justify a facade call (the §8.2 gate then denies
 *   with `stamp_missing`).
 *
 * @param plugin the plugin instance loaded in this process.
 * @param channel transport back to the main-process facade server.
 * @param nowMs injectable clock for the local deadline check (tests).
 */
class IsolatedPluginRunner(
    private val plugin: McosPlugin,
    private val channel: IsolationChannel,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Plugin lifecycle (04 §5): `onLoad` receives the proxy facade — no host objects. */
    suspend fun start() {
        plugin.onLoad(IsolatedHostServicesProxy(channel, stamp = null))
    }

    suspend fun stop() {
        plugin.onUnload()
    }

    /**
     * Serve one `OP_INVOKE` envelope. Never throws: every failure is
     * encoded as the shared error envelope, mirroring
     * [IsolatedFacadeServer.handle] on the main-process side.
     */
    suspend fun serveInvoke(envelope: JsonObject): JsonObject {
        val invocation = IsolationCodec.decodeInvocation(envelope)
            ?: return IsolationCodec.encodeError(
                code = McosErrorCode.PLUGIN_ERROR.name,
                message = "Isolated invocation envelope could not be decoded",
                retryable = false,
                details = buildJsonObject { put("reason", INVOCATION_DECODE_FAILURE) },
            )
        if (invocation.pluginId != plugin.manifest.id) {
            return IsolationCodec.encodeError(
                code = McosErrorCode.PERMISSION_DENIED.name,
                message = "Invocation targets plugin '${invocation.pluginId}' but this process serves '${plugin.manifest.id}'",
                retryable = false,
                details = buildJsonObject { put("reason", BinderIdentityPolicy.AUDIT_REASON) },
            )
        }
        val handler = plugin.handlers()[invocation.commandId]
        if (handler == null) {
            return IsolationCodec.encodeError(
                code = McosErrorCode.UNKNOWN_COMMAND.name,
                message = "Command '${invocation.commandId}' has no handler in the plugin process",
                retryable = false,
                details = buildJsonObject { put("reason", HANDLER_MISSING) },
            )
        }
        // The stamp must describe THIS invocation, or it justifies nothing.
        val stampForRun = invocation.auth?.takeIf {
            it.runId == invocation.runId && it.commandId == invocation.commandId && it.pluginId == invocation.pluginId
        }
        val remaining = invocation.deadlineMs - nowMs()
        if (remaining <= 0) {
            return timeoutResult(invocation.commandId)
        }
        val ctx = ExecutionContext(
            runId = invocation.runId,
            commandId = invocation.commandId,
            args = invocation.args,
            auth = stampForRun,
            deadline = invocation.deadlineMs,
            // Progress does not cross this wire (slice 3); the Executor's
            // own withTimeout still bounds the whole call from the main side.
            progress = null,
            services = IsolatedHostServicesProxy(channel, stamp = stampForRun),
        )
        return try {
            // Local deadline enforcement — the main side also cancels, but a
            // stuck channel must not let the handler run past its deadline.
            withTimeout(remaining) { handler.invoke(ctx) }.let(IsolationCodec::encodeResult)
        } catch (e: TimeoutCancellationException) {
            timeoutResult(invocation.commandId)
        } catch (e: McosException) {
            IsolationCodec.encodeError(e.code, e.message ?: "plugin handler failed", e.retryable, e.details)
        } catch (e: Exception) {
            IsolationCodec.encodeError(
                code = McosErrorCode.PLUGIN_ERROR.name,
                message = "Plugin handler '${invocation.commandId}' crashed: ${e.message ?: e.javaClass.simpleName}",
                retryable = false,
                details = buildJsonObject { put("reason", HANDLER_CRASH) },
            )
        }
    }

    private fun timeoutResult(commandId: String) = IsolationCodec.encodeError(
        code = McosErrorCode.TIMEOUT.name,
        message = "Command '$commandId' exceeded its deadline in the plugin process",
        retryable = false,
        details = buildJsonObject { put("reason", DEADLINE_EXCEEDED) },
    )

    companion object {
        const val INVOCATION_DECODE_FAILURE = "invocation_decode_failure"
        const val HANDLER_MISSING = "handler_missing_in_plugin_process"
        const val HANDLER_CRASH = "handler_crash"
        const val DEADLINE_EXCEEDED = "plugin_process_deadline_exceeded"
    }
}
