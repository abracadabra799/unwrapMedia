package com.multiviewer.parser

object FtypBoxDecoder : BoxDecoder {
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
            w.add("Box too short to contain major_brand and minor_version")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val majorBrand = reader.readFourCC(payloadStart)
        val minorVersion = reader.readUInt32(payloadStart + 4)
        val fields = mutableListOf(
            BoxField("major_brand", majorBrand, payloadStart, 4),
            BoxField("minor_version", minorVersion.toString(), payloadStart + 4, 4),
        )
        var pos = payloadStart + 8
        val brands = mutableListOf<String>()
        while (pos + 4 <= payloadEnd) {
            val brand = reader.readFourCC(pos)
            brands.add(brand)
            fields.add(BoxField("compatible_brand", brand, pos, 4))
            pos += 4
        }
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = w,
            summary = "$majorBrand, ${brands.size} compatible brand(s)",
        )
    }
}
