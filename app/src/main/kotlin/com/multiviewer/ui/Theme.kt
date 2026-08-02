package com.multiviewer.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { DARK, LIGHT }

private data class ThemePalette(
    val background: Color, val surface: Color, val panel: Color, val border: Color,
    val dividerHighlight: Color, val dividerShadow: Color,
    val neonGreen: Color, val neonBlue: Color, val neonPurple: Color, val neonRed: Color, val neonYellow: Color,
    val textPrimary: Color, val textSecondary: Color, val textMuted: Color,
    val selection: Color,
    val frameTypeI: Color, val frameTypeP: Color, val frameTypeB: Color,
)

private val DarkPalette = ThemePalette(
    // Border was originally 0x30363D -- barely distinguishable from Background (0x1A1D22),
    // making panel/data separators hard to see. Brightened for real contrast while staying
    // clearly darker than DividerHighlight (0x3D444C), which needs to read as "lighter than
    // Border" for its raised-edge resize-handle effect.
    background = Color(0xFF1A1D22), surface = Color(0xFF242930), panel = Color(0xFF2A2F36), border = Color(0xFF3A414B),
    dividerHighlight = Color(0xFF3D444C), dividerShadow = Color(0xFF0D0F12),
    neonGreen = Color(0xFF39FF14), neonBlue = Color(0xFF00F3FF), neonPurple = Color(0xFFBC13FE), neonRed = Color(0xFFFF3131), neonYellow = Color(0xFFFFF01F),
    textPrimary = Color(0xFFC9D1D9), textSecondary = Color(0xFF8B949E), textMuted = Color(0xFF484F58),
    selection = Color(0xFF264F78),
    frameTypeI = Color(0xFFE06C75), frameTypeP = Color(0xFF7EC699), frameTypeB = Color(0xFF6CA6E0),
)

private val LightPalette = ThemePalette(
    background = Color(0xFFFFFFFF), surface = Color(0xFFF3F4F6), panel = Color(0xFFECEEF1), border = Color(0xFFD0D3D8),
    dividerHighlight = Color(0xFFFFFFFF), dividerShadow = Color(0xFFB8BCC2),
    neonGreen = Color(0xFF1A7F37), neonBlue = Color(0xFF0969DA), neonPurple = Color(0xFF8250DF), neonRed = Color(0xFFCF222E), neonYellow = Color(0xFF9A6700),
    textPrimary = Color(0xFF1A1D22), textSecondary = Color(0xFF57606A), textMuted = Color(0xFF8B949E),
    selection = Color(0xFFCFE3FA),
    frameTypeI = Color(0xFFC53030), frameTypeP = Color(0xFF2F855A), frameTypeB = Color(0xFF2B6CB0),
)

private val LocalThemePalette = staticCompositionLocalOf { DarkPalette }

object AppColors {
    val Background: Color @Composable get() = LocalThemePalette.current.background
    val Surface: Color @Composable get() = LocalThemePalette.current.surface
    val Panel: Color @Composable get() = LocalThemePalette.current.panel
    val Border: Color @Composable get() = LocalThemePalette.current.border

    // A flat single-color divider line read as flat on the dark background -- these two, used
    // together (highlight on the side facing the notional light source, shadow on the other),
    // give panel-resize handles a raised-ridge look instead: lighter than Border catches light,
    // darker than Background casts a shadow.
    val DividerHighlight: Color @Composable get() = LocalThemePalette.current.dividerHighlight
    val DividerShadow: Color @Composable get() = LocalThemePalette.current.dividerShadow

    val NeonGreen: Color @Composable get() = LocalThemePalette.current.neonGreen
    val NeonBlue: Color @Composable get() = LocalThemePalette.current.neonBlue
    val NeonPurple: Color @Composable get() = LocalThemePalette.current.neonPurple
    val NeonRed: Color @Composable get() = LocalThemePalette.current.neonRed
    val NeonYellow: Color @Composable get() = LocalThemePalette.current.neonYellow

    val TextPrimary: Color @Composable get() = LocalThemePalette.current.textPrimary
    val TextSecondary: Color @Composable get() = LocalThemePalette.current.textSecondary
    val TextMuted: Color @Composable get() = LocalThemePalette.current.textMuted

    val Selection: Color @Composable get() = LocalThemePalette.current.selection
    // Marks the byte range for whatever node is selected in the structure tree (HexView) --
    // NeonBlue instead of the previous mustard/gold, both to read more clearly on the dark
    // background and to stay visually distinct from HexView's own manual drag-selection
    // highlight, which is green.
    val Highlight: Color @Composable get() = NeonBlue.copy(alpha = 0.35f)

    // Frame-type legend/bar colors for GopAnalysisView -- a separate muted palette from the Neon
    // accents above (neon reads fine for a single small badge but is overwhelming across a wide
    // row of adjacent bars), promoted here (rather than staying as GopAnalysisView-local
    // constants) so they can be theme-aware via the same CompositionLocal-backed mechanism.
    val FrameTypeI: Color @Composable get() = LocalThemePalette.current.frameTypeI
    val FrameTypeP: Color @Composable get() = LocalThemePalette.current.frameTypeP
    val FrameTypeB: Color @Composable get() = LocalThemePalette.current.frameTypeB
}

val AppTypography: Typography
    @Composable get() = Typography(
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.5.sp,
            color = AppColors.TextPrimary
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = AppColors.TextSecondary
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = AppColors.TextPrimary
        )
    )

// Compose Desktop's default ScrollbarStyle uses a low-alpha black thumb, tuned for a light
// background -- against this app's dark panels it's essentially invisible. Provided app-wide via
// LocalScrollbarStyle in Main.kt so every scrollbar (GOP graph, summary panels, etc.) is visible.
val AppScrollbarStyle: ScrollbarStyle
    @Composable get() = ScrollbarStyle(
        minimalHeight = 16.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300,
        unhoverColor = AppColors.TextMuted.copy(alpha = 0.6f),
        hoverColor = AppColors.TextSecondary,
    )

// Wraps app content with the chosen theme's palette (available to AppColors/AppTypography/
// AppScrollbarStyle via LocalThemePalette) and the matching Material3 color scheme. Replaces
// Main.kt's previous direct `MaterialTheme(colorScheme = darkColorScheme(...), ...)` call.
@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val palette = if (mode == ThemeMode.LIGHT) LightPalette else DarkPalette
    CompositionLocalProvider(LocalThemePalette provides palette) {
        val colorScheme = if (mode == ThemeMode.LIGHT) {
            lightColorScheme(background = AppColors.Background)
        } else {
            darkColorScheme(background = AppColors.Background)
        }
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography) {
            content()
        }
    }
}
