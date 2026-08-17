package com.morainet.mcos.android

import androidx.activity.ComponentActivity
import com.morainet.mcos.android.host.ActivityResultBridge
import com.morainet.mcos.android.host.AndroidHostServices
import com.morainet.mcos.android.host.AndroidSecureStore
import com.morainet.mcos.plugin.camera.CameraPlugin
import com.morainet.mcos.plugin.files.FilesPlugin
import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.plugin.system.SystemPlugin
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.executor.Executor
import com.morainet.mcos.runtime.registry.CommandRegistry
import com.morainet.mcos.runtime.security.EnterprisePolicy
import com.morainet.mcos.runtime.security.EnterprisePolicySource
import com.morainet.mcos.runtime.security.SecurityConfig
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.SecureStore

/**
 * Activity-scoped dependencies built once per [ComponentActivity] creation.
 *
 * The runtime is inherently activity-scoped: [ActivityResultBridge] forwards
 * through a Compose-registered activity-result launcher (camera capture), and
 * [AndroidHostServices] reaches Android APIs through the activity's context.
 * A configuration change therefore builds a fresh [AppDeps] and the
 * [McosViewModel] re-attaches via [McosViewModel.attach] — UI state survives
 * the change, the runtime does not (runs in flight keep using the previous
 * instance until their terminal event, bounded by the event-bus lifecycle).
 *
 * [secureStore] is typed as the sdk [SecureStore] interface (the production
 * instance is [AndroidSecureStore]) so tests can substitute an in-memory
 * fake without touching Android APIs.
 */
class AppDeps(
    val runtime: McosRuntime,
    val hostServices: HostServices,
    val registry: CommandRegistry,
    val plugins: List<McosPlugin>,
    val resultBridge: ActivityResultBridge,
    val secureStore: SecureStore,
)

/**
 * Composition root for the Android demo shell: builds the host services,
 * registry, enterprise policy, executor (full production security posture)
 * and the runtime, and collects the built-in plugins. Kept out of
 * [MainActivity] so the wiring is readable and the UI layer holds no
 * construction logic (architecture review #8).
 */
object CompositionRoot {

    fun create(activity: ComponentActivity): AppDeps {
        val resultBridge = ActivityResultBridge()
        val hostServices = AndroidHostServices(activity, resultBridge)
        val secureStore = AndroidSecureStore(activity.applicationContext)
        val registry = CommandRegistry()

        // Enterprise policy (08-security.md §13 / 09-marketplace.md §6.5):
        // sideloading is disabled by default (fail-closed). A host that
        // whitelists sideloading would serve a policy with
        // disableSideload=false; here the built-in plugins always load as
        // BUILTIN regardless of this flag.
        val enterprisePolicy = EnterprisePolicySource.fixed(
            EnterprisePolicy(disableSideload = true)
        )

        val runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withEnterprisePolicySource(enterprisePolicy)
            .withExecutor(
                Executor(
                    registry = registry,
                    hostServices = hostServices,
                    // Full production posture; the enterprise policy also
                    // reaches the executor now (it previously only gated
                    // plugin loading).
                    security = SecurityConfig.defaults().copy(
                        enterprisePolicy = enterprisePolicy,
                    ),
                )
            )
            .build()

        val plugins = listOf(HelloPlugin(), SystemPlugin(), CameraPlugin(), FilesPlugin())

        return AppDeps(
            runtime = runtime,
            hostServices = hostServices,
            registry = registry,
            plugins = plugins,
            resultBridge = resultBridge,
            secureStore = secureStore,
        )
    }
}
