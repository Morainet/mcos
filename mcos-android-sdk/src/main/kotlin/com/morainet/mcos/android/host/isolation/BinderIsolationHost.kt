package com.morainet.mcos.android.host.isolation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.executor.IsolatedInvocation
import com.morainet.mcos.runtime.core.executor.IsolationHost
import com.morainet.mcos.runtime.core.executor.UserGrantRegistry
import com.morainet.mcos.security.AuthStampSigner
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.HostServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The main-process [IsolationHost] of isolation slice 3b-final
 * ([08-security.md §8.1]): binds [IsolatedPluginProcessService] in the
 * dedicated `:mcos_plugin` process, hands that binding the plugin's staged
 * artifact plus a per-plugin [FacadeBinderEndpoint], and dispatches every
 * isolated invocation over the resulting wire as
 * `TransportIsolationHost(PipeIsolationChannel(BinderWirePipe(…), CODE_INVOKE))`
 * — the exact object composition the framed-wire E2E tests exercise, with
 * the Binder kernel as the only new element.
 *
 * Per-plugin connection state: bind on first invoke, cache the endpoint,
 * re-bind after death — `linkToDeath` drops the cache entry and the next
 * invoke reconnects (a stale endpoint would throw `DeadObjectException`,
 * which maps to `PLUGIN_ERROR`/`isolation_transport_failure` anyway;
 * re-binding is the recovery, not the safety net). Only the bind/cache
 * bookkeeping runs under a **per-plugin** lock — the plugin run itself
 * dispatches lock-free, and a slow bind of one plugin never blocks another's
 * first invoke.
 *
 * One [IsolatedFacadeServer] per plugin id pins the §8.2 admission (who the
 * facade agrees to serve) at bind time; the expected UID is this app's own
 * UID — an `android:process` split shares the Linux UID, so the identity
 * check holds against *foreign* apps, while sibling processes of the same
 * app are separated by the process split itself.
 *
 * NOT yet wired into
 * [CompositionRoot][com.morainet.mcos.android.CompositionRoot]: activation
 * is the remaining on-device-verification item, until which the runtime
 * keeps the audited in-process fallback for non-BUILTIN plugins.
 *
 * 🟡 Thin Android shell — binding/lifecycle only; every byte crossing the
 * boundary goes through the JVM-tested framing/serving/codec layers.
 *
 * @param context app context used for binding.
 * @param hostServices the runtime's host facade (served back to the plugin).
 * @param signer the runtime's AuthStamp signer (per-call §8.2 re-verification).
 * @param artifactFor resolves the staged `.mcos` artifact for a plugin id —
 *        the CompositionRoot wiring will read it from the install records.
 * @param bindTimeoutMs how long to wait for the plugin process to connect.
 */
