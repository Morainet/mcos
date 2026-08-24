package com.morainet.mcos.android

import androidx.activity.ComponentActivity
import com.morainet.mcos.android.host.ActivityResultBridge
import com.morainet.mcos.android.host.AndroidHostServices
import com.morainet.mcos.android.host.AndroidMarketplaceHttpTransport
import com.morainet.mcos.android.host.AndroidSecureStore
import com.morainet.mcos.plugin.camera.CameraPlugin
import com.morainet.mcos.plugin.files.FilesPlugin
import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.plugin.system.SystemPlugin
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.marketplace.InstallProgress
import com.morainet.mcos.marketplace.InstallRecordStore
import com.morainet.mcos.marketplace.MarketplaceHttpTransport
import com.morainet.mcos.marketplace.PluginInstaller
import com.morainet.mcos.marketplace.RecipeInstaller
import com.morainet.mcos.marketplace.RecipeSignatureVerifier
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
 *
 * [auditLog] is the one dependency that outlives the activity scope: it is
 * file-backed (`audit/audit.jsonl`), so a fresh instance replays the same
 * trail after a configuration change or process death.
 */
class AppDeps(
    val runtime: McosRuntime,
    val hostServices: HostServices,
    val registry: CommandRegistry,
    val plugins: List<McosPlugin>,
    val resultBridge: ActivityResultBridge,
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
 * Composition root for the Android demo shell: builds the host services,
 * registry, enterprise policy, executor (full production security posture)
 * and the runtime, and collects the built-in plugins. Kept out of
 * [MainActivity] so the wiring is readable and the UI layer holds no
 * construction logic (architecture review #8).
 */
object CompositionRoot {

    /** SecureStore key holding the device-bound audit export-signature seed. */
    private const val AUDIT_HMAC_SEED_KEY = "audit_hmac_seed"

    /** SecureStore key for the grant/install-record snapshot HMAC seed (separate domain). */
    private const val STATE_HMAC_SEED_KEY = "state_hmac_seed"

    fun create(activity: ComponentActivity): AppDeps {
        val resultBridge = ActivityResultBridge()
        val hostServices = AndroidHostServices(activity, resultBridge)
        val secureStore = AndroidSecureStore(activity.applicationContext)
        val registry = CommandRegistry()

        // Audit trail (03-runtime.md §13): persistent JSONL under filesDir
        // with 30d/10k retention, replayed on start so records survive
        // process death and configuration changes. `export()` carries an
        // HMAC-SHA256 signature line keyed by a device-bound seed — generated
        // once, then persisted via the SecureStore so signatures verify
        // across restarts.
        val auditLog = FileAuditLog(
            file = File(activity.filesDir, "audit/audit.jsonl"),
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
            file = File(activity.filesDir, "marketplace/install-records.json"),
            hmacKey = stateHmacKey,
        )
        val installer = PluginInstaller(
            transport = marketTransport,
            verifier = artifactVerifier,
            keyStore = publisherKeys,
            loader = pluginLoader,
            registry = registry,
            downloadDir = File(activity.filesDir, "marketplace").apply { mkdirs() }.absolutePath,
            onProgress = { installProgress.tryEmit(it) },
            installRecordStore = installRecordStore,
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
            pluginFactory = MarketplacePluginFactory(dynamicLoader = DexPluginLoader(activity)),
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
            FileGrantStore(File(activity.filesDir, "permissions/grants.json"), stateHmacKey),
        )

        // ONE signer shared by the confirmation coordinator (signs the
        // post-approval retry AuthStamp) and the executor (verifies it).
        // Two HmacAuthStampSigner instances each generate a fresh random
        // key, so a defaults()-built executor would reject every approved
        // stamp with "failed signature verification".
        val authStampSigner = HmacAuthStampSigner()

        // Built-in plugins ship with the app at BUILTIN trust level with no
        // install step, so their declared permissions are granted up front
        // (PluginPermissionBootstrap documents the consent-model trade-off).
        // First-use confirmation dialogs still apply — this only clears the
        // Stage-6 hard permission gate.
        val plugins = listOf(HelloPlugin(), SystemPlugin(), CameraPlugin(), FilesPlugin())
        plugins.forEach { PluginPermissionBootstrap.grantAll(permissionKernel, it) }

        // One system EventBus shared by the runtime (run events) and the
        // Agent loop (agent.* lifecycle events, 06 §11) so subscribers see
        // both streams from a single subscription point.
        val eventBus = TypedEventBus()

        val runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPermissionKernel(permissionKernel)
            .withAuthStampSigner(authStampSigner)
            .withEnterprisePolicySource(enterprisePolicy)
            .withPluginLoader(pluginLoader)
            .withPluginInstaller(installer)
            .withEventBus(eventBus)
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
                )
            )
            .build()

        return AppDeps(
            runtime = runtime,
            hostServices = hostServices,
            registry = registry,
            plugins = plugins,
            resultBridge = resultBridge,
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
     * [AndroidSecureStore]; kept narrow so only this read blocks. Same
     * construction as the audit export seed — separate keys per purpose.
     */
    private fun persistedSeed(store: SecureStore, key: String): String = runBlocking {
        store.get(key) ?: Base64.encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) },
            Base64.NO_WRAP,
        ).also { store.put(key, it) }
    }
}
