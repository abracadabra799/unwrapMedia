package com.multiviewer.parser

data class WarningEntry(val node: BoxNode, val warning: String)

fun collectWarnings(root: BoxNode): List<WarningEntry> {
    val entries = mutableListOf<WarningEntry>()
    fun walk(node: BoxNode) {
        node.warnings.forEach { entries.add(WarningEntry(node, it)) }
        node.children.forEach { walk(it) }
    }
    walk(root)
    return entries.sortedBy { it.node.offset }
}
