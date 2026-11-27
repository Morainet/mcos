package com.morainet.mcos.android.demo.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.android.BridgedMcpServer
import com.morainet.mcos.android.BridgedMcpTool
import com.morainet.mcos.android.McpAddResult
import com.morainet.mcos.android.McpEnableResult
import com.morainet.mcos.android.McpRemoveResult
import com.morainet.mcos.android.McpServerBridge
import com.morainet.mcos.android.McpServerController
import com.morainet.mcos.android.McpServerRecord
import com.morainet.mcos.android.SkippedBridgedTool
import com.morainet.mcos.android.demo.device.AdbInspectionPlugin
import com.morainet.mcos.android.demo.records.InspectionTask
import com.morainet.mcos.android.demo.settings.LlmVendor
import com.morainet.mcos.android.demo.settings.LlmVendors
import com.morainet.mcos.android.demo.skills.SkillStore
import com.morainet.mcos.android.host.AndroidLlmHttpTransport
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.RuntimeGateway
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.llm.AgentBridge
import com.morainet.mcos.llm.AgentSessionStore
import com.morainet.mcos.llm.AgentTurnResult
import com.morainet.mcos.llm.ChatOrchestrator
import com.morainet.mcos.llm.LlmConfig
import com.morainet.mcos.llm.LlmPlanner
import com.morainet.mcos.llm.LlmProbePolicy
import com.morainet.mcos.llm.LlmProviderRegistry
import com.morainet.mcos.llm.McosAgent
import com.morainet.mcos.llm.OpenAiLlmProvider
import com.morainet.mcos.llm.PromptInjectionDetector
import com.morainet.mcos.llm.ProviderHealth
import com.morainet.mcos.llm.Skill
import com.morainet.mcos.plugin.mcp.McpAdapter
import com.morainet.mcos.plugin.mcp.McpServerConfig
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.ir.IrInvoke
import com.morainet.mcos.runtime.core.plugin.LoadResult
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Legacy single-key store (pre multi-vendor). Migrated once on [attach] into
 * the OpenAI vendor slot, then removed.
 */
private const val LEGACY_LLM_API_KEY = "llm_api_key"

/** SecureStore key for the currently selected vendor id. */
private const val LLM_SELECTED_VENDOR = "llm_vendor_selected"

/** Per-vendor SecureStore key names (secrets + non-secret prefs alike). */
private fun vendorKeyKey(id: String) = "llm_vendor_${id}_key"
private fun vendorModelKey(id: String) = "llm_vendor_${id}_model"
private fun vendorEndpointKey(id: String) = "llm_vendor_${id}_endpoint"

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
    /** Per-vendor editable settings (key/model/endpoint), one row per preset. */
    val vendors: List<LlmVendorUi> = LlmVendors.all.map { LlmVendorUi.initial(it) },
    /** The vendor whose config chat/agent actually use. */
    val selectedVendorId: String = LlmVendors.DEFAULT_ID,
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
    /** Agent 循环进行中（探测/重规划），驱动进度指示。 */
    val agentWorking: Boolean = false,
    val agentProgress: String? = null,
    val inspectionRecordId: String? = null,
    /** Agent 计划待审批（PlanReady 预览文本）— 驱动 Agent 审批对话框。 */
    val pendingAgentPlan: String? = null,
    /**
     * 上一个 Agent 轮次的终态自包含结论行（[AgentTurnResult.TerminalResult.headline]）。
     * 宿主直接渲染这一行即可显示"这轮干了什么",无需回扫 flat log。
     */
    val lastAgentOutcome: String? = null,
    // ── MCP bridge (04 §10 per-server enablement / 10 §6.2) ─────────────
    val mcpServers: List<McpServerUi> = emptyList(),
    val mcpNewId: String = "",
    val mcpNewEndpoint: String = "",
    val mcpNewToken: String = "",
    /** Add-form busy flag (storing a new server). */
    val mcpBusy: Boolean = false,
    /** `mcp.json` paste buffer for bulk import. */
    val mcpImportText: String = "",
    // ── Skills (Claude-style skill packages, prompt-level augmentation) ──
    val skills: List<SkillUi> = emptyList(),
    /** Skill import paste buffer (SKILL.md or JSON). */
    val skillImportText: String = "",
    /** Typed conversation transcript — the chat page's primary view (presentation only). */
    val transcript: List<ChatMessage> = emptyList(),
    val streamingReply: ChatMessage? = null,
    val tasksReady: Boolean = false,
    val activeTaskId: String = FIRST_TASK_ID,
    val taskHistory: List<TaskHistoryEntry> = emptyList(),
    val taskSearchQuery: String = "",
    val taskSearchMatches: List<TaskHistoryEntry>? = null,
    val taskHistoryError: String? = null,
    val taskResumeAtMs: Long? = null,
) {
    /** The currently selected vendor's editable settings. */
    val selectedVendor: LlmVendorUi
        get() = vendors.firstOrNull { it.vendor.id == selectedVendorId } ?: vendors.first()
}

