package com.morainet.mcos.android

import android.content.Context
import com.morainet.mcos.android.host.ActivityResultBridge
import com.morainet.mcos.android.host.AndroidHostServices
import com.morainet.mcos.android.host.AndroidMarketplaceHttpTransport
import com.morainet.mcos.android.host.isolation.BinderIsolationHost
import com.morainet.mcos.android.host.isolation.StagedArtifactResolver
import com.morainet.mcos.android.host.AndroidSecureStore
import com.morainet.mcos.android.host.RuntimePermissionBridge
import com.morainet.mcos.plugin.camera.CameraPlugin
import com.morainet.mcos.plugin.files.FilesPlugin
import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.plugin.system.SystemPlugin
import com.morainet.mcos.android.host.AlarmManagerWakeScheduler
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.workflow.FileArmedScheduleStore
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.marketplace.InstallProgress
import com.morainet.mcos.marketplace.InstallRecordStore
import com.morainet.mcos.marketplace.MarketplaceHttpTransport
import com.morainet.mcos.marketplace.PluginInstaller
import com.morainet.mcos.marketplace.RecipeInstaller
import com.morainet.mcos.marketplace.RecipeSignatureVerifier
import com.morainet.mcos.runtime.core.plugin.McosPackage
import com.morainet.mcos.runtime.core.plugin.PluginLoader
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.ArtifactVerifier
import com.morainet.mcos.security.EnterprisePolicy
import com.morainet.mcos.security.EnterprisePolicySource
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.security.InMemoryPublisherKeyStore
import com.morainet.mcos.security.PluginTrustGate
import com.morainet.mcos.security.SnapshotFile
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.FileAuditLog
import com.morainet.mcos.security.audit.deriveAuditHmacKey
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.security.permission.FileGrantStore
import com.morainet.mcos.security.permission.PermissionKernel
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.SecureStore
import android.util.Base64
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.SecureRandom

/**
 * Process-lifetime dependencies, built once by [McosApplication] from the
 * application [Context] (durable schedule hosting, 10 §6): a broadcast receiver
 * that cold-starts the process (an AlarmManager wake, or BOOT_COMPLETED) needs
 * a live runtime with no Activity present. [AndroidHostServices] reaches Android
 * APIs through the application context; the Activity-bound capabilities,
 * [ActivityResultBridge] (camera capture's activity-result launcher, and the
 * WRITE_SETTINGS special-access deep-link) and [RuntimePermissionBridge]
 * (in-app runtime-permission prompts, 04 §6.3), are re-attached by the Compose
 * layer on each Activity create and return null / deny honestly while no
 * Activity is registered.
 *
 * A configuration change reuses this same [AppDeps] (the [McosViewModel]
 * re-attaches via [McosViewModel.attach]); the runtime and its registry now
 * survive the change rather than being rebuilt.
 *
 * [secureStore] is typed as the sdk [SecureStore] interface (the production
 * instance is [AndroidSecureStore]) so tests can substitute an in-memory
 * fake without touching Android APIs. [auditLog] is file-backed
 * (`audit/audit.jsonl`) and runs for the process lifetime.
 */
class AppDeps(
    val runtime: McosRuntime,
    val hostServices: HostServices,
    val registry: CommandRegistry,
    val plugins: List<McosPlugin>,
    val resultBridge: ActivityResultBridge,
    /** In-app runtime-permission prompts (04 §6.3); Activity-bound like the result bridge. */
    val permissionBridge: RuntimePermissionBridge,
    val secureStore: SecureStore,
    val marketplace: MarketplaceDeps,
    val auditLog: AuditLog,
    /** The kernel holding the file-backed grant table (install-time consent lands here). */
    val permissionKernel: PermissionKernel,
    /** System EventBus shared by the runtime and the Agent loop (agent.* events, 06 §11). */
    val eventBus: EventBus,
)

/**
 * Marketplace dependencies (09-marketplace.md §7 install pipeline).
 *
 * [index] is intentionally NOT built here: it is cheap to construct and the
 * base URL is user-configurable at runtime, so [MarketplaceViewModel] builds
 * one per base URL. The installer, verifier and key store are shared.
 */
class MarketplaceDeps(
    val transport: MarketplaceHttpTransport,
    val keyStore: InMemoryPublisherKeyStore,
    val verifier: ArtifactVerifier,
    val installer: PluginInstaller,
    val pluginFactory: MarketplacePluginFactory,
    val blocklistVerifier: BlocklistVerifier,
    /** Installer progress events, emitted from [PluginInstaller]'s onProgress hook. */
    val installProgress: MutableSharedFlow<InstallProgress>,
    /** Pure recipe install wizard (dependency resolution + placeholder compile). */
    val recipeInstaller: RecipeInstaller,
)

