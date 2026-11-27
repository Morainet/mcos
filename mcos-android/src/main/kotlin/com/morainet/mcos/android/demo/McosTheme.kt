package com.morainet.mcos.android.demo

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic colors for the management screens and developer console.
 *
 * Semantic accents (not just Material roles):
 * - [accent]  forest green — primary CTA, "run", selection
 * - [info]    cyan — AI / info / run-event markers
 * - [warn]    amber — elevated permissions / warnings
 * - [danger]  red — errors / destructive actions
 */
object McosColor {
    val bg = Color(0xFFFAFBFA)
    val surface = Color(0xFFFFFFFF)
    val surfaceAlt = Color(0xFFE9EFEA)
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
    val border = Color(0xFFD4E0D7)

    val fg = Color(0xFF172721)
    val fgMuted = Color(0xFF486058)

    // Semantic accents.
    val accent = Color(0xFF1C624B)
    val accentDeep = Color(0xFF16382D)
    val accentSoft = Color(0xFFDCEEE2)
    val onAccent = Color(0xFFFFFFFF)
    val info = Color(0xFF0E6E88)
    val warn = Color(0xFFB45309)
    val danger = Color(0xFFDC2626)
    val onDanger = Color(0xFFFFFFFF)
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

internal val LocalMcosDarkTheme = staticCompositionLocalOf { false }

private val DarkWarning = Color(0xFFFFCF83)

internal val McosWarningColor: Color
    @Composable get() = if (LocalMcosDarkTheme.current) DarkWarning else McosColor.warn

private val McosDarkColors = darkColorScheme(
    primary = Color(0xFFC4F3D6),
    onPrimary = Color(0xFF12281C),
    primaryContainer = Color(0xFF192B24),
    onPrimaryContainer = Color(0xFFD6F7E3),
    secondary = Color(0xFF8AD8EB),
    tertiary = Color(0xFFC4F3D6),
    onTertiary = Color(0xFF12281C),
    background = Color(0xFF101114),
    onBackground = Color(0xFFF1F4F2),
    surface = Color(0xFF191B20),
    onSurface = Color(0xFFF1F4F2),
    surfaceVariant = Color(0xFF272A31),
    onSurfaceVariant = Color(0xFFB3B7C0),
    surfaceContainer = Color(0xFF1F2127),
    surfaceContainerHigh = Color(0xFF2C2F37),
    outline = Color(0xFF727883),
    outlineVariant = Color(0xFF363A43),
    error = Color(0xFFFFB4A9),
)

private val McosLightColors = lightColorScheme(
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
    surfaceContainer = Color(0xFFF0F3F1),
    surfaceContainerHigh = Color(0xFFE7EDE9),
    outline = Color(0xFF708078),
    outlineVariant = McosColor.border,
    error = McosColor.danger,
    onError = McosColor.onDanger,
)

@Composable
fun McosTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMcosDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) McosDarkColors else McosLightColors,
            typography = McosTypography,
            shapes = McosShapes,
            content = content,
        )
    }
}
