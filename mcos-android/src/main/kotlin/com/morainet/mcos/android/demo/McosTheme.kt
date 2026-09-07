package com.morainet.mcos.android.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * MCOS design tokens — a single source of truth for the shell's clean,
 * AI-native light aesthetic. Components reference [McosColor] instead of raw
 * hex so the palette stays consistent and themeable (jetpack-compose "Design
 * system" guideline: centralized tokens, no hardcoded values).
 *
 * Semantic accents (not just Material roles):
 * - [accent]  violet — primary CTA, "run", selection
 * - [info]    cyan — AI / info / run-event markers
 * - [warn]    amber — elevated permissions / warnings
 * - [danger]  red — errors / destructive actions
 * - [success] teal — success / healthy provider
 */
object McosColor {
    // Surfaces — soft violet-tinted light, cards float on a lavender wash.
    val bg = Color(0xFFFAF5FF)
    val surface = Color(0xFFFFFFFF)
    val surfaceAlt = Color(0xFFF4F0FB)
    val console = Color(0xFFF4F1FB)
    val border = Color(0xFFDDD6FE)

    // Text — deep indigo on light, never pure black.
    val fg = Color(0xFF1E1B4B)
    val fgMuted = Color(0xFF6E6A8F)
    val fgDim = Color(0xFF9C98B8)

    // Semantic accents.
    val accent = Color(0xFF7C3AED)
    val onAccent = Color(0xFFFFFFFF)
    val info = Color(0xFF0891B2)
    val warn = Color(0xFFB45309)
    val danger = Color(0xFFDC2626)
    val onDanger = Color(0xFFFFFFFF)
    val success = Color(0xFF0F766E)
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
    val md = 12.dp
    val lg = 16.dp
}

/**
 * Wraps content in the MCOS light theme. The Material color roles are mapped
 * from [McosColor] so existing `MaterialTheme.colorScheme.*` usages pick up
 * the AI-native palette automatically.
 */
@Composable
fun McosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = McosColor.accent,
            onPrimary = McosColor.onAccent,
            secondary = McosColor.info,
            onSecondary = McosColor.onAccent,
            tertiary = McosColor.accent,
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
