package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.BoxNode

private data class FlatRow(val node: BoxNode, val depth: Int)

private const val DEPTH_INDENT_DP = 16
private const val ARROW_WIDTH_DP = 16
private const val BADGE_SIZE_DP = 16

@Composable
fun BoxTreeView(root: BoxNode, selected: BoxNode?, onSelect: (BoxNode) -> Unit) {
    val expanded = remember(root) { mutableStateOf(setOf(root)) }
    val rows = remember(root, expanded.value) { flatten(root, 0, expanded.value) }

    LazyColumn {
        items(rows) { row ->
            val isSelected = row.node === selected
            val isExpanded = row.node in expanded.value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(if (isSelected) AppColors.Selection else Color.Transparent)
                    .clickable {
                        onSelect(row.node)
                        if (row.node.children.isNotEmpty()) {
                            expanded.value = if (row.node in expanded.value) {
                                expanded.value - row.node
                            } else {
                                expanded.value + row.node
                            }
                        }
                    }
                    .padding(top = 2.dp, bottom = 2.dp),
            ) {
                repeat(row.depth) {
                    Box(modifier = Modifier.width(DEPTH_INDENT_DP.dp).fillMaxHeight()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(Color.Gray),
                        )
                    }
                }
                Box(
                    modifier = Modifier.width(ARROW_WIDTH_DP.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.node.children.isNotEmpty()) {
                        Text(if (isExpanded) "▼" else "▶", color = AppColors.TextPrimary)
                    }
                }
                val category = categorize(row.node)
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(BADGE_SIZE_DP.dp)
                        .align(Alignment.CenterVertically)
                        .background(category.color, CircleShape)
                        .then(
                            if (row.node.warnings.isNotEmpty()) {
                                Modifier.border(1.5.dp, AppColors.NeonRed, CircleShape)
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(category.letter, color = Color.Black.copy(alpha = 0.7f), fontSize = 9.sp)
                }
                Text(text = buildLabel(row.node), color = if (isSelected) Color.White else AppColors.TextPrimary)
            }
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

private enum class NodeCategory(val color: Color, val letter: String) {
    Container(AppColors.BadgeAmber, "F"),
    Table(AppColors.BadgeTeal, "T"),
    Image(AppColors.BadgeLavender, "I"),
    Metadata(AppColors.BadgeSky, "M"),
}

private fun categorize(node: BoxNode): NodeCategory = when {
    node.table != null -> NodeCategory.Table
    node.grid != null || node.type == "ThumbnailImage" -> NodeCategory.Image
    node.children.isNotEmpty() -> NodeCategory.Container
    else -> NodeCategory.Metadata
}
