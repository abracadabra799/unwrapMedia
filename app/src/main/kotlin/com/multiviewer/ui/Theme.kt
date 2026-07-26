package com.multiviewer.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppColors {
    val Background = Color(0xFF1A1D22)
    val Surface = Color(0xFF242930)
    val Panel = Color(0xFF2A2F36)
    val Border = Color(0xFF30363D)
    
    val NeonGreen = Color(0xFF39FF14)
    val NeonBlue = Color(0xFF00F3FF)
    val NeonPurple = Color(0xFFBC13FE)
    val NeonRed = Color(0xFFFF3131)
    val NeonYellow = Color(0xFFFFF01F)
    
    val TextPrimary = Color(0xFFC9D1D9)
    val TextSecondary = Color(0xFF8B949E)
    val TextMuted = Color(0xFF484F58)
    
    val Selection = Color(0xFF264F78)
    val Highlight = Color(0xFFD4BB00).copy(alpha = 0.4f)
}

val AppTypography = Typography(
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
val AppScrollbarStyle = ScrollbarStyle(
    minimalHeight = 16.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 300,
    unhoverColor = AppColors.TextMuted.copy(alpha = 0.6f),
    hoverColor = AppColors.TextSecondary,
)
