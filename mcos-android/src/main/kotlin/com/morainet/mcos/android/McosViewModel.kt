package com.morainet.mcos.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morainet.mcos.android.host.AndroidLlmHttpTransport
import com.morainet.mcos.runtime.api.ConfirmationDecision
import com.morainet.mcos.runtime.api.ExecuteRequest
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.api.Payload
import com.morainet.mcos.runtime.api.RuntimeEvent
import com.morainet.mcos.runtime.api.Source
import com.morainet.mcos.runtime.llm.ChatOrchestrator
import com.morainet.mcos.runtime.llm.LlmConfig
import com.morainet.mcos.runtime.llm.LlmPlanner
import com.morainet.mcos.runtime.llm.LlmProviderRegistry
import com.morainet.mcos.runtime.llm.OpenAiLlmProvider
import com.morainet.mcos.runtime.llm.PromptInjectionDetector
import com.morainet.mcos.runtime.llm.ProviderHealth
import com.morainet.mcos.runtime.plugin.LoadResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SharedPreferences key holding the LLM API key (managed via AndroidSecureStore). */
private const val LLM_API_KEY = "llm_api_key"

private const val DEFAULT_DSL = "hello.world(name=\"MCOS\")\ncamera.capture()"

/**
 * Immutable UI state for the shell screen. All mutation happens in
 * [McosViewModel]; the composables only render.
 */
data class McosUiState(
    val dslText: String = DEFAULT_DSL,
    val nlText: String = "",
    val apiKey: String = "",
    val isExecuting: Boolean = false,
    val pluginsLoaded: Boolean = false,
    val commandIds: List<String> = emptyList(),
    val previewText: String? = null,
    val artifacts: List<Pair<String, String>> = emptyList(),
    /** Pending runtime confirmation (08-security.md §5) — drives the dialog. */
    val pendingConfirmation: RuntimeEvent.ConfirmationNeeded? = null,
    /** LLM provider health (06 §17 V1 probing). */
    val providerHealth: List<ProviderHealth> = emptyList(),
    val probing: Boolean = false,
)

/**
 * Owns the shell's UI state and orchestration logic (DSL run, LLM chat,
 * provider probing, plugin loading, confirmation responses), keeping the
 * composables pure renderers (architecture review #8).
 *
 * The runtime and host services are activity-scoped (see [AppDeps]), so the
 * activity calls [attach] on every [android.app.Activity.onCreate] —
 * including configuration changes, where this view model survives with its
 * state and re-binds to the freshly built runtime. Jobs launched here run in
 * [viewModelScope] on the main dispatcher.
 *
 * Deliberately a plain [ViewModel] (no [android.app.Application]): nothing
 * here uses the application context, and staying constructor-free of Android
 * types keeps the whole class unit-testable on the JVM.
 */
class McosViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(McosUiState())
    val uiState: StateFlow<McosUiState> = _uiState.asStateFlow()

    /** Console log lines; separate flow so appends don't copy the whole state. */
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private var deps: AppDeps? = null
    private var persistedKeyLoaded = false

    private val llmRegistry = LlmProviderRegistry()
    private var probeDebounce: Job? = null
    private var previewJob: Job? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun now() = timeFormat.format(Date())

    /** Bind the activity-scoped dependencies; call from every onCreate. */
    fun attach(deps: AppDeps) {
        this.deps = deps
        // Load the persisted API key once; onApiKeyChange's debounce then
        // probes with it, matching the previous startup behavior.
        if (!persistedKeyLoaded) {
            persistedKeyLoaded = true
            viewModelScope.launch {
                deps.secureStore.get(LLM_API_KEY)?.let { onApiKeyChange(it) }
            }
        }
    }

    private fun deps(): AppDeps =
        checkNotNull(deps) { "attach(deps) must be called before using the view model" }

    private fun runtime(): McosRuntime = deps().runtime

    private fun log(line: String) {
        _events.value = _events.value + line
    }

    // ── input handlers ─────────────────────────────────────────────────

    fun onDslTextChange(value: String) {
        _uiState.update { it.copy(dslText = value) }
        // Live preview as the user types; a newer keystroke cancels the
        // previous preview job (same cancel semantics the LaunchedEffect had).
        previewJob?.cancel()
        if (value.isBlank()) {
            _uiState.update { it.copy(previewText = null) }
            return
        }
        previewJob = viewModelScope.launch {
            try {
                val p = runtime().preview(
                    ExecuteRequest(source = Source.CHAT, payload = Payload.DslText(value))
                )
                _uiState.update {
                    it.copy(
                        previewText = when {
                            p.commands.isEmpty() && p.warnings.isNotEmpty() ->
                                "\u26A0 ${p.warnings.first()}"
                            p.commands.isNotEmpty() ->
                                "\u2713 ${p.commandCount} cmd: ${p.commands.joinToString(", ") { c -> c.id }}"
                            else -> null
                        }
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(previewText = null) }
            }
        }
    }

    fun onNlTextChange(value: String) {
        _uiState.update { it.copy(nlText = value) }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value) }
        // Debounce: probe once the user stops typing for 500ms.
        probeDebounce?.cancel()
        if (value.isBlank()) {
            _uiState.update { it.copy(providerHealth = emptyList()) }
            return
        }
        probeDebounce = viewModelScope.launch {
            delay(500)
            refreshProbe()
        }
    }

    /** (Re)register a provider for the current key and run a fresh probe. */
    fun refreshProbe() {
        val apiKey = _uiState.value.apiKey
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(providerHealth = emptyList()) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(probing = true) }
            try {
                llmRegistry.unregister("openai")
                llmRegistry.register(
                    OpenAiLlmProvider(
                        config = LlmConfig(apiKey = apiKey.trim()),
                        transport = AndroidLlmHttpTransport(),
                    )
                )
                _uiState.update { it.copy(providerHealth = llmRegistry.probeAll()) }
            } finally {
                _uiState.update { it.copy(probing = false) }
            }
        }
    }

    fun clearInput() {
        previewJob?.cancel()
        _uiState.update { it.copy(dslText = "", previewText = null) }
        _events.value = emptyList()
    }

    fun clearLog() {
        _events.value = emptyList()
    }

    // ── plugin loading (idempotent) ─────────────────────────────────────
    // Built-in plugins are loaded through the runtime's install pipeline
    // (09-marketplace.md §7.0): PluginTrustGate → CommandRegistry. They are
    // marked `builtin = true` so they always register as BUILTIN; sideloaded
    // packages arriving without a valid signature are denied by the trust
    // gate (which consults the enterprise `disableSideload` policy).
    private suspend fun loadPlugins() {
        if (_uiState.value.pluginsLoaded) return
        val d = deps()
        for (plugin in d.plugins) {
            log("[${now()}] Loading ${plugin.manifest.name} v${plugin.manifest.version}")
            try {
                val result = runtime().loadPlugin(
                    packageId = plugin.manifest.id,
                    version = plugin.manifest.version,
                    builtin = true,
                    plugin = plugin,
                )
                when (result) {
                    is LoadResult.Installed -> {
                        plugin.onLoad(d.hostServices)
                        log("[${now()}]   └─ OK (${plugin.handlers().size} handlers, ${result.trustLevel})")
                    }
                    is LoadResult.Denied ->
                        log("[WARN]   └─ denied: ${result.code} — ${result.reason}")
                    is LoadResult.Failed ->
                        log("[WARN]   └─ failed: ${result.message}")
                }
            } catch (e: Exception) {
                log("[WARN]   └─ ${e.message}")
            }
        }
        // The command list is empty until plugins load; refresh it now.
        _uiState.update {
            it.copy(
                pluginsLoaded = true,
                commandIds = d.registry.allCommands().map { entry -> entry.id },
            )
        }
    }

    // ── DSL execution ───────────────────────────────────────────────────

    fun run() {
        val s = _uiState.value
        if (s.isExecuting || s.dslText.isBlank()) return
        _uiState.update { it.copy(isExecuting = true) }
        _events.value = emptyList()

        viewModelScope.launch {
            try {
                // 1. Preview
                log("[${now()}] Preview…")
                val preview = runtime().preview(
                    ExecuteRequest(source = Source.CHAT, payload = Payload.DslText(s.dslText))
                )
                log(
                    "[${now()}] ${preview.commandCount} command(s): ${
                        preview.commands.joinToString(", ") { it.id }
                    }"
                )
                preview.warnings.forEach { log("[WARN] $it") }

                // 2. Load plugins (once)
                loadPlugins()

                // 3. Execute
                log("[${now()}] Executing…")
                val handle = runtime().execute(
                    ExecuteRequest(source = Source.CHAT, payload = Payload.DslText(_uiState.value.dslText))
                )

                // 4. Collect events (the flow completes at the terminal event)
                runtime().observe(handle.runId).collect { event ->
                    log(event.toLogLine(now()))
                    when (event) {
                        is RuntimeEvent.ArtifactEmitted ->
                            _uiState.update { it.copy(artifacts = it.artifacts + (event.type to event.uri)) }
                        is RuntimeEvent.ConfirmationNeeded ->
                            _uiState.update { it.copy(pendingConfirmation = event) }
                        else -> { /* ignore */ }
                    }
                }

                // E2E chain: prefill the next step from produced image artifacts.
                val imageUris = _uiState.value.artifacts.filter { it.first == "image" }.map { it.second }
                if (imageUris.isNotEmpty()) {
                    val dsl = _uiState.value.dslText
                    when {
                        dsl.contains("camera.capture") -> {
                            val uriList = imageUris.joinToString(", ") { "\"$it\"" }
                            onDslTextChange("photo.compress(uris=[$uriList], quality=80)")
                            log("[${now()}] → 已生成压缩命令，再次执行即可")
                        }
                        dsl.contains("photo.compress") -> {
                            onDslTextChange(
                                "sys.notify(title=\"MCOS\", text=\"Compressed ${imageUris.size} image(s)\")"
                            )
                            log("[${now()}] → 已生成通知命令，再次执行即可")
                        }
                    }
                }
            } catch (e: Exception) {
                log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _uiState.update { it.copy(isExecuting = false) }
            }
        }
    }

    // ── LLM chat (NL → plan → execute) ──────────────────────────────────

    fun chat() {
        val s = _uiState.value
        if (s.isExecuting || s.nlText.isBlank()) return
        _uiState.update { it.copy(isExecuting = true) }
        _events.value = emptyList()

        val key = s.apiKey.trim()
        if (key.isBlank()) {
            log("[WARN] Set an LLM API key in the AI Chat card first.")
            _uiState.update { it.copy(isExecuting = false) }
            return
        }

        viewModelScope.launch {
            try {
                // Persist the key so it survives restarts.
                deps().secureStore.put(LLM_API_KEY, key)
                loadPlugins()

                log("[${now()}] Planning “${s.nlText.take(60)}”…")
                val orchestrator = ChatOrchestrator(
                    planner = LlmPlanner(
                        provider = OpenAiLlmProvider(
                            config = LlmConfig(apiKey = key),
                            transport = AndroidLlmHttpTransport(),
                        ),
                        registry = deps().registry,
                    ),
                    runtime = runtime(),
                    injectionDetector = PromptInjectionDetector(),
                )
                val result = orchestrator.chat(s.nlText)

                // Plan feedback
                if (result.plan.isSuccess) {
                    log("[${now()}] Plan: ${result.plan.commands.size} command(s)")
                    log(result.plan.rawDsl)
                    if (result.plan.rawDsl.isNotBlank()) {
                        onDslTextChange(result.plan.rawDsl)
                        log("[${now()}] → DSL pre-filled, press Run to execute manually.")
                    }
                } else {
                    log("[ERROR] Planning failed: ${result.plan.error?.message}")
                }

                // Outcome + execution events
                log(if (result.success) "[${now()}] ✓ ${result.summary}" else "[WARN] ${result.summary}")
                result.events.forEach { event ->
                    log(event.toLogLine(now()))
                    if (event is RuntimeEvent.ArtifactEmitted) {
                        _uiState.update { it.copy(artifacts = it.artifacts + (event.type to event.uri)) }
                    }
                }
            } catch (e: Exception) {
                log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _uiState.update { it.copy(isExecuting = false) }
            }
        }
    }

    // ── confirmation dialog (08-security.md §5) ─────────────────────────

    /**
     * Respond to the pending confirmation. The run is suspended on a
     * ConfirmationNeeded event until the user approves or denies it;
     * dismissing the dialog counts as denying.
     */
    fun respondConfirmation(approved: Boolean) {
        val confirmation = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null) }
        viewModelScope.launch {
            runtime().respondConfirmation(
                confirmation.runId,
                confirmation.commandId,
                if (approved) ConfirmationDecision.Approve() else ConfirmationDecision.Reject,
            )
        }
    }
}

