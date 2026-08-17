package com.morainet.mcos.android

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Shell screen: renders [McosViewModel] state and forwards user actions to
 * it. Owns no business state of its own — the only `remember` here is the
 * activity-result bridge wiring, which must live in the composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCOSApp(deps: AppDeps) {
    val vm: McosViewModel = viewModel()
    // The runtime is activity-scoped; re-bind on every (re)composition root.
    LaunchedEffect(deps) { vm.attach(deps) }
    val ui by vm.uiState.collectAsState()
    val events by vm.events.collectAsState()

    // Pure view-local visibility toggle for the command list card.
    var showCommands by remember { mutableStateOf(false) }

    // ── activity result bridge ─────────────────────────────────────────
    val resultLauncher = rememberLauncherForActivityResult(deps.resultBridge.contract) { result ->
        deps.resultBridge.onResult(result)
    }
    LaunchedEffect(resultLauncher) { deps.resultBridge.attach(resultLauncher) }

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
                        "Plugins (${ui.commandIds.size} commands)" +
                                if (!ui.pluginsLoaded) " \u2014 not loaded" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ui.pluginsLoaded) MaterialTheme.colorScheme.primary else Color(0xFFFFCC80),
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
                            ui.commandIds.take(24).forEach { id ->
                                Text(
                                    id,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (ui.commandIds.size > 24) {
                                Text(
                                    "\u2026 +${ui.commandIds.size - 24} more",
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
                                if (ui.apiKey.isBlank()) "no API key" else "key \u2026${ui.apiKey.takeLast(4)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ui.apiKey.isBlank()) Color(0xFFEF5350) else MaterialTheme.colorScheme.secondary,
                            )
                        }
                        // Provider health row (06 §17 V1 probing)
                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (ui.providerHealth.isEmpty()) {
                                Text(
                                    if (ui.apiKey.isBlank()) "provider: idle \u2014 set an API key" else if (ui.probing) "probing\u2026" else "provider: idle",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            } else {
                                ui.providerHealth.forEach { h ->
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
                                onClick = { vm.refreshProbe() },
                                enabled = !ui.probing && ui.apiKey.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(
                                    if (ui.probing) "PROBING\u2026" else "PROBE",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = ui.nlText,
                            onValueChange = { vm.onNlTextChange(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp, max = 76.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            placeholder = {
                                Text("Ask in natural language, e.g. take a photo and notify me\u2026")
                            },
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.chat() }),
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
                                onClick = { vm.chat() },
                                enabled = !ui.isExecuting && ui.nlText.isNotBlank() && ui.apiKey.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                            ) {
                                if (ui.isExecuting) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp), strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (ui.isExecuting) "Thinking\u2026" else "Chat")
                            }
                            OutlinedTextField(
                                value = ui.apiKey,
                                onValueChange = { vm.onApiKeyChange(it) },
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
                            value = ui.dslText,
                            onValueChange = { vm.onDslTextChange(it) },
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
                            keyboardActions = KeyboardActions(onDone = { vm.run() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )

                        // Live preview line
                        ui.previewText?.let {
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
                                onClick = { vm.run() },
                                enabled = !ui.isExecuting && ui.dslText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                if (ui.isExecuting) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp), strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (ui.isExecuting) "Running\u2026" else "Run")
                            }
                            OutlinedButton(onClick = { vm.clearInput() }) { Text("Clear") }
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
                        onClick = { vm.clearLog() },
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
                                    "  hello.world(name=\"World\")",
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
                                    "  sys.notify(title=\"Hi\", text=\"Hello!\")",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                )
                                Text(
                                    "  sys.clipboard(text=\"MCOS rocks\")",
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
        ui.pendingConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = {
                    // Dismissing is treated as denying the action.
                    vm.respondConfirmation(false)
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
                    TextButton(onClick = { vm.respondConfirmation(true) }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.respondConfirmation(false) }) { Text("Deny") }
                },
            )
        }
    }
}
