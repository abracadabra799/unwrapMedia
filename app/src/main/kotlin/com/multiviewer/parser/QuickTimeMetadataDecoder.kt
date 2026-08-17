package com.multiviewer.parser

import java.nio.charset.StandardCharsets

data class QuickTimeMetadataKey(
    val index: Int,
    val namespace: String,
    val key: String,
    val offset: Long,
    val length: Long,
)

val KNOWN_QUICKTIME_KEYS = mapOf(
    "com.apple.quicktime.make" to "Make",
    "com.apple.quicktime.model" to "Model",
    "com.apple.quicktime.software" to "Software",
    "com.apple.quicktime.creationdate" to "Creation Date",
    "com.apple.quicktime.location.ISO6709" to "Location (ISO 6709)",
    "com.apple.quicktime.location.accuracy.horizontal" to "Location Horizontal Accuracy",
    "com.apple.quicktime.location.name" to "Location Name",
    "com.apple.quicktime.location.body" to "Location Body",
    "com.apple.quicktime.location.note" to "Location Note",
    "com.apple.quicktime.camera.lens_model" to "Lens Model",
    "com.apple.quicktime.camera.focal_length" to "Focal Length",
    "com.apple.quicktime.camera.focal_length.35mm_equivalent" to "Focal Length In 35mm Format",
    "com.apple.quicktime.camera.aperture_f_number" to "Aperture",
    "com.apple.quicktime.camera.exposure_time" to "Exposure Time",
    "com.apple.quicktime.camera.iso" to "ISO",
    "com.apple.quicktime.camera.flash" to "Flash",
    "com.apple.quicktime.camera.white_balance" to "White Balance",
    "com.apple.quicktime.camera.focus_distance" to "Focus Distance",
    "com.apple.quicktime.content.identifier" to "Content Identifier",
    "com.apple.quicktime.live-photo.vitality-score" to "Live Photo Vitality Score",
    "com.apple.quicktime.live-photo.vitality-scoring-version" to "Live Photo Vitality Scoring Version",
    "com.apple.quicktime.live-photo.still-image-time" to "Live Photo Still Image Time",
    "com.apple.quicktime.live-photo.still-image-transform" to "Live Photo Still Image Transform",
    "com.apple.quicktime.live-photo.still-image-dimensions" to "Live Photo Still Image Dimensions",
    "com.apple.quicktime.smartstyle.rendering-version" to "Smart Style Rendering Version",
    "com.apple.quicktime.smartstyle.tone" to "Smart Style Tone",
    "com.apple.quicktime.smartstyle.color" to "Smart Style Color",
    "com.apple.quicktime.smartstyle.intensity" to "Smart Style Intensity",
    "com.apple.quicktime.smartstyle.bypass" to "Smart Style Bypass",
    "com.apple.quicktime.smartstyle.cast" to "Smart Style Cast",
    "com.apple.quicktime.video-orientation" to "Video Orientation",
    "com.apple.quicktime.full-frame-rate-playback-intent" to "Full Frame Rate Playback Intent",
    "com.apple.quicktime.cinematic-video.params" to "Cinematic Video Parameters",
    "com.apple.quicktime.spatial-audio" to "Spatial Audio",
    "com.apple.quicktime.display-color-space" to "Display Color Space",
    "com.apple.quicktime.artwork" to "Artwork",
    "com.apple.quicktime.author" to "Author",
    "com.apple.quicktime.copyright" to "Copyright",
    "com.apple.quicktime.description" to "Description",
    "com.apple.quicktime.title" to "Title",
    "\u00A9nam" to "Title",
    "\u00A9ART" to "Artist",
    "\u00A9alb" to "Album",
    "\u00A9day" to "Year",
    "\u00A9cmt" to "Comment",
    "\u00A9gen" to "Genre",
    "\u00A9wrt" to "Composer",
    "\u00A9too" to "Encoder Software",
    "covr" to "Cover Art",
)

object QuickTimeKeysBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 8) {
            return BoxNode(
                type = type,
                offset = offset,
                headerSize = headerSize,
                size = size,
                warnings = warnings + "keys box too short",
            )
        }

        val version = reader.readUInt8(payloadStart)
        val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
            (reader.readUInt8(payloadStart + 2) shl 8) or
            reader.readUInt8(payloadStart + 3)
        val entryCount = reader.readUInt32(payloadStart + 4)

        val fields = mutableListOf(
            BoxField("version", version.toString(), payloadStart, 1),
            BoxField("flags", "0x%06x".format(flags), payloadStart + 1, 3),
            BoxField("entry_count", entryCount.toString(), payloadStart + 4, 4),
        )

        var pos = payloadStart + 8
        for (i in 1..entryCount.toInt()) {
            if (pos + 8 > payloadEnd) {
                fields.add(BoxField("key[$i]", "(truncated)", pos, payloadEnd - pos))
                break
            }
            val keySize = reader.readUInt32(pos)
            if (keySize < 8 || pos + keySize > payloadEnd) {
                fields.add(BoxField("key[$i]", "(invalid size $keySize)", pos, (payloadEnd - pos).coerceAtLeast(0)))
                break
            }
            val namespaceBytes = reader.readBytes(pos + 4, 4)
            val namespace = String(namespaceBytes, StandardCharsets.US_ASCII)
            val keyBytes = reader.readBytes(pos + 8, (keySize - 8).toInt())
            val keyStr = String(keyBytes, StandardCharsets.UTF_8)
            fields.add(BoxField("key[$i]", "$namespace:$keyStr", pos, keySize))
            pos += keySize
        }

        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = warnings,
            summary = "$entryCount keys",
        )
    }
}

object QuickTimeDataBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 8) {
            return BoxNode(
                type = type,
                offset = offset,
                headerSize = headerSize,
                size = size,
                warnings = warnings + "data box too short",
            )
        }

        val typeFlags = reader.readUInt32(payloadStart)
        val typeCode = (typeFlags and 0x00FFFFFF).toInt()
        val locale = reader.readUInt32(payloadStart + 4)
        val dataLen = (payloadEnd - (payloadStart + 8)).toInt()
        val dataOffset = payloadStart + 8

        val valueDisplay = decodeDataValue(reader, typeCode, dataOffset, dataLen)

        val fields = listOf(
            BoxField("type_code", "$typeCode (0x%06x)".format(typeCode), payloadStart, 4),
            BoxField("locale", locale.toString(), payloadStart + 4, 4),
            BoxField("value", valueDisplay, dataOffset, dataLen.toLong()),
        )

        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = warnings,
            summary = valueDisplay,
        )
    }
}

object QuickTimeIlstBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val children = mutableListOf<BoxNode>()
        var pos = payloadStart
        while (pos + 8 <= payloadEnd) {
            val itemSize = reader.readUInt32(pos)
            if (itemSize < 8 || pos + itemSize > payloadEnd) break
            val itemType = reader.readFourCC(pos + 4)
            val itemPayloadStart = pos + 8
            val itemPayloadEnd = pos + itemSize
            val itemChildren = parseBoxes(reader, itemPayloadStart, itemPayloadEnd)
            children.add(
                BoxNode(
                    type = itemType,
                    offset = pos,
                    headerSize = 8,
                    size = itemSize,
                    children = itemChildren,
                ),
            )
            pos += itemSize
        }
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            children = children,
            warnings = warnings,
            summary = "${children.size} items",
        )
    }
}

fun decodeDataValue(reader: ByteReader, typeCode: Int, dataOffset: Long, dataLen: Int): String {
    if (dataLen <= 0) return ""
    return when (typeCode) {
        1, 4, 5 -> { // UTF-8 (1=text, 4=HTML, 5=XML)
            val bytes = reader.readBytes(dataOffset, dataLen)
            String(bytes, StandardCharsets.UTF_8)
        }
        2 -> { // UTF-16BE
            val bytes = reader.readBytes(dataOffset, dataLen)
            String(bytes, StandardCharsets.UTF_16BE)
        }
        3 -> { // Shift-JIS
            val bytes = reader.readBytes(dataOffset, dataLen)
            try {
                String(bytes, java.nio.charset.Charset.forName("Shift_JIS"))
            } catch (_: Exception) {
                String(bytes, StandardCharsets.ISO_8859_1)
            }
        }
        13 -> "JPEG Image ($dataLen bytes)"
        14 -> "PNG Image ($dataLen bytes)"
        27 -> "BMP Image ($dataLen bytes)"
        21 -> { // Signed integer
            when (dataLen) {
                1 -> reader.readUInt8(dataOffset).toByte().toString()
                2 -> reader.readUInt16(dataOffset).toShort().toString()
                3 -> {
                    val b0 = reader.readUInt8(dataOffset).toByte().toInt()
                    val b1 = reader.readUInt8(dataOffset + 1)
                    val b2 = reader.readUInt8(dataOffset + 2)
                    ((b0 shl 16) or (b1 shl 8) or b2).toString()
                }
                4 -> (reader.readUInt32(dataOffset).toInt()).toString()
                8 -> reader.readUInt64(dataOffset).toString()
                else -> {
                    val bytes = reader.readBytes(dataOffset, minOf(dataLen, 16))
                    bytes.joinToString(" ") { "%02x".format(it) }
                }
            }
        }
        22 -> { // Unsigned integer
            when (dataLen) {
                1 -> reader.readUInt8(dataOffset).toString()
                2 -> reader.readUInt16(dataOffset).toString()
                3 -> {
                    val b0 = reader.readUInt8(dataOffset)
                    val b1 = reader.readUInt8(dataOffset + 1)
                    val b2 = reader.readUInt8(dataOffset + 2)
                    ((b0 shl 16) or (b1 shl 8) or b2).toString()
                }
                4 -> reader.readUInt32(dataOffset).toString()
                8 -> {
                    val hi = reader.readUInt32(dataOffset)
                    val lo = reader.readUInt32(dataOffset + 4)
                    val ulongVal = (hi.toULong() shl 32) or lo.toULong()
                    ulongVal.toString()
                }
                else -> {
                    val bytes = reader.readBytes(dataOffset, minOf(dataLen, 16))
                    bytes.joinToString(" ") { "%02x".format(it) }
                }
            }
        }
        23 -> { // Float32
            if (dataLen >= 4) {
                val bits = reader.readUInt32(dataOffset).toInt()
                val f = java.lang.Float.intBitsToFloat(bits)
                String.format(java.util.Locale.US, "%.4f", f)
            } else "(invalid float)"
        }
        24 -> { // Float64
            if (dataLen >= 8) {
                val bits = reader.readUInt64(dataOffset)
                val d = java.lang.Double.longBitsToDouble(bits)
                String.format(java.util.Locale.US, "%.4f", d)
            } else "(invalid double)"
        }
        else -> { // Binary / undefined
            val previewLen = minOf(dataLen, 32)
            val bytes = reader.readBytes(dataOffset, previewLen)
            val hex = bytes.joinToString(" ") { "%02x".format(it) }
            if (dataLen > previewLen) "$hex... ($dataLen bytes)" else "$hex ($dataLen bytes)"
        }
    }
}

