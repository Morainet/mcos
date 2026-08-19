package com.morainet.mcos.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * MCOS design tokens — a single source of truth for the shell's terminal /
 * OLED aesthetic. Components reference [McosColor] instead of raw hex so the
 * palette stays consistent and themeable (jetpack-compose "Design system"
 * guideline: centralized tokens, no hardcoded values).
 *
 * Semantic accents (not just Material roles):
 * - [accent]  terminal green — primary CTA, "run", success
 * - [info]    blue — AI / info / run-event markers
 * - [warn]    amber — elevated permissions / warnings
 * - [danger]  red — errors / destructive actions
 */
object McosColor {
    // Surfaces — OLED-leaning midnight, deepening toward the console.
    val bg = Color(0xFF0A0E14)
    val surface = Color(0xFF12161F)
    val surfaceAlt = Color(0xFF1A2029)
    val console = Color(0xFF05070B)
    val border = Color(0xFF232B36)

    // Text.
    val fg = Color(0xFFE6EDF3)
    val fgMuted = Color(0xFF9BA7B6)
    val fgDim = Color(0xFF6B7686)

    // Semantic accents.
    val accent = Color(0xFF3FD68B)
    val onAccent = Color(0xFF04160C)
    val info = Color(0xFF7CB7FF)
    val warn = Color(0xFFF2B45A)
    val danger = Color(0xFFF2645A)
    val onDanger = Color(0xFF1A0605)
    val success = accent
}

/** 4/8-based spacing rhythm. */
object McosSpace {
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
}

/** Corner-radius scale for cards, fields, and buttons. */
object McosRadius {
    val sm = 8.dp
    val md = 10.dp
    val lg = 12.dp
}

/**
 * Wraps content in the MCOS dark theme. The Material color roles are mapped
 * from [McosColor] so existing `MaterialTheme.colorScheme.*` usages pick up
 * the terminal palette automatically.
 */
@Composable
fun McosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = McosColor.accent,
            onPrimary = McosColor.onAccent,
            secondary = McosColor.info,
            onSecondary = McosColor.onAccent,
            tertiary = McosColor.warn,
            onTertiary = McosColor.onAccent,
            background = McosColor.bg,
            onBackground = McosColor.fg,
            surface = McosColor.surface,
            onSurface = McosColor.fg,
            surfaceVariant = McosColor.surfaceAlt,
            onSurfaceVariant = McosColor.fgMuted,
            outline = McosColor.border,
            error = McosColor.danger,
            onError = McosColor.onDanger,
        ),
        content = content,
    )
}
