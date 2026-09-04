package com.morainet.mcos.android.host.isolation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.morainet.mcos.android.DexPluginLoader
import com.morainet.mcos.runtime.core.plugin.McosPackage
import com.morainet.mcos.runtime.core.error.McosErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The plugin-process host of isolation slice 3b-final ([08-security.md
 * §8.1]): declared `android:process=":mcos_plugin"` in the SDK manifest, it
 * is where a marketplace plugin's code runs instead of the main process.
 *
 * One bind = one plugin: the main process's [BinderIsolationHost] supplies
 * the plugin id, the staged (install-time verified) artifact path, and its
 * own facade endpoint Binder via [EXTRA_FACADE] (`Bundle.putBinder` — the
 * facade handle rides the binding, not the wire). This service then:
 *
 * 1. re-reads the artifact's manifest ([McosPackage.readManifest]) and
 *    refuses a manifest id that does not match the requested plugin id
 *    (masquerade guard, mirroring the in-process loader);
 * 2. DEX-loads the plugin ([DexPluginLoader]) — the plugin's code exists
 *    ONLY in this process;
 * 3. builds an [IsolatedPluginRunner] whose facade channel is
 *    [PipeIsolationChannel] over [BinderWirePipe] back to the main-process
 *    [FacadeBinderEndpoint] (`CODE_FACADE`), runs the plugin lifecycle
 *    (`onLoad` gets the proxy facade — no host objects), and returns an
 *    [InvokeBinderEndpoint] serving `CODE_INVOKE`.
 *
 * If the plugin cannot be loaded the service still returns an endpoint —
 * one answering every invoke with an honest `PLUGIN_ERROR`
 * (`plugin_load_failed`) — because a null [onBind] would collapse the
 * failure into an opaque bind error with no per-invocation diagnosis.
 *
 * 🟡 Thin Android shell: all protocol logic lives in the JVM-tested pure
 * halves ([BinderWire]/[WireService]/[IsolatedPluginRunner]); what remains
 * device-only is the process split itself — the pending on-device
 * verification item.
 */
class IsolatedPluginProcessService : Service() {

    /** The runner of the most recent successful binding (rebind = restart). */
    private var runner: IsolatedPluginRunner? = null

    override fun onBind(intent: Intent): IBinder {
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val artifactPath = intent.getStringExtra(EXTRA_ARTIFACT_PATH)
        val facade = intent.extras?.getBinder(EXTRA_FACADE)
        if (pluginId.isNullOrBlank() || artifactPath.isNullOrBlank() || facade == null) {
            return failingEndpoint("plugin process binding was incomplete")
        }

        val plugin = try {
            val bytes = File(artifactPath).readBytes()
            val info = McosPackage.readManifest(bytes)
            check(info.id == pluginId) {
                "artifact manifest id '${info.id}' does not match requested plugin '$pluginId'"
            }
            DexPluginLoader(this).load(pluginId, bytes, info.entry)
        } catch (e: Exception) {
            return failingEndpoint(
                "plugin '$pluginId' failed to load in the plugin process: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        val facadeChannel = PipeIsolationChannel(BinderWirePipe(facade), BinderWire.CODE_FACADE)
        val newRunner = IsolatedPluginRunner(plugin, facadeChannel)
        // Lifecycle before the endpoint goes live. onBind runs on this
        // process's main thread; the dedicated plugin process hosts nothing
        // else, so the blocking start is contained.
        runBlocking { newRunner.start() }
        runner?.let { old -> runBlocking { old.stop() } }
        runner = newRunner
        return InvokeBinderEndpoint { frame -> WireService.serveInvoke(frame, newRunner) }
    }

    override fun onDestroy() {
        runner?.let { runBlocking { it.stop() } }
        runner = null
        super.onDestroy()
    }

    /** An endpoint answering every invoke with an honest PLUGIN_ERROR envelope. */
    private fun failingEndpoint(reason: String): InvokeBinderEndpoint =
        InvokeBinderEndpoint { frame ->
            val op = BinderWire.unframe(frame)?.first
            BinderWire.frame(
                op ?: "error",
                IsolationCodec.encodeError(
                    code = McosErrorCode.PLUGIN_ERROR.name,
                    message = reason,
                    retryable = false,
                    details = buildJsonObject { put("reason", LOAD_FAILURE) },
                ),
            )
        }

    companion object {
        /** The plugin this binding must host ([McosPlugin.manifest].id). */
        const val EXTRA_PLUGIN_ID = "com.morainet.mcos.isolation.EXTRA_PLUGIN_ID"

        /** Absolute path of the staged, install-time-verified `.mcos` artifact. */
        const val EXTRA_ARTIFACT_PATH = "com.morainet.mcos.isolation.EXTRA_ARTIFACT_PATH"

        /** The main-process facade endpoint, via `Bundle.putBinder` (§8.2 check 1 source). */
        const val EXTRA_FACADE = "com.morainet.mcos.isolation.EXTRA_FACADE"

        /** Audit `details.reason` when the plugin process cannot load its plugin. */
        const val LOAD_FAILURE = "plugin_load_failed"

        /** Process suffix declared for this service in the SDK manifest. */
        const val PROCESS_SUFFIX = ":mcos_plugin"
    }
}
