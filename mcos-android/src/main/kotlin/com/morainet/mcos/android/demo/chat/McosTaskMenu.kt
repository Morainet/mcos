package com.morainet.mcos.android.demo.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.morainet.mcos.android.demo.LocalMcosDarkTheme
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.records.InspectionTask

@Composable
internal fun TaskMenu(
    canRunSample: Boolean,
    onChangeDarkTheme: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onRunSample: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(McosSpace.md),
            verticalArrangement = Arrangement.spacedBy(McosSpace.md),
        ) {
            Text(
                "Workspace",
                Modifier.padding(bottom = McosSpace.md),
                style = MaterialTheme.typography.headlineMedium,
            )
            TaskMenuItem("Task history", Icons.Default.History, onOpenHistory)
            TaskMenuItem("Model settings", Icons.Default.Tune, onOpenModelSettings)
            TaskMenuItem("Capabilities", Icons.Default.Extension, onOpenCapabilities)
            InspectionPreset(enabled = canRunSample, onClick = onRunSample)
            DarkModeRow(onChangeDarkTheme)
        }
    }
}

@Composable
private fun DarkModeRow(onChangeDarkTheme: (Boolean) -> Unit) {
    val dark = LocalMcosDarkTheme.current
    val palette = MaterialTheme.colorScheme
    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(McosRadius.lg),
        border = BorderStroke(1.dp, palette.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = dark, role = Role.Switch, onValueChange = onChangeDarkTheme)
                .padding(horizontal = McosSpace.xl, vertical = McosSpace.md),
            horizontalArrangement = Arrangement.spacedBy(McosSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Palette, contentDescription = null, tint = palette.primary)
            Text("Dark mode", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = dark, onCheckedChange = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickTasksSheet(canRunSample: Boolean, onDismiss: () -> Unit, onRunSample: () -> Unit) {
    val dark = LocalMcosDarkTheme.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        // Material's sheet window defaults to the device theme.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        if (window != null) {
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = McosSpace.xl, end = McosSpace.xl, bottom = McosSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(McosSpace.lg),
        ) {
            Text("Quick tasks", style = MaterialTheme.typography.titleLarge)
            InspectionPreset(enabled = canRunSample, onClick = onRunSample)
        }
    }
}

@Composable
private fun InspectionPreset(enabled: Boolean, onClick: () -> Unit) {
    TaskMenuItem(
        title = InspectionTask.TITLE,
        icon = Icons.Default.Description,
        onClick = onClick,
        enabled = enabled,
        description = "Sample task · runs without a model",
    )
}

@Composable
private fun TaskMenuItem(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String? = null,
) {
    val palette = MaterialTheme.colorScheme
    val foreground = if (enabled) palette.onSurface else palette.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = palette.surface,
        contentColor = foreground,
        shape = RoundedCornerShape(McosRadius.lg),
        border = BorderStroke(1.dp, palette.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(McosSpace.xl),
            horizontalArrangement = Arrangement.spacedBy(McosSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) palette.primary else foreground)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(McosSpace.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(description, color = palette.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
