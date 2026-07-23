package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummarySection
import java.io.ByteArrayInputStream

@Composable
fun MediaSummaryView(summary: MediaSummary?) {
    if (summary == null) return
    val videoSections = summary.motionPhotoVideoSections
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        summary.thumbnail?.let { bytes ->
            item { ThumbnailPreview(bytes) }
        }
        if (videoSections != null) {
            item {
                SummaryBox("📷 이미지", summary.sections)
                Spacer(modifier = Modifier.height(12.dp))
                SummaryBox("🎬 동영상 (모션포토)", videoSections)
            }
        } else {
            item {
                SummaryBox("General Info", summary.sections)
            }
        }
    }
}

@Composable
private fun ThumbnailPreview(bytes: ByteArray) {
    val bitmap = remember(bytes) {
        try {
            loadImageBitmap(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        }
    } ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Embedded thumbnail",
            modifier = Modifier.heightIn(max = 200.dp),
            contentScale = ContentScale.Fit,
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
}
