package com.multiviewer.parser

/** Small fixed-layout media boxes. Values retain their exact source byte ranges. */
object BasicMediaBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader, type: String, offset: Long, headerSize: Int,
        size: Long, warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()
        fun result() = BoxNode(type, offset, headerSize, size, fields = fields.toList(), warnings = w.toList())
        val required = when (type) {
            "vmhd", "btrt" -> 12
            "smhd" -> 8
            "fiel" -> 2
            else -> {
                w += "Unsupported box / 미지원 박스: $type"
                return result()
            }
        }
        if (offset < 0 || headerSize < 0 || size < headerSize ||
            offset > reader.length || size > reader.length - offset || size - headerSize < required) {
            w += "Box too short or outside file / 박스 길이 부족 또는 파일 범위 초과: $type"
            return result()
        }
        val start = offset + headerSize
        fun u16(name: String, relative: Int) {
            fields += BoxField(name, reader.readUInt16(start + relative).toString(), start + relative, 2)
        }
        fun u32(name: String, relative: Int) {
            fields += BoxField(name, reader.readUInt32(start + relative).toString(), start + relative, 4)
        }
        if (type == "vmhd" || type == "smhd") {
            val version = reader.readUInt8(start)
            val flags = reader.readUInt32(start) and 0xffffffL
            fields += BoxField("version", version.toString(), start, 1)
            fields += BoxField("flags", "0x${flags.toString(16).padStart(6, '0')}", start + 1, 3)
            if (version != 0) {
                w += "Unsupported version / 미지원 버전: $type v$version"
                return result()
            }
        }
        when (type) {
            "vmhd" -> {
                u16("graphicsmode", 4)
                repeat(3) { u16("opcolor[$it]", 6 + it * 2) }
            }
            "smhd" -> {
                val balance = reader.readUInt16(start + 4).toShort().toDouble() / 256.0
                fields += BoxField("balance", balance.toString(), start + 4, 2)
                u16("reserved", 6)
            }
            "btrt" -> {
                u32("bufferSizeDB", 0)
                u32("maxBitrate", 4)
                u32("avgBitrate", 8)
            }
            "fiel" -> {
                fields += BoxField("field_count", reader.readUInt8(start).toString(), start, 1)
                fields += BoxField("field_order", reader.readUInt8(start + 1).toString(), start + 1, 1)
            }
        }
        return result()
    }
}
