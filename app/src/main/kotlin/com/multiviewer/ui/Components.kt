package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.GridData
import com.multiviewer.parser.SummarySection

@Composable
fun SummaryBox(title: String, sections: List<SummarySection>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            title,
            modifier = Modifier.padding(bottom = 8.dp),
            style = AppTypography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = AppColors.NeonBlue
            )
        )
        CoreMetadataDisplay(sections = sections)
    }
}

@Composable
fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = AppTypography.labelLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = AppTypography.bodyLarge,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun GridDisplay(grid: GridData) {
    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .background(AppColors.Panel)
            .border(1.dp, AppColors.Border)
            .padding(8.dp)
    ) {
        Text(
            "Matrix Data (${grid.columns}x${grid.rows})",
            style = AppTypography.labelLarge.copy(color = AppColors.NeonGreen, fontSize = 10.sp),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        for (row in 0 until grid.rows) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (col in 0 until grid.columns) {
                    val value = grid.values.getOrNull(row * grid.columns + col) ?: ""
                    Text(
                        value.padStart(3),
                        style = AppTypography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = AppColors.TextPrimary
                        ),
                        modifier = Modifier.width(24.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}
