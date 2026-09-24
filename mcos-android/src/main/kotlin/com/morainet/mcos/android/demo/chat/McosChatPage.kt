package com.morainet.mcos.android.demo.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.shell.McosUiState
import com.morainet.mcos.android.demo.shell.McosViewModel
import com.morainet.mcos.android.demo.tools.OutputLog

/**
 * Main conversation page (主对话页). A compact status header (selected vendor +
 * health + agent toggle), the natural-language composer with one primary Send
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
        VendorStatusBar(
            ui = ui,
            vendorName = selected.vendor.name,
            model = selected.model,
            ready = ready,
            onAgentModeChange = { vm.onAgentModeChange(it) },
        )
        Spacer(Modifier.height(McosSpace.md))

        // Compact composer: rounded field + circular accent send — one row
        // instead of field + full-width button, so the console keeps height.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
        ) {
            OutlinedTextField(
                value = ui.nlText,
                onValueChange = { vm.onNlTextChange(it) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 110.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                placeholder = {
                    Text(
                        "Ask in natural language, e.g. take a photo and notify me…",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp, color = McosColor.fgDim,
                        ),
                    )
                },
                shape = RoundedCornerShape(McosRadius.lg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = McosColor.borderSoft,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            SendButton(
                enabled = !ui.isExecuting && ui.nlText.isNotBlank() && ready,
                working = ui.isExecuting,
                agentMode = ui.agentMode,
                onClick = send,
            )
        }

        // Agent loop progress (06 §11): probes/replans stream here while
        // the turn is open; CANCEL aborts it (user cancel wins).
        if (ui.agentWorking) {
            Spacer(Modifier.height(McosSpace.sm))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(McosColor.accentSoft, RoundedCornerShape(McosRadius.pill))
                    .padding(horizontal = McosSpace.lg, vertical = McosSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.secondary, CircleShape)
                )
                Spacer(Modifier.width(McosSpace.sm))
                Text(
                    "agent: probing → replanning…",
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.accentDeep,
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

        Spacer(Modifier.height(McosSpace.md))
        OutputLog(
            events = events,
            onClear = { vm.clearLog() },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** Vendor + health + mode row: pill chip on the left, agent toggle on the right. */
@Composable
private fun VendorStatusBar(
    ui: McosUiState,
    vendorName: String,
    model: String,
    ready: Boolean,
    onAgentModeChange: (Boolean) -> Unit,
) {
    val health = ui.providerHealth.firstOrNull { it.providerId == ui.selectedVendor.vendor.id }
    val (dotColor, statusText) = when {
        ui.probing -> McosColor.info to "probing…"
        health?.healthy == true -> McosColor.success to "ready · $model"
        !ready -> McosColor.danger to "set a key in Settings"
        health != null -> McosColor.warn to (health.errorCode ?: "down")
        else -> McosColor.fgDim to model
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.background(McosColor.surfaceAlt, RoundedCornerShape(McosRadius.pill)).padding(
                start = McosSpace.lg, end = McosSpace.xl,
                top = McosSpace.sm, bottom = McosSpace.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(McosSpace.md))
            Text(
                vendorName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(McosSpace.md))
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall,
                color = McosColor.fgMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Agent",
            style = MaterialTheme.typography.labelMedium,
            color = if (ui.agentMode) MaterialTheme.colorScheme.primary else McosColor.fgDim,
        )
        Switch(
            checked = ui.agentMode,
            onCheckedChange = onAgentModeChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = McosColor.onAccent,
            ),
        )
    }
}

/** Circular gradient send button — the page's single primary action. */
@Composable
private fun SendButton(
    enabled: Boolean,
    working: Boolean,
    agentMode: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = McosColor.surfaceAlt,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (enabled) {
                        Modifier.background(
                            Brush.linearGradient(listOf(McosColor.accent, McosColor.accentDeep)),
                            CircleShape,
                        )
                    } else {
                        Modifier.background(McosColor.surfaceAlt, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (working) {
                CircularProgressIndicator(
                    Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = McosColor.onAccent,
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (agentMode) "Run agent" else "Send",
                    tint = if (enabled) McosColor.onAccent else McosColor.fgDim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
