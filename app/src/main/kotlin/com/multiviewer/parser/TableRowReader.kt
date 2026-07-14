package com.multiviewer.parser

fun readTableRow(reader: ByteReader, entriesStart: Long, fieldWidths: List<Int>, rowIndex: Long): List<Long> {
    val entryWidth = fieldWidths.sum()
    var pos = entriesStart + rowIndex * entryWidth
    val row = mutableListOf<Long>()
    for (width in fieldWidths) {
        row.add(readUIntOfWidth(reader, pos, width))
        pos += width
    }
    return row
}
