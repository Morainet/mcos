package com.mcos.android

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcos.android.host.ActivityResultBridge
import com.mcos.android.host.AndroidHostServices
import com.mcos.android.host.AndroidLlmHttpTransport
import com.mcos.android.host.AndroidSecureStore
import com.mcos.plugin.camera.CameraPlugin
import com.mcos.plugin.files.FilesPlugin
import com.mcos.plugin.hello.HelloPlugin
import com.mcos.plugin.system.SystemPlugin
import com.mcos.runtime.api.*
import com.mcos.runtime.executor.Executor
import com.mcos.runtime.llm.ChatOrchestrator
import com.mcos.runtime.llm.LlmConfig
import com.mcos.runtime.llm.LlmPlanner
import com.mcos.runtime.llm.LlmProviderRegistry
import com.mcos.runtime.llm.OpenAiLlmProvider
import com.mcos.runtime.llm.PromptInjectionDetector
import com.mcos.runtime.llm.ProviderHealth
import com.mcos.runtime.permission.PermissionKernel
import com.mcos.runtime.plugin.LoadResult
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.runtime.security.AuthStampSigner
import com.mcos.runtime.security.EnterprisePolicy
import com.mcos.runtime.security.EnterprisePolicySource
import com.mcos.runtime.security.NetworkEgressPolicy
import com.mcos.runtime.security.RateLimiter
import com.mcos.sdk.HostServices
import com.mcos.sdk.McosPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/** SharedPreferences key holding the LLM API key (managed via [AndroidSecureStore]). */
private const val LLM_API_KEY = "llm_api_key"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val resultBridge = ActivityResultBridge()
        val hostServices = AndroidHostServices(this, resultBridge)
        val secureStore = AndroidSecureStore(this)
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
                    permissionKernel = PermissionKernel(),
                    rateLimiter = RateLimiter(),
                    egressPolicy = NetworkEgressPolicy(),
                    authStampSigner = AuthStampSigner(),
                )
            )
            .build()

        val plugins = listOf(HelloPlugin(), SystemPlugin(), CameraPlugin(), FilesPlugin())

        setContent {
            MCOSApp(runtime, hostServices, plugins, resultBridge, secureStore)
        }
    }
}

// ── Compose UI ──────────────────────────────────────────────────────────────

