package com.multiviewer.parser

import java.nio.charset.StandardCharsets

private val TIFF_TYPE_SIZES = mapOf(
    1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8,
)

val TAG_NAMES_MAKERNOTE_APPLE = mapOf(
    0x0001 to "MakerNoteVersion",
    0x0002 to "AEStable",
    0x0003 to "AETarget",
    0x0004 to "AEAverage",
    0x0005 to "AFStable",
    0x0006 to "AFPerformance",
    0x0007 to "AFMeasuredDepth",
    0x0008 to "AFConfidence",
    0x0009 to "ColorTemperature",
    0x000A to "CameraType",
    0x000B to "BurstUUID",
    0x000C to "FocusPosition",
    0x000E to "HDRImageType",
    0x000F to "BurstLength",
    0x0010 to "FrontCamera",
    0x0011 to "ContentIdentifier",
    0x0013 to "AccelerationVector",
    0x0014 to "ImageCaptureType",
    0x0017 to "ImageCaptureRequestID",
    0x0019 to "LivePhotoVideoIndex",
    0x001A to "FocusDistanceRange",
    0x001E to "PhotosAppCharacteristics",
    0x001F to "PhotosAppCharacteristicsVersion",
    0x0020 to "SignalToNoiseRatio",
    0x0021 to "HDRGain",
    0x0023 to "PhotoIdentifier",
    0x0025 to "SpatialOverCaptureGroupIdentifier",
    0x0027 to "SceneFlags",
    0x002B to "SemanticStyle",
    0x002D to "HDRHeadroom",
    0x002F to "PhotosAppCharacteristics2",
    0x0038 to "SmartStyle",
    0x003A to "PhotographicStyle",
    0x003D to "FocusMethod",
    0x0040 to "FlashCompensation",
    0x0041 to "OriginatingSignature",
    0x0043 to "MeteorHeadroom",
    0x004C to "SmartStyleCast",
    0x004D to "SmartStyleIntensity",
    0x004E to "SmartStyleTone",
    0x004F to "SmartStyleColor",
)

fun decodeAppleMakerNote(
    reader: ByteReader,
    tiffStart: Long,
    absolutePos: Long,
    byteLength: Int,
    littleEndian: Boolean,
    itemEnd: Long,
): BoxNode {
    val endPos = absolutePos + byteLength
    if (byteLength < 2) {
        return BoxNode(
            type = "MakerNote",
            offset = absolutePos,
            headerSize = 0,
            size = byteLength.toLong(),
            warnings = listOf("Apple MakerNote too short ($byteLength bytes)"),
        )
    }

    val entryCount = readUInt16Endian(reader, absolutePos, littleEndian)
    val fields = mutableListOf<BoxField>()
    val children = mutableListOf<BoxNode>()
    val warnings = mutableListOf<String>()

    var pos = absolutePos + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > endPos) {
            warnings.add("MakerNote IFD truncated at entry $i")
            break
        }
        val tag = readUInt16Endian(reader, pos, littleEndian)
        val fieldType = readUInt16Endian(reader, pos + 2, littleEndian)
        val count = readUInt32Endian(reader, pos + 4, littleEndian)
        val valueOffsetPos = pos + 8
        val typeSize = TIFF_TYPE_SIZES[fieldType] ?: 1
        val totalSize = typeSize * count

        val valueAbsolutePos = if (totalSize <= 4) {
            valueOffsetPos
        } else {
            tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
        }

        val name = TAG_NAMES_MAKERNOTE_APPLE[tag] ?: "Apple Tag 0x${tag.toString(16).padStart(4, '0')}"

        if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
            fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
        } else {
            // Check for embedded binary plist
            val isBplist = if (totalSize >= 8 && valueAbsolutePos + 8 <= itemEnd) {
                val magic = reader.readBytes(valueAbsolutePos, 8)
                String(magic, StandardCharsets.US_ASCII).startsWith("bplist")
            } else false

            if (isBplist) {
                val plistNode = decodeBinaryPlist(reader, valueAbsolutePos, totalSize)
                children.add(plistNode.copy(type = "$name (BinaryPlist)"))
                fields.add(BoxField(name, "(binary plist, $totalSize bytes)", valueAbsolutePos, totalSize))
            } else {
                val interpreted = formatAppleMakerNoteValue(reader, tag, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                val rawDisplay = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                val finalDisplay = if (interpreted != null && interpreted != rawDisplay) {
                    "$interpreted ($rawDisplay)"
                } else {
                    interpreted ?: rawDisplay
                }
                fields.add(BoxField(name, finalDisplay, valueAbsolutePos, totalSize))
            }
        }
        pos += 12
    }

    return BoxNode(
        type = "MakerNote",
        offset = absolutePos,
        headerSize = 2,
        size = byteLength.toLong(),
        fields = fields,
        children = children,
        warnings = warnings,
        summary = "Apple MakerNote ($entryCount entries)",
    )
}