class BinderIsolationHost(
    private val context: Context,
    private val hostServices: HostServices,
    private val signer: AuthStampSigner,
    private val artifactFor: (pluginId: String) -> File?,
    private val bindTimeoutMs: Long = 10_000,
    /**
     * The Executor's user-file grant table (04 §6.1), threaded through to
     * every [IsolatedFacadeServer] this host binds. Sharing ONE table with the
     * in-process Stage-4 facade is what makes a token minted in either path
     * redeemable in the other, and keeps cross-plugin rejection anchored to a
     * single authority. Null ⇒ the isolated `userFiles.*` ops report
     * `UNAVAILABLE` (the honest degradation, not a fake success).
     */
    private val userGrantRegistry: UserGrantRegistry? = null,
) : IsolationHost {

    // Per-plugin bind serialization. A single global mutex here would let the
    // first bind of plugin A (up to bindTimeoutMs) block the first invoke of
    // every other plugin — the class KDoc promises it does not.
    private val bindLocks = ConcurrentHashMap<String, Mutex>()

    // Guarded by the per-plugin bind lock; also cleared by death recipients
    // running on Binder threads.
    private val connections = ConcurrentHashMap<String, PluginConnection>()

    private class PluginConnection(
        val endpoint: IBinder,
        val serviceConnection: ServiceConnection,
        val deathWatch: IBinder.DeathRecipient,
    )

    override suspend fun invoke(request: IsolatedInvocation): CommandResult {
        val endpoint = endpointFor(request.pluginId)
            ?: return CommandResult.Err(
                code = McosErrorCode.PLUGIN_ERROR.name,
                message = "cannot bind the plugin process for '${request.pluginId}'",
                retryable = true,
                details = buildJsonObject { put("reason", BIND_FAILURE) },
            )
        return TransportIsolationHost(
            PipeIsolationChannel(BinderWirePipe(endpoint), BinderWire.CODE_INVOKE),
        ).invoke(request)
    }

    /**
     * Bind/cache bookkeeping for [pluginId]. Only same-plugin bookkeeping
     * serializes: the lock is per plugin id, so a slow bind of one plugin
     * cannot delay another's first invoke. The invocation itself runs
     * lock-free.
     */
    private suspend fun endpointFor(pluginId: String): IBinder? {
        val lock = bindLocks.computeIfAbsent(pluginId) { Mutex() }
        return lock.withLock {
            connections[pluginId]?.takeIf { it.endpoint.pingBinder() }?.let { return it.endpoint }
            connections.remove(pluginId)?.let { release(pluginId, it) }
            bind(pluginId)?.let { connection ->
                connections[pluginId] = connection
                connection.endpoint
            }
        }
    }

    private suspend fun bind(pluginId: String): PluginConnection? {
        val artifact = artifactFor(pluginId) ?: return null
        val facadeServer = IsolatedFacadeServer(
            host = hostServices,
            signer = signer,
            pluginId = pluginId,
            // Same-app isolated process → same Linux UID (§8.2 check 1 pins
            // the admission to this app; foreign UIDs never match).
            expectedUid = context.applicationInfo.uid,
            // Same table the in-process Stage-4 facade uses, so grants are
            // one namespace across both boundaries (04 §6.1).
            userGrantRegistry = userGrantRegistry,
        )
        val intent = Intent().setClass(context, IsolatedPluginProcessService::class.java).apply {
            putExtra(IsolatedPluginProcessService.EXTRA_PLUGIN_ID, pluginId)
            putExtra(IsolatedPluginProcessService.EXTRA_ARTIFACT_PATH, artifact.absolutePath)
            putExtras(
                Bundle().apply {
                    putBinder(
                        IsolatedPluginProcessService.EXTRA_FACADE,
                        FacadeBinderEndpoint(facadeServer),
                    )
                },
            )
        }
        val connected = CompletableDeferred<IBinder>()
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // Cache invalidation is the death recipient's job; a later
                // invoke re-binds through endpointFor.
            }
        }
        if (!context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) return null
        return try {
            val endpoint = withTimeout(bindTimeoutMs) { connected.await() }
            val deathWatch = IBinder.DeathRecipient {
                connections.remove(pluginId)?.let { stale -> release(pluginId, stale) }
            }
            endpoint.linkToDeath(deathWatch, 0)
            PluginConnection(endpoint, serviceConnection, deathWatch)
        } catch (e: CancellationException) {
            // Every cancellation path must release the binding, not just the
            // bind timeout: a run cancelled for any other reason would leak
            // the ServiceConnection and keep the plugin process alive. The
            // timeout is the only cancellation that is a legitimate "no
            // endpoint" outcome; anything else propagates.
            context.unbindService(serviceConnection)
            if (e is TimeoutCancellationException) null else throw e
        }
    }

    private fun release(pluginId: String, connection: PluginConnection) {
        runCatching { connection.endpoint.unlinkToDeath(connection.deathWatch, 0) }
        runCatching { context.unbindService(connection.serviceConnection) }
    }

    companion object {
        /** Audit `details.reason` when the plugin process cannot be bound. */
        const val BIND_FAILURE = "isolation_bind_failure"
    }
}
