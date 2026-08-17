package com.multiviewer.parser

import java.nio.charset.StandardCharsets

data class TimedMetadataLimits(
    val maxSamples: Int = 256,
    val maxSampleBytes: Int = 64 * 1024,
    val maxTotalBytes: Long = 4L * 1024 * 1024,
)

object TrakBoxDecoder : BoxDecoder {
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
        val children = parseBoxes(reader, payloadStart, payloadEnd).toMutableList()
        val tempNode = BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            children = children,
            warnings = warnings,
        )
        val timedMetaNode = decodeTimedMetadataTrack(reader, tempNode)
        if (timedMetaNode != null) {
            children.add(timedMetaNode)
        }
        return tempNode.copy(children = children)
    }
}

fun decodeTimedMetadataTrack(
    reader: ByteReader,
    trak: BoxNode,
    limits: TimedMetadataLimits = TimedMetadataLimits(),
): BoxNode? {
    val mdiaNode = trak.children.find { it.type == "mdia" } ?: return null
    val hdlrNode = mdiaNode.children.find { it.type == "hdlr" }
    val handlerType = hdlrNode?.fields?.find { it.name == "handler_type" }?.value
    if (handlerType != "mebx" && handlerType != "mdta" && handlerType != "meta") {
        return null
    }

    val mdhdNode = mdiaNode.children.find { it.type == "mdhd" }
    val timescale = mdhdNode?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull() ?: 1000L

    val minfNode = mdiaNode.children.find { it.type == "minf" }
    val stblNode = minfNode?.children?.find { it.type == "stbl" } ?: return null

    // Extract keys from stsd -> sample entries -> keyd
    val keysMap = mutableMapOf<Int, String>()
    val stsdNode = stblNode.children.find { it.type == "stsd" }
    if (stsdNode != null) {
        for (sampleEntry in stsdNode.children) {
            val keydNode = sampleEntry.children.find { it.type == "keyd" }
            if (keydNode != null) {
                val payloadStart = keydNode.offset + keydNode.headerSize
                val payloadEnd = keydNode.offset + keydNode.size
                if (payloadEnd - payloadStart >= 8) {
                    val entryCount = reader.readUInt32(payloadStart + 4)
                    var pos = payloadStart + 8
                    for (i in 1..entryCount.toInt()) {
                        if (pos + 8 > payloadEnd) break
                        val keySize = reader.readUInt32(pos)
                        if (keySize < 8 || pos + keySize > payloadEnd) break
                        val keyBytes = reader.readBytes(pos + 8, (keySize - 8).toInt())
                        val keyStr = String(keyBytes, StandardCharsets.UTF_8)
                        keysMap[i] = keyStr
                        pos += keySize
                    }
                }
            }
        }
    }

    // Extract sample timing from stts
    val sttsNode = stblNode.children.find { it.type == "stts" }
    val sampleDtsList = mutableListOf<Long>()
    val sampleDurationList = mutableListOf<Long>()
    if (sttsNode != null) {
        val payloadStart = sttsNode.offset + sttsNode.headerSize
        val payloadEnd = sttsNode.offset + sttsNode.size
        if (payloadEnd - payloadStart >= 8) {
            val entryCount = reader.readUInt32(payloadStart + 4)
            var currentDts = 0L
            var pos = payloadStart + 8
            for (i in 0 until entryCount.toInt()) {
                if (pos + 8 > payloadEnd) break
                val count = reader.readUInt32(pos)
                val delta = reader.readUInt32(pos + 4)
                for (c in 0 until count.toInt()) {
                    sampleDtsList.add(currentDts)
                    sampleDurationList.add(delta)
                    currentDts += delta
                }
                pos += 8
            }
        }
    }

    // Extract sample sizes from stsz
    val stszNode = stblNode.children.find { it.type == "stsz" }
    val sampleSizes = mutableListOf<Long>()
    if (stszNode != null) {
        val payloadStart = stszNode.offset + stszNode.headerSize
        val payloadEnd = stszNode.offset + stszNode.size
        if (payloadEnd - payloadStart >= 12) {
            val uniformSize = reader.readUInt32(payloadStart + 4)
            val sampleCount = reader.readUInt32(payloadStart + 8)
            if (uniformSize != 0L) {
                for (i in 0 until sampleCount.toInt()) {
                    sampleSizes.add(uniformSize)
                }
            } else {
                var pos = payloadStart + 12
                for (i in 0 until sampleCount.toInt()) {
                    if (pos + 4 > payloadEnd) break
                    sampleSizes.add(reader.readUInt32(pos))
                    pos += 4
                }
            }
        }
    }

    // Extract chunk offsets from stco / co64
    val chunkOffsets = mutableListOf<Long>()
    val stcoNode = stblNode.children.find { it.type == "stco" }
    val co64Node = stblNode.children.find { it.type == "co64" }
    if (stcoNode != null) {
        val payloadStart = stcoNode.offset + stcoNode.headerSize
        val payloadEnd = stcoNode.offset + stcoNode.size
        if (payloadEnd - payloadStart >= 8) {
            val entryCount = reader.readUInt32(payloadStart + 4)
            var pos = payloadStart + 8
            for (i in 0 until entryCount.toInt()) {
                if (pos + 4 > payloadEnd) break
                chunkOffsets.add(reader.readUInt32(pos))
                pos += 4
            }
        }
    } else if (co64Node != null) {
        val payloadStart = co64Node.offset + co64Node.headerSize
        val payloadEnd = co64Node.offset + co64Node.size
        if (payloadEnd - payloadStart >= 8) {
            val entryCount = reader.readUInt32(payloadStart + 4)
            var pos = payloadStart + 8
            for (i in 0 until entryCount.toInt()) {
                if (pos + 8 > payloadEnd) break
                chunkOffsets.add(reader.readUInt64(pos))
                pos += 8
            }
        }
    }

    // Extract sample to chunk from stsc
    data class StscEntry(val firstChunk: Int, val samplesPerChunk: Int, val sampleDesc: Int)
    val stscEntries = mutableListOf<StscEntry>()
    val stscNode = stblNode.children.find { it.type == "stsc" }
    if (stscNode != null) {
        val payloadStart = stscNode.offset + stscNode.headerSize
        val payloadEnd = stscNode.offset + stscNode.size
        if (payloadEnd - payloadStart >= 8) {
            val entryCount = reader.readUInt32(payloadStart + 4)
            var pos = payloadStart + 8
            for (i in 0 until entryCount.toInt()) {
                if (pos + 12 > payloadEnd) break
                val firstChunk = reader.readUInt32(pos).toInt()
                val samplesPerChunk = reader.readUInt32(pos + 4).toInt()
                val sampleDesc = reader.readUInt32(pos + 8).toInt()
                stscEntries.add(StscEntry(firstChunk, samplesPerChunk, sampleDesc))
                pos += 12
            }
        }
    }

    // Compute sample file offsets
    val sampleOffsets = mutableListOf<Long>()
    if (chunkOffsets.isNotEmpty() && stscEntries.isNotEmpty() && sampleSizes.isNotEmpty()) {
        var sampleIndex = 0
        for (chunkIdx in 1..chunkOffsets.size) {
            val stscEntry = stscEntries.lastOrNull { it.firstChunk <= chunkIdx } ?: stscEntries.first()
            val samplesInChunk = stscEntry.samplesPerChunk
            var currentOffsetInChunk = chunkOffsets[chunkIdx - 1]
            for (s in 0 until samplesInChunk) {
                if (sampleIndex >= sampleSizes.size) break
                sampleOffsets.add(currentOffsetInChunk)
                val sSize = sampleSizes[sampleIndex]
                currentOffsetInChunk += sSize
                sampleIndex++
            }
            if (sampleIndex >= sampleSizes.size) break
        }
    }

    val totalSamples = sampleSizes.size
    val warnings = mutableListOf<String>()
    val sampleLimit = minOf(totalSamples, limits.maxSamples)
    if (totalSamples > limits.maxSamples) {
        warnings.add("Track contains $totalSamples samples, decoding capped at maxSamples (${limits.maxSamples})")
    }

    val sampleNodes = mutableListOf<BoxNode>()
    var totalBytesDecoded = 0L

    for (i in 0 until sampleLimit) {
        if (totalBytesDecoded >= limits.maxTotalBytes) {
            warnings.add("Reached total byte limit (${limits.maxTotalBytes} bytes) at sample $i")
            break
        }

        val sOffset = if (i < sampleOffsets.size) sampleOffsets[i] else 0L
        val sSize = sampleSizes[i]
        val sDts = if (i < sampleDtsList.size) sampleDtsList[i] else 0L
        val sDur = if (i < sampleDurationList.size) sampleDurationList[i] else 0L
        val dtsSec = if (timescale > 0) sDts.toDouble() / timescale.toDouble() else sDts.toDouble()
        val durSec = if (timescale > 0) sDur.toDouble() / timescale.toDouble() else sDur.toDouble()

        if (sOffset <= 0 || sOffset + sSize > reader.length) {
            sampleNodes.add(
                BoxNode(
                    type = "Sample $i",
                    offset = sOffset,
                    headerSize = 0,
                    size = sSize,
                    warnings = listOf("Sample offset out of bounds ($sOffset, size $sSize)"),
                ),
            )
            continue
        }

        val readSize = minOf(sSize.toInt(), limits.maxSampleBytes)
        if (sSize > limits.maxSampleBytes) {
            warnings.add("Sample $i size ($sSize) exceeds maxSampleBytes (${limits.maxSampleBytes})")
        }
        totalBytesDecoded += readSize

        val isBplist = if (readSize >= 8) {
            val magic = reader.readBytes(sOffset, 8)
            String(magic, StandardCharsets.US_ASCII).startsWith("bplist")
        } else false

        val keyName = keysMap[1] ?: "Sample $i"
        val friendlyKey = KNOWN_QUICKTIME_KEYS[keyName] ?: keyName

        val sampleFields = mutableListOf(
            BoxField("DTS", "$sDts (${String.format(java.util.Locale.US, "%.3f", dtsSec)}s)", sOffset, 0),
            BoxField("Duration", "$sDur (${String.format(java.util.Locale.US, "%.3f", durSec)}s)", sOffset, 0),
            BoxField("Size", "$sSize bytes", sOffset, sSize),
        )

        var sampleChildren = emptyList<BoxNode>()
        var sampleSummary = "DTS=${String.format(java.util.Locale.US, "%.3f", dtsSec)}s ($sSize bytes)"

        if (isBplist) {
            val plistNode = decodeBinaryPlist(reader, sOffset, readSize.toLong())
            sampleChildren = listOf(plistNode.copy(type = "Payload (BinaryPlist)"))
            sampleFields.add(BoxField("Payload", "(binary plist, $readSize bytes)", sOffset, readSize.toLong()))
            if (plistNode.summary != null) {
                sampleSummary += " - ${plistNode.summary}"
            }
        } else {
            val valueDisplay = formatTimedSampleValue(reader, keyName, sOffset, readSize)
            sampleFields.add(BoxField("Value", valueDisplay, sOffset, readSize.toLong()))
            sampleSummary += " - $valueDisplay"
        }

        sampleNodes.add(
            BoxNode(
                type = "Sample $i ($friendlyKey)",
                offset = sOffset,
                headerSize = 0,
                size = sSize,
                fields = sampleFields,
                children = sampleChildren,
                summary = sampleSummary,
            ),
        )
    }

    return BoxNode(
        type = "Timed Metadata",
        offset = trak.offset,
        headerSize = 0,
        size = trak.size,
        children = sampleNodes,
        fields = listOf(
            BoxField("Handler", handlerType ?: "", trak.offset, 0),
            BoxField("Total Samples", totalSamples.toString(), trak.offset, 0),
            BoxField("Decoded Samples", sampleNodes.size.toString(), trak.offset, 0),
            BoxField("Timescale", timescale.toString(), trak.offset, 0),
        ) + keysMap.map { (idx, key) -> BoxField("Key[$idx]", key, trak.offset, 0) },
        warnings = warnings,
        summary = "$totalSamples samples ($handlerType)",
    )
}

private fun formatTimedSampleValue(reader: ByteReader, key: String, offset: Long, size: Int): String {
    return when {
        key.contains("video-orientation") -> {
            if (size == 4) {
                val degrees = reader.readUInt32(offset).toInt()
                "$degrees°"
            } else if (size == 1) {
                val v = reader.readUInt8(offset)
                "$v°"
            } else decodeRawBytes(reader, offset, size)
        }
        key.contains("still-image-time") -> {
            if (size == 4) {
                reader.readUInt32(offset).toString()
            } else if (size == 8) {
                reader.readUInt64(offset).toString()
            } else decodeRawBytes(reader, offset, size)
        }
        else -> decodeRawBytes(reader, offset, size)
    }
}

private fun decodeRawBytes(reader: ByteReader, offset: Long, size: Int): String {
    if (size <= 0) return ""
    val previewLen = minOf(size, 32)
    val bytes = reader.readBytes(offset, previewLen)
    val hex = bytes.joinToString(" ") { "%02x".format(it) }
    return if (size > previewLen) "$hex... ($size bytes)" else "$hex ($size bytes)"
}
