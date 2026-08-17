package com.multiviewer.parser

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter

data class BinaryPlistLimits(
    val maxDepth: Int = 16,
    val maxObjects: Int = 4096,
    val maxPreviewBytes: Int = 256,
)

fun decodeBinaryPlist(
    reader: ByteReader,
    start: Long,
    length: Long,
    limits: BinaryPlistLimits = BinaryPlistLimits(),
): BoxNode {
    val warnings = mutableListOf<String>()
    if (length < 32 + 8) {
        return BoxNode(
            type = "BinaryPlist",
            offset = start,
            headerSize = 0,
            size = length,
            warnings = listOf("Binary plist too short ($length bytes)"),
        )
    }

    val magic = reader.readBytes(start, 8)
    val magicStr = String(magic, StandardCharsets.US_ASCII)
    if (!magicStr.startsWith("bplist")) {
        return BoxNode(
            type = "BinaryPlist",
            offset = start,
            headerSize = 8,
            size = length,
            warnings = listOf("Invalid binary plist magic: $magicStr"),
        )
    }

    val trailerOffset = start + length - 32
    val offsetIntSize = reader.readUInt8(trailerOffset + 6)
    val objectRefSize = reader.readUInt8(trailerOffset + 7)
    val numObjects = reader.readUInt64(trailerOffset + 8)
    val topObject = reader.readUInt64(trailerOffset + 16)
    val offsetTableOffset = reader.readUInt64(trailerOffset + 24)

    if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) {
        return BoxNode(
            type = "BinaryPlist",
            offset = start,
            headerSize = 8,
            size = length,
            warnings = listOf("Invalid trailer sizes: offsetIntSize=$offsetIntSize, objectRefSize=$objectRefSize"),
        )
    }

    if (numObjects <= 0 || numObjects > limits.maxObjects) {
        return BoxNode(
            type = "BinaryPlist",
            offset = start,
            headerSize = 8,
            size = length,
            warnings = listOf("Object count $numObjects exceeds limit (${limits.maxObjects}) or is invalid"),
        )
    }

    if (offsetTableOffset < 8 || offsetTableOffset + numObjects * offsetIntSize > length - 32) {
        return BoxNode(
            type = "BinaryPlist",
            offset = start,
            headerSize = 8,
            size = length,
            warnings = listOf("Offset table out of bounds: tableOffset=$offsetTableOffset, length=$length"),
        )
    }

    if (topObject >= numObjects) {
        return BoxNode(
            type = "BinaryPlist",
            offset = start,
            headerSize = 8,
            size = length,
            warnings = listOf("Top object index $topObject out of range ($numObjects)"),
        )
    }

    // Read offset table
    val objectOffsets = LongArray(numObjects.toInt())
    val tableStart = start + offsetTableOffset
    for (i in 0 until numObjects.toInt()) {
        val entryPos = tableStart + i * offsetIntSize
        objectOffsets[i] = readVariableUInt(reader, entryPos, offsetIntSize)
    }

    val decoder = PlistObjectDecoder(
        reader = reader,
        plistStart = start,
        plistLength = length,
        offsetTable = objectOffsets,
        objectRefSize = objectRefSize,
        limits = limits,
    )

    val rootResult = decoder.decodeObject(topObject.toInt(), 0)
    warnings.addAll(decoder.warnings)

    return when (rootResult) {
        is PlistDecodedNode -> {
            val rootNode = rootResult.node
            BoxNode(
                type = "BinaryPlist",
                offset = start,
                headerSize = 8,
                size = length,
                children = rootNode.children,
                fields = rootNode.fields,
                warnings = warnings + rootNode.warnings,
                summary = rootNode.summary,
            )
        }
        is PlistDecodedField -> {
            BoxNode(
                type = "BinaryPlist",
                offset = start,
                headerSize = 8,
                size = length,
                fields = listOf(rootResult.field),
                warnings = warnings,
            )
        }
        null -> {
            BoxNode(
                type = "BinaryPlist",
                offset = start,
                headerSize = 8,
                size = length,
                warnings = warnings.ifEmpty { listOf("Failed to decode root object") },
            )
        }
    }
}

