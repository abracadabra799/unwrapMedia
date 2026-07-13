package com.multiviewer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode

@Composable
fun FieldPanel(node: BoxNode?) {
    if (node == null || node.fields.isEmpty()) return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        items(node.fields) { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("${field.name}: ", modifier = Modifier.padding(end = 4.dp))
                Text(field.value)
            }
        }
    }
}
