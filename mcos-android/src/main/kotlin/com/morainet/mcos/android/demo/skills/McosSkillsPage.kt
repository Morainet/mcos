package com.morainet.mcos.android.demo.skills

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.morainet.mcos.android.demo.shell.SkillUi
import com.morainet.mcos.android.demo.ui.IconBadge
import com.morainet.mcos.android.demo.ui.PageHeader
import com.morainet.mcos.android.demo.ui.QuietField
import com.morainet.mcos.android.demo.ui.SectionLabel

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
        PageHeader(
            icon = Icons.Default.Extension,
            tint = MaterialTheme.colorScheme.primary,
            title = "Skills",
            description = "Prompt-level guidance for the planner — skills steer how goals are planned, they never add commands.",
        )

        if (ui.skills.isEmpty()) {
            EmptySkills()
        } else {
            SectionLabel("Imported")
            ui.skills.forEach { skill ->
                SkillCard(
                    skill = skill,
                    onToggle = { vm.setSkillEnabled(skill.id, it) },
                    onRemove = { vm.removeSkill(skill.id) },
                )
                Spacer(Modifier.height(McosSpace.md))
            }
            Spacer(Modifier.height(McosSpace.xl))
        }

        SectionLabel("Import skill")
        QuietField(
            value = ui.skillImportText,
            onValueChange = { vm.onSkillImportTextChange(it) },
            placeholder = "---\nname: My Skill\ndescription: when to use it\n---\nInstructions…",
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            mono = true,
            minLinesHeight = 132,
        )
        Spacer(Modifier.height(McosSpace.md))
        Button(
            onClick = { vm.importSkill() },
            enabled = ui.skillImportText.isNotBlank(),
            shape = RoundedCornerShape(McosRadius.md),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("Import") }
        Spacer(Modifier.height(McosSpace.xl))
    }
}

@Composable
private fun EmptySkills() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = McosSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = Icons.Default.Extension, tint = McosColor.fgDim, size = 48, iconSize = 24)
        Spacer(Modifier.height(McosSpace.lg))
        Text("No skills yet", style = MaterialTheme.typography.titleMedium, color = McosColor.fgMuted)
        Spacer(Modifier.height(McosSpace.xs))
        Text(
            "Paste a SKILL.md below to teach MCOS a new trick.",
            style = MaterialTheme.typography.bodySmall,
            color = McosColor.fgDim,
        )
        Spacer(Modifier.height(McosSpace.xl))
    }
}

@Composable
private fun SkillCard(
    skill: SkillUi,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(McosRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (skill.enabled) McosColor.border else McosColor.borderSoft,
        ),
    ) {
        Column(Modifier.padding(McosSpace.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = Icons.Default.Extension,
                    tint = if (skill.enabled) MaterialTheme.colorScheme.primary else McosColor.fgDim,
                )
                Spacer(Modifier.width(McosSpace.lg))
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
                            maxLines = 2,
                        )
                    }
                }
                Switch(checked = skill.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Remove ${skill.name}",
                        tint = McosColor.fgDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (skill.instructions.isNotBlank()) {
                Spacer(Modifier.height(McosSpace.md))
                Surface(
                    shape = RoundedCornerShape(McosRadius.md),
                    color = McosColor.surfaceAlt,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        skill.instructions.take(240) + if (skill.instructions.length > 240) "…" else "",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = McosColor.fgMuted,
                        modifier = Modifier.padding(horizontal = McosSpace.lg, vertical = McosSpace.md),
                    )
                }
            }
        }
    }
}
