package com.multiviewer.parser

private const val TAG_EXIF_IFD_POINTER = 0x8769
private const val TAG_GPS_IFD_POINTER = 0x8825
private const val TAG_INTEROP_IFD_POINTER = 0xA005
private const val TAG_MAKER_NOTE = 0x927C

private val TIFF_TYPE_SIZES = mapOf(
    1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8,
)

private val TAG_NAMES_IFD0 = mapOf(
    0x0100 to "ImageWidth",
    0x0101 to "ImageLength",
    0x010F to "Make",
    0x0110 to "Model",
    0x0112 to "Orientation",
    0x011A to "XResolution",
    0x011B to "YResolution",
    0x0128 to "ResolutionUnit",
    0x0131 to "Software",
    0x0132 to "DateTime",
    0x0213 to "YCbCrPositioning",
)

private val TAG_NAMES_EXIF = mapOf(
    0x829A to "ExposureTime",
    0x829D to "FNumber",
    0x8822 to "ExposureProgram",
    0x8827 to "ISOSpeedRatings",
    0x9000 to "ExifVersion",
    0x9003 to "DateTimeOriginal",
    0x9004 to "DateTimeDigitized",
    0x9010 to "OffsetTime",
    0x9011 to "OffsetTimeOriginal",
    0x9201 to "ShutterSpeedValue",
    0x9202 to "ApertureValue",
    0x9203 to "BrightnessValue",
    0x9204 to "ExposureBiasValue",
    0x9205 to "MaxApertureValue",
    0x9207 to "MeteringMode",
    0x9209 to "Flash",
    0x920A to "FocalLength",
    0x9290 to "SubSecTime",
    0x9291 to "SubSecTimeOriginal",
    0x9292 to "SubSecTimeDigitized",
    0xA001 to "ColorSpace",
    0xA002 to "PixelXDimension",
    0xA003 to "PixelYDimension",
    0xA402 to "ExposureMode",
    0xA403 to "WhiteBalance",
    0xA404 to "DigitalZoomRatio",
    0xA405 to "FocalLengthIn35mmFilm",
    0xA406 to "SceneCaptureType",
    0xA420 to "ImageUniqueID",
)

private val TAG_NAMES_GPS = mapOf(
    0x0001 to "GPSLatitudeRef",
    0x0002 to "GPSLatitude",
    0x0003 to "GPSLongitudeRef",
    0x0004 to "GPSLongitude",
    0x0005 to "GPSAltitudeRef",
    0x0006 to "GPSAltitude",
)

private val TAG_NAMES_MAKERNOTE = mapOf(
    0x0001 to "Version",
    0x0002 to "DeviceType",
    0x0021 to "PictureWizard",
    0x0030 to "LocalLocationName",
    0x0031 to "LocationName",
    0x0035 to "Preview",
    0x0043 to "CameraTemperature",
    0xA001 to "FirmwareName",
    0xA003 to "LensType",
    0xA004 to "LensFirmware",
    0xA010 to "SensorAreas",
    0xA011 to "ColorSpace",
    0xA012 to "SmartRange",
    0xA013 to "ExposureBiasValue",
    0xA014 to "ISO",
    0xA018 to "ExposureTime",
    0xA019 to "FNumber",
    0xA01A to "FocalLengthIn35mmFormat",
    0xA020 to "EncryptionKey",
)

fun decodeExif(reader: ByteReader, itemStart: Long, itemEnd: Long): List<BoxNode> {
    if (itemEnd - itemStart < 4) return emptyList()
    val tiffHeaderOffsetField = reader.readUInt32(itemStart)
    val tiffStart = itemStart + 4 + tiffHeaderOffsetField
    return decodeTiff(reader, tiffStart, itemEnd)
}

fun decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode> {
    if (tiffStart + 8 > itemEnd) return emptyList()
    val byteOrderBytes = reader.readBytes(tiffStart, 2)
    val littleEndian = byteOrderBytes[0] == 'I'.code.toByte() && byteOrderBytes[1] == 'I'.code.toByte()
    val ifd0Offset = readUInt32Endian(reader, tiffStart + 4, littleEndian)
    val ifd0AbsoluteOffset = tiffStart + ifd0Offset
    val visitedOffsets = mutableSetOf<Long>()
    return listOf(
        decodeIfd(reader, tiffStart, ifd0AbsoluteOffset, itemEnd, littleEndian, "IFD0", TAG_NAMES_IFD0, visitedOffsets),
    )
}

private fun decodeIfd(
    reader: ByteReader,
    tiffStart: Long,
    ifdOffset: Long,
    itemEnd: Long,
    littleEndian: Boolean,
    label: String,
    tagNames: Map<Int, String>,
    visitedOffsets: MutableSet<Long>,
): BoxNode {
    if (!visitedOffsets.add(ifdOffset)) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("Circular or duplicate IFD reference detected, skipping"))
    }
    if (ifdOffset + 2 > itemEnd) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("IFD too short to contain entry_count"))
    }
    val entryCount = readUInt16Endian(reader, ifdOffset, littleEndian)
    val fields = mutableListOf<BoxField>()
    val children = mutableListOf<BoxNode>()
    var pos = ifdOffset + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > itemEnd) break
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

        when (tag) {
            TAG_EXIF_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Exif", TAG_NAMES_EXIF, visitedOffsets),
                )
            }
            TAG_GPS_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "GPS", TAG_NAMES_GPS, visitedOffsets),
                )
            }
            TAG_INTEROP_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(
                    decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Interop", TAG_NAMES_EXIF, visitedOffsets),
                )
            }
            TAG_MAKER_NOTE -> {
                if (valueAbsolutePos >= 0 && valueAbsolutePos + count <= itemEnd) {
                    children.add(decodeMakerNote(reader, tiffStart, valueAbsolutePos, count.toInt(), littleEndian, itemEnd))
                }
            }
            else -> {
                val name = tagNames[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
                if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
                    fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
                } else {
                    val display = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                    fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
                }
            }
        }
        pos += 12
    }
    return BoxNode(
        type = label, offset = ifdOffset, headerSize = 2, size = pos - ifdOffset,
        fields = fields, children = children,
    )
}

private fun decodeMakerNote(
    reader: ByteReader,
    tiffStart: Long,
    absolutePos: Long,
    byteLength: Int,
    littleEndian: Boolean,
    itemEnd: Long,
): BoxNode {
    val endPos = absolutePos + byteLength
    if (byteLength < 2) {
        return BoxNode("MakerNote", absolutePos, 0, byteLength.toLong(), warnings = listOf("MakerNote too short"))
    }
    val entryCount = readUInt16Endian(reader, absolutePos, littleEndian)
    val fields = mutableListOf<BoxField>()
    var pos = absolutePos + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > endPos) break
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
        val name = TAG_NAMES_MAKERNOTE[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
        if (valueAbsolutePos < 0 || valueAbsolutePos + totalSize > itemEnd) {
            fields.add(BoxField(name, "(out of bounds)", valueAbsolutePos, totalSize))
        } else {
            val display = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
            fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
        }
        pos += 12
    }
    return BoxNode(type = "MakerNote", offset = absolutePos, headerSize = 2, size = byteLength.toLong(), fields = fields)
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