fun enrichQuickTimeMetadata(reader: ByteReader, children: List<BoxNode>): List<BoxNode> {
    val keysNode = children.find { it.type == "keys" }
    val ilstNode = children.find { it.type == "ilst" }

    // Parse keys map: 1-based index -> QuickTimeMetadataKey
    val keysMap = mutableMapOf<Int, QuickTimeMetadataKey>()
    if (keysNode != null) {
        val payloadStart = keysNode.offset + keysNode.headerSize
        val payloadEnd = keysNode.offset + keysNode.size
        if (payloadEnd - payloadStart >= 8) {
            val entryCount = reader.readUInt32(payloadStart + 4)
            var pos = payloadStart + 8
            for (i in 1..entryCount.toInt()) {
                if (pos + 8 > payloadEnd) break
                val keySize = reader.readUInt32(pos)
                if (keySize < 8 || pos + keySize > payloadEnd) break
                val namespaceBytes = reader.readBytes(pos + 4, 4)
                val namespace = String(namespaceBytes, StandardCharsets.US_ASCII)
                val keyBytes = reader.readBytes(pos + 8, (keySize - 8).toInt())
                val keyStr = String(keyBytes, StandardCharsets.UTF_8)
                keysMap[i] = QuickTimeMetadataKey(i, namespace, keyStr, pos, keySize)
                pos += keySize
            }
        }
    }

    val extractedFields = mutableListOf<BoxField>()
    val resultChildren = children.toMutableList()

    if (ilstNode != null) {
        val enrichedIlstChildren = ilstNode.children.map { itemNode ->
            // Try to resolve key index (e.g. 0x00000001 or FourCC)
            val itemTypeChars = itemNode.type
            val indexVal = if (itemTypeChars.length == 4) {
                ((itemTypeChars[0].code and 0xFF) shl 24) or
                    ((itemTypeChars[1].code and 0xFF) shl 16) or
                    ((itemTypeChars[2].code and 0xFF) shl 8) or
                    (itemTypeChars[3].code and 0xFF)
            } else 0

            val keyEntry = keysMap[indexVal]
            val keyString = keyEntry?.key ?: itemNode.type
            val friendlyName = KNOWN_QUICKTIME_KEYS[keyString] ?: keyString

            // Find data box inside itemNode
            val dataChild = itemNode.children.find { it.type == "data" }
            val dataValue = dataChild?.fields?.find { it.name == "value" }?.value
                ?: dataChild?.summary

            if (dataValue != null) {
                val displayName = if (friendlyName != keyString) "$friendlyName ($keyString)" else keyString
                extractedFields.add(
                    BoxField(
                        name = displayName,
                        value = dataValue,
                        offset = itemNode.offset,
                        length = itemNode.size,
                    ),
                )
            }

            if (keyString != itemNode.type) {
                itemNode.copy(
                    type = keyString,
                    summary = dataValue ?: itemNode.summary,
                )
            } else itemNode
        }

        val ilstIndex = resultChildren.indexOf(ilstNode)
        if (ilstIndex >= 0) {
            resultChildren[ilstIndex] = ilstNode.copy(children = enrichedIlstChildren)
        }
    }

    if (extractedFields.isNotEmpty()) {
        val appleMetaNode = BoxNode(
            type = "Apple QuickTime Metadata",
            offset = ilstNode?.offset ?: 0L,
            headerSize = 0,
            size = ilstNode?.size ?: 0L,
            fields = extractedFields,
            summary = "${extractedFields.size} properties",
        )
        resultChildren.add(appleMetaNode)
    }

    return resultChildren
}
