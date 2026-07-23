package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CameraLCDPanel(make: String, model: String, lens: String, settings: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Panel)
            .border(2.dp, AppColors.Border)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$make $model",
                style = AppTypography.headlineSmall.copy(color = AppColors.NeonYellow, fontSize = 20.sp)
            )
            Spacer(Modifier.weight(1f))
            Text(text = "AF-ON", color = AppColors.TextMuted)
        }
        Spacer(Modifier.height(8.dp))
        Text(text = lens, style = AppTypography.labelLarge)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LCDField("SHUTTER", settings.split("|").getOrElse(0) { "1/500" })
            LCDField("APERTURE", settings.split("|").getOrElse(1) { "f/2.8" })
            LCDField("ISO", settings.split("|").getOrElse(2) { "400" })
        }
    }
}

@Composable
private fun LCDField(label: String, value: String) {
    Column {
        Text(label, style = AppTypography.labelLarge.copy(fontSize = 10.sp))
        Text(
            value,
            style = AppTypography.headlineSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 24.sp,
                color = AppColors.NeonGreen
            )
        )
    }
}
