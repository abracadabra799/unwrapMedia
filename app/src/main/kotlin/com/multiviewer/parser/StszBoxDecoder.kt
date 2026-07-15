package com.multiviewer.parser

object StszBoxDecoder : BoxDecoder {
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
        if (payloadEnd - payloadStart < 12) {
            w.add("Box too short to contain a FullBox header, sample_size and sample_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val sampleSizeOffset = payloadStart + 4
        val sampleSize = reader.readUInt32(sampleSizeOffset)
        val sampleCountOffset = payloadStart + 8
        val sampleCount = reader.readUInt32(sampleCountOffset)

        if (sampleSize != 0L) {
            return BoxNode(
                type = type, offset = offset, headerSize = headerSize, size = size, warnings = w,
                fields = listOf(
                    BoxField("sample_size", sampleSize.toString(), sampleSizeOffset, 4),
                    BoxField("sample_count", sampleCount.toString(), sampleCountOffset, 4),
                ),
                summary = "${pluralize(sampleCount, "sample", "samples")}, uniform size $sampleSize",
            )
        }

        val entriesStart = payloadStart + 12
        val available = payloadEnd - entriesStart
        val fitCount = available / 4
        val actualCount = minOf(sampleCount, fitCount)
        if (actualCount < sampleCount) {
            w.add("Declared $sampleCount entries but only enough space for $fitCount")
        }
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size, warnings = w,
            summary = "${pluralize(sampleCount, "entry", "entries")} (variable size)",
            table = TableData(listOf("sample_size"), listOf(4), entriesStart, actualCount),
        )
    }
}
