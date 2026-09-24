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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
 * Main conversation page (主对话页) — the mainstream assistant-app layout:
 * a quiet header that only appears when something needs attention, a
 * transcript (user bubbles right / assistant as plain text, ChatGPT-style /
 * system notices centered), a greeting-with-suggestion-cards empty state,
 * and a pill composer docked above the nav bar. The raw console stays on
 * the Tools page.
 */
@Composable
internal fun ChatPage(
    vm: McosViewModel,
    ui: McosUiState,
) {
    val send: () -> Unit = { if (ui.agentMode) vm.agentTurn() else vm.chat() }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Status surfaces only when it needs attention — a ready system is
        // invisible (mainstream behavior).
        StatusNotice(ui = ui)

        Transcript(
            ui = ui,
            onSuggestion = { vm.onNlTextChange(it) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        if (ui.agentWorking) {
            AgentStrip(onCancel = { vm.cancelAgentTurn() })
        }

        Composer(
            ui = ui,
            onTextChange = { vm.onNlTextChange(it) },
            onAgentModeChange = { vm.onAgentModeChange(it) },
            send = send,
        )
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
        contentPadding = PaddingValues(horizontal = McosSpace.xl, vertical = McosSpace.lg),
        verticalArrangement = Arrangement.spacedBy(McosSpace.lg),
    ) {
        if (ui.transcript.isEmpty()) {
            item { Greeting(onSuggestion = onSuggestion) }
        }
        items(ui.transcript, key = { it.id }) { message ->
            when (message.role) {
                ChatRole.USER -> UserBubble(message.text)
                ChatRole.AGENT -> AgentText(message.text)
                ChatRole.SYSTEM -> SystemLine(message.text)
            }
        }
        if (ui.isExecuting && ui.transcript.isNotEmpty()) {
            item { TypingRow(agent = ui.agentMode) }
        }
    }
}

/** Empty state: avatar, one-line greeting, tappable suggestion cards (icon + title + subtitle). */
@Composable
private fun Greeting(onSuggestion: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = McosColor.onAccent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(McosSpace.xl))
            Text(
                "How can I help you today?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Ask anything, or run a command — I'll compile it to verified DSL.",
                style = MaterialTheme.typography.bodyMedium,
                color = McosColor.fgMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
        }
        Column(
            Modifier.padding(horizontal = McosSpace.lg),
            verticalArrangement = Arrangement.spacedBy(McosSpace.md),
        ) {
            suggestionCards().forEach { card ->
                SuggestionCard(card = card, onClick = { onSuggestion(card.prompt) })
            }
        }
    }
}

private data class Suggestion(val icon: ImageVector, val tint: Color, val title: String, val subtitle: String, val prompt: String)

private fun suggestionCards(): List<Suggestion> = listOf(
    Suggestion(
        icon = Icons.Default.PhotoCamera, tint = Color(0xFFE91E63),
        title = "Take a photo", subtitle = "camera.capture()",
        prompt = "take a photo",
    ),
    Suggestion(
        icon = Icons.Default.Battery6Bar, tint = Color(0xFF43A047),
        title = "Check battery", subtitle = "Battery level and charging state",
        prompt = "what's my battery level?",
    ),
    Suggestion(
        icon = Icons.Default.Brightness6, tint = Color(0xFFFFB300),
        title = "Dim the screen", subtitle = "Set brightness to 30%",
        prompt = "set brightness to 30%",
    ),
    Suggestion(
        icon = Icons.Default.QrCodeScanner, tint = Color(0xFF1E88E5),
        title = "Scan a QR code", subtitle = "camera.scan()",
        prompt = "scan this QR code",
    ),
)

@Composable
private fun SuggestionCard(card: Suggestion, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(McosRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, McosColor.borderSoft),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = McosSpace.xl, vertical = McosSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(card.tint.copy(alpha = 0.12f), RoundedCornerShape(McosRadius.md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(card.icon, contentDescription = null, tint = card.tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(McosSpace.xl))
            Column {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.fgMuted,
                )
            }
        }
    }
}

// ── Messages ────────────────────────────────────────────────────────────────

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                color = McosColor.onAccent,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Assistant output is plain text on the page background — no bubble (mainstream). */
@Composable
private fun AgentText(text: String) {
    val isMono = text.contains("\n")
    Text(
        text,
        style = if (isMono) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.widthIn(max = 320.dp),
    )
}

@Composable
private fun SystemLine(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TypingRow(agent: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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

// ── Status / agent strip / composer ─────────────────────────────────────────

/** Quiet by default: renders only when the vendor needs attention. */
@Composable
private fun StatusNotice(ui: McosUiState) {
    val selected = ui.selectedVendor
    val health = ui.providerHealth.firstOrNull { it.providerId == selected.vendor.id }
    val notice: Pair<Color, String>? = when {
        ui.probing -> McosColor.info to "checking ${selected.vendor.name}…"
        health?.healthy == true -> null // healthy → invisible
        !selected.usable -> McosColor.warn to "Add an API key in Settings to chat"
        health != null && !health.healthy ->
            McosColor.danger to "${selected.vendor.name} is down (${health.errorCode ?: "unreachable"})"
        else -> null
    }
    val (color, text) = notice ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = McosSpace.xl, vertical = McosSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(McosSpace.md))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgMuted,
        )
    }
}

@Composable
private fun AgentStrip(onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = McosSpace.xl, vertical = McosSpace.xs)
            .background(McosColor.surfaceAlt, RoundedCornerShape(McosRadius.pill))
            .padding(horizontal = McosSpace.lg, vertical = McosSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
        Spacer(Modifier.width(McosSpace.sm))
        Text(
            "agent: probing → replanning…",
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgMuted,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "CANCEL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onCancel() }.padding(horizontal = McosSpace.md, vertical = McosSpace.xs),
        )
    }
}

/**
 * Composer: one soft-filled pill (no border, mainstream) holding the Agent
 * toggle (left), the field, and the circular send (right, arrow-up).
 */
@Composable
private fun Composer(
    ui: McosUiState,
    onTextChange: (String) -> Unit,
    onAgentModeChange: (Boolean) -> Unit,
    send: () -> Unit,
) {
    val enabled = !ui.isExecuting && ui.nlText.isNotBlank() && ui.selectedVendor.usable
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = McosColor.surfaceAlt,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McosSpace.lg, vertical = McosSpace.md),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Agent mode toggle (06 §11) lives in the composer — one tap from
            // the text it applies to.
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (ui.agentMode) McosColor.accentSoft else Color.Transparent,
                        CircleShape,
                    )
                    .clickable { onAgentModeChange(!ui.agentMode) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = "Agent mode",
                    tint = if (ui.agentMode) MaterialTheme.colorScheme.primary else McosColor.fgDim,
                    modifier = Modifier.size(22.dp),
                )
            }
            OutlinedTextField(
                value = ui.nlText,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                placeholder = {
                    Text(
                        "Message MCOS…",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = McosColor.fgDim),
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                maxLines = 4,
            )
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary else McosColor.border,
                        CircleShape,
                    )
                    .clickable(enabled = enabled) { send() },
                contentAlignment = Alignment.Center,
            ) {
                if (ui.isExecuting) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = McosColor.onAccent,
                    )
                } else {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = if (ui.agentMode) "Run agent" else "Send",
                        tint = McosColor.onAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