private fun formatAppleMakerNoteValue(
    reader: ByteReader,
    tag: Int,
    type: Int,
    count: Int,
    valuePos: Long,
    littleEndian: Boolean,
): String? {
    return when (tag) {
        0x0002, 0x0005, 0x0010 -> { // AEStable, AFStable, FrontCamera
            if (count == 1) {
                val v = readIntScalar(reader, type, valuePos, littleEndian)
                when (v) {
                    0L -> "No (0)"
                    1L -> "Yes (1)"
                    else -> null
                }
            } else null
        }
        0x000A -> { // CameraType
            if (count == 1) {
                val v = readIntScalar(reader, type, valuePos, littleEndian)
                when (v) {
                    0L -> "Back (0)"
                    1L -> "Front (1)"
                    2L -> "Back Telephoto (2)"
                    3L -> "Back Ultra-wide (3)"
                    4L -> "Back LiDAR (4)"
                    5L -> "Back Super Telephoto (5)"
                    else -> "CameraType $v"
                }
            } else null
        }
        0x000E -> { // HDRImageType
            if (count == 1) {
                val v = readIntScalar(reader, type, valuePos, littleEndian)
                when (v) {
                    2L -> "HDR (2)"
                    3L -> "Original / Non-HDR (3)"
                    4L -> "Live Photo HDR (4)"
                    else -> null
                }
            } else null
        }
        0x0014 -> { // ImageCaptureType
            if (count == 1) {
                val v = readIntScalar(reader, type, valuePos, littleEndian)
                when (v) {
                    0L -> "Standard (0)"
                    1L -> "Photo (1)"
                    2L -> "Portrait (2)"
                    3L -> "ProRAW (3)"
                    4L -> "Manual Focus (4)"
                    5L -> "Deep Fusion (5)"
                    10L -> "Night Mode (10)"
                    11L -> "Portrait Night Mode (11)"
                    else -> "Capture Type $v"
                }
            } else null
        }
        0x0021, 0x002D, 0x0043 -> { // HDRGain, HDRHeadroom, MeteorHeadroom
            if (type == 5 || type == 10) {
                val num = readUInt32Endian(reader, valuePos, littleEndian)
                val den = readUInt32Endian(reader, valuePos + 4, littleEndian)
                if (den != 0L) {
                    val floatVal = num.toDouble() / den.toDouble()
                    "$num/$den (${String.format(java.util.Locale.US, "%.2f", floatVal)})"
                } else "$num/$den"
            } else null
        }
        0x0013 -> { // AccelerationVector (X, Y, Z in G)
            if (count == 3 && (type == 5 || type == 10 || type == 9 || type == 8 || type == 3)) {
                null // let default format display X, Y, Z
            } else null
        }
        else -> null
    }
}

private fun readIntScalar(reader: ByteReader, type: Int, pos: Long, littleEndian: Boolean): Long {
    return when (type) {
        1 -> reader.readUInt8(pos).toLong()
        3 -> readUInt16Endian(reader, pos, littleEndian).toLong()
        4 -> readUInt32Endian(reader, pos, littleEndian)
        6 -> reader.readUInt8(pos).toByte().toLong()
        8 -> readUInt16Endian(reader, pos, littleEndian).toShort().toLong()
        9 -> readUInt32Endian(reader, pos, littleEndian).toInt().toLong()
        else -> 0L
    }
}

private fun formatTiffValue(reader: ByteReader, type: Int, count: Int, valuePos: Long, littleEndian: Boolean): String {
    return when (type) {
        2 -> {
            val bytes = reader.readBytes(valuePos, count)
            val nullIndex = bytes.indexOf(0)
            String(bytes, 0, if (nullIndex >= 0) nullIndex else bytes.size, Charsets.UTF_8)
        }
        3 -> (0 until count).joinToString(", ") { i -> readUInt16Endian(reader, valuePos + i * 2, littleEndian).toString() }
        8 -> (0 until count).joinToString(", ") { i -> readUInt16Endian(reader, valuePos + i * 2, littleEndian).toShort().toString() }
        4 -> (0 until count).joinToString(", ") { i -> readUInt32Endian(reader, valuePos + i * 4, littleEndian).toString() }
        9 -> (0 until count).joinToString(", ") { i -> readUInt32Endian(reader, valuePos + i * 4, littleEndian).toInt().toString() }
        5 -> (0 until count).joinToString(", ") { i ->
            val num = readUInt32Endian(reader, valuePos + i * 8, littleEndian)
            val den = readUInt32Endian(reader, valuePos + i * 8 + 4, littleEndian)
            "$num/$den"
        }
        10 -> (0 until count).joinToString(", ") { i ->
            val num = readUInt32Endian(reader, valuePos + i * 8, littleEndian).toInt()
            val den = readUInt32Endian(reader, valuePos + i * 8 + 4, littleEndian).toInt()
            "$num/$den"
        }
        else -> {
            val bytes = reader.readBytes(valuePos, count.coerceAtMost(64))
            bytes.joinToString(" ") { "%02x".format(it) }
        }
    }
}

private fun readUInt16Endian(reader: ByteReader, offset: Long, littleEndian: Boolean): Int {
    if (!littleEndian) return reader.readUInt16(offset)
    val bytes = reader.readBytes(offset, 2)
    return ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
}

private fun readUInt32Endian(reader: ByteReader, offset: Long, littleEndian: Boolean): Long {
    if (!littleEndian) return reader.readUInt32(offset)
    val bytes = reader.readBytes(offset, 4)
    return ((bytes[3].toLong() and 0xFF) shl 24) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        (bytes[0].toLong() and 0xFF)
}
