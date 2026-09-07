package com.morainet.mcos.android.demo

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Skills page (技能页). Imports Claude-style skill packages — name/description/
 * instructions blocks that steer the planner/agent for specific kinds of
 * request. A skill adds no commands; it is prompt-level guidance folded into
 * the LLM system prompt. Paste a `SKILL.md` (YAML frontmatter + body) or a JSON
 * object, then toggle skills on/off; enabled skills reach the next chat/agent
 * turn.
 */
@Composable
internal fun SkillsPage(
    vm: McosViewModel,
    ui: McosUiState,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Skills · 技能包",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(McosSpace.xs))
        Text(
            "Import a SKILL.md or JSON skill. Enabled skills are added to the LLM prompt to guide " +
                "how it plans — they never add new commands.",
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgMuted,
        )
        Spacer(Modifier.height(McosSpace.md))

        // ── Import form ──────────────────────────────────────────────────
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(McosRadius.md),
        ) {
            Column(Modifier.padding(McosSpace.lg)) {
                Text(
                    "Import skill",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(McosSpace.sm))
                OutlinedTextField(
                    value = ui.skillImportText,
                    onValueChange = { vm.onSkillImportTextChange(it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 180.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    ),
                    placeholder = {
                        Text(
                            "---\nname: My Skill\ndescription: when to use it\n---\nInstructions…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    shape = RoundedCornerShape(McosRadius.sm),
                    colors = fieldColorsSkills(),
                )
                Spacer(Modifier.height(McosSpace.sm))
                Button(
                    onClick = { vm.importSkill() },
                    enabled = ui.skillImportText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("Import") }
            }
        }

        Spacer(Modifier.height(McosSpace.md))

        // ── Skill list ───────────────────────────────────────────────────
        if (ui.skills.isEmpty()) {
            Text(
                "No skills imported yet.",
                style = MaterialTheme.typography.labelSmall,
                color = McosColor.fgDim,
            )
        }
        ui.skills.forEach { skill ->
            SkillCard(
                skill = skill,
                onToggle = { vm.setSkillEnabled(skill.id, it) },
                onRemove = { vm.removeSkill(skill.id) },
            )
            Spacer(Modifier.height(McosSpace.md))
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillUi,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (skill.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(McosRadius.lg),
    ) {
        Column(Modifier.padding(McosSpace.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (skill.description.isNotBlank()) {
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = McosColor.fgMuted,
                        )
                    }
                }
                Switch(checked = skill.enabled, onCheckedChange = onToggle)
                TextButton(
                    onClick = onRemove,
                    contentPadding = PaddingValues(horizontal = McosSpace.sm),
                ) {
                    Text("REMOVE", style = MaterialTheme.typography.labelSmall, color = McosColor.danger)
                }
            }
            if (skill.instructions.isNotBlank()) {
                Spacer(Modifier.height(McosSpace.sm))
                Text(
                    skill.instructions.take(240) + if (skill.instructions.length > 240) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = McosColor.fgDim,
                )
            }
        }
    }
}

@Composable
private fun fieldColorsSkills() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = McosColor.border,
    cursorColor = MaterialTheme.colorScheme.primary,
)
