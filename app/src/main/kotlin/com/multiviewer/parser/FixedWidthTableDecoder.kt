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
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length || size > reader.length - offset) {
            w.add("Invalid sample table byte range / 샘플 표 바이트 범위 오류")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 8) {
            w.add("Box too short to contain a FullBox header and entry count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val flags = reader.readUInt32(payloadStart) and 0xffffff
        val entryCount = reader.readUInt32(payloadStart + 4)
        val fields = listOf(
            BoxField("version", version.toString(), payloadStart, 1),
            BoxField("flags", "0x${flags.toString(16).padStart(6, '0')}", payloadStart + 1, 3),
            BoxField("entry_count", entryCount.toString(), payloadStart + 4, 4),
        )
        if (version !in (if (type == "ctts") 0..1 else 0..0) || flags != 0L) {
            w.add("Unsupported sample table version/flags / 미지원 샘플 표 버전 또는 플래그")
            return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w)
        }
        val encoding = if (type == "ctts" && version == 1) TableRowEncoding.SIGNED_LAST_32 else TableRowEncoding.UNSIGNED
        val entriesStart = payloadStart + 8
        val available = payloadEnd - entriesStart
        val fitCount = if (entryWidth == 0) 0 else available / entryWidth
        val actualCount = minOf(entryCount, fitCount)
        if (actualCount < entryCount) {
            w.add("Declared $entryCount entries but only enough space for $fitCount")
        }
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = w,
            summary = pluralize(entryCount, "entry", "entries"),
            table = TableData(columns, fieldWidths, entriesStart, actualCount, encoding),
        )
    }
}
