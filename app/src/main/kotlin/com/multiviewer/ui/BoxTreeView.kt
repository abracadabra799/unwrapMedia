package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode

private data class FlatRow(val node: BoxNode, val depth: Int)

@Composable
fun BoxTreeView(root: BoxNode, selected: BoxNode?, onSelect: (BoxNode) -> Unit) {
    val expanded = remember { mutableStateOf(setOf<BoxNode>()) }
    val rows = remember(root, expanded.value) { flatten(root, 0, expanded.value) }

    LazyColumn {
        items(rows) { row ->
            val isSelected = row.node === selected
            Text(
                text = buildLabel(row.node),
                modifier = Modifier
                    .background(if (isSelected) Color.LightGray else Color.Transparent)
                    .padding(start = (row.depth * 16).dp, top = 2.dp, bottom = 2.dp)
                    .clickable {
                        onSelect(row.node)
                        if (row.node.children.isNotEmpty()) {
                            expanded.value = if (row.node in expanded.value) {
                                expanded.value - row.node
                            } else {
                                expanded.value + row.node
                            }
                        }
                    },
            )
        }
    }
}

private fun flatten(node: BoxNode, depth: Int, expanded: Set<BoxNode>): List<FlatRow> {
    val rows = mutableListOf(FlatRow(node, depth))
    if (node.children.isNotEmpty() && node in expanded) {
        for (child in node.children) {
            rows.addAll(flatten(child, depth + 1, expanded))
        }
    }
    return rows
}

private fun buildLabel(node: BoxNode): String {
    val warningPrefix = if (node.warnings.isNotEmpty()) "⚠ " else ""
    val summarySuffix = node.summary?.let { " — $it" } ?: ""
    return "$warningPrefix${node.type}$summarySuffix"
}
