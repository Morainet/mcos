package com.morainet.mcos.android.demo.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.shell.ChatMessage
import com.morainet.mcos.android.demo.shell.ChatRole
import kotlinx.coroutines.launch

@Composable
internal fun TaskConversation(
    messages: List<ChatMessage>,
    outcome: String?,
    modifier: Modifier,
) {
    val latest = messages.last()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(latest.id) {
        if (latest.role == ChatRole.USER) list.scrollToItem(0)
    }
    val showLatest by remember(list) {
        derivedStateOf { list.canScrollBackward }
    }

    Box(modifier) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = McosSpace.xl, bottom = McosSpace.lg),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(McosSpace.xxl, Alignment.Top),
        ) {
            // Index zero anchors the bottom even while the last message grows.
            item(key = "conversation-footer") { Spacer(Modifier.height(1.dp)) }
            itemsIndexed(messages.asReversed(), key = { _, message -> message.id }) { index, message ->
                val result = outcome?.takeIf {
                    index == 0 && message.role == ChatRole.AGENT && message.text == "✓ $it"
                }
                if (result != null) {
                    TaskResult(result)
                } else {
                    val previous = messages.getOrNull(messages.lastIndex - index - 1)
                    TaskMessage(message, showAgentLabel = previous?.role != ChatRole.AGENT)
                }
            }
        }
        if (showLatest) {
            FilledTonalButton(
                onClick = { scope.launch { list.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = McosSpace.md),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                Text("Latest")
            }
        }
    }
}

@Composable
private fun TaskMessage(message: ChatMessage, showAgentLabel: Boolean) {
    when (message.role) {
        ChatRole.USER -> Box(
            Modifier.fillMaxWidth().padding(start = 40.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = McosRadius.xl,
                    topEnd = McosRadius.xl,
                    bottomStart = McosRadius.xl,
                    bottomEnd = McosRadius.sm,
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                SelectionContainer {
                    Text(
                        message.text,
                        modifier = Modifier.padding(horizontal = McosSpace.xl, vertical = McosSpace.lg),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        ChatRole.AGENT -> Column(
            Modifier.fillMaxWidth().padding(horizontal = McosSpace.xs),
            verticalArrangement = Arrangement.spacedBy(McosSpace.xs),
        ) {
            if (showAgentLabel) {
                Text(
                    "Agent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                MarkdownReply(message.text)
            }
        }
        ChatRole.SYSTEM -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(McosRadius.md),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(McosSpace.lg),
                horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
            ) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                SelectionContainer {
                    Text(message.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TaskResult(result: String) {
    val palette = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(McosRadius.lg),
        color = palette.primaryContainer,
        contentColor = palette.onPrimaryContainer,
        border = BorderStroke(1.dp, palette.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(McosSpace.xl),
            horizontalArrangement = Arrangement.spacedBy(McosSpace.lg),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = palette.primary)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(McosSpace.xs)) {
                    Text(result.substringBefore('\n'), style = MaterialTheme.typography.titleMedium)
                    val details = result.substringAfter('\n', "")
                    if (details.isNotBlank()) {
                        Text(details, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
