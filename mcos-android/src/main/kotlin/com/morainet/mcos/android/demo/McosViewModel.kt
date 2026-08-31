package com.morainet.mcos.android.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.android.BridgedMcpServer
import com.morainet.mcos.android.McpAddResult
import com.morainet.mcos.android.McpEnableResult
import com.morainet.mcos.android.McpRemoveResult
import com.morainet.mcos.android.McpServerBridge
import com.morainet.mcos.android.McpServerController
import com.morainet.mcos.android.McpServerRecord
import com.morainet.mcos.android.SkippedBridgedTool
import com.morainet.mcos.android.host.AndroidLlmHttpTransport
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.llm.AgentBridge
import com.morainet.mcos.llm.AgentTurnResult
import com.morainet.mcos.llm.ChatOrchestrator
import com.morainet.mcos.llm.LlmConfig
import com.morainet.mcos.llm.LlmPlanner
import com.morainet.mcos.llm.LlmProviderRegistry
import com.morainet.mcos.llm.McosAgent
import com.morainet.mcos.llm.OpenAiLlmProvider
import com.morainet.mcos.llm.PromptInjectionDetector
import com.morainet.mcos.llm.ProviderHealth
import com.morainet.mcos.plugin.mcp.McpAdapter
import com.morainet.mcos.plugin.mcp.McpServerConfig
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.ir.IrInvoke
import com.morainet.mcos.runtime.core.plugin.LoadResult
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
 * Cap on retained console log lines. Bounds both memory and the per-append
 * copy cost of the immutable-list StateFlow — an uncapped `list + line`
 * appends in O(n), i.e. O(n²) over a session, and grows without limit.
 */
private const val MAX_LOG_LINES = 1_000

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
    /** Agent 模式开关（06 §11 多轮循环）：发送键走 probe → replan 循环。 */
    val agentMode: Boolean = false,
    /** Agent 循环进行中（探测/重规划），驱动进度指示。 */
    val agentWorking: Boolean = false,
    /** Agent 计划待审批（PlanReady 预览文本）— 驱动 Agent 审批对话框。 */
    val pendingAgentPlan: String? = null,
    // ── MCP bridge (04 §10 per-server enablement / 10 §6.2) ─────────────
    val mcpServers: List<McpServerUi> = emptyList(),
    val mcpNewId: String = "",
    val mcpNewEndpoint: String = "",
    val mcpNewToken: String = "",
    /** Add-form busy flag (storing a new server). */
    val mcpBusy: Boolean = false,
)

