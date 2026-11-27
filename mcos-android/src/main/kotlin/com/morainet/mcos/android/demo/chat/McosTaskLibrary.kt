package com.morainet.mcos.android.demo.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morainet.mcos.android.demo.shell.McosUiState
import com.morainet.mcos.android.demo.shell.TASK_TITLE_LIMIT
import com.morainet.mcos.android.demo.shell.TaskHistoryEntry

@Composable
internal fun TaskLibraryPage(
    ui: McosUiState,
    canSwitch: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onNewTask: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = ui.taskSearchMatches ?: ui.taskHistory
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to workspace")
            }
            Text("Tasks", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(enabled = canSwitch, onClick = {
                focus.clearFocus()
                onNewTask()
            }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("New task", Modifier.padding(start = 4.dp))
            }
        }
        OutlinedTextField(
            value = ui.taskSearchQuery,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search tasks") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (ui.taskSearchQuery.isNotEmpty()) {
                { IconButton(onClick = { onSearch("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }
            } else null,
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        if (!canSwitch && ui.tasksReady) {
            Text("Finish or stop the current task before switching.", style = MaterialTheme.typography.bodySmall)
        }
        val emptyMessage = when {
            !ui.tasksReady -> "Opening task history…"
            entries.isNotEmpty() -> null
            ui.taskSearchMatches != null -> "No matching tasks"
            else -> "Your tasks will appear here."
        }
        if (emptyMessage != null) {
            Text(emptyMessage, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                TaskHistoryRow(
                    entry = entry, current = entry.id == ui.activeTaskId, enabled = canSwitch,
                    onOpen = {
                        focus.clearFocus()
                        onOpenTask(entry.id)
                    },
                    onRename = {
                        focus.clearFocus()
                        renameId = entry.id
                    },
                )
            }
        }
    }
    ui.taskHistory.firstOrNull { it.id == renameId }?.let { entry ->
        RenameTaskDialog(
            entry = entry, enabled = canSwitch,
            onDismiss = { renameId = null },
            onSave = { title ->
                onRename(entry.id, title)
                renameId = null
            },
        )
    }
}

@Composable
internal fun TaskHistoryRow(
    entry: TaskHistoryEntry,
    current: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f)
                    .semantics { selected = current }
                    .clickable(enabled = enabled, onClick = onOpen)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.subtitle.isNotBlank()) Text(
                        entry.subtitle, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onRename == null) Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                    modifier = Modifier.size(18.dp))
            }
            if (onRename != null) IconButton(onClick = onRename, enabled = enabled) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${entry.title}", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun RenameTaskDialog(entry: TaskHistoryEntry, enabled: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable(entry.id) { mutableStateOf(entry.title) }
    val canSave = enabled && title.isNotBlank() && title.trim() != entry.title
    val save: () -> Unit = { if (canSave) onSave(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename task") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= TASK_TITLE_LIMIT) title = it },
                label = { Text("Task name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        },
        confirmButton = { TextButton(onClick = save, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
