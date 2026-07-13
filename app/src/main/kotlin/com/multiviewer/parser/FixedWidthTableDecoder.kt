package com.multiviewer.parser

class FixedWidthTableDecoder(
    private val columns: List<String>,
    private val fieldWidths: List<Int>,
) : BoxDecoder {
    init {
        require(columns.size == fieldWidths.size) { "columns and fieldWidths must be the same size" }
    }

    private val entryWidth = fieldWidths.sum()

    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 8) {
            w.add("Box too short to contain a FullBox header and entry count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val entryCount = reader.readUInt32(payloadStart + 4)
        val entriesStart = payloadStart + 8
        val available = payloadEnd - entriesStart
        val fitCount = if (entryWidth == 0) 0 else available / entryWidth
        val actualCount = minOf(entryCount, fitCount)
        if (actualCount < entryCount) {
            w.add("Declared $entryCount entries but only enough space for $fitCount")
        }
        val rows = mutableListOf<List<Long>>()
        var pos = entriesStart
        repeat(actualCount.toInt()) {
            val row = mutableListOf<Long>()
            var fieldPos = pos
            for (width in fieldWidths) {
                row.add(readUIntOfWidth(reader, fieldPos, width))
                fieldPos += width
            }
            rows.add(row)
            pos += entryWidth
        }
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            warnings = w,
            summary = "$entryCount entries",
            table = TableData(columns, rows),
        )
    }
}
