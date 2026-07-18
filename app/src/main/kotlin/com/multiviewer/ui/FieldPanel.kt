package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
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
import com.multiviewer.parser.GridData

@Composable
fun FieldPanel(node: BoxNode?) {
    if (node == null) return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        item {
            Column {
                MetadataRow("Type", node.type)
                MetadataRow("Offset", "${node.offset} (0x${node.offset.toString(16).uppercase()})")
                MetadataRow("Size", "${node.size}")
                MetadataRow("Header size", "${node.headerSize}")
                MetadataRow("Payload size", "${node.size - node.headerSize}")
                if (node.children.isNotEmpty()) {
                    MetadataRow("Children", "${node.children.size}")
                }
                if (node.warnings.isNotEmpty()) {
                    Text("Warnings:", modifier = Modifier.padding(top = 4.dp))
                    node.warnings.forEach { warning ->
                        Text("- $warning", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        items(node.fields) { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("${field.name}: ", modifier = Modifier.padding(end = 4.dp))
                Text(field.value)
            }
        }
        val grid = node.grid
        if (grid != null) {
            item {
                GridDisplay(grid)
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", modifier = Modifier.padding(end = 4.dp))
        Text(value)
    }
}

@Composable
private fun GridDisplay(grid: GridData) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        for (row in 0 until grid.rows) {
            Row {
                for (col in 0 until grid.columns) {
                    Text(
                        grid.values[row * grid.columns + col].padStart(4),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}