/**
 * Render a [RuntimeEvent] as a single log line for the output console.
 * Shared by the DSL runner and the AI Chat pipeline.
 */
private fun RuntimeEvent.toLogLine(time: String): String = when (this) {
    is RuntimeEvent.RunStarted -> "[$time] ■ Run ${runId.take(8)}… started"
    is RuntimeEvent.StepStarted -> "[$time] ▶ ${commandId}"
    is RuntimeEvent.Progress ->
        "[$time]   ${percent?.let { "$it% " } ?: ""}${message ?: "in progress…"}"
    is RuntimeEvent.ArtifactEmitted ->
        "[$time]   artifact ${type}: ${uri} (${mimeType ?: "?"})"
    is RuntimeEvent.LogEmitted -> "[${level}] ${message}"
    is RuntimeEvent.ConfirmationNeeded -> "[$time] confirm: ${commandId} — ${reason}"
    is RuntimeEvent.StepSucceeded -> "[$time] ✓ ${commandId} (${durationMs}ms)"
    is RuntimeEvent.StepFailed -> "[$time] ✗ ${commandId}: ${error}"
    is RuntimeEvent.RunSucceeded -> "[$time] ■ Done (${durationMs}ms)"
    is RuntimeEvent.RunFailed -> "[ERROR] Run failed: ${error}"
    is RuntimeEvent.RunCancelled -> "[$time] ■ Cancelled"
}
