package com.morainet.mcos.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morainet.mcos.marketplace.InstallResult
import com.morainet.mcos.marketplace.InstallState
import com.morainet.mcos.marketplace.PackageMetadata
import com.morainet.mcos.marketplace.RecipeEnvelope

// ── Marketplace card (09-marketplace.md §7) ─────────────────────────────────

/** States during which the install/uninstall pipeline is still working. */
private val busyInstallStates = setOf(
    InstallState.DOWNLOADING, InstallState.VERIFYING, InstallState.STAGING,
    InstallState.LOADING, InstallState.UNINSTALLING,
)

@Composable
internal fun MarketplaceCard(
    vm: MarketplaceViewModel,
    ui: MarketplaceUiState,
    show: Boolean,
    onToggle: () -> Unit,
    onInstallRequest: (PackageMetadata) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = McosSpace.md)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Marketplace",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(McosSpace.md))
            Text(
                if (ui.installResults.isEmpty()) "" else "${ui.installResults.size} installed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = McosSpace.md)) {
                Text(if (show) "HIDE" else "SHOW", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (show) {
            var recipeMode by remember { mutableStateOf(false) }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(McosRadius.md),
            ) {
                Column(Modifier.padding(McosSpace.lg)) {
                    // Plugins ↔ Recipes mode toggle.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(false to "Plugins", true to "Recipes").forEach { (mode, label) ->
                            val selected = recipeMode == mode
                            TextButton(
                                onClick = { recipeMode = mode },
                                contentPadding = PaddingValues(horizontal = McosSpace.lg),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.tertiary else McosColor.fgDim,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = ui.baseUrl,
                        onValueChange = { vm.onBaseUrlChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        placeholder = { Text("Marketplace index URL, e.g. https://index.example.com") },
                        shape = RoundedCornerShape(McosRadius.sm),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                            unfocusedBorderColor = McosColor.border,
                            cursorColor = MaterialTheme.colorScheme.tertiary,
                        ),
                    )
                    Spacer(Modifier.height(McosSpace.sm))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = ui.query,
                            onValueChange = { vm.onQueryChange(it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            placeholder = { Text(if (recipeMode) "Search recipes…" else "Search plugins…") },
                            shape = RoundedCornerShape(McosRadius.sm),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { if (recipeMode) vm.searchRecipes() else vm.search() }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                                unfocusedBorderColor = McosColor.border,
                                cursorColor = MaterialTheme.colorScheme.tertiary,
                            ),
                        )
                        Button(
                            onClick = { if (recipeMode) vm.searchRecipes() else vm.search() },
                            enabled = !ui.searching && ui.baseUrl.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ),
                        ) {
                            if (ui.searching) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(McosSpace.sm))
                            }
                            Text(if (ui.searching) "Searching…" else "Search")
                        }
                    }
                    ui.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = McosColor.danger,
                            modifier = Modifier.padding(top = McosSpace.xs),
                        )
                    }
                    ui.message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = McosSpace.xs),
                        )
                    }
                    val hasResults = if (recipeMode) ui.recipeResults.isNotEmpty() else ui.results.isNotEmpty()
                    if (hasResults) {
                        Spacer(Modifier.height(McosSpace.sm))
                        val listScroll = rememberScrollState()
                        Column(
                            Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(listScroll)
                        ) {
                            if (recipeMode) {
                                ui.recipeResults.forEachIndexed { index, recipe ->
                                    if (index > 0) Spacer(Modifier.height(McosSpace.sm))
                                    RecipeResultRow(recipe, ui.searching, vm)
                                }
                            } else {
                                ui.results.forEachIndexed { index, meta ->
                                    if (index > 0) Spacer(Modifier.height(McosSpace.sm))
                                    PackageResultRow(meta, ui, vm, onInstallRequest)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageResultRow(
    meta: PackageMetadata,
    ui: MarketplaceUiState,
    vm: MarketplaceViewModel,
    onInstallRequest: (PackageMetadata) -> Unit,
) {
    val result = ui.installResults[meta.packageId]
    val progress = ui.installStates[meta.packageId]
    val busy = progress != null && progress.state in busyInstallStates

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = McosColor.console),
        shape = RoundedCornerShape(McosRadius.sm),
    ) {
        Column(Modifier.padding(McosSpace.md)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meta.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "  v${meta.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.fgDim,
                )
                Spacer(Modifier.weight(1f))
                when {
                    result is InstallResult.Installed -> {
                        val installed = ui.installedMetas[meta.packageId]
                        val updatable = installed != null && installed.version != meta.version
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (updatable) {
                                Button(
                                    onClick = { vm.requestUpdate(meta) },
                                    enabled = !busy,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                    contentPadding = PaddingValues(horizontal = McosSpace.lg),
                                ) { Text("Update", style = MaterialTheme.typography.labelSmall) }
                                Spacer(Modifier.width(McosSpace.sm))
                            }
                            OutlinedButton(
                                onClick = { vm.uninstall(meta.packageId) },
                                enabled = !busy,
                                contentPadding = PaddingValues(horizontal = McosSpace.lg),
                            ) { Text("Uninstall", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    busy -> CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    else -> Button(
                        onClick = { onInstallRequest(meta) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        ),
                        contentPadding = PaddingValues(horizontal = McosSpace.lg),
                    ) { Text("Install", style = MaterialTheme.typography.labelSmall) }
                }
            }
            Text(
                "${meta.publisherName} · ↓${meta.downloadCount} · ${meta.summary}",
                style = MaterialTheme.typography.labelSmall,
                color = McosColor.fgMuted,
                maxLines = 2,
            )
            if (meta.commandsPreview.isNotEmpty()) {
                Text(
                    meta.commandsPreview.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            when {
                result is InstallResult.Installed -> Text(
                    "✓ INSTALLED · ${result.trustLevel} · ${result.commandsRegistered} command(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.success,
                )
                result is InstallResult.Failed -> Text(
                    "✗ ${result.code}: ${result.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.danger,
                )
                progress != null -> Text(
                    progress.state.name + (progress.message?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.warn,
                )
                else -> { /* nothing running */ }
            }
        }
    }
}

@Composable
private fun RecipeResultRow(
    recipe: RecipeEnvelope,
    searching: Boolean,
    vm: MarketplaceViewModel,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = McosColor.console),
        shape = RoundedCornerShape(McosRadius.sm),
    ) {
        Column(Modifier.padding(McosSpace.md)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    recipe.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "  v${recipe.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.fgDim,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.prepareRecipe(recipe) },
                    enabled = !searching,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    contentPadding = PaddingValues(horizontal = McosSpace.lg),
                ) { Text("Install", style = MaterialTheme.typography.labelSmall) }
            }
            recipe.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.fgMuted,
                    maxLines = 2,
                )
            }
            if (recipe.requiredPlugins.isNotEmpty()) {
                Text(
                    "needs: " + recipe.requiredPlugins.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun RecipeWizardDialog(
    active: ActiveRecipePlan,
    onCancel: () -> Unit,
    onSubmit: (Map<String, String>) -> Unit,
) {
    val plan = active.plan
    // Seed each field from the memory suggestion, then the recipe default.
    val bindings = remember(active) {
        mutableStateMapOf<String, String>().apply {
            plan.prompts.forEach { p -> put(p.key, p.suggested ?: p.default ?: "") }
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Install recipe: ${active.recipe.name}") },
        text = {
            Column {
                active.recipe.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(McosSpace.md))
                }
                when {
                    plan.blockedOnDependencies -> {
                        Text(
                            "Missing plugin(s) — install these first:",
                            style = MaterialTheme.typography.labelMedium,
                            color = McosColor.danger,
                        )
                        plan.missingDependencies.forEach { dep ->
                            val avail = dep.suggestedVersion?.let { " (available $it)" } ?: " (not in marketplace)"
                            Text(
                                "• ${dep.pluginId}@${dep.range}$avail",
                                style = MaterialTheme.typography.bodySmall,
                                color = McosColor.danger,
                            )
                        }
                    }
                    plan.prompts.isEmpty() -> Text(
                        "No inputs required.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> plan.prompts.forEach { prompt ->
                        val label = (prompt.label ?: prompt.key) + if (prompt.required) " *" else ""
                        OutlinedTextField(
                            value = bindings[prompt.key] ?: "",
                            onValueChange = { bindings[prompt.key] = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        prompt.suggested?.let {
                            Text(
                                "from memory: $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Spacer(Modifier.height(McosSpace.sm))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(bindings.toMap()) },
                enabled = !plan.blockedOnDependencies,
            ) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
internal fun UpdateConsentDialog(
    pending: PendingUpdate,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val diff = pending.diff
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Update ${pending.newMeta.name}?") },
        text = {
            Column {
                Text(
                    "v${pending.oldMeta.version} → v${pending.newMeta.version}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(McosSpace.md))
                Text(
                    "This update requests new or elevated permissions:",
                    style = MaterialTheme.typography.labelMedium,
                    color = McosColor.warn,
                )
                diff.added.forEach { entry ->
                    val tierColor = when (entry.riskTier) {
                        "destructive" -> McosColor.danger
                        "elevated" -> McosColor.warn
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        "+ ${entry.type}:${entry.name} — ${entry.riskTier}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tierColor,
                    )
                }
                diff.changed.forEach { change ->
                    Text(
                        "Δ ${change.scope}: ${change.oldEntry.riskTier} → ${change.newEntry.riskTier}",
                        style = MaterialTheme.typography.bodySmall,
                        color = McosColor.warn,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Update") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
