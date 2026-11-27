package com.morainet.mcos.android.demo.mcp

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.shell.McosUiState
import com.morainet.mcos.android.demo.shell.McosViewModel
import com.morainet.mcos.android.demo.shell.McpServerUi
import com.morainet.mcos.android.demo.shell.McpToolUi
import com.morainet.mcos.android.demo.ui.IconBadge
import com.morainet.mcos.android.demo.ui.PageHeader
import com.morainet.mcos.android.demo.ui.QuietField
import com.morainet.mcos.android.demo.ui.SectionLabel
import com.morainet.mcos.android.demo.ui.StatusDot

/**
 * MCP page (MCP 服务器). Manages user-configured MCP servers (04 §10 per-server
 * enablement, refined to per-tool): each server toggles on (discover + register
 * its selected `mcp.<id>.*` commands) or off, expands to show its discovered
 * tools for per-tool enable/disable, and can be removed. A paste box imports a
 * standard `mcp.json` (`mcpServers` map) in bulk. Tokens go to SecureStore,
 * never into the bridged config (04 §11.1 / 10 §6.2).
 */
@Composable
internal fun McpPage(
    vm: McosViewModel,
    ui: McosUiState,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader(
            icon = Icons.Default.Hub,
            tint = McosColor.info,
            title = "MCP Servers",
            description = "Bridge external tools as commands. Enable a server, then pick which of its tools to expose.",
        )

        // ── Configured servers ───────────────────────────────────────────
        if (ui.mcpServers.isEmpty()) {
            EmptyServers()
        } else {
            SectionLabel("Servers")
        }
        ui.mcpServers.forEach { server ->
            McpServerCard(vm, server)
            Spacer(Modifier.height(McosSpace.md))
        }

        Spacer(Modifier.height(McosSpace.xl))

        // ── Add server form ──────────────────────────────────────────────
        SectionLabel("Add server")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
        ) {
            QuietField(
                value = ui.mcpNewId,
                onValueChange = { vm.onMcpNewIdChange(it) },
                placeholder = "id · github",
                modifier = Modifier.width(128.dp),
            )
            QuietField(
                value = ui.mcpNewEndpoint,
                onValueChange = { vm.onMcpNewEndpointChange(it) },
                placeholder = "https://…/mcp",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(McosSpace.md))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietField(
                value = ui.mcpNewToken,
                onValueChange = { vm.onMcpNewTokenChange(it) },
                placeholder = "bearer token (optional)",
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { vm.addMcpServer() },
                enabled = !ui.mcpBusy && ui.mcpNewId.isNotBlank() && ui.mcpNewEndpoint.isNotBlank(),
                shape = RoundedCornerShape(McosRadius.md),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(McosSpace.xs))
                Text("Add")
            }
        }

        Spacer(Modifier.height(McosSpace.xl))

        // ── Import mcp.json ──────────────────────────────────────────────
        SectionLabel("Import mcp.json")
        QuietField(
            value = ui.mcpImportText,
            onValueChange = { vm.onMcpImportTextChange(it) },
            placeholder = "{\n  \"mcpServers\": {\n    \"github\": { \"url\": \"https://…/mcp\" }\n  }\n}",
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            mono = true,
            minLinesHeight = 120,
        )
        Spacer(Modifier.height(McosSpace.md))
        Button(
            onClick = { vm.importMcpJson() },
            enabled = ui.mcpImportText.isNotBlank(),
            shape = RoundedCornerShape(McosRadius.md),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("Import") }
        Text(
            "HTTP servers only — stdio (command) entries are skipped.",
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgDim,
            modifier = Modifier.padding(top = McosSpace.sm, bottom = McosSpace.xl),
        )
    }
}

@Composable
private fun EmptyServers() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = McosSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = Icons.Default.Dns, tint = McosColor.fgDim, size = 48, iconSize = 24)
        Spacer(Modifier.height(McosSpace.lg))
        Text("No servers yet", style = MaterialTheme.typography.titleMedium, color = McosColor.fgMuted)
        Spacer(Modifier.height(McosSpace.xs))
        Text(
            "Add one below, or paste an mcp.json to import in bulk.",
            style = MaterialTheme.typography.bodySmall,
            color = McosColor.fgDim,
        )
    }
}

/** One server card: identity, status, enable switch, delete, expandable tools. */
@Composable
private fun McpServerCard(
    vm: McosViewModel,
    server: McpServerUi,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(McosRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, McosColor.borderSoft),
    ) {
        Column(Modifier.padding(McosSpace.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Default.Dns, tint = McosColor.info)
                Spacer(Modifier.width(McosSpace.lg))
                Column(Modifier.weight(1f)) {
                    Text(
                        server.id,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    val (dot, statusText) = when {
                        server.busy -> McosColor.info to "connecting…"
                        server.enabled && server.status?.startsWith("on") == true ->
                            McosColor.success to (server.status ?: "online")
                        server.enabled -> McosColor.warn to (server.status ?: "connecting…")
                        else -> McosColor.fgDim to "off"
                    }
                    StatusDot(color = dot, text = statusText)
                }
                if (server.busy) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(McosSpace.sm))
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = { vm.setMcpServerEnabled(server.id, it) },
                    enabled = !server.busy,
                )
                IconButton(
                    onClick = { vm.removeMcpServer(server.id) },
                    enabled = !server.busy,
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Remove ${server.id}",
                        tint = McosColor.fgDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (server.tools.isNotEmpty()) {
                Spacer(Modifier.height(McosSpace.md))
                Surface(
                    shape = RoundedCornerShape(McosRadius.md),
                    color = McosColor.surfaceAlt,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = McosSpace.lg, vertical = McosSpace.md)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(vertical = McosSpace.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = if (expanded) "Collapse" else "Expand",
                                tint = McosColor.fgMuted,
                                modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                            )
                            Spacer(Modifier.width(McosSpace.md))
                            Text(
                                "${server.tools.size} tools",
                                style = MaterialTheme.typography.labelMedium,
                                color = McosColor.fgMuted,
                            )
                        }
                        if (expanded) {
                            server.tools.forEach { tool ->
                                McpToolRow(
                                    tool = tool,
                                    busy = server.busy,
                                    onToggle = { vm.setMcpToolEnabled(server.id, tool.name, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolRow(
    tool: McpToolUi,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = McosSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tool.name + if (!tool.mapped) "  ·  unmappable" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (tool.mapped) MaterialTheme.colorScheme.onSurface else McosColor.fgDim,
            )
            tool.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = McosColor.fgDim, maxLines = 2)
            }
        }
        Switch(
            checked = tool.enabled && tool.mapped,
            onCheckedChange = onToggle,
            enabled = tool.mapped && !busy,
        )
    }
}
