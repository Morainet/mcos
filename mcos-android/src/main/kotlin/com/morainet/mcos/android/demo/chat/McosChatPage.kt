package com.morainet.mcos.android.demo.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.R
import com.morainet.mcos.android.demo.shell.AgentProgress
import com.morainet.mcos.android.demo.shell.McosUiState
import com.morainet.mcos.android.demo.shell.McosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AgentPage(
    vm: McosViewModel,
    ui: McosUiState,
    canSwitch: Boolean,
    imageStore: ChatImageStore,
    onOpenMenu: () -> Unit,
    onConnectModel: () -> Unit,
) {
    val canStart = canSwitch && ui.taskResumeAtMs == null
    val canRun = canStart && (ui.nlText.isNotBlank() || ui.draftImageId != null)
    val inlineProgress = when (ui.agentProgress) {
        AgentProgress.WAITING_FOR_MODEL, AgentProgress.SLOW_MODEL_RESPONSE ->
            ui.agentProgress.label.takeIf { ui.transcript.isNotEmpty() }
        else -> null
    }
    var quickTasksOpen by rememberSaveable { mutableStateOf(false) }
    var imagePickerTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val taskId = imagePickerTaskId
        imagePickerTaskId = null
        if (uri != null && taskId != null) vm.prepareChatImage(taskId) { imageStore.import(uri) }
    }
    val focus = LocalFocusManager.current
    val submit: () -> Unit = {
        if (canRun) {
            focus.clearFocus()
            if (ui.selectedVendor.usable) vm.agentTurn() else onConnectModel()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val compact = maxHeight < 240.dp
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
            if (!compact || !keyboardVisible) {
                ChatHeader(
                    canCreate = canSwitch &&
                        (ui.transcript.isNotEmpty() || ui.nlText.isNotBlank() || ui.draftImageId != null),
                    onOpenMenu = {
                        focus.clearFocus()
                        onOpenMenu()
                    },
                    onNewTask = {
                        focus.clearFocus()
                        vm.newTask()
                    },
                )
            }
            ui.taskHistoryError?.let { message ->
                Text(
                    message,
                    modifier = Modifier.padding(McosSpace.sm),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val feed = Modifier.fillMaxWidth().weight(1f)
            if (ui.transcript.isEmpty()) {
                Box(feed.padding(horizontal = McosSpace.xl), contentAlignment = Alignment.Center) {
                    if (!compact) EmptyConversation()
                }
            } else {
                key(ui.activeTaskId) {
                    TaskConversation(
                        messages = ui.transcript + listOfNotNull(ui.streamingReply),
                        outcome = ui.lastAgentOutcome,
                        inlineProgress = inlineProgress,
                        canRestoreRequest = canStart && ui.nlText.isEmpty() && ui.draftImageId == null,
                        onRestoreRequest = vm::restoreRequestForEditing,
                        imageStore = imageStore,
                        modifier = feed,
                    )
                }
            }
            if (inlineProgress == null) {
                val progress = ui.agentProgress?.label
                if (progress != null) {
                    TaskProgress(progress)
                } else if (!ui.agentWorking && ui.taskResumeAtMs != null) {
                    TaskProgress("Waiting to resume", spinning = false)
                }
            }
            TaskComposer(
                text = ui.nlText,
                enabled = ui.tasksReady,
                canRun = canRun,
                running = ui.agentWorking,
                maxLines = if (compact) 2 else 5,
                attachment = {
                    ui.draftImageId?.let { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChatImagePreview(id, imageStore, Modifier.size(72.dp))
                            IconButton(onClick = vm::removeChatImage, enabled = canSwitch) {
                                Icon(Icons.Default.Close, contentDescription = "Remove image")
                            }
                        }
                    }
                    val status = if (ui.preparingImage) "Preparing image…" else ui.imageError
                    if (status != null) {
                        Text(
                            status,
                            modifier = Modifier.padding(McosSpace.sm),
                            color = if (ui.imageError == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onOpenQuickTasks = {
                    focus.clearFocus()
                    quickTasksOpen = true
                },
                onTextChange = vm::onNlTextChange,
                onRun = submit,
                onStop = vm::cancelAgentTurn,
            )
        }
    }
    if (quickTasksOpen) {
        QuickTasksSheet(
            canRunSample = canStart,
            canAttachImage = canStart,
            onDismiss = { quickTasksOpen = false },
            onAttachImage = {
                quickTasksOpen = false
                imagePickerTaskId = ui.activeTaskId
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRunSample = {
                quickTasksOpen = false
                vm.runInspectionTask()
            },
        )
    }
}

@Composable
internal fun ChatImagePreview(imageId: String, imageStore: ChatImageStore, modifier: Modifier = Modifier) {
    var bitmap by remember(imageId, imageStore) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(imageId, imageStore) {
        bitmap = withContext(Dispatchers.IO) {
            imageStore.read(imageId)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        }
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(McosRadius.md)) {
        val preview = bitmap
        if (preview == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Image", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Attached image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun EmptyConversation() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(76.dp),
        )
        Text(
            "What would you like to do?",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatHeader(canCreate: Boolean, onOpenMenu: () -> Unit, onNewTask: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(McosRadius.md)
    val border = BorderStroke(1.dp, palette.outlineVariant)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedIconButton(
            onClick = onOpenMenu,
            shape = shape,
            border = border,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.Menu, contentDescription = "Task menu", modifier = Modifier.size(21.dp))
        }
        OutlinedIconButton(
            onClick = onNewTask,
            enabled = canCreate,
            shape = shape,
            border = border,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.Edit, contentDescription = "New task", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TaskComposer(
    text: String,
    enabled: Boolean,
    canRun: Boolean,
    running: Boolean,
    maxLines: Int,
    attachment: @Composable () -> Unit,
    onOpenQuickTasks: () -> Unit,
    onTextChange: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, palette.outlineVariant),
        color = palette.surfaceContainer,
    ) {
        Column(Modifier.padding(McosSpace.md)) {
            attachment()
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                    .semantics { contentDescription = "Describe a task" },
                cursorBrush = SolidColor(palette.primary),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.onSurface),
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onRun() }),
                decorationBox = { field ->
                    Box(
                        Modifier.padding(horizontal = McosSpace.md, vertical = McosSpace.md),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                "Describe a task",
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = onOpenQuickTasks,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = palette.surfaceVariant,
                        contentColor = palette.onSurface,
                    ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Quick tasks", modifier = Modifier.size(20.dp))
                }
                FilledIconButton(
                    onClick = if (running) onStop else onRun,
                    enabled = running || canRun,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = palette.onSurface,
                        contentColor = palette.background,
                        disabledContainerColor = palette.surfaceVariant,
                        disabledContentColor = palette.outline,
                    ),
                    modifier = Modifier.size(48.dp),
                ) {
                    val icon = if (running) Icons.Default.Stop else Icons.Default.ArrowUpward
                    Icon(icon, contentDescription = if (running) "Stop" else "Run task")
                }
            }
        }
    }
}
