package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode

private data class FlatRow(val node: BoxNode, val depth: Int)

private const val DEPTH_INDENT_DP = 16
private const val ARROW_WIDTH_DP = 16

@Composable
fun BoxTreeView(root: BoxNode, selected: BoxNode?, onSelect: (BoxNode) -> Unit) {
    val expanded = remember(root) { mutableStateOf(setOf(root)) }

    // Selection can arrive from anywhere (a manual tree click, the Detailed Properties warnings
    // summary, etc.) -- whatever ancestors of the selected node aren't already expanded need to
    // be, or the node has no row to render/highlight at all (flatten() below skips children of
    // collapsed nodes entirely).
    LaunchedEffect(selected) {
        val target = selected ?: return@LaunchedEffect
        val ancestors = findAncestors(root, target, emptyList())
        if (!ancestors.isNullOrEmpty()) {
            expanded.value = expanded.value + ancestors
        }
    }

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
                Text(text = buildLabel(row.node), color = AppColors.TextPrimary)
            }
        }
    }
}

// Returns the chain of ancestors (root..parent-of-target, not including target itself) that must
// be expanded for target's row to be reachable, or null if target isn't in this subtree. Uses
// reference equality (===), matching how `selected` is already compared elsewhere in this file --
// BoxNode is a data class, so structurally-identical-but-distinct nodes are common in real trees
// (e.g. repeated small boxes) and must not be confused with each other.
fun findAncestors(current: BoxNode, target: BoxNode, path: List<BoxNode>): List<BoxNode>? {
    if (current === target) return path
    for (child in current.children) {
        val result = findAncestors(child, target, path + current)
        if (result != null) return result
    }
    return null
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
