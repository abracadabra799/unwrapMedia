package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummarySection

@Composable
fun CoreMetadataDisplay(summary: MediaSummary, modifier: Modifier = Modifier) {
    CoreMetadataDisplay(sections = summary.sections, modifier = modifier)
}

@Composable
fun CoreMetadataDisplay(sections: List<SummarySection>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sections.forEach { section ->
            // Filter sections to show only General, Video, Audio or Image specific core ones
            if (section.title in listOf("General", "Video", "Audio", "Image", "Camera Info", "Track List")) {
                MetadataCard(section, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetadataCard(section: SummarySection, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AppColors.Panel)
            .border(1.dp, AppColors.Border)
            .padding(12.dp)
    ) {
        Text(
            text = section.title.uppercase(),
            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonBlue)
        )
        Spacer(Modifier.height(8.dp))
        section.fields.take(5).forEach { field ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(field.label, style = AppTypography.labelLarge.copy(fontSize = 11.sp))
                Text(field.value, style = AppTypography.bodyLarge.copy(fontSize = 11.sp), color = AppColors.TextPrimary)
            }
        }
    }
}
