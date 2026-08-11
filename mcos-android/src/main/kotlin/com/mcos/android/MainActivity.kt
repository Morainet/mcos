package com.mcos.android

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.mcos.android.host.AndroidHostServices
import com.mcos.plugin.camera.CameraPlugin
import com.mcos.plugin.files.FilesPlugin
import com.mcos.plugin.hello.HelloPlugin
import com.mcos.plugin.system.SystemPlugin
import com.mcos.runtime.api.*
import com.mcos.runtime.executor.Executor
import com.mcos.runtime.permission.PermissionKernel
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.runtime.security.AuthStampSigner
import com.mcos.runtime.security.NetworkEgressPolicy
import com.mcos.runtime.security.RateLimiter
import com.mcos.sdk.HostServices
import com.mcos.sdk.McosPlugin
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hostServices = AndroidHostServices(this)
        val registry = CommandRegistry()

        val runtime = McosRuntime.Builder()
            .withRegistry(registry)
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
            MCOSApp(runtime, hostServices, plugins)
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
) {
    val scope = rememberCoroutineScope()

    // ── state ──────────────────────────────────────────────────────────
    var dslText by remember { mutableStateOf("hello.greet(name=\"MCOS\")\ncamera.capture()") }
    val events = remember { mutableStateListOf<String>() }
    var isExecuting by remember { mutableStateOf(false) }
    var pluginsLoaded by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf<String?>(null) }

    val registry = runtime.registry()
    val commandIds = remember { registry.allCommands().map { it.id } }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    fun now() = timeFormat.format(Date())

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
                if (!pluginsLoaded) {
                    for (plugin in plugins) {
                        events.add("[${now()}] Loading ${plugin.manifest.name} v${plugin.manifest.version}")
                        try {
                            registry.register(plugin)
                            plugin.onLoad(hostServices)
                            events.add("[${now()}]   └─ OK (${plugin.handlers().size} handlers)")
                        } catch (e: Exception) {
                            events.add("[WARN]   └─ ${e.message}")
                        }
                    }
                    pluginsLoaded = true
                }

                // 3. Execute
                events.add("[${now()}] Executing…")
                val handle = runtime.execute(
                    ExecuteRequest(source = Source.CHAT, payload = Payload.DslText(dslText))
                )

                // 4. Collect events
                runtime.observe(handle.runId).collect { event ->
                    when (event) {
                        is RuntimeEvent.RunStarted ->
                            events.add("[${now()}] ■ Run ${event.runId.take(8)}… started")

                        is RuntimeEvent.StepStarted ->
                            events.add("[${now()}] ▶ ${event.commandId}")

                        is RuntimeEvent.Progress ->
                            events.add(
                                "[${now()}]   ${event.percent?.let { "$it% " } ?: ""}${event.message ?: "in progress…"}"
                            )

                        is RuntimeEvent.ArtifactEmitted ->
                            events.add(
                                "[${now()}]   artifact ${event.type}: ${event.uri} (${event.mimeType ?: "?"})"
                            )

                        is RuntimeEvent.LogEmitted ->
                            events.add("[${event.level}] ${event.message}")

                        is RuntimeEvent.ConfirmationNeeded ->
                            events.add("[${now()}] confirm: ${event.commandId} — ${event.reason}")

                        is RuntimeEvent.StepSucceeded ->
                            events.add("[${now()}] ✓ ${event.commandId} (${event.durationMs}ms)")

                        is RuntimeEvent.StepFailed ->
                            events.add("[${now()}] ✗ ${event.commandId}: ${event.error}")

                        is RuntimeEvent.RunSucceeded ->
                            events.add("[${now()}] ■ Done (${event.durationMs}ms)")

                        is RuntimeEvent.RunFailed ->
                            events.add("[ERROR] Run failed: ${event.error}")

                        is RuntimeEvent.RunCancelled ->
                            events.add("[${now()}] ■ Cancelled")
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
    }
}
