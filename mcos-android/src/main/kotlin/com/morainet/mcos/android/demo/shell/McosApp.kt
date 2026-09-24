package com.morainet.mcos.android.demo.shell

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.chat.ChatPage
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

/**
 * Shell screen: renders [McosViewModel] state and forwards user actions to it.
 * Owns no business state of its own — the only `remember`s here are the
 * activity-result bridge wiring and pure view-local visibility toggles.
 *
 * Structure: this composable is a thin shell. Each section is its own
 * component ([com.morainet.mcos.android.demo.tools.StatusBar], [com.morainet.mcos.android.demo.marketplace.MarketplaceCard], [AiChatCard], [McpServerCard],
 * [com.morainet.mcos.android.demo.tools.DslInputCard], [com.morainet.mcos.android.demo.tools.OutputLog]); the terminal palette lives in [com.morainet.mcos.android.demo.McosTheme] /
 * [com.morainet.mcos.android.demo.McosColor].
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

    // On-demand runtime-permission prompts (04 §6.3): a handler that hits a
    // missing runtime grant (e.g. sys.device.location) prompts in-app through
    // the bridge instead of pointing the user at system settings. Headless
    // runs never attach a prompter; the command degrades honestly.
    val promptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> deps.permissionBridge.onResult(granted) }
    LaunchedEffect(promptLauncher) { deps.permissionBridge.attach { promptLauncher.launch(it) } }

    var page by remember { mutableStateOf(ShellPage.CHAT) }

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
                            horizontal = _root_ide_package_.com.morainet.mcos.android.demo.McosSpace.lg,
                            vertical = _root_ide_package_.com.morainet.mcos.android.demo.McosSpace.md
                        )
                ) {
                    when (current) {
                        ShellPage.CHAT -> ChatPage(
                            vm = vm,
                            ui = ui,
                        )

                        ShellPage.SKILLS -> SkillsPage(vm = vm, ui = ui)
                        ShellPage.MCP -> McpPage(vm = vm, ui = ui)
                        ShellPage.TOOLS -> Column(Modifier.fillMaxSize().verticalScroll(_root_ide_package_.androidx.compose.foundation.rememberScrollState())) {
                            com.morainet.mcos.android.demo.ui.PageHeader(
                                icon = Icons.Default.Terminal,
                                tint = McosColor.warn,
                                title = "Tools",
                                description = "Run DSL directly, browse the marketplace, watch the raw console.",
                            )
                            StatusBar(ui = ui, show = showCommands, onToggle = { showCommands = !showCommands })
                            MarketplaceCard(
                                vm = marketVm,
                                ui = marketUi,
                                show = showMarketplace,
                                onToggle = { showMarketplace = !showMarketplace },
                                onInstallRequest = { pendingInstall = it },
                            )
                            DslInputCard(vm = vm, ui = ui)
                            Spacer(_root_ide_package_.androidx.compose.ui.Modifier.Companion.height(_root_ide_package_.com.morainet.mcos.android.demo.McosSpace.md))
                            // Scrollable page → fixed console height (weight(1f) is
                            // meaningless inside verticalScroll).
                            OutputLog(
                                events = events,
                                onClear = { vm.clearLog() },
                                modifier = Modifier.fillMaxWidth().height(320.dp),
                            )
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
                title = { Text("Approve action?") },
                text = {
                    Column {
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
                confirmButton = { TextButton(onClick = { vm.respondConfirmation(true) }) { Text("Allow") } },
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
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    SKILLS("Skills", Icons.Default.Extension),
    MCP("MCP", Icons.Default.Hub),
    TOOLS("Tools", Icons.Default.Terminal),
    SETTINGS("Settings", Icons.Default.Settings),
}

/**
 * Slim bottom navigation (mainstream app proportions): 64dp tall, no divider —
 * a soft shadow separates it from content. Selection feedback is a perfectly
 * proportioned capsule (58×30, radius 15) whose fill and the icon/label colors
 * cross-fade; there is deliberately no full-cell ripple — the animation is the
 * feedback.
 */
@Composable
private fun McosNavBar(page: ShellPage, onSelect: (ShellPage) -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ShellPage.entries.forEach { p ->
                val selected = page == p
                val tint by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else McosColor.fgMuted,
                    animationSpec = tween(durationMillis = 180),
                    label = "nav-tint",
                )
                val capsule by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) McosColor.accentSoft else androidx.compose.ui.graphics.Color.Transparent,
                    animationSpec = tween(durationMillis = 180),
                    label = "nav-capsule",
                )
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(p) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 58.dp, height = 30.dp)
                            .background(capsule, RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            p.icon,
                            contentDescription = p.title,
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        p.title,
                        fontSize = 10.sp,
                        letterSpacing = 0.3.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = tint,
                    )
                }
            }
        }
    }
}
