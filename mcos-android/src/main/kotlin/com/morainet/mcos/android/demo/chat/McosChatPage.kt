package com.morainet.mcos.android.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Main conversation page (主对话页). A compact status header (selected vendor +
 * health + agent toggle), the natural-language input with one primary Send
 * action (Chat or Agent by mode), the agent progress/cancel strip, and the
 * output console filling the rest. The API key is *not* here — it lives on the
 * Settings page; this page only surfaces which vendor is active and whether
 * it's ready.
 */
@Composable
internal fun ChatPage(
    vm: McosViewModel,
    ui: McosUiState,
    events: List<String>,
) {
    // One send action routed by mode: plain chat (plan once, run once) or the
    // multi-turn Agent loop (probe → replan → approve → execute, 06 §11).
    val send: () -> Unit = { if (ui.agentMode) vm.agentTurn() else vm.chat() }
    val selected = ui.selectedVendor
    val ready = selected.usable

    Column(Modifier.fillMaxSize()) {
        Card(
            Modifier.fillMaxWidth().padding(bottom = McosSpace.md),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(McosRadius.lg),
        ) {
            Column(Modifier.padding(McosSpace.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val health = ui.providerHealth.firstOrNull { it.providerId == selected.vendor.id }
                    val dotColor = when {
                        health?.healthy == true -> McosColor.success
                        !ready -> McosColor.danger
                        else -> McosColor.fgDim
                    }
                    Text("●", color = dotColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(McosSpace.sm))
                    Text(
                        selected.vendor.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(McosSpace.sm))
                    Text(
                        when {
                            ui.probing -> "probing…"
                            health?.healthy == true -> "ready · ${selected.model}"
                            !ready -> "set a key in Settings"
                            health != null -> (health.errorCode ?: "down")
                            else -> selected.model
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = McosColor.fgMuted,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ui.agentMode) MaterialTheme.colorScheme.primary else McosColor.fgDim,
                    )
                    Switch(checked = ui.agentMode, onCheckedChange = { vm.onAgentModeChange(it) })
                }
                Spacer(Modifier.height(McosSpace.sm))
                OutlinedTextField(
                    value = ui.nlText,
                    onValueChange = { vm.onNlTextChange(it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 96.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    placeholder = { Text("Ask in natural language, e.g. take a photo and notify me…") },
                    shape = RoundedCornerShape(McosRadius.md),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { send() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = McosColor.border,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Spacer(Modifier.height(McosSpace.sm))
                Button(
                    onClick = send,
                    enabled = !ui.isExecuting && ui.nlText.isNotBlank() && ready,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (ui.isExecuting) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(McosSpace.sm))
                    }
                    Text(
                        when {
                            ui.isExecuting -> if (ui.agentMode) "Probing…" else "Thinking…"
                            else -> if (ui.agentMode) "Run Agent" else "Send"
                        }
                    )
                }
                // Agent loop progress (06 §11): probes/replans stream here while
                // the turn is open; CANCEL aborts it (user cancel wins).
                if (ui.agentWorking) {
                    Spacer(Modifier.height(McosSpace.sm))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(12.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(McosSpace.sm))
                        Text(
                            "agent: probing → replanning…",
                            style = MaterialTheme.typography.labelSmall,
                            color = McosColor.fgDim,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { vm.cancelAgentTurn() },
                            contentPadding = PaddingValues(horizontal = McosSpace.md),
                        ) {
                            Text("CANCEL", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        OutputLog(
            events = events,
            onClear = { vm.clearLog() },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