/** Speaker of one transcript entry — drives bubble styling in the chat view. */
enum class ChatRole { USER, AGENT, SYSTEM }

/** One conversation entry; [id] is monotonic per VM instance (stable lazy keys). */
data class ChatMessage(val id: Long, val role: ChatRole, val text: String)

data class TaskHistoryEntry(val id: String, val title: String, val subtitle: String)

/**
 * Editable UI state for one LLM vendor: the [vendor] preset plus the user's
 * current key/model/endpoint. [endpoint] defaults to the preset endpoint and
 * stays editable (custom vendors start blank).
 */
data class LlmVendorUi(
    val vendor: LlmVendor,
    val apiKey: String = "",
    val model: String = "",
    val endpoint: String = "",
) {
    /** Ready to send: has a model + endpoint, and a key unless the vendor allows none. */
    val usable: Boolean
        get() = model.isNotBlank() && endpoint.isNotBlank() && (vendor.keyOptional || apiKey.isNotBlank())

    companion object {
        fun initial(v: LlmVendor) = LlmVendorUi(
            vendor = v,
            apiKey = "",
            model = v.defaultModel,
            endpoint = v.endpoint,
        )
    }
}

/** UI view of one configured MCP server (04 §10 per-server enablement). */
data class McpServerUi(
    val id: String,
    val endpoint: String,
    val enabled: Boolean,
    val busy: Boolean = false,
    /** Last per-server connect/disable outcome (null = untried). */
    val status: String? = null,
    /** Discovered tools (populated after the first successful connect). */
    val tools: List<McpToolUi> = emptyList(),
)

/** UI view of one MCP tool for per-tool enablement. */
data class McpToolUi(
    val name: String,
    val description: String?,
    val commandId: String,
    val enabled: Boolean,
    /** False for a tool whose schema is unmappable — visible but never registrable. */
    val mapped: Boolean,
)

