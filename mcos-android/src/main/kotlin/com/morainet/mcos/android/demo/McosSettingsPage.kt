package com.morainet.mcos.android.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Multi-vendor API settings page (多厂商设置页). Every vendor preset is a card:
 * a radio selects which one chat/agent use; expanding reveals the API key
 * (masked, with SHOW/HIDE), model (with suggestion chips), and endpoint
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
        Text(
            "LLM 厂商 · API Providers",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(McosSpace.xs))
        Text(
            "Select a provider and enter its API key. Each vendor's key and model are stored separately.",
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgMuted,
        )
        Spacer(Modifier.height(McosSpace.md))

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
    var expanded by remember { mutableStateOf(false) }
    var revealKey by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(McosRadius.lg),
    ) {
        Column(Modifier.padding(McosSpace.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text(
                        statusLine(row, healthy, healthError),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (healthy) {
                            true -> McosColor.success
                            false -> McosColor.danger
                            else -> McosColor.fgDim
                        },
                    )
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = McosSpace.md),
                ) {
                    Text(if (expanded) "HIDE" else "EDIT", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (expanded) {
                Spacer(Modifier.height(McosSpace.sm))
                // API key (masked) + reveal toggle.
                OutlinedTextField(
                    value = row.apiKey,
                    onValueChange = onKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API key" + if (row.vendor.keyOptional) " (optional)" else "") },
                    placeholder = { Text(row.vendor.keyHint) },
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { revealKey = !revealKey }) {
                            Text(if (revealKey) "HIDE" else "SHOW", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    shape = RoundedCornerShape(McosRadius.sm),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(McosSpace.sm))
                // Model + suggestion chips.
                OutlinedTextField(
                    value = row.model,
                    onValueChange = onModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Model") },
                    placeholder = { Text(row.vendor.defaultModel.ifBlank { "model name" }) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    shape = RoundedCornerShape(McosRadius.sm),
                    colors = fieldColors(),
                )
                if (row.vendor.models.isNotEmpty()) {
                    Spacer(Modifier.height(McosSpace.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(McosSpace.sm)) {
                        row.vendor.models.forEach { m ->
                            FilterChip(
                                selected = row.model == m,
                                onClick = { onModelChange(m) },
                                label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(McosSpace.sm))
                // Endpoint (editable; required for custom).
                OutlinedTextField(
                    value = row.endpoint,
                    onValueChange = onEndpointChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Endpoint") },
                    placeholder = { Text("https://…/v1/chat/completions") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    ),
                    shape = RoundedCornerShape(McosRadius.sm),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(McosSpace.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onProbe,
                        enabled = !probing && row.usable,
                        contentPadding = PaddingValues(horizontal = McosSpace.md),
                    ) {
                        if (probing) {
                            CircularProgressIndicator(
                                Modifier.size(12.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(McosSpace.sm))
                        }
                        Text(if (probing) "TESTING…" else "TEST", style = MaterialTheme.typography.labelSmall)
                    }
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

private fun statusLine(row: LlmVendorUi, healthy: Boolean?, healthError: String?): String = when {
    healthy == true -> "ready · ${row.model}"
    healthy == false -> "down: ${healthError ?: "probe failed"}"
    row.apiKey.isNotBlank() || row.vendor.keyOptional -> "configured · ${row.model}"
    else -> "no key"
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = McosColor.border,
    cursorColor = MaterialTheme.colorScheme.primary,
)
