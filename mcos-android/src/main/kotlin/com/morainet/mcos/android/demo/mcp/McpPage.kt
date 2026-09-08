package com.morainet.mcos.android.demo.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        Text(
            "MCP Servers",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(McosSpace.xs))
        Text(
            "Bridge Model Context Protocol servers. Enable a server, then expand it to pick which " +
                "tools to expose as commands.",
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgMuted,
        )
        Spacer(Modifier.height(McosSpace.md))

        // ── Configured servers ───────────────────────────────────────────
        if (ui.mcpServers.isEmpty()) {
            Text(
                "No servers configured — add one or import an mcp.json below.",
                style = MaterialTheme.typography.labelSmall,
                color = McosColor.fgDim,
            )
        }
        ui.mcpServers.forEach { server ->
            McpServerCardExpandable(vm, server)
            Spacer(Modifier.height(McosSpace.md))
        }

        // ── Add server form ──────────────────────────────────────────────
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(McosRadius.md),
        ) {
            Column(Modifier.padding(McosSpace.lg)) {
                Text(
                    "Add server",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(McosSpace.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
                ) {
                    McpField(
                        value = ui.mcpNewId,
                        onValueChange = { vm.onMcpNewIdChange(it) },
                        placeholder = "id, e.g. github",
                        modifier = Modifier.width(120.dp),
                    )
                    McpField(
                        value = ui.mcpNewEndpoint,
                        onValueChange = { vm.onMcpNewEndpointChange(it) },
                        placeholder = "https://…/mcp",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(McosSpace.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(McosSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { vm.addMcpServer() },
                        enabled = !ui.mcpBusy && ui.mcpNewId.isNotBlank() && ui.mcpNewEndpoint.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text("Add") }
                    McpField(
                        value = ui.mcpNewToken,
                        onValueChange = { vm.onMcpNewTokenChange(it) },
                        placeholder = "bearer token (optional)",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(McosSpace.md))

        // ── Import mcp.json ──────────────────────────────────────────────
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(McosRadius.md),
        ) {
            Column(Modifier.padding(McosSpace.lg)) {
                Text(
                    "Import mcp.json",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(McosSpace.sm))
                OutlinedTextField(
                    value = ui.mcpImportText,
                    onValueChange = { vm.onMcpImportTextChange(it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    ),
                    placeholder = {
                        Text(
                            "{\n  \"mcpServers\": {\n    \"github\": { \"url\": \"https://…/mcp\" }\n  }\n}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    shape = RoundedCornerShape(McosRadius.sm),
                    colors = mcpFieldColors(),
                )
                Spacer(Modifier.height(McosSpace.sm))
                Button(
                    onClick = { vm.importMcpJson() },
                    enabled = ui.mcpImportText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("Import") }
                Text(
                    "HTTP servers only — stdio (command) entries are skipped.",
                    style = MaterialTheme.typography.labelSmall,
                    color = McosColor.fgDim,
                    modifier = Modifier.padding(top = McosSpace.xs),
                )
            }
        }
    }
}

/** One server row with an expandable tool list for per-tool enablement. */
@Composable
private fun McpServerCardExpandable(
    vm: McosViewModel,
    server: McpServerUi,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(McosRadius.lg),
    ) {
        Column(Modifier.padding(McosSpace.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        server.id,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        server.status ?: server.endpoint,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            server.status?.startsWith("on") == true -> MaterialTheme.colorScheme.secondary
                            server.status != null && server.status != "off" -> McosColor.danger
                            else -> McosColor.fgDim
                        },
                    )
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
                TextButton(
                    onClick = { vm.removeMcpServer(server.id) },
                    enabled = !server.busy,
                    contentPadding = PaddingValues(horizontal = McosSpace.sm),
                ) {
                    Text("REMOVE", style = MaterialTheme.typography.labelSmall, color = McosColor.danger)
                }
            }
            if (server.tools.isNotEmpty()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(
                        (if (expanded) "▾ " else "▸ ") + "${server.tools.size} tools",
                        style = MaterialTheme.typography.labelSmall,
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
                tool.name + if (!tool.mapped) "  (unmappable)" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (tool.mapped) MaterialTheme.colorScheme.onSurface else McosColor.fgDim,
            )
            tool.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = McosColor.fgDim)
            }
        }
        Switch(
            checked = tool.enabled && tool.mapped,
            onCheckedChange = onToggle,
            enabled = tool.mapped && !busy,
        )
    }
}

/** Shared compact text field used by the MCP add form. */
@Composable
internal fun McpField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        placeholder = { Text(placeholder) },
        shape = RoundedCornerShape(McosRadius.sm),
        colors = mcpFieldColors(),
    )
}

@Composable
private fun mcpFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = McosColor.border,
    cursorColor = MaterialTheme.colorScheme.primary,
)
