package com.morainet.mcos.android.demo.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.shell.ChatMessage
import com.morainet.mcos.android.demo.shell.ChatRole
import com.morainet.mcos.android.demo.shell.McosUiState
import com.morainet.mcos.android.demo.shell.McosViewModel

/**
 * Main conversation page (主对话页) — a real chat surface, not a console.
 *
 * The conversation transcript (typed [ChatMessage]s maintained by the view
 * model) renders as bubbles: user on the right in the accent gradient, agent
 * replies on the left as bordered cards, system notices centered and muted.
 * The full raw event log still lives on the Tools page's console.
 *
 * A compact vendor/health/agent-mode row sits under the top bar; the composer
 * is docked above the navigation bar and stays visible while scrolling.
 */
@Composable
internal fun ChatPage(
    vm: McosViewModel,
    ui: McosUiState,
) {
    // One send action routed by mode: plain chat (plan once, run once) or the
    // multi-turn Agent loop (probe → replan → approve → execute, 06 §11).
    val send: () -> Unit = { if (ui.agentMode) vm.agentTurn() else vm.chat() }
    val selected = ui.selectedVendor
    val ready = selected.usable

    Column(Modifier.fillMaxSize().imePadding()) {
        VendorStatusBar(
            ui = ui,
            vendorName = selected.vendor.name,
            model = selected.model,
            ready = ready,
            onAgentModeChange = { vm.onAgentModeChange(it) },
        )

        Transcript(
            ui = ui,
            onSuggestion = { vm.onNlTextChange(it) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        // Agent loop progress (06 §11): probes/replans stream here while
        // the turn is open; CANCEL aborts it (user cancel wins).
        if (ui.agentWorking) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = McosSpace.lg, vertical = McosSpace.xs)
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

        // Docked composer: rounded field + circular accent send.
        Row(
            Modifier.fillMaxWidth().padding(top = McosSpace.sm),
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
                        "Describe what you want, or type DSL…",
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
    }
}

// ── Transcript ──────────────────────────────────────────────────────────────

@Composable
private fun Transcript(
    ui: McosUiState,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(ui.transcript.size, ui.isExecuting) {
        if (ui.transcript.isNotEmpty()) listState.animateScrollToItem(ui.transcript.size - 1)
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(vertical = McosSpace.lg),
        verticalArrangement = Arrangement.spacedBy(McosSpace.lg),
    ) {
        if (ui.transcript.isEmpty()) {
            item { ChatHero(onSuggestion = onSuggestion) }
        }
        items(ui.transcript, key = { it.id }) { message ->
            when (message.role) {
                ChatRole.USER -> UserBubble(message.text)
                ChatRole.AGENT -> AgentBubble(message.text)
                ChatRole.SYSTEM -> SystemLine(message.text)
            }
        }
        if (ui.isExecuting && ui.transcript.isNotEmpty()) {
            item { TypingRow(agent = ui.agentMode) }
        }
    }
}

/** First-run hero: what this is, and tappable starters that pre-fill the composer. */
@Composable
private fun ChatHero(onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = McosSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(
                    Brush.linearGradient(listOf(McosColor.accent, McosColor.accentDeep)),
                    RoundedCornerShape(McosRadius.xl),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ">_",
                color = McosColor.onAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
            )
        }
        Spacer(Modifier.height(McosSpace.xl))
        Text(
            "Hi, I'm MCOS",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(McosSpace.sm))
        Text(
            "Describe a goal in natural language — I compile it to verified DSL and run it on-device.",
            style = MaterialTheme.typography.bodyMedium,
            color = McosColor.fgMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.height(McosSpace.xl))
        val suggestions = listOf(
            "Take a photo" to "take a photo",
            "Battery status" to "what's my battery level?",
            "Dim the screen" to "set brightness to 30%",
            "Scan a QR code" to "scan this QR code",
        )
        Column(
            Modifier.widthIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(McosSpace.md),
        ) {
            suggestions.chunked(2).forEach { rowPairs ->
                Row(horizontalArrangement = Arrangement.spacedBy(McosSpace.md)) {
                    rowPairs.forEach { (label, prompt) ->
                        SuggestionChip(
                            label = label,
                            onClick = { onSuggestion(prompt) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(McosRadius.pill),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, McosColor.border),
        modifier = modifier.clickable { onClick() },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = McosSpace.lg, vertical = McosSpace.md),
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            color = Color.Transparent,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Box(
                Modifier
                    .background(
                        Brush.linearGradient(listOf(McosColor.accent, McosColor.accentDeep)),
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                    )
                    .padding(horizontal = McosSpace.xl, vertical = McosSpace.lg),
            ) {
                Text(text, color = McosColor.onAccent, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AgentBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, McosColor.borderSoft),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = McosSpace.xl, vertical = McosSpace.lg)) {
                val isMono = text.contains("\n")
                Text(
                    text,
                    style = if (isMono) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SystemLine(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun TypingRow(agent: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = McosSpace.xs), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(McosRadius.lg),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, McosColor.borderSoft),
        ) {
            Row(
                Modifier.padding(horizontal = McosSpace.lg, vertical = McosSpace.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    Modifier.size(14.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(McosSpace.md))
                Text(
                    if (agent) "probing…" else "thinking…",
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.fgMuted,
                )
            }
        }
    }
}

// ── Status + send ───────────────────────────────────────────────────────────

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
        health?.healthy == true -> McosColor.success to model
        !ready -> McosColor.danger to "set a key in Settings"
        health != null -> McosColor.warn to (health.errorCode ?: "down")
        else -> McosColor.fgDim to model
    }
    Row(Modifier.fillMaxWidth().padding(bottom = McosSpace.sm), verticalAlignment = Alignment.CenterVertically) {
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
            disabledContainerColor = Color.Transparent,
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
