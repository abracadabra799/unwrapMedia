package com.multiviewer.parser

/** Shared by the detail panel and JSON export. Never classify by arbitrary value text. */
fun detailInterpretation(node: BoxNode): ValueInterpretation {
    if (node.interpretation == ValueInterpretation.UNPARSABLE || node.fields.any { it.interpretation == ValueInterpretation.UNPARSABLE }) return ValueInterpretation.UNPARSABLE
    if (node.interpretation == ValueInterpretation.UNINTERPRETED || node.fields.any { it.interpretation == ValueInterpretation.UNINTERPRETED }) return ValueInterpretation.UNINTERPRETED
    if (node.fields.isEmpty() && node.children.isEmpty() && node.table == null && node.grid == null) {
        if (node.warnings.isNotEmpty()) return ValueInterpretation.UNPARSABLE
        if (node.size > node.headerSize) return ValueInterpretation.UNINTERPRETED
    }
    return ValueInterpretation.PARSED
}