/**
 * Composition root for the Android host runtime: builds the host services,
 * registry, enterprise policy, executor (full production security posture)
 * and the runtime, and collects the host's built-in plugins. UI-free by
 * design — the reference demo shell (and any custom-host app) calls
 * [create] from its Application and binds the Activity-bound bridges from
 * its own UI layer (architecture review #8: the UI layer holds no
 * construction logic).
 */
object CompositionRoot {

    /** SecureStore key holding the device-bound audit export-signature seed. */
    private const val AUDIT_HMAC_SEED_KEY = "audit_hmac_seed"

    /** SecureStore key for the grant/install-record snapshot HMAC seed (separate domain). */
    private const val STATE_HMAC_SEED_KEY = "state_hmac_seed"

    /**
     * The reference host's default built-in plugin set (hello / system /
     * camera / files). A custom host passes its own list to [create] — the
     * marketplace factory dispatch (curated ids → local implementations) is
     * built from whatever lands here.
     */
    fun defaultBuiltIns(): List<McosPlugin> = listOf(
        HelloPlugin(), SystemPlugin(), CameraPlugin(), FilesPlugin(),
    )

    /**
     * Build the process-lifetime [AppDeps].
     *
     * @param context any Context; the application context is extracted.
     * @param builtIns plugins that ship inside the host app at BUILTIN trust
     *   (defaults to the reference set — inject your own catalog instead).
     * @param processIsolation opt-in activation of the plugin-process
     *   boundary (08 §8.1, item 44): when true the executor dispatches every
     *   non-BUILTIN plugin through [BinderIsolationHost] into the dedicated
     *   `:mcos_plugin` process. Default FALSE — flipping it requires the
     *   on-device verification of the Binder chain (process split,
     *   `getCallingUid` identity, crash isolation, rebind-after-death);
     *   until then non-BUILTIN plugins keep the audited in-process fallback.
     *   Under the flag, registration is manifest-only (item 45): the install
     *   pipeline registers descriptors from the `.mcos` plugin.json — the
     *   plugin's dex never loads in the MAIN process at all, and non-BUILTIN
     *   execution only ever runs in `:mcos_plugin`. A bind failure surfaces
     *   as an honest PLUGIN_ERROR (no fallback).
     */
    fun create(
        context: Context,
        builtIns: List<McosPlugin> = defaultBuiltIns(),
        processIsolation: Boolean = false,
    ): AppDeps {
        val appContext = context.applicationContext
        val resultBridge = ActivityResultBridge()
        // In-app runtime-permission prompts (04 §6.3): attached by the Compose
        // layer per Activity; a headless run (schedule alarm / boot) sees null
        // from request() and the command degrades honestly.
        val permissionBridge = RuntimePermissionBridge()
        val hostServices = AndroidHostServices(appContext, resultBridge, permissionBridge)
        val secureStore = AndroidSecureStore(appContext)
        val registry = CommandRegistry()

        // Audit trail (03-runtime.md §13): persistent JSONL under filesDir
        // with 30d/10k retention, replayed on start so records survive
        // process death and configuration changes. `export()` carries an
        // HMAC-SHA256 signature line keyed by a device-bound seed — generated
        // once, then persisted via the SecureStore so signatures verify
        // across restarts.
        val auditLog = FileAuditLog(
            file = File(appContext.filesDir, "audit/audit.jsonl"),
            hmacKey = deriveAuditHmacKey(persistedSeed(secureStore, AUDIT_HMAC_SEED_KEY)),
        ).apply { start() }

        // Enterprise policy (08-security.md §13 / 09-marketplace.md §6.5):
        // sideloading is disabled by default (fail-closed). A host that
        // whitelists sideloading would serve a policy with
        // disableSideload=false; here the built-in plugins always load as
        // BUILTIN regardless of this flag.
        val enterprisePolicy = EnterprisePolicySource.fixed(
            EnterprisePolicy(disableSideload = true)
        )

        // Marketplace wiring (09-marketplace.md §7). The ArtifactVerifier and
        // the trust-gated PluginLoader MUST share one verifier: the facade's
        // default loader runs with verifier=null, which denies signed
        // artifacts outright (verifier_not_configured) — so wiring only the
        // installer would fail at the LOADING step. The key store starts
        // empty: §6.3 public-key bootstrap is a follow-up, and an empty store
        // fails closed (unknown_key) rather than trusting unsigned artifacts.
        val marketTransport = AndroidMarketplaceHttpTransport()
        // §6.3 initial trust: seed the bundled trust anchors so signature
        // verification works on a cold start (before any install pins a key).
        // Idempotent — install-pinned keys and revoked-key refreshes layer on.
        val publisherKeys = InMemoryPublisherKeyStore().apply { bootstrap(TrustAnchors.bundled()) }
        val artifactVerifier = ArtifactVerifier(publisherKeys)
        val pluginLoader = PluginLoader(
            trustGate = PluginTrustGate(verifier = artifactVerifier) { enterprisePolicy.current() },
            registry = registry,
        )
        val installProgress = MutableSharedFlow<InstallProgress>(extraBufferCapacity = 64)
        // Durable install records (snapshot paradigm, tamper-evident via the
        // device-bound state seed). Persisted installs rehydrate after a
        // restart — see MarketplaceViewModel.attach and
        // PluginInstaller.rehydrateInstalled.
        val stateHmacKey = SnapshotFile.deriveHmacKey(
            persistedSeed(secureStore, STATE_HMAC_SEED_KEY),
        )
        val installRecordStore = InstallRecordStore(
            file = File(appContext.filesDir, "marketplace/install-records.json"),
            hmacKey = stateHmacKey,
        )
        val installer = PluginInstaller(
            transport = marketTransport,
            verifier = artifactVerifier,
            keyStore = publisherKeys,
            loader = pluginLoader,
            registry = registry,
            downloadDir = File(appContext.filesDir, "marketplace").apply { mkdirs() }.absolutePath,
            onProgress = { installProgress.tryEmit(it) },
            installRecordStore = installRecordStore,
            // Manifest-only registration (08 §8, item 45): under process
            // isolation the LOADING step registers the decoded plugin.json —
            // descriptors, schemas, permissions — and never instantiates
            // plugin code in this process; the dex exists only in
            // :mcos_plugin. Flag off (default) keeps the instance path.
            manifestDecoder = if (processIsolation) {
                { bytes -> McosPackage.readPluginManifest(bytes) }
            } else {
                null
            },
        )
        // Marketplace well-known key (§6.3/§14.3): the signed blocklist is
        // verified against this bundled trust anchor. See TrustAnchors — the
        // material is a structurally valid placeholder pending the operator's
        // real key, so blocklist verification is fail-closed until then.
        val blocklistVerifier = BlocklistVerifier(TrustAnchors.marketplaceKey)
        // Same trust anchor gates recipe compilation (§8.3 step 5): the
        // envelope must carry a valid marketplace signature or the installer
        // refuses to compile it.
        val recipeSignatureVerifier = RecipeSignatureVerifier(TrustAnchors.marketplaceKey)
        val marketplace = MarketplaceDeps(
            transport = marketTransport,
            keyStore = publisherKeys,
            verifier = artifactVerifier,
            installer = installer,
            // Curated built-ins + dynamic .mcos loading (§16.3 DexClassLoader
            // isolation) for any other signed, verified package.
            pluginFactory = MarketplacePluginFactory(dynamicLoader = DexPluginLoader(appContext)),
            blocklistVerifier = blocklistVerifier,
            installProgress = installProgress,
            recipeInstaller = RecipeInstaller(recipeSignatureVerifier),
        )

        // Owned here (not Builder-defaulted) so the built-in plugins'
        // permissions can be granted onto the SAME kernel instance the
        // executor authorizes against — see below. The grant table is
        // file-backed (tamper-evident via the state seed): consent survives
        // restarts, and a tampered/missing file fails closed (grants
        // nothing). Session grants never persist, by kernel design.
        val permissionKernel = DefaultPermissionKernel(
            FileGrantStore(File(appContext.filesDir, "permissions/grants.json"), stateHmacKey),
        )

        // ONE signer shared by the confirmation coordinator (signs the
        // post-approval retry AuthStamp) and the executor (verifies it).
        // Two HmacAuthStampSigner instances each generate a fresh random
        // key, so a defaults()-built executor would reject every approved
        // stamp with "failed signature verification".
        val authStampSigner = HmacAuthStampSigner()

        // Built-in plugins ship with the app at BUILTIN trust level with no
        // install step (the host-injected [builtIns] list — the reference
        // default is the hello/system/camera/files set), so their declared
        // permissions are granted up front (PluginPermissionBootstrap
        // documents the consent-model trade-off). First-use confirmation
        // dialogs still apply — this only clears the Stage-6 hard gate.
        val plugins = builtIns
        plugins.forEach { PluginPermissionBootstrap.grantAll(permissionKernel, it) }

        // One system EventBus shared by the runtime (run events) and the
        // Agent loop (agent.* lifecycle events, 06 §11) so subscribers see
        // both streams from a single subscription point.
        val eventBus = TypedEventBus()

        // Durable schedule hosting (10 §6): armed schedules persist here
        // (tamper-evident via the same device-bound state seed as grants/
        // installs). MarketplaceViewModel.attach calls runtime.rehydrateSchedules
        // after rehydrating installed recipes, so a re-registered scheduled
        // workflow re-arms across process death and reboots.
        val armedScheduleStore = FileArmedScheduleStore(
            file = File(appContext.filesDir, "triggers/armed-schedules.json"),
            hmacKey = stateHmacKey,
        )
        // Exact AlarmManager wakes at cron boundaries (10 §6): schedules fire
        // while backgrounded / in Doze, not just while the poll driver runs.
        val wakeScheduler = AlarmManagerWakeScheduler(appContext)

        // Plugin-process boundary (08 §8.1, item 44): opt-in only. The
        // staged-artifact resolver reads the tamper-evident install records
        // at bind time (once per plugin per process lifetime), so a plugin
        // installed after this host was built still resolves.
        val isolationHost = if (processIsolation) {
            BinderIsolationHost(
                context = appContext,
                hostServices = hostServices,
                signer = authStampSigner,
                artifactFor = { pluginId ->
                    StagedArtifactResolver.resolve(
                        records = installRecordStore.load(),
                        downloadDir = File(appContext.filesDir, "marketplace"),
                        pluginId = pluginId,
                    )
                },
            )
        } else {
            null
        }

        val runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(permissionKernel)
            .withAuthStampSigner(authStampSigner)
            .withEnterprisePolicySource(enterprisePolicy)
            .withPluginLoader(pluginLoader)
            .withPluginInstaller(installer)
            .withEventBus(eventBus)
            .withArmedScheduleStore(armedScheduleStore)
            .withWakeScheduler(wakeScheduler)
            // Wires the default WorkflowEngine's audit sink; the injected
            // executor below carries the same log via its SecurityConfig.
            .withAuditLog(auditLog)
            .withExecutor(
                Executor(
                    registry = registry,
                    hostServices = hostServices,
                    // Full production posture; the enterprise policy, the
                    // persistent audit log, the shared permission kernel
                    // (holding the built-ins' grants), and the shared stamp
                    // signer all reach the executor now.
                    security = SecurityConfig.defaults().copy(
                        enterprisePolicy = enterprisePolicy,
                        auditLog = auditLog,
                        kernel = permissionKernel,
                        signer = authStampSigner,
                    ),
                    // null (default) → audited in-process fallback for
                    // non-BUILTIN plugins; the opt-in host (08 §8.1) routes
                    // them into the :mcos_plugin process instead.
                    isolationHost = isolationHost,
                )
            )
            .build()

        // Let the schedule alarm receiver reach this runtime (10 §6). Rebuilt
        // per Activity onCreate; the latest runtime wins.
        McosRuntimeHolder.runtime = runtime

        return AppDeps(
            runtime = runtime,
            hostServices = hostServices,
            registry = registry,
            plugins = plugins,
            resultBridge = resultBridge,
            permissionBridge = permissionBridge,
            secureStore = secureStore,
            marketplace = marketplace,
            auditLog = auditLog,
            permissionKernel = permissionKernel,
            eventBus = eventBus,
        )
    }

    /**
     * Return the persisted seed for [key], generating and storing it on
     * first use. One short runBlocking over the SharedPreferences-backed
     * [AndroidSecureStore]; kept narrow so only this read blocks. The raw
     * 32 bytes live in the store (04 §6.4 byte-valued secrets); the Base64
     * rendering is only the in-memory key-material form. Same construction
     * as the audit export seed — separate keys per purpose.
     */
    private fun persistedSeed(store: SecureStore, key: String): String = runBlocking {
        val existing = store.get(key)
        val seed = existing ?: ByteArray(32)
            .also { random -> SecureRandom().nextBytes(random) }
            .also { generated -> store.put(key, generated) }
        Base64.encodeToString(seed, Base64.NO_WRAP)
    }
}
