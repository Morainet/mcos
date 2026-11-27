package com.morainet.mcos.android.demo.settings

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import com.morainet.mcos.android.demo.shell.LlmVendorUi
import com.morainet.mcos.android.demo.shell.McosUiState
import com.morainet.mcos.android.demo.shell.McosViewModel
import com.morainet.mcos.android.demo.ui.PageHeader
import com.morainet.mcos.android.demo.ui.QuietField
import com.morainet.mcos.android.demo.ui.StatusDot

/**
 * Multi-vendor API settings page (多厂商设置页). Every vendor preset is a card:
 * a radio selects which one chat/agent use; expanding reveals the API key
 * (masked, with an eye toggle), model (with suggestion chips), and endpoint
 * fields, plus a per-vendor TEST (probe) button. Keys, models, and endpoints
 * are persisted per-vendor by [McosViewModel]; switching the radio genuinely
 * changes which backend the conversation hits.
 */
@Composable
internal fun SettingsPage(
    vm: McosViewModel,
    ui: McosUiState,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader(
            icon = Icons.Default.Key,
            tint = MaterialTheme.colorScheme.primary,
            title = "API Providers",
            description = "Pick the vendor chat uses; each keeps its own key, model and endpoint on-device.",
        )
        ui.vendors.forEach { row ->
            val health = ui.providerHealth.firstOrNull { it.providerId == row.vendor.id }
            VendorCard(
                row = row,
                selected = ui.selectedVendorId == row.vendor.id,
                probing = ui.probing,
                healthy = health?.healthy,
                healthError = health?.errorCode,
                onSelect = { vm.selectVendor(row.vendor.id) },
                onKeyChange = { vm.onVendorKeyChange(row.vendor.id, it) },
                onModelChange = { vm.onVendorModelChange(row.vendor.id, it) },
                onEndpointChange = { vm.onVendorEndpointChange(row.vendor.id, it) },
                onProbe = { vm.refreshProbe() },
            )
            Spacer(Modifier.height(McosSpace.md))
        }
        Spacer(Modifier.height(McosSpace.xl))
    }
}

@Composable
private fun VendorCard(
    row: LlmVendorUi,
    selected: Boolean,
    probing: Boolean,
    healthy: Boolean?,
    healthError: String?,
    onSelect: () -> Unit,
    onKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onProbe: () -> Unit,
) {
    var expanded by remember { mutableStateOf(selected && row.apiKey.isBlank()) }
    var revealKey by remember { mutableStateOf(false) }

    Surface(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(McosRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) McosColor.border else McosColor.borderSoft,
        ),
    ) {
        Column(Modifier.padding(McosSpace.xl)) {
            Row(
                Modifier.fillMaxWidth().clickable { onSelect() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        row.vendor.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    val (dot, statusText) = when {
                        healthy == true -> McosColor.success to "ready · ${row.model}"
                        healthy == false -> McosColor.danger to "down · ${healthError ?: "probe failed"}"
                        row.apiKey.isNotBlank() || row.vendor.keyOptional -> McosColor.info to "configured · ${row.model}"
                        else -> McosColor.fgDim to "no key"
                    }
                    StatusDot(color = dot, text = statusText)
                }
                Text(
                    if (expanded) "Hide" else "Edit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(horizontal = McosSpace.lg, vertical = McosSpace.md),
                )
            }

            if (expanded) {
                Spacer(Modifier.height(McosSpace.lg))
                FieldLabel("API key" + if (row.vendor.keyOptional) " · optional" else "")
                QuietField(
                    value = row.apiKey,
                    onValueChange = onKeyChange,
                    placeholder = row.vendor.keyHint,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealKey) "Hide key" else "Show key",
                                tint = McosColor.fgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
                Spacer(Modifier.height(McosSpace.md))
                FieldLabel("Model")
                QuietField(
                    value = row.model,
                    onValueChange = onModelChange,
                    placeholder = row.vendor.defaultModel.ifBlank { "model name" },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (row.vendor.models.isNotEmpty()) {
                    Spacer(Modifier.height(McosSpace.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(McosSpace.sm)) {
                        row.vendor.models.forEach { m ->
                            FilterChip(
                                selected = row.model == m,
                                onClick = { onModelChange(m) },
                                label = {
                                    Text(m, style = MaterialTheme.typography.labelSmall)
                                },
                                shape = RoundedCornerShape(McosRadius.pill),
                                border = BorderStroke(1.dp, McosColor.border),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = McosColor.onAccent,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(McosSpace.md))
                FieldLabel("Endpoint")
                QuietField(
                    value = row.endpoint,
                    onValueChange = onEndpointChange,
                    placeholder = "https://…/v1/chat/completions",
                    modifier = Modifier.fillMaxWidth(),
                    mono = true,
                )
                Spacer(Modifier.height(McosSpace.lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onProbe,
                        enabled = !probing && row.usable,
                        shape = RoundedCornerShape(McosRadius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        if (probing) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp), strokeWidth = 2.dp,
                                color = McosColor.onAccent,
                            )
                            Spacer(Modifier.width(McosSpace.sm))
                            Text("Testing…")
                        } else {
                            Icon(
                                Icons.Default.CheckCircle, contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(McosSpace.xs))
                            Text("Test")
                        }
                    }
                    Spacer(Modifier.width(McosSpace.lg))
                    if (!row.usable) {
                        Text(
                            if (row.vendor.custom) "enter endpoint + model + key" else "enter an API key",
                            style = MaterialTheme.typography.labelSmall,
                            color = McosColor.fgDim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = McosColor.fgMuted,
        modifier = Modifier.padding(bottom = McosSpace.sm, start = McosSpace.xs),
    )
}
