package com.morainet.mcos.android.demo.shell

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.chat.AgentPage
import com.morainet.mcos.android.demo.marketplace.MarketplaceCard
import com.morainet.mcos.android.demo.marketplace.MarketplaceViewModel
import com.morainet.mcos.android.demo.marketplace.RecipeWizardDialog
import com.morainet.mcos.android.demo.marketplace.UpdateConsentDialog
import com.morainet.mcos.android.demo.mcp.McpPage
import com.morainet.mcos.android.demo.settings.SettingsPage
import com.morainet.mcos.android.demo.skills.SkillsPage
import com.morainet.mcos.android.demo.tools.DslInputCard
import com.morainet.mcos.android.demo.tools.OutputLog
import com.morainet.mcos.android.demo.tools.StatusBar
import com.morainet.mcos.marketplace.PackageMetadata

/** Routes the Agent workspace and capability settings, and hosts Runtime approval dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCOSApp(deps: AppDeps) {
    val vm: McosViewModel = viewModel()
    // Bind the process runtime to this Activity's view model.
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
    var showDeveloperTools by rememberSaveable { mutableStateOf(false) }
    var pendingInstall by remember { mutableStateOf<PackageMetadata?>(null) }

    // ── activity result bridge ─────────────────────────────────────────
    val resultLauncher = rememberLauncherForActivityResult(deps.resultBridge.contract) { result ->
        deps.resultBridge.onResult(result)
    }
    LaunchedEffect(resultLauncher) { deps.resultBridge.attach(resultLauncher) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // On-demand runtime-permission prompts (04 §6.3): a handler that hits a
    // missing runtime grant (e.g. sys.device.location) prompts in-app through
    // the bridge instead of pointing the user at system settings. Headless
    // runs never attach a prompter; the command degrades honestly.
    val promptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> deps.permissionBridge.onResult(granted) }
    LaunchedEffect(promptLauncher) { deps.permissionBridge.attach { promptLauncher.launch(it) } }

    var page by rememberSaveable { mutableStateOf(ShellPage.AGENT) }
    BackHandler(page != ShellPage.AGENT) {
        page = if (page == ShellPage.SKILLS || page == ShellPage.MCP) ShellPage.TOOLS else ShellPage.AGENT
    }

    _root_ide_package_.com.morainet.mcos.android.demo.McosTheme {
        Scaffold(
            // Full-screen immersive, no top bar (mainstream chat shells go
            // edge-to-edge under the status bar). IME is deliberately EXCLUDED
            // from these insets — the page container applies imePadding() once;
            // doing it in both places double-lifts the content and opens a gap
            // between the composer and the keyboard.
            contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
            bottomBar = { McosNavBar(page = page, onSelect = { page = it }) },
        ) { padding ->
            Crossfade(targetState = page, label = "shell-page") { current ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(padding)
                        .padding(
                            horizontal = if (current == ShellPage.AGENT) McosSpace.xl else McosSpace.lg,
                            vertical = McosSpace.md
                        )
                ) {
                    when (current) {
                        ShellPage.AGENT -> AgentPage(
                            vm = vm,
                            ui = ui,
                            onConnectModel = { page = ShellPage.SETTINGS },
                        )

                        ShellPage.SKILLS -> SkillsPage(vm = vm, ui = ui)
                        ShellPage.MCP -> McpPage(vm = vm, ui = ui)
                        ShellPage.TOOLS -> Column(Modifier.fillMaxSize().verticalScroll(_root_ide_package_.androidx.compose.foundation.rememberScrollState())) {
                            com.morainet.mcos.android.demo.ui.PageHeader(
                                icon = Icons.Default.Extension,
                                tint = McosColor.warn,
                                title = "Capabilities",
                                description = "Manage tools, skills and device permissions.",
                            )
                            Row {
                                TextButton(onClick = { page = ShellPage.SKILLS }) { Text("Skills") }
                                TextButton(onClick = { page = ShellPage.MCP }) { Text("MCP servers") }
                            }
                            TextButton(onClick = {
                                val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_IMAGES)
                                } else {
                                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                                permissionLauncher.launch(needed)
                            }) { Text("Manage media and notification permissions") }
                            MarketplaceCard(
                                vm = marketVm,
                                ui = marketUi,
                                show = showMarketplace,
                                onToggle = { showMarketplace = !showMarketplace },
                                onInstallRequest = { pendingInstall = it },
                            )
                            TextButton(onClick = { showDeveloperTools = !showDeveloperTools }) {
                                Text(if (showDeveloperTools) "Hide developer tools" else "Developer tools")
                            }
                            if (showDeveloperTools) {
                                StatusBar(ui = ui, show = showCommands, onToggle = { showCommands = !showCommands })
                                DslInputCard(vm = vm, ui = ui)
                                Spacer(Modifier.height(McosSpace.md))
                                OutputLog(
                                    events = events,
                                    onClear = { vm.clearLog() },
                                    modifier = Modifier.fillMaxWidth().height(320.dp),
                                )
                            }
                        }

                        ShellPage.SETTINGS -> SettingsPage(vm = vm, ui = ui)
                    }
                }
            }
        }

        // ── Pending confirmation dialog (08-security.md §5) ─────────────
        // The run is suspended on a ConfirmationNeeded event until the user
        // approves or denies the command.
        ui.pendingConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = { vm.respondConfirmation(false) },
                title = {
                    val recordId = ui.inspectionRecordId
                    Text(if (recordId != null) "Export inspection $recordId?" else "Approve action?")
                },
                text = {
                    if (ui.inspectionRecordId != null) {
                        Text("A text file will be saved in MCOS private storage.")
                    } else Column {
                        Text(
                            confirmation.commandId,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(_root_ide_package_.androidx.compose.ui.Modifier.Companion.height(_root_ide_package_.com.morainet.mcos.android.demo.McosSpace.sm))
                        Text(confirmation.reason, style = MaterialTheme.typography.bodyMedium)
                        confirmation.sideEffectClass?.let { risk ->
                            Spacer(_root_ide_package_.androidx.compose.ui.Modifier.Companion.height(_root_ide_package_.com.morainet.mcos.android.demo.McosSpace.md))
                            Text(
                                "Risk level: $risk",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (risk == "destructive") _root_ide_package_.com.morainet.mcos.android.demo.McosColor.danger else _root_ide_package_.com.morainet.mcos.android.demo.McosColor.warn,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.respondConfirmation(true) }) {
                        Text(if (ui.inspectionRecordId != null) "Export" else "Allow")
                    }
                },
                dismissButton = { TextButton(onClick = { vm.respondConfirmation(false) }) { Text("Deny") } },
            )
        }

        // ── Agent plan approval (06-agent.md §11 PlanReady) ─────────────
        // The multi-turn Agent loop staged a final plan after its probes;
        // only the user's Allow here executes it (reads already ran during
        // probing; everything beyond read waits for this decision).
        ui.pendingAgentPlan?.let { preview ->
            AlertDialog(
                onDismissRequest = { vm.resumeAgentTurn(false) },
                title = { Text("Approve agent plan?") },
                text = {
                    Column {
                        Text("The agent staged this plan:", style = MaterialTheme.typography.bodyMedium)
                        Spacer(_root_ide_package_.androidx.compose.ui.Modifier.Companion.height(_root_ide_package_.com.morainet.mcos.android.demo.McosSpace.sm))
                        Text(
                            preview,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { vm.resumeAgentTurn(true) }) { Text("Allow") } },
                dismissButton = { TextButton(onClick = { vm.resumeAgentTurn(false) }) { Text("Deny") } },
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
                        Spacer(_root_ide_package_.androidx.compose.ui.Modifier.Companion.height(_root_ide_package_.com.morainet.mcos.android.demo.McosSpace.md))
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
                                    "destructive" -> _root_ide_package_.com.morainet.mcos.android.demo.McosColor.danger
                                    "elevated" -> _root_ide_package_.com.morainet.mcos.android.demo.McosColor.warn
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
                                        color = _root_ide_package_.com.morainet.mcos.android.demo.McosColor.fgDim,
                                    )
                                }
                            }
                        }
                        if (meta.commandsPreview.isNotEmpty()) {
                            Spacer(_root_ide_package_.androidx.compose.ui.Modifier.Companion.height(_root_ide_package_.com.morainet.mcos.android.demo.McosSpace.md))
                            Text(
                                "Commands: " + meta.commandsPreview.joinToString(", "),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(_root_ide_package_.androidx.compose.ui.Modifier.Companion.height(_root_ide_package_.com.morainet.mcos.android.demo.McosSpace.md))
                        Text(
                            "The artifact is verified (SHA-256 + publisher signature) before activation.",
                            style = MaterialTheme.typography.labelSmall,
                            color = _root_ide_package_.com.morainet.mcos.android.demo.McosColor.fgDim,
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

/** The shell tabs (bottom navigation, ≤5 items). */
enum class ShellPage(val title: String, val icon: ImageVector) {
    AGENT("Agent", Icons.AutoMirrored.Filled.Chat),
    SKILLS("Skills", Icons.Default.Extension),
    MCP("MCP", Icons.Default.Hub),
    TOOLS("Capabilities", Icons.Default.Extension),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
private fun McosNavBar(page: ShellPage, onSelect: (ShellPage) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = McosColor.border)
            NavigationBar(
                modifier = Modifier.navigationBarsPadding().height(68.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                windowInsets = WindowInsets(0),
            ) {
                listOf(ShellPage.AGENT, ShellPage.TOOLS, ShellPage.SETTINGS).forEach { item ->
                    val selected = page == item ||
                        (item == ShellPage.TOOLS && (page == ShellPage.SKILLS || page == ShellPage.MCP))
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelect(item) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.title) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        }
    }
}
