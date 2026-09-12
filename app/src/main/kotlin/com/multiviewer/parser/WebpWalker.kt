package com.multiviewer.parser

fun parseWebpChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (end - start < 12) return result

    // 1. RIFF Header
    val riffHeader = BoxNode(
        type = "RIFF",
        offset = start,
        headerSize = 8,
        size = 12,
        fields = listOf(
            BoxField("file_size", (reader.readUInt32LE(start + 4) + 8).toString(), start + 4, 4),
            BoxField("webp_identifier", reader.readFourCC(start + 8), start + 8, 4)
        )
    )
    result.add(riffHeader)

    // 2. Chunks
    var pos = start + 12
    while (pos + 8 <= end) {
        val type = reader.readFourCC(pos)
        val chunkSize = reader.readUInt32LE(pos + 4)
        val totalSize = 8 + chunkSize
        val paddedSize = if (chunkSize % 2 == 1L) totalSize + 1 else totalSize
        
        if (pos + totalSize > end) {
            result.add(BoxNode(type, pos, 8, end - pos, warnings = listOf("Chunk extends past end of file")))
            break
        }

        result.add(decodeWebpChunk(reader, type, pos, chunkSize, totalSize))
        pos += paddedSize
    }
    return result
}

private fun decodeWebpChunk(reader: ByteReader, type: String, offset: Long, payloadSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 8
    val fields = mutableListOf<BoxField>()
    var summary: String? = null

    when (type) {
        "VP8X" -> {
            if (payloadSize >= 10) {
                val flags = reader.readUInt8(payloadStart)
                val width = reader.readUInt24(payloadStart + 4) + 1
                val height = reader.readUInt24(payloadStart + 7) + 1
                fields.add(BoxField("flags", "0x${flags.toString(16)}", payloadStart, 1))
                fields.add(BoxField("width", width.toString(), payloadStart + 4, 3))
                fields.add(BoxField("height", height.toString(), payloadStart + 7, 3))
                summary = "Extended, ${width}x${height}"
            }
        }
        "VP8 " -> {
            if (payloadSize >= 10) {
                // VP8 bitstream header contains dimensions at offset 6
                val width = reader.readUInt16LE(payloadStart + 6) and 0x3FFF
                val height = reader.readUInt16LE(payloadStart + 8) and 0x3FFF
                fields.add(BoxField("width", width.toString(), payloadStart + 6, 2))
                fields.add(BoxField("height", height.toString(), payloadStart + 8, 2))
                summary = "Lossy, ${width}x${height}"
            }
        }
        "VP8L" -> {
            if (payloadSize >= 5) {
                // VP8L header: signature(0x2f), then 14 bits width, 14 bits height
                val b1 = reader.readUInt8(payloadStart + 1)
                val b2 = reader.readUInt8(payloadStart + 2)
                val b3 = reader.readUInt8(payloadStart + 3)
                val b4 = reader.readUInt8(payloadStart + 4)
                val width = ((b2 and 0x3F) shl 8 or b1) + 1
                val height = ((b4 and 0x0F) shl 10 or (b3 shl 2) or (b2 shr 6)) + 1
                fields.add(BoxField("width", width.toString(), payloadStart + 1, 2))
                fields.add(BoxField("height", height.toString(), payloadStart + 3, 2))
                summary = "Lossless, ${width}x${height}"
            }
        }
        "EXIF" -> {
            val children = decodeExif(reader, payloadStart, payloadStart + payloadSize)
            return BoxNode(type, offset, 8, totalSize, children = children, summary = "Exif metadata")
        }
        "ICCP" -> {
            if (payloadSize < 128) {
                return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, warnings = listOf("ICC profile too short to contain a valid header"))
            }
            val headerBytes = reader.readBytes(payloadStart, 128)
            val headerFields = decodeIccProfileHeader(headerBytes, payloadStart)
            val version = headerFields.first { it.name == "version" }.value
            return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, fields = headerFields, summary = "ICC Profile v$version ($payloadSize bytes)")
        }
        "XMP " -> {
            val text = String(reader.readBytes(payloadStart, payloadSize.toInt()), Charsets.UTF_8)
            return BoxNode(
                type = type, offset = offset, headerSize = 8, size = totalSize,
                fields = listOf(BoxField("xmp", text, payloadStart, payloadSize)),
                summary = "XMP (${text.length} chars)",
            )
        }
        "ANIM" -> {
            if (payloadSize < 6) {
                return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, warnings = listOf("ANIM chunk too short to contain background color and loop count"))
            }
            val blue = reader.readUInt8(payloadStart)
            val green = reader.readUInt8(payloadStart + 1)
            val red = reader.readUInt8(payloadStart + 2)
            val alpha = reader.readUInt8(payloadStart + 3)
            val backgroundColor = "#%02X%02X%02X%02X".format(red, green, blue, alpha)
            val loopCount = reader.readUInt16LE(payloadStart + 4)
            val loopCountLabel = if (loopCount == 0) "0 (infinite)" else loopCount.toString()
            return BoxNode(
                type = type, offset = offset, headerSize = 8, size = totalSize,
                fields = listOf(
                    BoxField("background_color", backgroundColor, payloadStart, 4),
                    BoxField("loop_count", loopCountLabel, payloadStart + 4, 2),
                ),
                summary = "Loop count: $loopCountLabel",
            )
        }
        "ANMF" -> {
            if (payloadSize < 16) {
                return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, warnings = listOf("ANMF chunk too short to contain its frame header"))
            }
            val frameX = reader.readUInt24(payloadStart) * 2
            val frameY = reader.readUInt24(payloadStart + 3) * 2
            val width = reader.readUInt24(payloadStart + 6) + 1
            val height = reader.readUInt24(payloadStart + 9) + 1
            val durationMs = reader.readUInt24(payloadStart + 12)
            val flags = reader.readUInt8(payloadStart + 15)
            val blending = if (flags and 0x02 != 0) "Do not blend" else "Blend"
            val disposal = if (flags and 0x01 != 0) "Dispose to background" else "Do not dispose"
            val frameDataSize = payloadSize - 16
            return BoxNode(
                type = type, offset = offset, headerSize = 8, size = totalSize,
                fields = listOf(
                    BoxField("frame_x", frameX.toString(), payloadStart, 3),
                    BoxField("frame_y", frameY.toString(), payloadStart + 3, 3),
                    BoxField("width", width.toString(), payloadStart + 6, 3),
                    BoxField("height", height.toString(), payloadStart + 9, 3),
                    BoxField("duration_ms", durationMs.toString(), payloadStart + 12, 3),
                    BoxField("blending", blending, payloadStart + 15, 1),
                    BoxField("disposal", disposal, payloadStart + 15, 1),
                ),
                summary = "${width}x${height}, ${durationMs}ms (frame data: $frameDataSize bytes, not parsed)",
            )
        }
    }

    return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, fields = fields, summary = summary)
}

// Helper for WebP 24-bit little endian values
private fun ByteReader.readUInt24(offset: Long): Int {
    val bytes = readBytes(offset, 3)
    return (bytes[0].toInt() and 0xFF) or
           ((bytes[1].toInt() and 0xFF) shl 8) or
           ((bytes[2].toInt() and 0xFF) shl 16)
}

// RIFF/WebP stores all its multi-byte integers little-endian (unlike most other
// formats this parser handles, which is why these live here rather than on the
// shared ByteReader) -- see readUInt24 above for the existing precedent.
private fun ByteReader.readUInt16LE(offset: Long): Int {
    val bytes = readBytes(offset, 2)
    return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
}

private fun ByteReader.readUInt32LE(offset: Long): Long {
    val bytes = readBytes(offset, 4)
    return (bytes[0].toLong() and 0xFF) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[3].toLong() and 0xFF) shl 24)
}
