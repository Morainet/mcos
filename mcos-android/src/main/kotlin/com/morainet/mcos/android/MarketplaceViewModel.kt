package com.morainet.mcos.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morainet.mcos.marketplace.InstallProgress
import com.morainet.mcos.marketplace.InstallResult
import com.morainet.mcos.marketplace.InstallState
import com.morainet.mcos.marketplace.MarketplaceIndex
import com.morainet.mcos.marketplace.MarketplaceIndexException
import com.morainet.mcos.marketplace.PackageMetadata
import com.morainet.mcos.marketplace.PermissionDiff
import com.morainet.mcos.marketplace.RecipeEnvelope
import com.morainet.mcos.marketplace.RecipeInstallOutcome
import com.morainet.mcos.marketplace.RecipeInstallPlan
import com.morainet.mcos.marketplace.UninstallResult
import com.morainet.mcos.marketplace.UpdateResult
import com.morainet.mcos.runtime.core.workflow.WorkflowJson
import com.morainet.mcos.sdk.McosPlugin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Secure-store key holding the marketplace index base URL. */
private const val MARKETPLACE_URL = "marketplace_url"

/**
 * Immutable UI state for the marketplace card. All mutation happens in
 * [MarketplaceViewModel]; the composables only render.
 */
data class MarketplaceUiState(
    /** Marketplace index base URL (e.g. "https://index.example.com"), user-configured. */
    val baseUrl: String = "",
    val query: String = "",
    val results: List<PackageMetadata> = emptyList(),
    val searching: Boolean = false,
    /** Latest [InstallProgress] per package id (DOWNLOADING → … → INSTALLED). */
    val installStates: Map<String, InstallProgress> = emptyMap(),
    /** Terminal install outcome per package id. */
    val installResults: Map<String, InstallResult> = emptyMap(),
    /**
     * Metadata a package was installed with, kept so an update can diff the
     * old version's permissions against the new one (§7.2). Populated on
     * install/update success — a restart-only install (rehydrated, never
     * re-installed this session) has no entry, so the update affordance stays
     * hidden until the package is seen in search again.
     */
    val installedMetas: Map<String, PackageMetadata> = emptyMap(),
    /** Recipe search results (§8.2). */
    val recipeResults: List<RecipeEnvelope> = emptyList(),
    /** The recipe wizard currently open, or null. */
    val recipePlan: ActiveRecipePlan? = null,
    /** A pending update awaiting permission-diff consent (§7.2), or null. */
    val pendingUpdate: PendingUpdate? = null,
    /**
     * Monotonic revision of registry-affecting events (successful install or
     * uninstall). The UI observes it to refresh the DSL command palette.
     */
    val registryRevision: Int = 0,
    val error: String? = null,
    val message: String? = null,
)

/** A recipe and its resolved install plan, driving the wizard dialog (§8.3). */
data class ActiveRecipePlan(
    val recipe: RecipeEnvelope,
    val plan: RecipeInstallPlan,
)

/** An update held at the consent gate: the diff must be accepted first (§7.2). */
data class PendingUpdate(
    val oldMeta: PackageMetadata,
    val newMeta: PackageMetadata,
    val diff: PermissionDiff,
)

/**
 * Owns the marketplace UI state: index search, plugin install / uninstall via
 * the real [com.morainet.mcos.marketplace.PluginInstaller] pipeline
 * (download → verify → trust gate → registry), keeping the composables pure
 * renderers (architecture review #8).
 *
 * Same lifecycle contract as [McosViewModel]: plain [ViewModel] that survives
 * configuration changes and re-attaches to the activity-scoped [AppDeps].
 * A fresh [AppDeps] carries a fresh registry, so attach() rehydrates
 * persisted installs (records + staged artifacts survive on disk): each
 * record is re-verified against its pinned publisher key before anything
 * is registered, then the plugin is loaded — the marketplace card keeps
 * working across restarts.
 *
 * Known limitation (documented in 11-implementation-status): rehydration
 * covers the curated [MarketplacePluginFactory] ids — dynamic `.mcos` code
 * loading is a later slice.
 */
class MarketplaceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MarketplaceUiState())
    val uiState: StateFlow<MarketplaceUiState> = _uiState.asStateFlow()

    private var deps: AppDeps? = null
    private var baseUrlLoaded = false
    private var progressJob: Job? = null

    /** Bind the activity-scoped dependencies; call from every onCreate. */
    fun attach(deps: AppDeps) {
        val freshChain = this.deps !== deps
        this.deps = deps
        if (freshChain) {
            // Registry/installer state was rebuilt — drop records belonging to
            // the previous activity-scoped chain, and (re)collect progress.
            _uiState.update { it.copy(installStates = emptyMap(), installResults = emptyMap()) }
            progressJob?.cancel()
            progressJob = viewModelScope.launch {
                deps.marketplace.installProgress.collect { progress ->
                    // Uninstall bookkeeping states are dropped: the terminal
                    // NOT_INSTALLED / the UNINSTALLING marker can arrive after
                    // uninstall()'s own state updates (collector resumptions
                    // are queued) and would leave a stale chip nothing clears.
                    // uninstall() sets its own synchronous UNINSTALLING entry.
                    if (progress.state == InstallState.NOT_INSTALLED ||
                        progress.state == InstallState.UNINSTALLING
                    ) {
                        return@collect
                    }
                    _uiState.update {
                        it.copy(installStates = it.installStates + (progress.packageId to progress))
                    }
                }
            }
            // Rehydrate persisted installs asynchronously: each record is
            // re-verified (Ed25519) against its pinned key before anything
            // registers — CPU work that must not block cold start. Progress
            // flows through installProgress above; restored plugins get
            // onLoad and the palette refresh bumps with registryRevision.
            viewModelScope.launch {
                val outcomes = deps.marketplace.installer.rehydrateInstalled(
                    pluginFactory = { pkg -> deps.marketplace.pluginFactory.factoryFor(pkg) },
                    seedKey = { key -> deps.marketplace.keyStore.put(key) },
                )
                val restored = outcomes.filter { it.state == InstallState.INSTALLED }
                if (restored.isNotEmpty()) {
                    restored.forEach { it.plugin?.onLoad(deps.hostServices) }
                    _uiState.update {
                        it.copy(
                            message = "Restored ${restored.size} marketplace plugin(s): " +
                                restored.joinToString { o -> o.packageId },
                            registryRevision = it.registryRevision + 1,
                        )
                    }
                }
            }
        }
        // Load the persisted index URL once per process.
        if (!baseUrlLoaded) {
            baseUrlLoaded = true
            viewModelScope.launch {
                deps.secureStore.get(MARKETPLACE_URL)?.let { url ->
                    if (url.isNotBlank()) {
                        _uiState.update { it.copy(baseUrl = url) }
                        // §6.3 startup revocation refresh, once a target index is
                        // known. Kept off the search hot path so browsing isn't
                        // coupled to trust maintenance.
                        refreshKeyTrust()
                    }
                }
            }
        }
    }

    private fun deps(): AppDeps =
        checkNotNull(deps) { "attach(deps) must be called before using the view model" }

    // ── input handlers ─────────────────────────────────────────────────

    fun onBaseUrlChange(value: String) {
        _uiState.update { it.copy(baseUrl = value, error = null) }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value, error = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ── search ─────────────────────────────────────────────────────────

    fun search() {
        val url = _uiState.value.baseUrl.trim().trimEnd('/')
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Set a marketplace index URL first (09-marketplace.md §11.1 compatible)") }
            return
        }
        _uiState.update { it.copy(searching = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val d = deps()
                d.secureStore.put(MARKETPLACE_URL, url)
                // The index is cheap to construct; build one per search so the
                // user can retarget the index URL without rebuilding deps.
                val index = MarketplaceIndex(
                    baseUrl = url,
                    transport = d.marketplace.transport,
                    blocklistVerifier = d.marketplace.blocklistVerifier,
                )
                val query = _uiState.value.query.takeIf { it.isNotBlank() }
                val response = index.search(query = query)
                _uiState.update {
                    it.copy(
                        results = response.results,
                        message = "${response.total} result(s)" + if (query != null) " for “$query”" else "",
                    )
                }
            } catch (e: MarketplaceIndexException) {
                _uiState.update {
                    it.copy(
                        results = emptyList(),
                        error = "[${e.code}] ${e.message}" + if (e.retryable) " (retryable)" else "",
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                _uiState.update { it.copy(searching = false) }
            }
        }
    }

    // ── key trust (§6.3 revocation) ─────────────────────────────────────

    /**
     * Best-effort §6.3 revocation refresh: pull `/v1/keys/revoked` and mark
     * matching keys REVOKED in the shared publisher key store, so later
     * installs (and restart rehydration re-verification) reject artifacts
     * signed by a revoked key. The revoked list is process-cached by
     * [MarketplaceIndex], so repeated calls are cheap. Fires from search;
     * failures are swallowed — trust refresh must not block browsing.
     */
    fun refreshKeyTrust() {
        val url = _uiState.value.baseUrl.trim().trimEnd('/')
        if (url.isBlank()) return
        viewModelScope.launch {
            val d = deps()
            val index = MarketplaceIndex(
                baseUrl = url,
                transport = d.marketplace.transport,
                blocklistVerifier = d.marketplace.blocklistVerifier,
            )
            try {
                val revoked = index.fetchRevokedKeys()
                if (revoked.isNotEmpty()) d.marketplace.keyStore.applyRevoked(revoked)
            } catch (_: Exception) {
                // Best-effort; the marketplace being unreachable must not block
                // search. A cached revocation stays applied (stale-ok, §6.3).
            }
        }
    }

    // ── install / uninstall ────────────────────────────────────────────

    fun install(metadata: PackageMetadata) {
        val d = deps()
        val factory = d.marketplace.pluginFactory.factoryFor(metadata.packageId)
        if (factory == null) {
            _uiState.update {
                it.copy(
                    error = "No local implementation for ${metadata.packageId} — " +
                        "dynamic .mcos code loading is not enabled in this build"
                )
            }
            return
        }
        viewModelScope.launch {
            // The installer invokes the factory only after cryptographic
            // verification; capture the instance so onLoad can run on success
            // (the loader registers commands but does not call onLoad).
            var instance: McosPlugin? = null
            try {
                val result = d.marketplace.installer.installPackage(metadata) { bytes ->
                    factory(bytes).also { instance = it }
                }
                if (result is InstallResult.Installed) {
                    // The install dialog's permissionsPreview WAS the consent
                    // moment — grant the declared permissions so the plugin's
                    // commands clear the Stage-6 hard gate. The kernel's
                    // GrantStore persists this, so rehydrated installs keep
                    // their grants across restarts.
                    activatePlugin(d, instance)
                    _uiState.update {
                        it.copy(
                            installResults = it.installResults + (metadata.packageId to result),
                            installedMetas = it.installedMetas + (metadata.packageId to metadata),
                            registryRevision = it.registryRevision + 1,
                            message = "Installed ${result.packageId} v${result.version} " +
                                "(${result.trustLevel}, ${result.commandsRegistered} command(s))",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            installResults = it.installResults + (metadata.packageId to result),
                            error = "Install failed (${(result as InstallResult.Failed).code}): ${result.reason}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Install error: ${e.message}") }
            }
        }
    }

    fun uninstall(packageId: String) {
        val d = deps()
        // Immediate spinner: the installer's own UNINSTALLING event is not
        // recorded by the progress collector (see attach), so this VM-owned
        // entry is the sole busy marker — removed on completion below.
        _uiState.update {
            it.copy(
                installStates = it.installStates +
                    (packageId to InstallProgress(packageId, InstallState.UNINSTALLING)),
            )
        }
        viewModelScope.launch {
            try {
                val result = d.marketplace.installer.uninstallPackage(packageId)
                _uiState.update {
                    when (result) {
                        UninstallResult.Done -> it.copy(
                            installResults = it.installResults - packageId,
                            installStates = it.installStates - packageId,
                            registryRevision = it.registryRevision + 1,
                            message = "Uninstalled $packageId",
                        )
                        is UninstallResult.Failed -> it.copy(
                            error = "Uninstall failed (${result.code}): ${result.reason}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Uninstall error: ${e.message}") }
            }
        }
    }

    // ── recipes (§8.2/§8.3) ─────────────────────────────────────────────

    /** Search the recipe store using the current base URL + query. */
    fun searchRecipes() {
        val url = _uiState.value.baseUrl.trim().trimEnd('/')
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Set a marketplace index URL first (09-marketplace.md §11.1 compatible)") }
            return
        }
        _uiState.update { it.copy(searching = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val d = deps()
                d.secureStore.put(MARKETPLACE_URL, url)
                val index = MarketplaceIndex(
                    baseUrl = url,
                    transport = d.marketplace.transport,
                    blocklistVerifier = d.marketplace.blocklistVerifier,
                )
                val query = _uiState.value.query.takeIf { it.isNotBlank() }
                val response = index.searchRecipes(query = query)
                _uiState.update {
                    it.copy(
                        recipeResults = response.results,
                        message = "${response.total} recipe(s)" + if (query != null) " for “$query”" else "",
                    )
                }
            } catch (e: MarketplaceIndexException) {
                _uiState.update {
                    it.copy(
                        recipeResults = emptyList(),
                        error = "[${e.code}] ${e.message}" + if (e.retryable) " (retryable)" else "",
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                _uiState.update { it.copy(searching = false) }
            }
        }
    }

    /** Resolve dependencies + placeholder prompts and open the wizard (§8.3). */
    fun prepareRecipe(recipe: RecipeEnvelope) {
        val url = _uiState.value.baseUrl.trim().trimEnd('/')
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Set a marketplace index URL first") }
            return
        }
        viewModelScope.launch {
            try {
                val d = deps()
                val index = MarketplaceIndex(
                    baseUrl = url,
                    transport = d.marketplace.transport,
                    blocklistVerifier = d.marketplace.blocklistVerifier,
                )
                // `memoryLookup` is a plain function but Memory reads are
                // suspending — pre-resolve every `fromMemory` path here so the
                // wizard can prefill suggested values.
                val memoryValues = HashMap<String, String>()
                recipe.placeholders.forEach { ph ->
                    ph.fromMemory?.let { path ->
                        jsonToPlain(d.runtime.memory().get(path))?.let { memoryValues[path] = it }
                    }
                }
                val plan = d.marketplace.recipeInstaller.prepare(
                    recipe = recipe,
                    installedVersion = { pkg -> d.marketplace.installer.installedVersion(pkg) },
                    marketplaceLookup = { pkg -> index.getPackage(pkg) },
                    memoryLookup = { path -> memoryValues[path] },
                )
                _uiState.update { it.copy(recipePlan = ActiveRecipePlan(recipe, plan), error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Recipe prepare error: ${e.message}") }
            }
        }
    }

    fun cancelRecipe() {
        _uiState.update { it.copy(recipePlan = null) }
    }

    /**
     * Validate the wizard bindings and, on success, decode the compiled
     * workflow and register it under the recipe id so it can be triggered by
     * name later (§8.3 — registration into the local workflow DB). Not an
     * install-and-run: activation is a deliberate later step.
     */
    fun submitRecipe(bindings: Map<String, String>) {
        val active = _uiState.value.recipePlan ?: return
        val d = deps()
        when (val outcome = d.marketplace.recipeInstaller.submit(active.recipe, active.plan, bindings)) {
            is RecipeInstallOutcome.NeedsDependencies ->
                _uiState.update {
                    it.copy(error = "Recipe needs plugin(s): " + outcome.missing.joinToString { m -> "${m.pluginId}@${m.range}" })
                }
            is RecipeInstallOutcome.NeedsInput ->
                _uiState.update {
                    it.copy(error = "Fill required field(s): " + outcome.prompts.joinToString { p -> p.label ?: p.key })
                }
            is RecipeInstallOutcome.Installed -> {
                val step = WorkflowJson.fromJson(outcome.recipe.workflow)
                if (step == null) {
                    _uiState.update { it.copy(error = "Recipe workflow could not be decoded") }
                } else {
                    d.runtime.workflowStore().register(outcome.recipe.recipeId, step)
                    _uiState.update {
                        it.copy(
                            recipePlan = null,
                            registryRevision = it.registryRevision + 1,
                            message = "Installed recipe “${outcome.recipe.name}” " +
                                "(workflow '${outcome.recipe.recipeId}')",
                        )
                    }
                }
            }
        }
    }

    // ── updates with permission-diff consent (§7.2) ─────────────────────

    /** Request an update to [newMeta]; may surface a consent gate first. */
    fun requestUpdate(newMeta: PackageMetadata) {
        val old = _uiState.value.installedMetas[newMeta.packageId]
        if (old == null) {
            _uiState.update {
                it.copy(error = "No in-session install of ${newMeta.packageId} to update from")
            }
            return
        }
        runUpdate(old, newMeta, consentGiven = false)
    }

    /** Proceed with the pending update after the user accepted the diff. */
    fun confirmUpdate() {
        val pending = _uiState.value.pendingUpdate ?: return
        _uiState.update { it.copy(pendingUpdate = null) }
        runUpdate(pending.oldMeta, pending.newMeta, consentGiven = true)
    }

    fun cancelUpdate() {
        _uiState.update { it.copy(pendingUpdate = null) }
    }

    private fun runUpdate(old: PackageMetadata, new: PackageMetadata, consentGiven: Boolean) {
        val d = deps()
        val factory = d.marketplace.pluginFactory.factoryFor(new.packageId)
        if (factory == null) {
            _uiState.update {
                it.copy(
                    error = "No local implementation for ${new.packageId} — " +
                        "dynamic .mcos code loading is not enabled in this build"
                )
            }
            return
        }
        viewModelScope.launch {
            var instance: McosPlugin? = null
            try {
                val result = d.marketplace.installer.updatePackage(old, new, consentGiven) { bytes ->
                    factory(bytes).also { instance = it }
                }
                when (result) {
                    is UpdateResult.NeedsConsent ->
                        _uiState.update { it.copy(pendingUpdate = PendingUpdate(old, new, result.diff)) }
                    is UpdateResult.Installed -> {
                        activatePlugin(d, instance)
                        _uiState.update {
                            it.copy(
                                installResults = it.installResults + (
                                    new.packageId to InstallResult.Installed(
                                        packageId = result.packageId,
                                        version = result.version,
                                        trustLevel = result.trustLevel,
                                        commandsRegistered = result.commandsRegistered,
                                        aliasesRegistered = result.aliasesRegistered,
                                    )
                                ),
                                installedMetas = it.installedMetas + (new.packageId to new),
                                registryRevision = it.registryRevision + 1,
                                message = "Updated ${result.packageId} to v${result.version}",
                            )
                        }
                    }
                    is UpdateResult.Failed ->
                        _uiState.update { it.copy(error = "Update failed (${result.code}): ${result.reason}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Update error: ${e.message}") }
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Run onLoad and grant the plugin's declared permissions. The loader
     * registers commands but does not call onLoad, and the consent moment
     * (install dialog / update diff) already happened — so grant here so the
     * commands clear the Stage-6 hard gate.
     */
    private suspend fun activatePlugin(d: AppDeps, instance: McosPlugin?) {
        instance?.onLoad(d.hostServices)
        instance?.let { PluginPermissionBootstrap.grantAll(d.permissionKernel, it) }
    }

    private fun jsonToPlain(element: JsonElement?): String? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> element.contentOrNull ?: element.toString()
        else -> element.toString()
    }
}
