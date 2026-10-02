package com.multiviewer.parser

import java.io.File

private const val AV2_SEQUENCE_HEADER_OBU_TYPE = 1

fun extractAv2CRawSequenceHeader(file: File, av2CNode: BoxNode): RawNal? = try {
    ByteReader.open(file).use { reader ->
        val start = av2CNode.offset + av2CNode.headerSize
        val end = minOf(av2CNode.offset + av2CNode.size, reader.length)
        if (end - start < 2) return@use null
        val count = reader.readUInt8(start + 1) + 1
        var cursor = start + 2
        repeat(count) {
            val length = (readAv2Leb128(reader, cursor, end) as? Av2ParseResult.Value) ?: return@use null
            val obuStart = length.nextOffset
            val obuEnd = obuStart + length.value
            if (length.value <= 0 || obuEnd < obuStart || obuEnd > end) return@use null
            val header = (parseAv2ObuHeader(reader, obuStart, obuEnd) as? Av2ParseResult.Value)?.value ?: return@use null
            if (header.obuType == AV2_SEQUENCE_HEADER_OBU_TYPE) {
                val payloadStart = obuStart + header.headerSize
                return@use RawNal(reader.readBytes(payloadStart, (obuEnd - payloadStart).toInt()), payloadStart)
            }
            cursor = obuEnd
        }
        null
    }
} catch (_: Exception) { null }
