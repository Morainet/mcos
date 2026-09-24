package com.morainet.mcos.android.demo.tools

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.shell.McosUiState
import com.morainet.mcos.android.demo.shell.McosViewModel

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

// ── DSL input card (02-command-protocol.md) ─────────────────────────────────

@Composable
internal fun DslInputCard(
    vm: McosViewModel,
    ui: McosUiState,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(McosRadius.lg),
    ) {
        Column(Modifier.padding(McosSpace.xl)) {
            com.morainet.mcos.android.demo.ui.QuietField(
                value = ui.dslText,
                onValueChange = { vm.onDslTextChange(it) },
                placeholder = "hello.world(name=\"World\")\nsys.notify(title=\"Hi\", text=\"It works\")",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                mono = true,
                minLinesHeight = 88,
                keyboardActions = KeyboardActions(onDone = { vm.run() }),
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
                    shape = RoundedCornerShape(McosRadius.md),
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
            Spacer(Modifier.width(McosSpace.md))
            Text(
                "${events.size} events",
                style = MaterialTheme.typography.labelSmall,
                color = McosColor.fgDim,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = McosSpace.md)) {
                Text("CLEAR", style = MaterialTheme.typography.labelSmall)
            }
        }
        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = McosColor.console),
            shape = RoundedCornerShape(McosRadius.lg),
        ) {
            val scrollState = rememberScrollState()
            LaunchedEffect(events.size) {
                if (events.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
            }
            Box(Modifier.verticalScroll(scrollState).padding(McosSpace.xl)) {
                if (events.isEmpty()) {
                    EmptyLogHint()
                } else {
                    Column {
                        events.forEachIndexed { i, e ->
                            Text(
                                e,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 19.sp,
                                color = eventLineColor(e),
                            )
                            if (i < events.size - 1) Spacer(Modifier.height(2.dp))
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
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = McosColor.consoleFg,
        )
        Spacer(Modifier.height(McosSpace.md))
        Text(
            "Try these examples:",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = McosColor.consoleFgDim,
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
                color = McosColor.consoleFgDim,
            )
        }
    }
}

/** Semantic coloring for a console line based on its status marker. */
private fun eventLineColor(e: String): Color = when {
    e.contains("ERROR") || e.startsWith("✗") -> McosColor.consoleDanger
    e.contains("WARN") || e.startsWith("⚠") -> McosColor.consoleWarn
    e.contains("└ OK") || e.startsWith("✓") -> McosColor.consoleSuccess
    e.startsWith("▶") || e.startsWith("■") -> McosColor.consoleInfo
    e.contains("Done") -> McosColor.consoleWarn
    else -> McosColor.consoleFg
}
