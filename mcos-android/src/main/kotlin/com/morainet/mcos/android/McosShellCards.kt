package com.morainet.mcos.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Plugin status bar + command palette ─────────────────────────────────────

@Composable
internal fun StatusBar(
    ui: McosUiState,
    show: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = McosSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Star, null,
            tint = if (ui.pluginsLoaded) MaterialTheme.colorScheme.primary else McosColor.warn,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(McosSpace.sm))
        Text(
            "Plugins (${ui.commandIds.size} commands)" +
                if (!ui.pluginsLoaded) " — not loaded" else "",
            style = MaterialTheme.typography.labelMedium,
            color = if (ui.pluginsLoaded) MaterialTheme.colorScheme.primary else McosColor.warn,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = McosSpace.md)) {
            Text(if (show) "HIDE" else "SHOW", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (show) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(McosRadius.sm),
        ) {
            Column(Modifier.padding(McosSpace.lg)) {
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
                        "… +${ui.commandIds.size - 24} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = McosColor.fgDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(McosSpace.md))
    }
}

// ── AI Chat card (06-agent.md §17) ──────────────────────────────────────────

@Composable
internal fun AiChatCard(
    vm: McosViewModel,
    ui: McosUiState,
) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = McosSpace.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(McosRadius.md),
    ) {
        Column(Modifier.padding(McosSpace.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AI Chat",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.apiKey.isBlank()) "no API key" else "key …${ui.apiKey.takeLast(4)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ui.apiKey.isBlank()) McosColor.danger else MaterialTheme.colorScheme.secondary,
                )
            }
            // Provider health row (06 §17 V1 probing).
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.providerHealth.isEmpty()) {
                    Text(
                        if (ui.apiKey.isBlank()) "provider: idle — set an API key"
                        else if (ui.probing) "probing…" else "provider: idle",
                        style = MaterialTheme.typography.labelSmall,
                        color = McosColor.fgDim,
                    )
                } else {
                    ui.providerHealth.forEach { h ->
                        val color = if (h.healthy) MaterialTheme.colorScheme.secondary else McosColor.danger
                        Text(
                            if (h.healthy) "●" else "○",
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                        )
                        Spacer(Modifier.width(McosSpace.xs))
                        Text(
                            "${h.providerId} (${h.tier}) " +
                                if (h.healthy) "ready" else (h.errorCode ?: "down"),
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                        )
                        Spacer(Modifier.width(McosSpace.lg))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { vm.refreshProbe() },
                    enabled = !ui.probing && ui.apiKey.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = McosSpace.md),
                ) {
                    Text(
                        if (ui.probing) "PROBING…" else "PROBE",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(McosSpace.sm))
            OutlinedTextField(
                value = ui.nlText,
                onValueChange = { vm.onNlTextChange(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 76.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                placeholder = { Text("Ask in natural language, e.g. take a photo and notify me…") },
                shape = RoundedCornerShape(McosRadius.sm),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.chat() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = McosColor.border,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(Modifier.height(McosSpace.sm))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { vm.chat() },
                    enabled = !ui.isExecuting && ui.nlText.isNotBlank() && ui.apiKey.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (ui.isExecuting) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(McosSpace.sm))
                    }
                    Text(if (ui.isExecuting) "Thinking…" else "Chat")
                }
                OutlinedTextField(
                    value = ui.apiKey,
                    onValueChange = { vm.onApiKeyChange(it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    placeholder = { Text("OpenAI API key…") },
                    shape = RoundedCornerShape(McosRadius.sm),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = McosColor.border,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}

// ── DSL input card (02-command-protocol.md) ─────────────────────────────────

@Composable
internal fun DslInputCard(
    vm: McosViewModel,
    ui: McosUiState,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(McosRadius.md),
    ) {
        Column(Modifier.padding(McosSpace.lg)) {
            OutlinedTextField(
                value = ui.dslText,
                onValueChange = { vm.onDslTextChange(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 110.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                placeholder = { Text("Type DSL commands…", style = MaterialTheme.typography.bodySmall) },
                shape = RoundedCornerShape(McosRadius.sm),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.run() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = McosColor.border,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            // Live preview line.
            ui.previewText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (it.startsWith("⚠")) McosColor.danger else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = McosSpace.xs),
                )
            }
            Spacer(Modifier.height(McosSpace.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
            ) {
                Button(
                    onClick = { vm.run() },
                    enabled = !ui.isExecuting && ui.dslText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(McosSpace.sm))
                    if (ui.isExecuting) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(McosSpace.sm))
                    }
                    Text(if (ui.isExecuting) "Running…" else "Run")
                }
                OutlinedButton(onClick = { vm.clearInput() }) { Text("Clear") }
            }
        }
    }
}

// ── Output header + event log ───────────────────────────────────────────────

@Composable
internal fun OutputLog(
    events: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = McosSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List, null,
                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(McosSpace.sm))
            Text("Output", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = McosSpace.md)) {
                Text("CLEAR", style = MaterialTheme.typography.labelSmall)
            }
        }
        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = McosColor.console),
            shape = RoundedCornerShape(McosRadius.md),
        ) {
            val scrollState = rememberScrollState()
            LaunchedEffect(events.size) {
                if (events.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
            }
            Box(Modifier.verticalScroll(scrollState).padding(McosSpace.lg)) {
                if (events.isEmpty()) {
                    EmptyLogHint()
                } else {
                    Column {
                        events.forEachIndexed { i, e ->
                            Text(
                                e,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                                color = eventLineColor(e),
                            )
                            if (i < events.size - 1) Spacer(Modifier.height(1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLogHint() {
    Column {
        Text(
            "Ready.",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = McosColor.fg.copy(alpha = 0.35f),
        )
        Spacer(Modifier.height(McosSpace.md))
        Text(
            "Try these examples:",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = McosColor.fg.copy(alpha = 0.25f),
        )
        listOf(
            "  hello.world(name=\"World\")",
            "  camera.capture()",
            "  sys.notify(title=\"Hi\", text=\"Hello!\")",
            "  sys.clipboard(text=\"MCOS rocks\")",
        ).forEach { example ->
            Text(
                example,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = McosColor.fg.copy(alpha = 0.2f),
            )
        }
    }
}

/** Semantic coloring for a console line based on its status marker. */
private fun eventLineColor(e: String): Color = when {
    e.contains("ERROR") || e.startsWith("✗") -> McosColor.danger
    e.contains("WARN") || e.startsWith("⚠") -> McosColor.warn
    e.contains("└ OK") || e.startsWith("✓") -> McosColor.success
    e.startsWith("▶") || e.startsWith("■") -> McosColor.info
    e.contains("Done") -> McosColor.warn
    else -> McosColor.fgMuted
}