@SuppressLint("SimpleDateFormat")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCOSApp(
    runtime: McosRuntime,
    hostServices: HostServices,
    plugins: List<McosPlugin>,
    resultBridge: ActivityResultBridge,
    secureStore: AndroidSecureStore,
) {
    val scope = rememberCoroutineScope()

    // ── activity result bridge ─────────────────────────────────────────
    val resultLauncher = rememberLauncherForActivityResult(resultBridge.contract) { result ->
        resultBridge.onResult(result)
    }
    LaunchedEffect(resultLauncher) { resultBridge.attach(resultLauncher) }

    // Request runtime permissions so sys.notify and photo.search/compress work:
    //  - POST_NOTIFICATIONS on API 33+ (notification posting)
    //  - READ_MEDIA_IMAGES on API 33+ / READ_EXTERNAL_STORAGE below (media store)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    // ── state ──────────────────────────────────────────────────────────
    var dslText by remember { mutableStateOf("hello.greet(name=\"MCOS\")\ncamera.capture()") }
    val events = remember { mutableStateListOf<String>() }
    var isExecuting by remember { mutableStateOf(false) }
    var pluginsLoaded by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var lastArtifacts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // Pending runtime confirmation (08-security.md §5) — drives the dialog below.
    var pendingConfirmation by remember { mutableStateOf<RuntimeEvent.ConfirmationNeeded?>(null) }

    // ── LLM chat state ─────────────────────────────────────────────────
    var nlText by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    // ── LLM provider health (06 §17 V1 probing) ────────────────────────
    val llmRegistry = remember { LlmProviderRegistry() }
    var providerHealth by remember { mutableStateOf<List<ProviderHealth>>(emptyList()) }
    var probing by remember { mutableStateOf(false) }

    /** (Re)register a provider for the current key and run a fresh probe. */
    suspend fun refreshProbe() {
        if (apiKey.isBlank()) {
            providerHealth = emptyList()
            return
        }
        probing = true
        try {
            llmRegistry.unregister("openai")
            llmRegistry.register(
                OpenAiLlmProvider(
                    config = LlmConfig(apiKey = apiKey.trim()),
                    transport = AndroidLlmHttpTransport(),
                )
            )
            providerHealth = llmRegistry.probeAll()
        } finally {
            probing = false
        }
    }

    // Debounce: probe once the user stops typing for 500ms.
    LaunchedEffect(apiKey) {
        if (apiKey.isBlank()) {
            providerHealth = emptyList()
            return@LaunchedEffect
        }
        delay(500)
        refreshProbe()
    }

    // Load the persisted API key once on startup.
    LaunchedEffect(Unit) {
        apiKey = secureStore.get(LLM_API_KEY) ?: ""
    }

    val registry = runtime.registry()
    val commandIds = remember { registry.allCommands().map { it.id } }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    fun now() = timeFormat.format(Date())

    // ── plugin loading (idempotent) ────────────────────────────────────
    // Built-in plugins are loaded through the runtime's install pipeline
    // (09-marketplace.md §7.0): PluginTrustGate → CommandRegistry. They are
    // marked `builtin = true` so they always register as BUILTIN; sideloaded
    // packages arriving without a valid signature are denied by the trust
    // gate (which consults the enterprise `disableSideload` policy).
    suspend fun loadPlugins() {
        if (pluginsLoaded) return
        for (plugin in plugins) {
            events.add("[${now()}] Loading ${plugin.manifest.name} v${plugin.manifest.version}")
            try {
                val result = runtime.loadPlugin(
                    packageId = plugin.manifest.id,
                    version = plugin.manifest.version,
                    builtin = true,
                    plugin = plugin,
                )
                when (result) {
                    is LoadResult.Installed -> {
                        plugin.onLoad(hostServices)
                        events.add("[${now()}]   └─ OK (${plugin.handlers().size} handlers, ${result.trustLevel})")
                    }
                    is LoadResult.Denied ->
                        events.add("[WARN]   └─ denied: ${result.code} — ${result.reason}")
                    is LoadResult.Failed ->
                        events.add("[WARN]   └─ failed: ${result.message}")
                }
            } catch (e: Exception) {
                events.add("[WARN]   └─ ${e.message}")
            }
        }
        pluginsLoaded = true
    }

    // ── execute ────────────────────────────────────────────────────────
    fun run() {
        if (isExecuting || dslText.isBlank()) return
        isExecuting = true
        events.clear()

        scope.launch {
            try {
                // 1. Preview
                events.add("[${now()}] Preview…")
                val preview = runtime.preview(
                    ExecuteRequest(source = Source.CHAT, payload = Payload.DslText(dslText))
                )
                events.add(
                    "[${now()}] ${preview.commandCount} command(s): ${
                        preview.commands.joinToString(", ") { it.id }
                    }"
                )
                preview.warnings.forEach { events.add("[WARN] $it") }

                // 2. Load plugins (once)
                loadPlugins()

                // 3. Execute
                events.add("[${now()}] Executing…")
                val handle = runtime.execute(
                    ExecuteRequest(source = Source.CHAT, payload = Payload.DslText(dslText))
                )

                // 4. Collect events
                runtime.observe(handle.runId).collect { event ->
                    events.add(event.toLogLine(now()))
                    when (event) {
                        is RuntimeEvent.ArtifactEmitted ->
                            lastArtifacts = lastArtifacts + (event.type to event.uri)
                        is RuntimeEvent.ConfirmationNeeded ->
                            pendingConfirmation = event
                        else -> { /* ignore */ }
                    }
                }

                // E2E chain: prefill the next step from produced image artifacts.
                val imageUris = lastArtifacts.filter { it.first == "image" }.map { it.second }
                if (imageUris.isNotEmpty()) {
                    when {
                        dslText.contains("camera.capture") -> {
                            val uriList = imageUris.joinToString(", ") { "\"$it\"" }
                            dslText = "photo.compress(uris=[$uriList], quality=80)"
                            events.add("[${now()}] → 已生成压缩命令，再次执行即可")
                        }
                        dslText.contains("photo.compress") -> {
                            dslText = "sys.notify(title=\"MCOS\", text=\"Compressed ${imageUris.size} image(s)\")"
                            events.add("[${now()}] → 已生成通知命令，再次执行即可")
                        }
                    }
                }
            } catch (e: Exception) {
                events.add("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                isExecuting = false
            }
        }
    }

    // ── LLM chat (NL → plan → execute) ────────────────────────────────
    fun chat() {
        if (isExecuting || nlText.isBlank()) return
        isExecuting = true
        events.clear()

        val key = apiKey.trim()
        if (key.isBlank()) {
            events.add("[WARN] Set an LLM API key in the AI Chat card first.")
            isExecuting = false
            return
        }

        scope.launch {
            try {
                // Persist the key so it survives restarts.
                secureStore.put(LLM_API_KEY, key)
                loadPlugins()

                events.add("[${now()}] Planning “${nlText.take(60)}”…")
                val orchestrator = ChatOrchestrator(
                    planner = LlmPlanner(
                        provider = OpenAiLlmProvider(
                            config = LlmConfig(apiKey = key),
                            transport = AndroidLlmHttpTransport(),
                        ),
                        registry = registry,
                    ),
                    runtime = runtime,
                    injectionDetector = PromptInjectionDetector(),
                )
                val result = orchestrator.chat(nlText)

                // Plan feedback
                if (result.plan.isSuccess) {
                    events.add("[${now()}] Plan: ${result.plan.commands.size} command(s)")
                    events.add(result.plan.rawDsl)
                    if (result.plan.rawDsl.isNotBlank()) {
                        dslText = result.plan.rawDsl
                        events.add("[${now()}] → DSL pre-filled, press Run to execute manually.")
                    }
                } else {
                    events.add("[ERROR] Planning failed: ${result.plan.error?.message}")
                }

                // Outcome + execution events
                events.add(if (result.success) "[${now()}] ✓ ${result.summary}" else "[WARN] ${result.summary}")
                result.events.forEach { event ->
                    events.add(event.toLogLine(now()))
                    if (event is RuntimeEvent.ArtifactEmitted) {
                        lastArtifacts = lastArtifacts + (event.type to event.uri)
                    }
                }
            } catch (e: Exception) {
                events.add("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                isExecuting = false
            }
        }
    }

    // Live preview as user types
    LaunchedEffect(dslText) {
        if (dslText.isBlank()) {
            previewText = null
            return@LaunchedEffect
        }
        try {
            val p = runtime.preview(
                ExecuteRequest(source = Source.CHAT, payload = Payload.DslText(dslText))
            )
            previewText = when {
                p.commands.isEmpty() && p.warnings.isNotEmpty() ->
                    "\u26A0 ${p.warnings.first()}"
                p.commands.isNotEmpty() ->
                    "\u2713 ${p.commandCount} cmd: ${p.commands.joinToString(", ") { it.id }}"
                else -> null
            }
        } catch (_: Exception) {
            previewText = null
        }
    }

    // ── Material 3 dark theme ───────────────────────────────────────────
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF90CAF9),
            secondary = Color(0xFFA5D6A7),
            tertiary = Color(0xFFFFCC80),
            background = Color(0xFF0D0D0D),
            surface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFF262626),
            onBackground = Color(0xFFE0E0E0),
            onSurface = Color(0xFFE0E0E0),
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MCOS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("  Shell", fontWeight = FontWeight.Normal)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // ── Plugin status bar ──────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Plugins (${commandIds.size} commands)" +
                                if (!pluginsLoaded) " \u2014 not loaded" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (pluginsLoaded) MaterialTheme.colorScheme.primary else Color(0xFFFFCC80),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { showCommands = !showCommands },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            if (showCommands) "HIDE" else "SHOW",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                if (showCommands) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            commandIds.take(24).forEach { id ->
                                Text(
                                    id,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (commandIds.size > 24) {
                                Text(
                                    "\u2026 +${commandIds.size - 24} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ── AI Chat card ───────────────────────────────────────
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "AI Chat",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (apiKey.isBlank()) "no API key" else "key \u2026${apiKey.takeLast(4)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (apiKey.isBlank()) Color(0xFFEF5350) else MaterialTheme.colorScheme.secondary,
                            )
                        }
                        // Provider health row (06 §17 V1 probing)
                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (providerHealth.isEmpty()) {
                                Text(
                                    if (apiKey.isBlank()) "provider: idle \u2014 set an API key" else if (probing) "probing\u2026" else "provider: idle",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            } else {
                                providerHealth.forEach { h ->
                                    val color = if (h.healthy) MaterialTheme.colorScheme.secondary else Color(0xFFEF5350)
                                    Text(
                                        if (h.healthy) "\u25CF" else "\u25CB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "${h.providerId} (${h.tier}) " +
                                            if (h.healthy) "ready" else (h.errorCode ?: "down"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { scope.launch { refreshProbe() } },
                                enabled = !probing && apiKey.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(
                                    if (probing) "PROBING\u2026" else "PROBE",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = nlText,
                            onValueChange = { nlText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp, max = 76.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            placeholder = {
                                Text("Ask in natural language, e.g. take a photo and notify me\u2026")
                            },
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { chat() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { chat() },
                                enabled = !isExecuting && nlText.isNotBlank() && apiKey.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                            ) {
                                if (isExecuting) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp), strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (isExecuting) "Thinking\u2026" else "Chat")
                            }
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                placeholder = { Text("OpenAI API key\u2026") },
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                    cursorColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }

                // ── DSL input card ─────────────────────────────────────
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        OutlinedTextField(
                            value = dslText,
                            onValueChange = { dslText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp, max = 110.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            ),
                            placeholder = {
                                Text("Type DSL commands\u2026", style = MaterialTheme.typography.bodySmall)
                            },
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { run() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )

                        // Live preview line
                        previewText?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (it.startsWith("\u26A0")) Color(0xFFEF5350) else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { run() },
                                enabled = !isExecuting && dslText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                if (isExecuting) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp), strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (isExecuting) "Running\u2026" else "Run")
                            }
                            OutlinedButton(onClick = {
                                dslText = ""
                                events.clear()
                                previewText = null
                            }) { Text("Clear") }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Output header ─────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Output",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { events.clear() },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("CLEAR", style = MaterialTheme.typography.labelSmall) }
                }

                // ── Event log ─────────────────────────────────────────
                Card(
                    Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(events.size) {
                        if (events.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    Box(Modifier.verticalScroll(scrollState).padding(10.dp)) {
                        if (events.isEmpty()) {
                            Column {
                                Text(
                                    "Ready.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Try these examples:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                )
                                Text(
                                    "  hello.greet(name=\"World\")",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                )
                                Text(
                                    "  camera.capture()",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                )
                                Text(
                                    "  sys.notify(title=\"Hi\", body=\"Hello!\")",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                )
                                Text(
                                    "  sys.clipboard.copy(text=\"MCOS rocks\")",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                )
                            }
                        }
                        Column {
                            events.forEachIndexed { i, e ->
                                Text(
                                    e,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp,
                                    color = when {
                                        e.contains("ERROR") || e.startsWith("\u2717") -> Color(0xFFEF5350)
                                        e.contains("WARN") || e.startsWith("\u26A0") -> Color(0xFFFFCC80)
                                        e.contains("\u2514 OK") || e.startsWith("\u2713") -> Color(0xFFA5D6A7)
                                        e.startsWith("\u25B6") || e.startsWith("\u25A0") -> Color(0xFF90CAF9)
                                        e.contains("Done") -> MaterialTheme.colorScheme.tertiary
                                        else -> Color(0xFFB0B0B0)
                                    },
                                )
                                if (i < events.size - 1) Spacer(Modifier.height(1.dp))
                            }
                        }
                    }
                }
            }
        }

        // ── Pending confirmation dialog (08-security.md §5) ─────────────
        // The run is suspended on a ConfirmationNeeded event until the user
        // approves or denies the command.
        pendingConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = {
                    // Dismissing is treated as denying the action.
                    scope.launch {
                        runtime.respondConfirmation(
                            confirmation.runId,
                            confirmation.commandId,
                            ConfirmationDecision.Reject,
                        )
                    }
                    pendingConfirmation = null
                },
                title = { Text("Approve action?") },
                text = {
                    Column {
                        Text(
                            confirmation.commandId,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(confirmation.reason, style = MaterialTheme.typography.bodyMedium)
                        confirmation.sideEffectClass?.let { risk ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Risk level: $risk",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (risk == "destructive") Color(0xFFEF5350) else Color(0xFFFFCC80),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            runtime.respondConfirmation(
                                confirmation.runId,
                                confirmation.commandId,
                                ConfirmationDecision.Approve(),
                            )
                        }
                        pendingConfirmation = null
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch {
                            runtime.respondConfirmation(
                                confirmation.runId,
                                confirmation.commandId,
                                ConfirmationDecision.Reject,
                            )
                        }
                        pendingConfirmation = null
                    }) { Text("Deny") }
                },
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
