package com.morainet.mcos.android.demo.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.R
import com.morainet.mcos.android.demo.records.InspectionTask
import com.morainet.mcos.android.demo.shell.ChatMessage
import com.morainet.mcos.android.demo.shell.ChatRole
import com.morainet.mcos.android.demo.shell.McosUiState
import com.morainet.mcos.android.demo.shell.McosViewModel

@Composable
internal fun AgentPage(vm: McosViewModel, ui: McosUiState, onConnectModel: () -> Unit) {
    val canSwitch = ui.tasksReady && !ui.isExecuting && ui.pendingAgentPlan == null && ui.pendingConfirmation == null
    val canStart = canSwitch && ui.taskResumeAtMs == null
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    BackHandler(historyOpen) { historyOpen = false }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 660.dp).fillMaxSize()) {
            if (historyOpen) {
                TaskLibraryPage(
                    ui = ui, canSwitch = canSwitch,
                    onBack = { historyOpen = false },
                    onSearch = vm::searchTaskHistory,
                    onRename = vm::renameTask,
                    onNewTask = {
                        vm.newTask()
                        historyOpen = false
                    },
                    onOpenTask = { id ->
                        vm.selectTask(id)
                        historyOpen = false
                    },
                )
            } else {
                val submit: () -> Unit = {
                    if (canStart && ui.nlText.isNotBlank()) {
                        focus.clearFocus()
                        if (ui.selectedVendor.usable) vm.agentTurn() else onConnectModel()
                    }
                }
                val runSample: () -> Unit = {
                    focus.clearFocus()
                    vm.runInspectionTask()
                }
                WorkspaceHeader(
                    model = if (ui.selectedVendor.usable) ui.selectedVendor.vendor.name else "Connect model",
                    onConnectModel = onConnectModel,
                    onOpenHistory = {
                        focus.clearFocus()
                        vm.searchTaskHistory("")
                        historyOpen = true
                    },
                )
                ui.taskHistoryError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (ui.transcript.isEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(top = 14.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item { WorkspaceIntroduction() }
                        item { TaskComposer(ui, canStart, onTextChange = vm::onNlTextChange, onRun = submit) }
                        item { InspectionShortcut(enabled = canStart, onRun = runSample) }
                        val recent = ui.taskHistory.filterNot { it.id == ui.activeTaskId }.take(2)
                        if (recent.isNotEmpty()) {
                            item { Text("Recent tasks", style = MaterialTheme.typography.titleMedium) }
                            items(recent, key = { it.id }) { entry ->
                                TaskHistoryRow(entry, current = false, enabled = canSwitch, onOpen = {
                                    focus.clearFocus()
                                    vm.selectTask(entry.id)
                                })
                            }
                        }
                    }
                } else {
                    val list = key(ui.activeTaskId) { rememberLazyListState(ui.transcript.lastIndex) }
                    LaunchedEffect(ui.activeTaskId, ui.transcript.last().id) {
                        list.animateScrollToItem(ui.transcript.lastIndex)
                    }
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(ui.transcript, key = { it.id }) { message -> TaskMessage(message) }
                    }
                    TaskProgress(ui, onStop = vm::cancelAgentTurn)
                    TaskComposer(ui, canStart, onTextChange = vm::onNlTextChange, onRun = submit, onSample = runSample)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(model: String, onConnectModel: () -> Unit, onOpenHistory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(colorResource(R.color.mcos_launcher_background), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.Unspecified,
            )
        }
        Text("MCOS", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = onConnectModel) {
            Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 136.dp))
        }
        IconButton(onClick = onOpenHistory) {
            Icon(Icons.Default.History, contentDescription = "Task history")
        }
    }
}

@Composable
private fun WorkspaceIntroduction() {
    Surface(shape = RoundedCornerShape(24.dp), color = McosColor.accentDeep, contentColor = McosColor.onAccent) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("What should MCOS do?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Give the agent a goal. It will ask before sensitive actions.",
                style = MaterialTheme.typography.bodyMedium,
                color = McosColor.onAccent.copy(alpha = .82f),
            )
        }
    }
}

@Composable
private fun TaskComposer(
    ui: McosUiState,
    canStart: Boolean,
    onTextChange: (String) -> Unit,
    onRun: () -> Unit,
    onSample: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, McosColor.border),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicTextField(
                value = ui.nlText,
                onValueChange = onTextChange,
                enabled = ui.tasksReady,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                    .semantics { contentDescription = "Describe a task" },
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onRun() }),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().padding(8.dp)) {
                        if (ui.nlText.isEmpty()) Text(
                            "Describe a task", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        field()
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onSample != null && canStart) TextButton(onClick = onSample) { Text("Try sample") }
                Button(
                    onClick = onRun,
                    enabled = canStart && ui.nlText.isNotBlank(),
                    contentPadding = PaddingValues(start = 14.dp, end = 16.dp),
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(17.dp))
                    Text("Run task", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun InspectionShortcut(enabled: Boolean, onRun: () -> Unit) {
    Surface(
        onClick = onRun, enabled = enabled, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, McosColor.border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(McosColor.accentSoft, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(InspectionTask.TITLE, style = MaterialTheme.typography.titleSmall)
                Text("Sample task · runs on this phone", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun TaskProgress(ui: McosUiState, onStop: () -> Unit) {
    if (ui.agentWorking) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(ui.agentProgress.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onStop) { Text("Stop") }
        }
    } else if (ui.taskResumeAtMs != null) {
        Text("Waiting to resume", Modifier.padding(6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TaskMessage(message: ChatMessage) {
    val user = message.role == ChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (user) .88f else 1f),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
        ) {
            SelectionContainer {
                Text(
                    message.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
