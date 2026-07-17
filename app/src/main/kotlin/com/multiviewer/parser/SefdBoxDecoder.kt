package com.multiviewer.parser

object SefdBoxDecoder : BoxDecoder {
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
            w.add("Box too short to contain a SEFH/SEFT trailer")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val sefMagic = reader.readFourCC(payloadEnd - 4)
        if (sefMagic != "SEFT") {
            w.add("Missing SEFT trailer magic")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val sefSize = readUInt32LE(reader, payloadEnd - 8)
        val sefhPosition = payloadEnd - 8 - sefSize
        if (sefhPosition < payloadStart || sefhPosition + 12 > payloadEnd) {
            w.add("SEFH position computed from sef_size is out of bounds")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val sefhMagic = reader.readFourCC(sefhPosition)
        if (sefhMagic != "SEFH") {
            w.add("Missing SEFH header magic at computed position")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val count = readUInt32LE(reader, sefhPosition + 8)

        val children = mutableListOf<BoxNode>()
        var entryPos = sefhPosition + 12
        var entriesFound = 0L
        while (entriesFound < count && entryPos + 12 <= payloadEnd) {
            val entryMarker = readUInt16LE(reader, entryPos + 2)
            val entryOffset = readUInt32LE(reader, entryPos + 4)
            val entrySize = readUInt32LE(reader, entryPos + 8)
            entryPos += 12
            entriesFound++

            val blockStart = sefhPosition - entryOffset
            val blockEnd = blockStart + entrySize
            if (blockStart < payloadStart || blockEnd > payloadEnd) {
                w.add("Field directory entry for marker 0x${entryMarker.toString(16)} points out of bounds")
                continue
            }
            children.add(decodeField(reader, blockStart, entrySize, entryMarker))
        }
        if (entriesFound < count) {
            w.add("Declared $count directory entries but only found $entriesFound")
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w,
            summary = pluralize(count, "field", "fields"),
        )
    }

    private fun decodeField(reader: ByteReader, blockStart: Long, blockSize: Long, directoryMarker: Int): BoxNode {
        val warnings = mutableListOf<String>()
        if (blockSize < 8) {
            warnings.add("Field block too short for its own header")
            return BoxNode("?", blockStart, 0, blockSize, warnings = warnings)
        }
        val blockMarker = readUInt16LE(reader, blockStart + 2)
        val nameSize = readUInt32LE(reader, blockStart + 4)
        if (blockMarker != directoryMarker) {
            warnings.add(
                "Directory marker 0x${directoryMarker.toString(16)} does not match block marker 0x${blockMarker.toString(16)}",
            )
        }
        val nameOffset = blockStart + 8
        if (nameOffset + nameSize > blockStart + blockSize) {
            warnings.add("Field name_size runs past the end of its block")
            return BoxNode("?", blockStart, 8, blockSize, warnings = warnings)
        }
        val nameBytes = reader.readBytes(nameOffset, nameSize.toInt())
        val name = String(nameBytes, Charsets.UTF_8).trimEnd(Char(0))
        val fieldHeaderSize = (8 + nameSize).toInt()
        val dataStart = blockStart + fieldHeaderSize
        val dataEnd = blockStart + blockSize
        val dataLength = (dataEnd - dataStart).toInt()

        val markerField = BoxField("marker", "0x" + blockMarker.toString(16).padStart(4, '0'), blockStart + 2, 2)

        if (dataLength >= 8 && reader.readFourCC(dataStart + 4) == "ftyp") {
            val nestedChildren = parseBoxes(reader, dataStart, dataEnd)
            return BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                children = nestedChildren, fields = listOf(markerField), warnings = warnings,
                summary = "$dataLength bytes, embedded MP4",
            )
        }

        if (name == "MotionPhoto_Data" && dataLength == 12) {
            val formatTag = reader.readFourCC(dataStart)
            val videoOffset = reader.readUInt32(dataStart + 4)
            val videoLength = reader.readUInt32(dataStart + 8)
            val fields = listOf(
                markerField,
                BoxField("format_tag", formatTag, dataStart, 4),
                BoxField("video_offset", videoOffset.toString(), dataStart + 4, 4),
                BoxField("video_length", videoLength.toString(), dataStart + 8, 4),
            )
            return BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = fields, warnings = warnings,
                summary = "offset=$videoOffset, length=$videoLength",
            )
        }

        val dataBytes = reader.readBytes(dataStart, dataLength)
        val isPrintable = dataBytes.all { b ->
            val v = b.toInt() and 0xFF
            v in 0x20..0x7E || v == 0x09 || v == 0x0A || v == 0x0D
        }
        return if (isPrintable) {
            val value = String(dataBytes, Charsets.UTF_8)
            BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = listOf(markerField, BoxField("value", value, dataStart, dataLength.toLong())),
                warnings = warnings,
                summary = value,
            )
        } else {
            BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = listOf(markerField), warnings = warnings,
                summary = "$dataLength bytes (binary)",
            )
        }
    }
}

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val bytes = reader.readBytes(offset, 2)
    return ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val bytes = reader.readBytes(offset, 4)
    return ((bytes[3].toLong() and 0xFF) shl 24) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        (bytes[0].toLong() and 0xFF)
}
