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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.shell.ChatMessage
import com.morainet.mcos.android.demo.shell.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TaskConversation(
    messages: List<ChatMessage>,
    outcome: String?,
    inlineProgress: String?,
    canRestoreRequest: Boolean,
    onRestoreRequest: (Long) -> Unit,
    imageStore: ChatImageStore,
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
            item(key = "conversation-footer") {
                if (inlineProgress == null) {
                    Spacer(Modifier.height(1.dp))
                } else {
                    TaskProgress(inlineProgress)
                }
            }
            itemsIndexed(messages.asReversed(), key = { _, message -> message.id }) { index, message ->
                val result = outcome?.takeIf {
                    index == 0 && message.role == ChatRole.AGENT && message.text == "✓ $it"
                }
                if (result != null) {
                    TaskResult(result)
                } else {
                    val previous = messages.getOrNull(messages.lastIndex - index - 1)
                    val editRetry: (() -> Unit)? =
                        if (index == 0 && canRestoreRequest && message.retryOfUserMessageId != null) {
                            { onRestoreRequest(message.id) }
                        } else null
                    TaskMessage(message, previous?.role != ChatRole.AGENT, editRetry, imageStore)
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
internal fun TaskProgress(message: String, spinning: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(McosSpace.lg).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
    ) {
        if (spinning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TaskMessage(
    message: ChatMessage,
    showAgentLabel: Boolean,
    onEditRetry: (() -> Unit)?,
    imageStore: ChatImageStore,
) {
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
                Column(horizontalAlignment = Alignment.End) {
                    message.imageId?.let { id ->
                        ChatImagePreview(id, imageStore, Modifier.padding(McosSpace.md).size(188.dp, 144.dp))
                    }
                    SelectionContainer {
                        Text(
                            message.text,
                            modifier = Modifier.padding(horizontal = McosSpace.xl, vertical = McosSpace.lg),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
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
                MarkdownReply(message.text, canCopyCode = message.useAsContext)
            }
            if (message.useAsContext && message.text.isNotBlank()) {
                CopyToClipboardButton(message.text, label = "Copy reply")
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
                Column {
                    SelectionContainer {
                        Text(message.text, style = MaterialTheme.typography.bodySmall)
                    }
                    if (onEditRetry != null) {
                        TextButton(onClick = onEditRetry) { Text("Edit and retry") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CopyToClipboardButton(text: String, label: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500L)
            copied = false
        }
    }

    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        modifier = Modifier.semantics {
            if (copied) stateDescription = "Copied to clipboard"
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Outlined.ContentCopy,
            contentDescription = label,
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
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