/** UI view of one imported skill package (Skills tab). */
data class SkillUi(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val enabled: Boolean,
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
    private var taskWorkspace = TaskWorkspace()
    private val taskSnapshots = MutableStateFlow<TaskWorkspace?>(null)

    /**
     * Imported skill packages (prompt-level augmentation). Rebuilt on [attach]
     * like [mcpController]; the SecureStore is the single source of truth so a
     * rebuild re-reads it. The enabled set is folded into every planner/agent
     * the view model constructs.
     */
    private var skillStore: SkillStore? = null

    /** Enabled skills, read once at attach and refreshed after each skill edit. */
    private var cachedSkills: List<Skill> = emptyList()
    private var cachedSkillsVersion: String = ""

    private fun skills(): SkillStore =
        checkNotNull(skillStore) { "attach(deps) must be called before using the view model" }

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
        skillStore = SkillStore(deps.secureStore)
        // Agent sessions persist suspended turns (06 §11.4) so a scheduled
        // resume survives process death; restore them before any turn runs.
        val sessions = agentSessions
            ?: AgentSessionStore(SecureStoreAgentSuspension(deps.secureStore)).also { agentSessions = it }
        // Load persisted vendor settings once (migrating the legacy single
        // key into the OpenAI slot), then probe every configured vendor.
        if (!persistedKeyLoaded) {
            persistedKeyLoaded = true
            viewModelScope.launch {
                restoreVendors(deps.secureStore)
                restoreSkills()
                restoreTaskWorkspace(TaskWorkspaceStore(deps.secureStore), sessions)
                restoreMcpServers()
                scheduleActiveTaskResume()
            }
        }
    }

    private suspend fun restoreTaskWorkspace(store: TaskWorkspaceStore, sessions: AgentSessionStore) {
        try {
            val (saved, suspendedIds) = withContext(Dispatchers.IO) { store.load() to sessions.restore().toSet() }
            val base = saved ?: TaskWorkspace()
            nextMsgId = (base.tasks.flatMap { it.messages }.maxOfOrNull { it.id } ?: -1L) + 1
            taskWorkspace = base.copy(tasks = base.tasks.map { task ->
                val resumeAt = if (task.id in suspendedIds) {
                    task.resumeAtMs ?: if (saved == null && task.id == FIRST_TASK_ID) System.currentTimeMillis() else null
                } else {
                    null
                }
                val interrupted = (task.unfinished || task.resumeAtMs != null) && resumeAt == null
                if (interrupted) {
                    val message = "Task interrupted. Review the last result before running it again."
                    task.copy(
                        messages = (task.messages + ChatMessage(nextMsgId++, ChatRole.SYSTEM, message)).takeLast(MAX_LOG_LINES),
                        outcome = message, resumeAtMs = null, unfinished = false,
                    )
                } else {
                    task.copy(resumeAtMs = resumeAt, unfinished = false)
                }
            })
            viewModelScope.launch {
                taskSnapshots.filterNotNull().collect { snapshot ->
                    try {
                        withContext(Dispatchers.IO) { store.save(snapshot) }
                        _uiState.update { it.copy(taskHistoryError = null) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        _uiState.update { it.copy(taskHistoryError = "Task history could not be saved.") }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Preserve an unreadable snapshot on disk. This launch can still run tasks in memory.
            _uiState.update { it.copy(taskHistoryError = "Saved task history could not be opened.") }
        }
        showTask(taskWorkspace.tasks.first { it.id == taskWorkspace.activeId })
        publishTaskWorkspace()
    }

    /**
     * Rehydrate the per-vendor key/model/endpoint from SecureStore. A legacy
     * `llm_api_key` (single-vendor era) is migrated once into the OpenAI slot
     * and then removed. After loading, every vendor with a key is registered
     * and probed so the settings page shows real health.
     */
    private suspend fun restoreVendors(store: SecureStore) {
        // One-time migration: legacy key → openai vendor (only if not already set).
        val legacy = store.get(LEGACY_LLM_API_KEY)?.decodeToString()
        if (!legacy.isNullOrBlank() && store.get(vendorKeyKey(LlmVendors.DEFAULT_ID)) == null) {
            store.put(vendorKeyKey(LlmVendors.DEFAULT_ID), legacy.encodeToByteArray())
        }
        if (legacy != null) store.remove(LEGACY_LLM_API_KEY)

        val selected = store.get(LLM_SELECTED_VENDOR)?.decodeToString()
            ?.takeIf { LlmVendors.byId(it) != null } ?: LlmVendors.DEFAULT_ID
        val rows = LlmVendors.all.map { v ->
            val key = store.get(vendorKeyKey(v.id))?.decodeToString() ?: ""
            val model = store.get(vendorModelKey(v.id))?.decodeToString()?.ifBlank { null } ?: v.defaultModel
            val endpoint = store.get(vendorEndpointKey(v.id))?.decodeToString()?.ifBlank { null } ?: v.endpoint
            LlmVendorUi(vendor = v, apiKey = key, model = model, endpoint = endpoint)
        }
        _uiState.update { it.copy(vendors = rows, selectedVendorId = selected) }
        if (rows.any { it.apiKey.isNotBlank() }) refreshProbe()
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

    /**
     * Append one typed entry to the conversation transcript (chat-page view).
     * Presentation-only: everything logged via [log] still reaches the Tools
     * console unchanged.
     */
    private fun say(role: ChatRole, text: String) {
        _uiState.update { st ->
            st.copy(transcript = (st.transcript + ChatMessage(nextMsgId++, role, text)).takeLast(MAX_LOG_LINES))
        }
        rememberActiveTask()
    }

    private fun finishReply(text: String) {
        _uiState.update { state ->
            val message = state.streamingReply?.copy(text = text) ?: ChatMessage(nextMsgId++, ChatRole.AGENT, text)
            state.copy(
                transcript = (state.transcript + message).takeLast(MAX_LOG_LINES),
                streamingReply = null,
            )
        }
        rememberActiveTask()
    }

    private var nextMsgId = 0L

    private fun taskCanSwitch(): Boolean = _uiState.value.let {
        it.tasksReady && !it.isExecuting && it.pendingAgentPlan == null && it.pendingConfirmation == null
    }

    fun newTask() {
        if (!taskCanSwitch()) return
        val active = taskWorkspace.tasks.first { it.id == taskWorkspace.activeId }
        if (!active.visibleInHistory) return
        val task = SavedTask(UUID.randomUUID().toString())
        taskWorkspace = taskWorkspace.withTask(task)
        selectTask(task.id)
    }

    fun selectTask(id: String) {
        if (!taskCanSwitch() || id == taskWorkspace.activeId) return
        val task = taskWorkspace.tasks.firstOrNull { it.id == id } ?: return
        agentResumeJob?.cancel()
        taskWorkspace = taskWorkspace.copy(
            activeId = id,
            tasks = taskWorkspace.tasks.filter { it.id == id || it.visibleInHistory },
        )
        showTask(task)
        publishTaskWorkspace()
        scheduleActiveTaskResume()
    }

    fun renameTask(id: String, title: String) {
        val newTitle = title.trim().replace('\n', ' ').replace('\r', ' ')
        if (!taskCanSwitch() || newTitle.isBlank() || newTitle.length > TASK_TITLE_LIMIT) return
        val task = taskWorkspace.tasks.firstOrNull { it.id == id && it.visibleInHistory } ?: return
        if (task.title == newTitle) return
        taskWorkspace = taskWorkspace.copy(tasks = taskWorkspace.tasks.map {
            if (it.id == id) it.copy(title = newTitle) else it
        })
        publishTaskWorkspace()
    }

    fun searchTaskHistory(query: String) {
        _uiState.update {
            it.copy(taskSearchQuery = query, taskSearchMatches = taskSearchResults(it.taskHistory, query))
        }
    }

    private fun showTask(task: SavedTask) {
        _uiState.update {
            it.copy(
                tasksReady = true, activeTaskId = task.id, nlText = task.draft, transcript = task.messages,
                lastAgentOutcome = task.outcome, taskResumeAtMs = task.resumeAtMs, artifacts = emptyList(),
            )
        }
    }

    private fun rememberActiveTask() {
        val state = _uiState.value
        if (!state.tasksReady) return
        val previous = taskWorkspace.tasks.first { it.id == taskWorkspace.activeId }
        val title = previous.title.ifBlank {
            state.transcript.firstOrNull { it.role == ChatRole.USER }?.text?.lineSequence()?.firstOrNull()
                ?.take(TASK_TITLE_LIMIT).orEmpty()
        }
        val current = previous.copy(
            title = title, draft = state.nlText, messages = state.transcript, outcome = state.lastAgentOutcome,
            resumeAtMs = state.taskResumeAtMs, unfinished = state.agentWorking || state.pendingAgentPlan != null,
        )
        if (current == previous) return
        taskWorkspace = taskWorkspace.withTask(current)
        publishTaskWorkspace()
    }

    private fun publishTaskWorkspace() {
        val history = taskWorkspace.tasks.filter { it.visibleInHistory }.map { task ->
            TaskHistoryEntry(
                task.id,
                task.title.ifBlank {
                    task.draft.trim().lineSequence().first().take(TASK_TITLE_LIMIT).ifBlank { "Untitled task" }
                },
                when {
                    task.resumeAtMs != null -> "Waiting to resume"
                    task.unfinished -> "In progress"
                    task.outcome != null -> task.outcome.lineSequence().first()
                    task.messages.isEmpty() -> "Draft"
                    else -> ""
                },
            )
        }
        _uiState.update {
            it.copy(taskHistory = history, taskSearchMatches = taskSearchResults(history, it.taskSearchQuery))
        }
        taskSnapshots.value = taskWorkspace
    }

    private fun taskSearchResults(history: List<TaskHistoryEntry>, query: String): List<TaskHistoryEntry>? {
        val term = query.trim()
        if (term.isEmpty()) return null
        val matchingIds = taskWorkspace.tasks.asSequence()
            .filter { it.visibleInHistory && it.matches(term) }
            .map { it.id }.toSet()
        return history.filter { it.id in matchingIds || it.title.contains(term, ignoreCase = true) }
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
        rememberActiveTask()
    }

    // ── multi-vendor LLM settings (06 §17 V1 probing) ───────────────────

    private fun updateVendor(id: String, transform: (LlmVendorUi) -> LlmVendorUi) {
        _uiState.update { st ->
            st.copy(vendors = st.vendors.map { if (it.vendor.id == id) transform(it) else it })
        }
    }

    /** The [LlmConfig] for a vendor row, or null when it isn't ready to send. */
    private fun LlmVendorUi.toConfigOrNull(): LlmConfig? =
        if (!usable) null
        else LlmConfig(apiKey = apiKey.trim(), model = model.trim(), endpoint = endpoint.trim())

    /**
     * The selected vendor's config, or null when the selection has no key /
     * model / endpoint. Test seam: pure mapping, no side effects.
     */
    internal fun selectedLlmConfig(): LlmConfig? = _uiState.value.selectedVendor.toConfigOrNull()

    fun onVendorKeyChange(id: String, value: String) {
        updateVendor(id) { it.copy(apiKey = value) }
        persistVendorField(vendorKeyKey(id), value)
        scheduleProbe()
    }

    fun onVendorModelChange(id: String, value: String) {
        updateVendor(id) { it.copy(model = value) }
        persistVendorField(vendorModelKey(id), value)
        scheduleProbe()
    }

    fun onVendorEndpointChange(id: String, value: String) {
        updateVendor(id) { it.copy(endpoint = value) }
        persistVendorField(vendorEndpointKey(id), value)
        scheduleProbe()
    }

    /** Choose the vendor chat/agent will use; persists and re-probes. */
    fun selectVendor(id: String) {
        if (LlmVendors.byId(id) == null) return
        _uiState.update { it.copy(selectedVendorId = id) }
        // Selecting a different vendor invalidates the cached agent bridge.
        agentBridge = null
        agentBridgeKey = null
        persistVendorField(LLM_SELECTED_VENDOR, id)
        scheduleProbe()
    }

    private fun persistVendorField(key: String, value: String) {
        viewModelScope.launch {
            val store = deps().secureStore
            if (value.isBlank()) store.remove(key) else store.put(key, value.encodeToByteArray())
        }
    }

    /** Debounce: re-probe once the user stops editing for 500ms. */
    private fun scheduleProbe() {
        probeDebounce?.cancel()
        probeDebounce = viewModelScope.launch {
            delay(500)
            refreshProbe()
        }
    }

    /**
     * (Re)register every vendor that has a usable config under its own id and
     * run a fresh probe; vendors without a key are unregistered so stale
     * health drops off. Health is reported per-vendor (06 §17 V1).
     */
    fun refreshProbe() {
        val vendors = _uiState.value.vendors
        viewModelScope.launch {
            _uiState.update { it.copy(probing = true) }
            try {
                vendors.forEach { row ->
                    val config = row.toConfigOrNull()
                    llmRegistry.unregister(row.vendor.id)
                    if (config != null) {
                        llmRegistry.register(
                            OpenAiLlmProvider(
                                config = config,
                                transport = AndroidLlmHttpTransport(),
                                id = row.vendor.id,
                            )
                        )
                    }
                }
                if (llmRegistry.size == 0) {
                    _uiState.update { it.copy(providerHealth = emptyList()) }
                } else {
                    val health = llmRegistry.probeAll(LlmProbePolicy(probeTimeoutMs = 20_000))
                    _uiState.update { it.copy(providerHealth = health) }
                }
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
            // Pull the freshly-discovered tool catalog (cached on the record) into
            // the UI so the per-tool list appears after a connect.
            syncMcpServers()
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
            st.copy(
                mcpServers = records.map { r ->
                    // Preserve the transient busy flag/status the UI is holding.
                    val prev = st.mcpServers.find { it.id == r.id }
                    McpServerUi(
                        id = r.id,
                        endpoint = r.endpoint,
                        enabled = r.enabled,
                        busy = prev?.busy ?: false,
                        status = prev?.status,
                        tools = r.tools.map { t ->
                            McpToolUi(t.name, t.description, t.commandId, t.enabled, t.mapped)
                        },
                    )
                },
            )
        }
    }

    // ── MCP bulk import + per-tool enablement ───────────────────────────

    fun onMcpImportTextChange(value: String) = _uiState.update { it.copy(mcpImportText = value) }

    /** Import servers from a pasted standard `mcp.json` (`mcpServers` map). */
    fun importMcpJson() {
        val text = _uiState.value.mcpImportText.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            val result = mcp().importJsonConfig(text)
            log(
                "[${now()}] MCP import: ${result.added} added, ${result.duplicates} duplicate, " +
                    "${result.skippedStdio} stdio-skipped, ${result.invalid} invalid",
            )
            if (result.added > 0) {
                _uiState.update { it.copy(mcpImportText = "") }
                syncMcpServers()
            }
        }
    }

    /** Toggle one tool of a server; re-registers live when the server is enabled. */
    fun setMcpToolEnabled(serverId: String, toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            val result = mcp().setToolEnabled(serverId, toolName, enabled) ?: return@launch
            applyMcpResult(serverId, result)
            syncMcpServers()
            refreshCommandList()
        }
    }

    // ── Skills (Claude-style skill packages) ────────────────────────────

    fun onSkillImportTextChange(value: String) = _uiState.update { it.copy(skillImportText = value) }

    /** Import a skill from the paste buffer (SKILL.md or JSON). */
    fun importSkill() {
        val text = _uiState.value.skillImportText.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            val record = skills().import(text)
            if (record == null) {
                log("[WARN] Skill import: could not parse (need name + instructions)")
                return@launch
            }
            log("[${now()}] Skill imported: ${record.skill.name}")
            _uiState.update { it.copy(skillImportText = "") }
            refreshSkills()
        }
    }

    fun removeSkill(id: String) {
        viewModelScope.launch {
            skills().remove(id)
            refreshSkills()
        }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            skills().setEnabled(id, enabled)
            refreshSkills()
        }
    }

    /** Re-read the skill list into the UI and refresh the cached enabled set. */
    private suspend fun refreshSkills() {
        val list = skills().list()
        _uiState.update { st ->
            st.copy(
                skills = list.map {
                    SkillUi(it.skill.id, it.skill.name, it.skill.description, it.skill.instructions, it.enabled)
                },
            )
        }
        cachedSkills = list.filter { it.enabled }.map { it.skill }
        cachedSkillsVersion = skills().enabledVersion()
        // A skill change invalidates any cached agent bridge (prompt changed).
        agentBridge = null
        agentBridgeKey = null
    }

    private suspend fun restoreSkills() = refreshSkills()

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
     * uninstalls mutate the registry at runtime (see [com.morainet.mcos.android.demo.marketplace.MarketplaceViewModel]),
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

        val config = selectedLlmConfig()
        if (config == null) {
            val hint = "Set an LLM API key in Settings first."
            log("[WARN] $hint")
            say(ChatRole.SYSTEM, hint)
            _uiState.update { it.copy(isExecuting = false) }
            return
        }
        say(ChatRole.USER, s.nlText.trim())

        viewModelScope.launch {
            try {
                loadPlugins()

                log("[${now()}] Planning “${s.nlText.take(60)}” via ${s.selectedVendor.vendor.name}…")
                val orchestrator = ChatOrchestrator(
                    planner = LlmPlanner(
                        provider = OpenAiLlmProvider(
                            config = config,
                            transport = AndroidLlmHttpTransport(),
                            id = s.selectedVendorId,
                        ),
                        registry = deps().registry,
                        skills = cachedSkills,
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
                say(
                    ChatRole.AGENT,
                    buildString {
                        if (result.plan.isSuccess && result.plan.rawDsl.isNotBlank()) {
                            append("Plan — ${result.plan.commands.size} command(s)\n")
                            append(result.plan.rawDsl.trim())
                            append("\n\n")
                        }
                        append(if (result.success) "✓ " else "⚠ ")
                        append(result.summary)
                        if (result.plan.isSuccess && result.plan.rawDsl.isNotBlank()) {
                            append("\n(DSL pre-filled — open Tools and press Run to execute)")
                        }
                    },
                )

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
                say(ChatRole.SYSTEM, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _uiState.update { it.copy(isExecuting = false) }
            }
        }
    }

    // ── Agent loop (06-agent.md §11 multi-turn) ────────────────────────

    /** Built on first agent turn; rebuilt if the selected vendor/config changes. */
    private var agentBridge: AgentBridge? = null
    private var agentBridgeKey: String? = null
    private var agentJob: Job? = null
    private var agentRunId: String? = null
    private var agentResumeJob: Job? = null

    /**
     * Shared across every bridge rebuild so a suspended turn's session (06 §11.4)
     * outlives a vendor/config change. Backed by [SecureStoreAgentSuspension] so
     * a suspension survives process death; restored once at attach.
     */
    private var agentSessions: AgentSessionStore? = null

    /**
     * Test seam: when set, agent turns/resumes run against this bridge
     * instead of a real [McosAgent] (keeps the JVM unit tests network-free).
     */
    internal var agentBridgeOverride: AgentBridge? = null

    /** Start an Agent turn for the current NL input (probe → replan loop). */
    fun agentTurn() {
        val s = _uiState.value
        if (!taskCanSwitch() || s.taskResumeAtMs != null || s.nlText.isBlank()) return

        val config = selectedLlmConfig()
        if (config == null && agentBridgeOverride == null) {
            say(ChatRole.SYSTEM, "Connect a model in Settings to run your own tasks.")
            return
        }

        val goal = s.nlText.trim()
        _events.value = emptyList()
        _uiState.update { it.copy(nlText = "", lastAgentOutcome = null) }
        say(ChatRole.USER, goal)
        launchAgentWork("Planning the task") {
            loadPlugins()
            val bridge = agentBridgeOverride ?: bridgeFor(s.selectedVendorId, checkNotNull(config))
            var readOnlyPlan = false
            bridge.runTurn(s.activeTaskId, goal).collect { result ->
                if (result is AgentTurnResult.PlanReady && !result.needsConfirmation) {
                    _uiState.update { it.copy(streamingReply = null) }
                    readOnlyPlan = true
                } else {
                    handleAgentResult(result)
                }
            }
            if (readOnlyPlan) bridge.resume(s.activeTaskId, true).collect { handleAgentResult(it) }
        }
    }

    /**
     * Resolve the pending [AgentTurnResult.PlanReady] staged by the last
     * turn: approve executes it through the kernel, deny declines.
     */
    fun resumeAgentTurn(approved: Boolean) {
        val state = _uiState.value
        if (state.pendingAgentPlan == null || state.isExecuting) return
        val bridge = agentBridgeOverride ?: agentBridge ?: return
        _uiState.update { it.copy(pendingAgentPlan = null) }
        launchAgentWork("Running the approved plan") {
            bridge.resume(state.activeTaskId, approved).collect { handleAgentResult(it) }
        }
    }

    /** A first task users can finish before connecting a model. */
    fun runInspectionTask() {
        val state = _uiState.value
        if (!taskCanSwitch() || state.taskResumeAtMs != null) return
        _uiState.update { it.copy(lastAgentOutcome = null) }
        say(ChatRole.USER, InspectionTask.TITLE)
        launchAgentWork("Looking up completed inspections") {
            loadPlugins()
            val gateway = agentRuntime()
            val adbTool = deps().plugins.filterIsInstance<AdbInspectionPlugin>().firstOrNull()
            if (adbTool?.connected() == true) {
                val result = gateway.executeProbe(listOf(Command(AdbInspectionPlugin.COMMAND, JsonObject(emptyMap()))))
                when (val probe = result.singleOrNull()) {
                    is CommandResult.Ok -> say(ChatRole.AGENT, "Checked the connected phone screen.")
                    is CommandResult.Err -> log("[WARN] ADB observation: ${probe.message}")
                    null -> Unit
                }
            }
            val recordId = InspectionTask.latestCompleted(gateway)
            _uiState.update { it.copy(inspectionRecordId = recordId, agentProgress = "Preparing export") }
            say(ChatRole.AGENT, "Found completed inspection $recordId.")
            val handle = gateway.execute(InspectionTask.request(recordId))
            var artifact: String? = null
            var outcome: AgentTurnResult? = null
            gateway.observe(handle.runId).collect { event ->
                when (event) {
                    is RuntimeEvent.ArtifactEmitted -> artifact = event.uri
                    is RuntimeEvent.RunFailed -> outcome = AgentTurnResult.Refuse("EXPORT_FAILED", event.error)
                    is RuntimeEvent.RunCancelled -> outcome = AgentTurnResult.Declined("Task stopped")
                    is RuntimeEvent.RunSucceeded -> {
                        val verified = InspectionTask.verify(recordId, checkNotNull(artifact))
                        outcome = AgentTurnResult.Done("Export verified\n$verified")
                    }
                    else -> Unit
                }
            }
            handleAgentResult(checkNotNull(outcome) { "The export did not report a result." })
        }
    }

    private fun launchAgentWork(progress: String, work: suspend () -> Unit) {
        _uiState.update { it.copy(isExecuting = true, agentWorking = true, agentProgress = progress, artifacts = emptyList()) }
        rememberActiveTask()
        agentJob = viewModelScope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                handleAgentResult(AgentTurnResult.Declined("Task stopped"))
                throw cancelled
            } catch (failure: Exception) {
                handleAgentResult(AgentTurnResult.Refuse("TASK_FAILED", failure.message ?: "Task failed"))
            } finally {
                agentRunId?.let(runtime()::cancel)
                agentRunId = null
                _uiState.update {
                    it.copy(
                        isExecuting = false, agentWorking = false, agentProgress = null,
                        streamingReply = null,
                        pendingConfirmation = null, inspectionRecordId = null,
                    )
                }
                rememberActiveTask()
            }
        }
    }

    private fun agentRuntime(): RuntimeGateway {
        val kernel = runtime()
        return object : RuntimeGateway by kernel {
            override suspend fun execute(request: ExecuteRequest) =
                kernel.execute(request).also { agentRunId = it.runId }

            override fun observe(runId: String) = kernel.observe(runId).onEach { event ->
                log(event.toLogLine(now()))
                when (event) {
                    is RuntimeEvent.ConfirmationNeeded -> _uiState.update {
                        it.copy(pendingConfirmation = event, agentProgress = "Waiting for your approval")
                    }
                    is RuntimeEvent.ArtifactEmitted -> _uiState.update {
                        it.copy(artifacts = it.artifacts + (event.type to event.uri), agentProgress = "Checking the result")
                    }
                    is RuntimeEvent.RunSucceeded, is RuntimeEvent.RunFailed, is RuntimeEvent.RunCancelled -> {
                        if (agentRunId == runId) agentRunId = null
                        _uiState.update { it.copy(pendingConfirmation = null) }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** User cancel always wins (06 §11.2) — abort the active turn. */
    fun cancelAgentTurn() {
        val runId = agentRunId
        if (runId == null) agentJob?.cancel() else runtime().cancel(runId)
    }

    /** Lazily build (and cache per vendor+config) the real [McosAgent]. */
    private fun bridgeFor(vendorId: String, config: LlmConfig): AgentBridge {
        val cacheKey = "$vendorId|${config.apiKey}|${config.model}|${config.endpoint}|$cachedSkillsVersion"
        val cached = agentBridge
        if (cached != null && agentBridgeKey == cacheKey) return cached
        val bridge = McosAgent(
            planner = LlmPlanner(
                provider = OpenAiLlmProvider(
                    config = config,
                    transport = AndroidLlmHttpTransport(),
                    id = vendorId,
                ),
                registry = deps().registry,
                skills = cachedSkills,
                allowConversationalReplies = true,
            ),
            runtime = agentRuntime(),
            registry = deps().registry,
            injectionDetector = PromptInjectionDetector(),
            eventBus = deps().eventBus,
            eventTimeoutMs = 90_000L,
            sessions = agentSessions
                ?: AgentSessionStore(SecureStoreAgentSuspension(deps().secureStore))
                    .also { agentSessions = it },
        )
        agentBridge = bridge
        agentBridgeKey = cacheKey
        return bridge
    }

    /** Surface one streamed Agent state on the console (and dialogs). */
    private fun handleAgentResult(result: AgentTurnResult) {
        if (result is AgentTurnResult.AnswerDelta) {
            _uiState.update { state ->
                val reply = state.streamingReply ?: ChatMessage(nextMsgId++, ChatRole.AGENT, "")
                state.copy(streamingReply = reply.copy(text = reply.text + result.text), agentProgress = "Generating reply")
            }
            return
        }
        // Non-terminal progress: log the detail, don't touch the outcome line.
        if (result is AgentTurnResult.Probing) {
            _uiState.update { it.copy(agentProgress = "Checking device capabilities", streamingReply = null) }
            say(ChatRole.AGENT, result.observation)
            log("[${now()}] ⌖ probe: ${result.observation.replace("\n", " | ").take(120)}")
            log("[${now()}] ↻ ${result.nextAction}")
            return
        }
        // Terminal states carry a self-contained headline: record it verbatim
        // as the turn outcome, then log any state-specific detail below it.
        if (result is AgentTurnResult.TerminalResult) {
            val partial = _uiState.value.streamingReply
            if (partial != null && result !is AgentTurnResult.Answer) {
                if (result is AgentTurnResult.Declined || result is AgentTurnResult.Refuse) {
                    finishReply(partial.text)
                } else {
                    _uiState.update { it.copy(streamingReply = null) }
                }
            }
            _uiState.update {
                it.copy(
                    lastAgentOutcome = result.headline,
                    taskResumeAtMs = (result as? AgentTurnResult.Suspended)?.resumeAtEpochMs,
                )
            }
        }
        when (result) {
            is AgentTurnResult.Probing, is AgentTurnResult.AnswerDelta -> Unit // handled above
            is AgentTurnResult.PlanReady -> {
                val preview = describeIr(result.ir)
                _uiState.update { it.copy(pendingAgentPlan = preview) }
                log("[${now()}] ${result.headline}")
                log(preview)
                say(ChatRole.AGENT, "Staged plan — approve to execute:\n$preview")
            }
            is AgentTurnResult.Clarify -> {
                log("[${now()}] ? ${result.headline}")
                say(ChatRole.AGENT, result.headline)
            }
            is AgentTurnResult.Answer -> finishReply(result.text)
            is AgentTurnResult.Refuse -> {
                log("[ERROR] ${result.headline}")
                say(ChatRole.SYSTEM, result.headline)
            }
            is AgentTurnResult.Declined -> {
                log("[${now()}] ✗ ${result.headline}")
                say(ChatRole.SYSTEM, "✗ ${result.headline}")
            }
            is AgentTurnResult.Done -> {
                log("[${now()}] ✓ ${result.headline}")
                say(ChatRole.AGENT, "✓ ${result.headline}")
            }
            is AgentTurnResult.Suspended -> {
                log("[${now()}] ⏸ ${result.headline}")
                say(ChatRole.SYSTEM, "⏸ ${result.headline}")
                scheduleActiveTaskResume()
            }
        }
    }

    /** A suspended task can resume in its own workspace after it is selected and due. */
    private fun scheduleActiveTaskResume() {
        agentResumeJob?.cancel()
        val state = _uiState.value
        val resumeAtEpochMs = state.taskResumeAtMs ?: return
        agentResumeJob = viewModelScope.launch {
            val waitMs = (resumeAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (waitMs > 0) delay(waitMs)
            uiState.first {
                it.activeTaskId != state.activeTaskId ||
                    (!it.isExecuting && it.pendingAgentPlan == null && it.pendingConfirmation == null)
            }
            if (uiState.value.activeTaskId != state.activeTaskId || uiState.value.taskResumeAtMs != resumeAtEpochMs) {
                return@launch
            }
            val bridge = agentBridgeOverride ?: agentBridge ?: selectedLlmConfig()?.let {
                bridgeFor(uiState.value.selectedVendorId, it)
            } ?: return@launch
            launchAgentWork("Resuming the task") {
                log("[${now()}] ↻ resuming suspended turn…")
                bridge.resumeSuspended(state.activeTaskId).collect { handleAgentResult(it) }
            }
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
        _uiState.update {
            val progress = if (it.agentWorking) "Finishing the action" else it.agentProgress
            it.copy(pendingConfirmation = null, agentProgress = progress)
        }
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

    override suspend fun discover(
        record: McpServerRecord,
        secretKey: String?,
        enabledTools: Set<String>?,
    ): BridgedMcpServer {
        val discovery = McpAdapter.discover(
            deps.hostServices.net,
            McpServerConfig(
                id = record.id,
                endpoint = record.endpoint,
                secretKey = secretKey,
            ),
            secretLookup = { key -> deps.hostServices.secureStore.get(key)?.decodeToString() },
            enabledTools = enabledTools,
        )
        return BridgedMcpServer(
            plugin = discovery.plugin,
            skippedTools = discovery.skipped.map {
                SkippedBridgedTool(it.toolName, it.unmappedType, it.reason)
            },
            tools = discovery.tools.map {
                BridgedMcpTool(it.name, it.description, it.commandId, it.mapped)
            },
        )
    }

    /** Mirrors McpAdapter's registry-id convention (legacy records without a persisted pluginId). */
    override fun pluginIdFor(serverId: String): String = McpAdapter.pluginId(serverId)
}
