package com.morainet.mcos.android

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morainet.mcos.marketplace.PackageMetadata

/**
 * Shell screen: renders [McosViewModel] state and forwards user actions to it.
 * Owns no business state of its own — the only `remember`s here are the
 * activity-result bridge wiring and pure view-local visibility toggles.
 *
 * Structure: this composable is a thin shell. Each section is its own
 * component ([StatusBar], [MarketplaceCard], [AiChatCard], [DslInputCard],
 * [OutputLog]); the terminal palette lives in [McosTheme] / [McosColor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCOSApp(deps: AppDeps) {
    val vm: McosViewModel = viewModel()
    // The runtime is activity-scoped; re-bind on every (re)composition root.
    LaunchedEffect(deps) { vm.attach(deps) }
    val ui by vm.uiState.collectAsState()
    val events by vm.events.collectAsState()

    // Marketplace (09-marketplace.md §7): search + install pipeline.
    val marketVm: MarketplaceViewModel = viewModel()
    LaunchedEffect(deps) { marketVm.attach(deps) }
    val marketUi by marketVm.uiState.collectAsState()
    // Installs/uninstalls mutate the registry at runtime — refresh the DSL
    // command palette (and plugin status bar) whenever they do.
    LaunchedEffect(marketUi.registryRevision) {
        if (marketUi.registryRevision > 0) vm.refreshCommandList()
    }

    // Pure view-local visibility toggles.
    var showCommands by remember { mutableStateOf(false) }
    var showMarketplace by remember { mutableStateOf(false) }
    var pendingInstall by remember { mutableStateOf<PackageMetadata?>(null) }

    // ── activity result bridge ─────────────────────────────────────────
    val resultLauncher = rememberLauncherForActivityResult(deps.resultBridge.contract) { result ->
        deps.resultBridge.onResult(result)
    }
    LaunchedEffect(resultLauncher) { deps.resultBridge.attach(resultLauncher) }

    // Request runtime permissions so sys.notify and photo.search/compress work:
    //  - POST_NOTIFICATIONS on API 33+ (notification posting)
    //  - READ_MEDIA_IMAGES on API 33+ / READ_EXTERNAL_STORAGE below (media store)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    McosTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MCOS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("  Shell", fontWeight = FontWeight.Normal)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = McosSpace.lg, vertical = McosSpace.md)
            ) {
                StatusBar(ui = ui, show = showCommands, onToggle = { showCommands = !showCommands })

                MarketplaceCard(
                    vm = marketVm,
                    ui = marketUi,
                    show = showMarketplace,
                    onToggle = { showMarketplace = !showMarketplace },
                    onInstallRequest = { pendingInstall = it },
                )

                AiChatCard(vm = vm, ui = ui)

                DslInputCard(vm = vm, ui = ui)

                Spacer(Modifier.height(McosSpace.md))

                OutputLog(
                    events = events,
                    onClear = { vm.clearLog() },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }

        // ── Pending confirmation dialog (08-security.md §5) ─────────────
        // The run is suspended on a ConfirmationNeeded event until the user
        // approves or denies the command.
        ui.pendingConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = { vm.respondConfirmation(false) },
                title = { Text("Approve action?") },
                text = {
                    Column {
                        Text(
                            confirmation.commandId,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(McosSpace.sm))
                        Text(confirmation.reason, style = MaterialTheme.typography.bodyMedium)
                        confirmation.sideEffectClass?.let { risk ->
                            Spacer(Modifier.height(McosSpace.md))
                            Text(
                                "Risk level: $risk",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (risk == "destructive") McosColor.danger else McosColor.warn,
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.respondConfirmation(true) }) { Text("Allow") } },
                dismissButton = { TextButton(onClick = { vm.respondConfirmation(false) }) { Text("Deny") } },
            )
        }

        // ── Marketplace install confirmation ─────────────────────────────
        // Shows the package's requested permissions (risk-tier colored) and
        // command previews before handing off to the install pipeline.
        pendingInstall?.let { meta ->
            AlertDialog(
                onDismissRequest = { pendingInstall = null },
                title = { Text("Install ${meta.name}?") },
                text = {
                    Column {
                        Text(
                            "${meta.packageId} v${meta.version}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("by ${meta.publisherName}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(McosSpace.md))
                        if (meta.permissionsPreview.isEmpty()) {
                            Text("No permissions requested.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                "Permissions:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            meta.permissionsPreview.forEach { entry ->
                                val tierColor = when (entry.riskTier) {
                                    "destructive" -> McosColor.danger
                                    "elevated" -> McosColor.warn
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Text(
                                    "• ${entry.type}:${entry.name} — ${entry.riskTier}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tierColor,
                                )
                                entry.justification?.let {
                                    Text(
                                        "    “$it”",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = McosColor.fgDim,
                                    )
                                }
                            }
                        }
                        if (meta.commandsPreview.isNotEmpty()) {
                            Spacer(Modifier.height(McosSpace.md))
                            Text(
                                "Commands: " + meta.commandsPreview.joinToString(", "),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(McosSpace.md))
                        Text(
                            "The artifact is verified (SHA-256 + publisher signature) before activation.",
                            style = MaterialTheme.typography.labelSmall,
                            color = McosColor.fgDim,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { marketVm.install(meta); pendingInstall = null }) { Text("Install") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingInstall = null }) { Text("Cancel") }
                },
            )
        }

        // ── Recipe install wizard (09-marketplace.md §8.3) ───────────────
        marketUi.recipePlan?.let { active ->
            RecipeWizardDialog(
                active = active,
                onCancel = { marketVm.cancelRecipe() },
                onSubmit = { bindings -> marketVm.submitRecipe(bindings) },
            )
        }

        // ── Update permission-diff consent (09-marketplace.md §7.2) ──────
        marketUi.pendingUpdate?.let { pending ->
            UpdateConsentDialog(
                pending = pending,
                onCancel = { marketVm.cancelUpdate() },
                onConfirm = { marketVm.confirmUpdate() },
            )
        }
    }
}
