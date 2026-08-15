package com.multiviewer.parser

import java.io.File

private const val AV1C_FIXED_HEADER_SIZE = 4
private const val OBU_TYPE_SEQUENCE_HEADER = 1

// Reads av1C's 4-byte AV1CodecConfigurationRecord fixed header (see Av1CBoxDecoder, which decodes
// the same header for Structure Analyser display -- this function only uses it to find where
// configOBUs starts), then walks the OBUs in configOBUs (AV1 Codec ISO Media File Format Binding
// sec 2.2) looking for the first Sequence Header OBU -- typically the only OBU there, but not
// guaranteed. Returns just that OBU's own payload bytes and their absolute file offset as a
// RawNal (a generic bytes+offset pair despite its H.264-flavored name -- see RawNal.kt); the
// returned bytes do NOT include the OBU header or leb128 size prefix (see this plan's Global
// Constraints) -- parseAv1SequenceHeader parses straight from bit 0 of what's returned here.
fun extractAv1CRawSequenceHeader(file: File, av1CNode: BoxNode): RawNal? {
    return try {
        ByteReader.open(file).use { reader ->
            val payloadStart: Long = av1CNode.offset + av1CNode.headerSize.toLong()
            val payloadEnd: Long = av1CNode.offset + av1CNode.size
            if (payloadEnd - payloadStart < AV1C_FIXED_HEADER_SIZE.toLong()) return@use null
            var pos: Long = payloadStart + AV1C_FIXED_HEADER_SIZE.toLong()
            while (pos < payloadEnd) {
                val header = parseObuHeader(reader, pos)
                if (!header.hasSizeField) return@use null // can't determine this OBU's length
                val leb128Result = readLeb128(reader, pos + header.headerSize.toLong())
                val obuSize: Long = leb128Result.first
                val obuPayloadStart: Long = leb128Result.second
                if (obuPayloadStart + obuSize > payloadEnd) return@use null
                if (header.obuType == OBU_TYPE_SEQUENCE_HEADER) {
                    return@use RawNal(reader.readBytes(obuPayloadStart, obuSize.toInt()), obuPayloadStart)
                }
                pos = obuPayloadStart + obuSize
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}
