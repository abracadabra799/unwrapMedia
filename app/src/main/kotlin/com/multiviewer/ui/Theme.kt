package com.multiviewer.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AppColors {
    val Background = Color(0xFF12151A)
    val Surface = Color(0xFF1C2128)
    val Panel = Color(0xFF22272E)
    val Border = Color(0xFF30363D)
    
    val NeonGreen = Color(0xFF39FF14)
    val NeonBlue = Color(0xFF00F3FF)
    val NeonPurple = Color(0xFFBC13FE)
    val NeonRed = Color(0xFFFF3131)
    val NeonYellow = Color(0xFFFFF01F)

    val BadgeAmber = Color(0xFFFFB74D)    // Container
    val BadgeTeal = Color(0xFF4DD0C4)     // Table data
    val BadgeLavender = Color(0xFFCE93D8) // Image/grid data
    val BadgeSky = Color(0xFF64B5F6)      // Metadata / leaf (catch-all)

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
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp,
        color = AppColors.TextPrimary
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = AppColors.TextSecondary
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = AppColors.TextPrimary
    )
)
