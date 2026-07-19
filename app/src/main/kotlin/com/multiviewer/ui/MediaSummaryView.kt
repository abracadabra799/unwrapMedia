package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.MediaSummary

@Composable
fun MediaSummaryView(summary: MediaSummary?) {
    if (summary == null) return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        items(summary.sections) { section ->
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(section.title, modifier = Modifier.padding(bottom = 4.dp))
                section.fields.forEach { field ->
                    MetadataRow(field.label, field.value)
                }
            }
        }
    }
}