/** UI view of one configured MCP server (04 §10 per-server enablement). */
data class McpServerUi(
    val id: String,
    val endpoint: String,
    val enabled: Boolean,
    val busy: Boolean = false,
    /** Last per-server connect/disable outcome (null = untried). */
    val status: String? = null,
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

    /**
     * Activity-scoped MCP server controller (item 40: the management logic —
     * persistence, secrets, enable/disable lifecycle — lives in the SDK; this
     * shell only maps outcomes to UI). Rebuilt on every [attach], like [deps]:
     * the SecureStore is the single source of truth, so a rebuild re-reads it.
     */
    private var mcpController: McpServerController? = null

    private fun mcp(): McpServerController =
        checkNotNull(mcpController) { "attach(deps) must be called before using the view model" }

    private val llmRegistry = LlmProviderRegistry()
    private var probeDebounce: Job? = null
    private var previewJob: Job? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun now() = timeFormat.format(Date())

    /** Bind the activity-scoped dependencies; call from every onCreate. */
    fun attach(deps: AppDeps) {
        this.deps = deps
        mcpController = McpServerController(
            secureStore = deps.secureStore,
            runtime = deps.runtime,
            registry = deps.registry,
            hostServices = deps.hostServices,
            permissionKernel = deps.permissionKernel,
            bridge = DemoMcpBridge(deps),
        )
        // Load the persisted API key once; onApiKeyChange's debounce then
        // probes with it, matching the previous startup behavior.
        if (!persistedKeyLoaded) {
            persistedKeyLoaded = true
            viewModelScope.launch {
                deps.secureStore.get(LLM_API_KEY)?.decodeToString()?.let { onApiKeyChange(it) }
                restoreMcpServers()
            }
        }
    }

    private fun deps(): AppDeps =
        checkNotNull(deps) { "attach(deps) must be called before using the view model" }

    private fun runtime(): McosRuntime = deps().runtime

    private fun log(line: String) {
        _events.update { prev ->
            val next = prev + line
            if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        }
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

    // ── MCP bridge (04 §10 per-server enablement / 10 §6.2) ─────────────

    fun onMcpNewIdChange(value: String) = _uiState.update { it.copy(mcpNewId = value) }
    fun onMcpNewEndpointChange(value: String) = _uiState.update { it.copy(mcpNewEndpoint = value) }
    fun onMcpNewTokenChange(value: String) = _uiState.update { it.copy(mcpNewToken = value) }

    /** Add a server to the configured list (disabled). Its token, if any, goes to SecureStore. */
    fun addMcpServer() {
        val s = _uiState.value
        val id = s.mcpNewId.trim()
        val endpoint = s.mcpNewEndpoint.trim()
        if (s.mcpBusy || id.isBlank() || endpoint.isBlank()) return
        if (s.mcpServers.any { it.id == id }) {
            log("[WARN] MCP: server '$id' is already configured")
            return
        }
        val token = s.mcpNewToken.trim().ifBlank { null }
        _uiState.update { it.copy(mcpBusy = true) }
        viewModelScope.launch {
            when (mcp().addServer(id, endpoint, token)) {
                is McpAddResult.Added -> {
                    syncMcpServers()
                    _uiState.update {
                        it.copy(
                            mcpNewId = "",
                            mcpNewEndpoint = "",
                            mcpNewToken = "",
                            mcpBusy = false,
                        )
                    }
                    log("[${now()}] MCP: added server '$id' ($endpoint)")
                }
                McpAddResult.Duplicate ->
                    _uiState.update { it.copy(mcpBusy = false) } // raced a second add
                McpAddResult.Invalid ->
                    _uiState.update { it.copy(mcpBusy = false) }
            }
        }
    }

    /** Remove a server: unregister its commands if enabled, drop its secret + record. */
    fun removeMcpServer(id: String) {
        viewModelScope.launch {
            when (val result = mcp().removeServer(id)) {
                is McpRemoveResult.Removed ->
                    log("[${now()}] MCP: removed server '$id' (${result.commandsUnregistered} cmd unregistered)")
                McpRemoveResult.Unknown -> return@launch
            }
            syncMcpServers()
            refreshCommandList()
        }
    }

    /** Toggle a server on (discover + register) or off (unregister its commands). */
    fun setMcpServerEnabled(id: String, enabled: Boolean) {
        val server = _uiState.value.mcpServers.find { it.id == id } ?: return
        if (server.busy) return
        updateServer(id) { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            if (enabled) log("[${now()}] MCP: connecting to '${server.id}' (${server.endpoint})…")
            applyMcpResult(server.id, mcp().setEnabled(id, enabled))
            refreshCommandList()
        }
    }

    /** Map one SDK controller outcome onto the per-server UI status + console log. */
    private fun applyMcpResult(id: String, result: McpEnableResult?) {
        when (result) {
            null -> updateServer(id) { it.copy(busy = false) }
            is McpEnableResult.Enabled -> {
                result.skipped.forEach {
                    log("[WARN]   └─ skipped '${it.toolName}': ${it.unmappedType} (${it.reason})")
                }
                log("[${now()}]   └─ OK (${result.commandsRegistered} cmd, ${result.skipped.size} skipped)")
                updateServer(id) {
                    it.copy(
                        enabled = true,
                        busy = false,
                        status = "on: ${result.commandsRegistered} cmd, ${result.skipped.size} skipped",
                    )
                }
            }
            is McpEnableResult.Denied -> {
                log("[WARN]   └─ denied: ${result.code} — ${result.reason}")
                updateServer(id) { it.copy(enabled = false, busy = false, status = "denied: ${result.code}") }
            }
            is McpEnableResult.Failed -> {
                log("[WARN]   └─ failed: ${result.message}")
                updateServer(id) { it.copy(enabled = false, busy = false, status = "failed") }
            }
            is McpEnableResult.Error -> {
                log("[WARN] MCP: ${result.message}")
                updateServer(id) { it.copy(enabled = false, busy = false, status = "error: ${result.message}") }
            }
            is McpEnableResult.Disabled -> {
                log("[${now()}] MCP: disabled '$id' (${result.commandsUnregistered} cmd unregistered)")
                updateServer(id) { it.copy(enabled = false, busy = false, status = "off") }
            }
        }
    }

    private fun updateServer(id: String, transform: (McpServerUi) -> McpServerUi) {
        _uiState.update { st -> st.copy(mcpServers = st.mcpServers.map { if (it.id == id) transform(it) else it }) }
    }

    /** Re-read the persisted server list into the UI view (SDK controller owns it). */
    private suspend fun syncMcpServers() {
        val records = mcp().servers()
        _uiState.update { st ->
            st.copy(mcpServers = records.map { r -> McpServerUi(r.id, r.endpoint, enabled = r.enabled) })
        }
    }

    /** Restore the configured list, reconnecting the servers the user left enabled. */
    private suspend fun restoreMcpServers() {
        val records = mcp().servers()
        if (records.isEmpty()) return
        syncMcpServers()
        val toReconnect = records.filter { it.enabled }
        if (toReconnect.isEmpty()) return
        // Best-effort reconnect (one bad endpoint must not block the others);
        // failures surface per-server status and leave the record for a retry.
        toReconnect.forEach { r ->
            updateServer(r.id) { it.copy(busy = true) }
            log("[${now()}] MCP: connecting to '${r.id}' (${r.endpoint})…")
        }
        val results = mcp().reconnectEnabled()
        toReconnect.forEach { r -> applyMcpResult(r.id, results[r.id]) }
        refreshCommandList()
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

    /**
     * Re-read the registry command list. Called after marketplace installs or
     * uninstalls mutate the registry at runtime (see [MarketplaceViewModel]),
     * so the command palette reflects newly available commands without a
     * restart — the registry resolves live, there is no cache to invalidate.
     */
    fun refreshCommandList() {
        val d = deps()
        _uiState.update {
            it.copy(commandIds = d.registry.allCommands().map { entry -> entry.id })
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
                deps().secureStore.put(LLM_API_KEY, key.encodeToByteArray())
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

    // ── Agent loop (06-agent.md §11 multi-turn) ────────────────────────

    /**
     * Single demo-shell conversation id. The Agent loop keeps its
     * observation log and pending plan per session; one continuous
     * conversation is the right scope for the shell.
     */
    private val agentSessionId = "main"

    /** Built on first agent turn; rebuilt if the API key changes. */
    private var agentBridge: AgentBridge? = null
    private var agentApiKey: String? = null

    /**
     * Test seam: when set, agent turns/resumes run against this bridge
     * instead of a real [McosAgent] (keeps the JVM unit tests network-free).
     */
    internal var agentBridgeOverride: AgentBridge? = null

    fun onAgentModeChange(enabled: Boolean) {
        _uiState.update { it.copy(agentMode = enabled) }
    }

    /** Start an Agent turn for the current NL input (probe → replan loop). */
    fun agentTurn() {
        val s = _uiState.value
        if (s.isExecuting || s.nlText.isBlank()) return
        _uiState.update { it.copy(isExecuting = true, agentWorking = true) }
        _events.value = emptyList()

        val key = s.apiKey.trim()
        if (key.isBlank()) {
            log("[WARN] Set an LLM API key in the AI Chat card first.")
            _uiState.update { it.copy(isExecuting = false, agentWorking = false) }
            return
        }

        viewModelScope.launch {
            try {
                // Persist the key so it survives restarts.
                deps().secureStore.put(LLM_API_KEY, key.encodeToByteArray())
                loadPlugins()

                val bridge = agentBridgeOverride ?: bridgeFor(key)
                log("[${now()}] Agent turn: “${s.nlText.take(60)}”…")
                bridge.runTurn(agentSessionId, s.nlText).collect { handleAgentResult(it) }
            } catch (e: Exception) {
                log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _uiState.update { it.copy(isExecuting = false, agentWorking = false) }
            }
        }
    }

    /**
     * Resolve the pending [AgentTurnResult.PlanReady] staged by the last
     * turn: approve executes it through the kernel, deny declines.
     */
    fun resumeAgentTurn(approved: Boolean) {
        if (_uiState.value.pendingAgentPlan == null) return
        _uiState.update { it.copy(pendingAgentPlan = null, isExecuting = true, agentWorking = true) }
        val bridge = agentBridgeOverride ?: agentBridge
        if (bridge == null) {
            _uiState.update { it.copy(isExecuting = false, agentWorking = false) }
            return
        }
        viewModelScope.launch {
            try {
                bridge.resume(agentSessionId, approved).collect { handleAgentResult(it) }
            } catch (e: Exception) {
                log("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _uiState.update { it.copy(isExecuting = false, agentWorking = false) }
            }
        }
    }

    /** User cancel always wins (06 §11.2) — abort the active turn. */
    fun cancelAgentTurn() {
        viewModelScope.launch {
            (agentBridgeOverride ?: agentBridge)?.cancel(agentSessionId)
        }
    }

    /** Lazily build (and cache per key) the real [McosAgent]. */
    private fun bridgeFor(key: String): AgentBridge {
        val cached = agentBridge
        if (cached != null && agentApiKey == key) return cached
        val bridge = McosAgent(
            planner = LlmPlanner(
                provider = OpenAiLlmProvider(
                    config = LlmConfig(apiKey = key),
                    transport = AndroidLlmHttpTransport(),
                ),
                registry = deps().registry,
            ),
            runtime = runtime(),
            registry = deps().registry,
            injectionDetector = PromptInjectionDetector(),
            eventBus = deps().eventBus,
        )
        agentBridge = bridge
        agentApiKey = key
        return bridge
    }

    /** Surface one streamed Agent state on the console (and dialogs). */
    private fun handleAgentResult(result: AgentTurnResult) {
        when (result) {
            is AgentTurnResult.Probing -> {
                log("[${now()}] ⌖ probe: ${result.observation.replace("\n", " | ").take(120)}")
                log("[${now()}] ↻ ${result.nextAction}")
            }
            is AgentTurnResult.PlanReady -> {
                val preview = describeIr(result.ir)
                _uiState.update { it.copy(pendingAgentPlan = preview) }
                log(
                    "[${now()}] Plan ready" +
                        (if (result.needsConfirmation) " — needs approval" else "") + ":",
                )
                log(preview)
            }
            is AgentTurnResult.Clarify -> log("[${now()}] ? ${result.question}")
            is AgentTurnResult.Refuse ->
                log("[ERROR] Agent refused (${result.category}): ${result.reason}")
            is AgentTurnResult.Declined -> log("[${now()}] ✗ Declined: ${result.reason}")
            is AgentTurnResult.Done -> log("[${now()}] ✓ ${result.summary}")
        }
    }

    /** Human-readable one-line-per-step preview of a staged plan. */
    private fun describeIr(ir: ExecutionIr): String = when (ir) {
        is ExecutionIr.Invoke -> describeInvoke(ir.invoke)
        is ExecutionIr.Sequence -> ir.sequence.steps.joinToString("\n") { describeInvoke(it) }
        is ExecutionIr.Workflow -> "workflow: ${ir.body}"
    }

    private fun describeInvoke(invoke: IrInvoke): String =
        if (invoke.args.isEmpty()) invoke.id
        else invoke.id + "(" + invoke.args.entries.joinToString(", ") { (k, v) -> "$k=$v" } + ")"

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

/**
 * Demo wiring of the SDK's [McpServerBridge] seam onto plugins:mcos-plugin-mcp
 * (item 40: the SDK module owns the management lifecycle and stays free of any
 * MCP client dependency; the shell supplies the discovery adapter). The token
 * stays a SecureStore *key name* across this seam — the adapter resolves it
 * per call, so the raw token never enters the manifest, IR, or audit trail
 * (04 §11.1 / 10 §6.2).
 */
private class DemoMcpBridge(private val deps: AppDeps) : McpServerBridge {

    override suspend fun discover(record: McpServerRecord, secretKey: String?): BridgedMcpServer {
        val discovery = McpAdapter.discover(
            deps.hostServices.net,
            McpServerConfig(
                id = record.id,
                endpoint = record.endpoint,
                secretKey = secretKey,
            ),
            secretLookup = { key -> deps.hostServices.secureStore.get(key)?.decodeToString() },
        )
        return BridgedMcpServer(
            plugin = discovery.plugin,
            skippedTools = discovery.skipped.map {
                SkippedBridgedTool(it.toolName, it.unmappedType, it.reason)
            },
        )
    }

    /** Mirrors McpAdapter's registry-id convention (legacy records without a persisted pluginId). */
    override fun pluginIdFor(serverId: String): String = McpAdapter.pluginId(serverId)
}
