package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BoxBlockView(boxWeights: Map<String, Long>, modifier: Modifier = Modifier) {
    val totalSize = boxWeights.values.sum().coerceAtLeast(1L)
    
    Column(modifier = modifier.padding(8.dp)) {
        Text("Box Volume Treemap", style = AppTypography.labelLarge)
        Spacer(Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxSize().border(1.dp, AppColors.Border)) {
            boxWeights.forEach { (type, size) ->
                val weight = size.toDouble() / totalSize
                if (weight > 0.01) { // Only show boxes > 1%
                    val color = when (type) {
                        "mdat" -> AppColors.NeonGreen.copy(alpha = 0.3f)
                        "moov" -> AppColors.NeonPurple.copy(alpha = 0.3f)
                        else -> AppColors.NeonBlue.copy(alpha = 0.3f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(weight.toFloat())
                            .fillMaxHeight()
                            .background(color)
                            .border(0.5.dp, AppColors.Border)
                            .padding(4.dp)
                    ) {
                        Text(type, style = AppTypography.labelLarge.copy(color = AppColors.TextPrimary))
                    }
                }
            }
        }
    }
}
