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

// Stacked vertically (was a horizontal Row with each card at weight(1f)) -- that layout assumed
// the wide center panel it used to share with the preview; now that this is the only remaining
// caller (the right-side DetailedPropertiesPanel's Overview tab, always narrow), a Row squeezed
// every card down to a sliver, wrapping every field's label/value onto separate misaligned lines.
// Also no longer filters by section title -- the old whitelist ("General"/"Video"/"Audio"/
// "Image"/"Camera Info") silently dropped sections like "GPS Location" and "Samsung Metadata"
// that MediaSummaryBuilder deliberately produces; an at-a-glance overview should show all of them.
@Composable
fun CoreMetadataDisplay(sections: List<SummarySection>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sections.forEach { section ->
            MetadataCard(section, modifier = Modifier.fillMaxWidth())
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
            style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonBlue)
        )
        Spacer(Modifier.height(8.dp))
        section.fields.forEach { field ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(field.label, style = AppTypography.labelLarge.copy(fontSize = 12.sp))
                Text(field.value, style = AppTypography.bodyLarge.copy(fontSize = 12.sp), color = AppColors.TextPrimary)
            }
        }
    }
}
