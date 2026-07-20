package com.multiviewer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummarySection

@Composable
fun MediaSummaryView(summary: MediaSummary?) {
    if (summary == null) return
    val videoSections = summary.motionPhotoVideoSections
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        if (videoSections != null) {
            item {
                SummaryBox("📷 이미지", summary.sections)
                Spacer(modifier = Modifier.height(12.dp))
                SummaryBox("🎬 동영상 (모션포토)", videoSections)
            }
        } else {
            items(summary.sections) { section ->
                SectionContent(section)
            }
        }
    }
}

@Composable
private fun SummaryBox(title: String, sections: List<SummarySection>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            title,
            modifier = Modifier.padding(bottom = 8.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        sections.forEach { section -> SectionContent(section) }
    }
}

@Composable
private fun SectionContent(section: SummarySection) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            section.title,
            modifier = Modifier.padding(bottom = 6.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        section.fields.forEach { field ->
            MetadataRow(field.label, field.value)
        }
    }
}
