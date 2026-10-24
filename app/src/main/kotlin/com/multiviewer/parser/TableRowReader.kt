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

/** Decode only the requested visible row; fieldWidths always describes on-disk storage. */
fun readTableRow(reader: ByteReader, table: TableData, rowIndex: Long): List<Long> {
    require(rowIndex >= 0 && rowIndex < table.entryCount) { "Row outside table" }
    return when (table.rowEncoding) {
        TableRowEncoding.UNSIGNED -> readTableRow(reader, table.entriesStart, table.fieldWidths, rowIndex)
        TableRowEncoding.SDTP_BITS -> {
            require(table.fieldWidths == listOf(1))
            val value = reader.readUInt8(table.entriesStart + rowIndex)
            listOf(6, 4, 2, 0).map { ((value shr it) and 3).toLong() }
        }
        TableRowEncoding.SIGNED_LAST_32 -> {
            require(table.fieldWidths.isNotEmpty() && table.fieldWidths.all { it == 4 })
            val values = readTableRow(reader, table.entriesStart, table.fieldWidths, rowIndex)
            values.dropLast(1) + values.last().toInt().toLong()
        }
        TableRowEncoding.SIGNED_16 -> {
            require(table.fieldWidths == listOf(2))
            listOf(reader.readUInt16(table.entriesStart + rowIndex * 2).toShort().toLong())
        }
    }
}
