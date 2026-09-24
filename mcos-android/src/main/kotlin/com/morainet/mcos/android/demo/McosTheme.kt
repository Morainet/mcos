package com.morainet.mcos.android.demo

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val console = Color(0xFF17141F)
    val consoleDim = Color(0xFF241F31)

    // Console foregrounds — tuned for the dark console surface (the light-theme
    // semantic accents above are too dim on 0xFF17141F to stay legible).
    val consoleFg = Color(0xFFB9B3D6)
    val consoleFgDim = Color(0xFF8B85AD)
    val consoleInfo = Color(0xFF22D3EE)
    val consoleWarn = Color(0xFFFBBF24)
    val consoleDanger = Color(0xFFF87171)
    val consoleSuccess = Color(0xFF2DD4BF)
    val border = Color(0xFFDDD6FE)
    val borderSoft = Color(0xFFECE8F9)

    // Text — deep indigo on light, never pure black.
    val fg = Color(0xFF1E1B4B)
    val fgMuted = Color(0xFF6E6A8F)
    val fgDim = Color(0xFF9C98B8)

    // Semantic accents.
    val accent = Color(0xFF7C3AED)
    val accentDeep = Color(0xFF5B21B6)
    val accentSoft = Color(0xFFEDE9FE)
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
    val xxl = 20.dp
}

/** Corner-radius scale for cards, fields, and buttons. */
object McosRadius {
    val xs = 6.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 22.dp
    /** Fully-round — pills and the circular send button. */
    val pill = 50.dp
}

/**
 * Type scale on top of Material3 defaults — only the roles the shell restyles;
 * everything else inherits the platform defaults. Titles tighten up (the shell
 * is dense), labels keep a semibold where they act as section headers.
 */
private val McosTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.4.sp),
)

/** Shape scale mapped from [McosRadius] so Material components round consistently. */
private val McosShapes = Shapes(
    extraSmall = RoundedCornerShape(McosRadius.xs),
    small = RoundedCornerShape(McosRadius.sm),
    medium = RoundedCornerShape(McosRadius.md),
    large = RoundedCornerShape(McosRadius.lg),
    extraLarge = RoundedCornerShape(McosRadius.xl),
)

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
            primaryContainer = McosColor.accentSoft,
            onPrimaryContainer = McosColor.accentDeep,
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
        typography = McosTypography,
        shapes = McosShapes,
        content = content,
    )
}