private sealed interface PlistDecodedItem
private data class PlistDecodedField(val field: BoxField) : PlistDecodedItem
private data class PlistDecodedNode(val node: BoxNode) : PlistDecodedItem

private class PlistObjectDecoder(
    private val reader: ByteReader,
    private val plistStart: Long,
    private val plistLength: Long,
    private val offsetTable: LongArray,
    private val objectRefSize: Int,
    private val limits: BinaryPlistLimits,
) {
    val warnings = mutableListOf<String>()
    private val activeStack = mutableSetOf<Int>()

    fun decodeObject(index: Int, depth: Int): PlistDecodedItem? {
        if (depth > limits.maxDepth) {
            warnings.add("Exceeded maximum recursion depth (${limits.maxDepth})")
            return null
        }
        if (index < 0 || index >= offsetTable.size) {
            warnings.add("Object index $index out of bounds")
            return null
        }
        if (!activeStack.add(index)) {
            warnings.add("Cyclic object reference detected at object $index")
            return null
        }

        try {
            val relOffset = offsetTable[index]
            val absOffset = plistStart + relOffset
            if (relOffset < 0 || relOffset >= plistLength) {
                warnings.add("Object offset $relOffset out of bounds")
                return null
            }

            val marker = reader.readUInt8(absOffset)
            val type = (marker and 0xF0) shr 4
            val info = marker and 0x0F

            return when (type) {
                0x0 -> decodeSimple(absOffset, info)
                0x1 -> decodeInt(absOffset, info)
                0x2 -> decodeReal(absOffset, info)
                0x3 -> decodeDate(absOffset, info)
                0x4 -> decodeData(absOffset, info)
                0x5 -> decodeAsciiString(absOffset, info)
                0x6 -> decodeUtf16String(absOffset, info)
                0x8 -> decodeUid(absOffset, info)
                0xA -> decodeArray(absOffset, info, depth, isSet = false)
                0xC -> decodeArray(absOffset, info, depth, isSet = true)
                0xD -> decodeDict(absOffset, info, depth)
                else -> {
                    warnings.add("Unsupported object type 0x${type.toString(16)}")
                    PlistDecodedField(BoxField("Unknown", "type=0x${type.toString(16)}", absOffset, 1))
                }
            }
        } finally {
            activeStack.remove(index)
        }
    }

    private fun decodeSimple(offset: Long, info: Int): PlistDecodedItem {
        return when (info) {
            0x00 -> PlistDecodedField(BoxField("null", "null", offset, 1))
            0x08 -> PlistDecodedField(BoxField("bool", "false", offset, 1))
            0x09 -> PlistDecodedField(BoxField("bool", "true", offset, 1))
            0x0F -> PlistDecodedField(BoxField("fill", "0xFF", offset, 1))
            else -> PlistDecodedField(BoxField("simple", "0x${info.toString(16)}", offset, 1))
        }
    }

    private fun decodeInt(offset: Long, info: Int): PlistDecodedItem {
        val byteCount = 1 shl info
        val valOffset = offset + 1
        val valueStr = when (byteCount) {
            1 -> reader.readUInt8(valOffset).toByte().toString()
            2 -> reader.readUInt16(valOffset).toShort().toString()
            4 -> (reader.readUInt32(valOffset).toInt()).toString()
            8 -> reader.readUInt64(valOffset).toString()
            16 -> {
                val hi = reader.readUInt64(valOffset)
                val lo = reader.readUInt64(valOffset + 8)
                "0x%016x%016x".format(hi, lo)
            }
            else -> "int(${byteCount}b)"
        }
        return PlistDecodedField(BoxField("int", valueStr, offset, 1L + byteCount))
    }

    private fun decodeReal(offset: Long, info: Int): PlistDecodedItem {
        val byteCount = 1 shl info
        val valOffset = offset + 1
        val valueStr = when (byteCount) {
            4 -> {
                val bits = reader.readUInt32(valOffset).toInt()
                java.lang.Float.intBitsToFloat(bits).toString()
            }
            8 -> {
                val bits = reader.readUInt64(valOffset)
                java.lang.Double.longBitsToDouble(bits).toString()
            }
            else -> "real(${byteCount}b)"
        }
        return PlistDecodedField(BoxField("real", valueStr, offset, 1L + byteCount))
    }

    private fun decodeDate(offset: Long, info: Int): PlistDecodedItem {
        val valOffset = offset + 1
        val bits = reader.readUInt64(valOffset)
        val seconds = java.lang.Double.longBitsToDouble(bits)
        // Apple epoch is 2001-01-01 00:00:00 UTC (978307200 seconds after Unix epoch)
        val epochSeconds = (seconds + 978307200.0).toLong()
        val isoDate = try {
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epochSeconds))
        } catch (_: Exception) {
            "$seconds (Apple epoch)"
        }
        return PlistDecodedField(BoxField("date", isoDate, offset, 9))
    }

    private fun decodeData(offset: Long, info: Int): PlistDecodedItem {
        val (length, headerLen) = readLength(offset, info)
        val valOffset = offset + headerLen
        val previewLen = minOf(length.toInt(), limits.maxPreviewBytes)
        val bytes = if (previewLen > 0) reader.readBytes(valOffset, previewLen) else ByteArray(0)
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        val display = if (length > previewLen) "$hex... ($length bytes)" else "$hex ($length bytes)"
        return PlistDecodedField(BoxField("data", display, offset, headerLen + length))
    }

    private fun decodeAsciiString(offset: Long, info: Int): PlistDecodedItem {
        val (length, headerLen) = readLength(offset, info)
        val valOffset = offset + headerLen
        val strBytes = reader.readBytes(valOffset, length.toInt().coerceAtMost(limits.maxPreviewBytes))
        val str = String(strBytes, StandardCharsets.US_ASCII)
        val display = if (length > limits.maxPreviewBytes) "$str... (${length} chars)" else str
        return PlistDecodedField(BoxField("string", display, offset, headerLen + length))
    }

    private fun decodeUtf16String(offset: Long, info: Int): PlistDecodedItem {
        val (charCount, headerLen) = readLength(offset, info)
        val byteLen = charCount * 2
        val valOffset = offset + headerLen
        val readBytesLen = (charCount.toInt().coerceAtMost(limits.maxPreviewBytes)) * 2
        val strBytes = reader.readBytes(valOffset, readBytesLen)
        val str = String(strBytes, StandardCharsets.UTF_16BE)
        val display = if (charCount > limits.maxPreviewBytes) "$str... (${charCount} chars)" else str
        return PlistDecodedField(BoxField("string", display, offset, headerLen + byteLen))
    }

    private fun decodeUid(offset: Long, info: Int): PlistDecodedItem {
        val byteCount = info + 1
        val valOffset = offset + 1
        val value = readVariableUInt(reader, valOffset, byteCount)
        return PlistDecodedField(BoxField("UID", value.toString(), offset, 1L + byteCount))
    }

    private fun decodeArray(offset: Long, info: Int, depth: Int, isSet: Boolean): PlistDecodedItem {
        val (count, headerLen) = readLength(offset, info)
        val totalSize = headerLen + count * objectRefSize
        val refsOffset = offset + headerLen

        val children = mutableListOf<BoxNode>()
        val fields = mutableListOf<BoxField>()

        for (i in 0 until count.toInt()) {
            if (i >= limits.maxObjects) {
                warnings.add("Array item count exceeds limit (${limits.maxObjects})")
                break
            }
            val refPos = refsOffset + i * objectRefSize
            val refIndex = readVariableUInt(reader, refPos, objectRefSize).toInt()
            when (val item = decodeObject(refIndex, depth + 1)) {
                is PlistDecodedField -> {
                    fields.add(item.field.copy(name = "[$i]"))
                }
                is PlistDecodedNode -> {
                    children.add(item.node.copy(type = "[$i] ${item.node.type}"))
                }
                null -> {}
            }
        }

        val typeName = if (isSet) "Set" else "Array"
        return PlistDecodedNode(
            BoxNode(
                type = typeName,
                offset = offset,
                headerSize = headerLen,
                size = totalSize,
                children = children,
                fields = fields,
                summary = "$count items",
            ),
        )
    }

    private fun decodeDict(offset: Long, info: Int, depth: Int): PlistDecodedItem {
        val (count, headerLen) = readLength(offset, info)
        val totalSize = headerLen + count * 2 * objectRefSize
        val keysOffset = offset + headerLen
        val valuesOffset = keysOffset + count * objectRefSize

        val fields = mutableListOf<BoxField>()
        val children = mutableListOf<BoxNode>()
        val keyMap = mutableMapOf<String, String>()

        for (i in 0 until count.toInt()) {
            if (i >= limits.maxObjects) {
                warnings.add("Dictionary key count exceeds limit (${limits.maxObjects})")
                break
            }
            val keyRefPos = keysOffset + i * objectRefSize
            val keyIndex = readVariableUInt(reader, keyRefPos, objectRefSize).toInt()
            val keyName = getStringValue(keyIndex) ?: "Key$i"

            val valRefPos = valuesOffset + i * objectRefSize
            val valIndex = readVariableUInt(reader, valRefPos, objectRefSize).toInt()

            when (val valItem = decodeObject(valIndex, depth + 1)) {
                is PlistDecodedField -> {
                    fields.add(valItem.field.copy(name = keyName))
                    keyMap[keyName] = valItem.field.value
                }
                is PlistDecodedNode -> {
                    children.add(valItem.node.copy(type = keyName))
                }
                null -> {
                    fields.add(BoxField(keyName, "(null)", valRefPos, objectRefSize.toLong()))
                }
            }
        }

        // Check if this dictionary represents a CMTime structure
        val cmTimeSummary = if (keyMap.containsKey("value") && keyMap.containsKey("timescale")) {
            val v = keyMap["value"]?.toLongOrNull()
            val ts = keyMap["timescale"]?.toLongOrNull()
            if (v != null && ts != null && ts != 0L) {
                val sec = v.toDouble() / ts.toDouble()
                "$v/$ts s (${String.format(java.util.Locale.US, "%.3f", sec)}s)"
            } else null
        } else null

        val nodeType = if (cmTimeSummary != null) "CMTime" else "Dict"

        return PlistDecodedNode(
            BoxNode(
                type = nodeType,
                offset = offset,
                headerSize = headerLen,
                size = totalSize,
                children = children,
                fields = fields,
                summary = cmTimeSummary ?: "$count entries",
            ),
        )
    }

    private fun getStringValue(index: Int): String? {
        if (index < 0 || index >= offsetTable.size) return null
        val relOffset = offsetTable[index]
        val absOffset = plistStart + relOffset
        val marker = reader.readUInt8(absOffset)
        val type = (marker and 0xF0) shr 4
        val info = marker and 0x0F
        return when (type) {
            0x5 -> {
                val (len, hLen) = readLength(absOffset, info)
                val bytes = reader.readBytes(absOffset + hLen, len.toInt())
                String(bytes, StandardCharsets.US_ASCII)
            }
            0x6 -> {
                val (charCount, hLen) = readLength(absOffset, info)
                val bytes = reader.readBytes(absOffset + hLen, (charCount * 2).toInt())
                String(bytes, StandardCharsets.UTF_16BE)
            }
            else -> null
        }
    }

    private fun readLength(offset: Long, info: Int): Pair<Long, Int> {
        return if (info == 0x0F) {
            // Next object is an integer containing the length
            val intMarker = reader.readUInt8(offset + 1)
            val intInfo = intMarker and 0x0F
            val intByteCount = 1 shl intInfo
            val lenVal = readVariableUInt(reader, offset + 2, intByteCount)
            Pair(lenVal, 2 + intByteCount)
        } else {
            Pair(info.toLong(), 1)
        }
    }
}

private fun readVariableUInt(reader: ByteReader, offset: Long, size: Int): Long {
    var result = 0L
    for (i in 0 until size) {
        val b = reader.readUInt8(offset + i).toLong()
        result = (result shl 8) or (b and 0xFF)
    }
    return result
}
