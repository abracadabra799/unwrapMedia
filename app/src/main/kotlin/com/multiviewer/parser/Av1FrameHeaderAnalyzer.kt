package com.multiviewer.parser

import com.multiviewer.ui.FrameInfo
import java.io.File

private const val OBU_TYPE_FRAME_HEADER = 3
private const val OBU_TYPE_FRAME = 6
private const val MAX_FRAME_HEADER_PREFIX_BYTES = 4096

// Walks `frames` (decode order, from FrameTypeAnalyzer.probeFrameTypes) and, for each frame with a
// known byteOffset, locates its OBU_FRAME_HEADER or OBU_FRAME OBU -- the wrapper differs (a
// standalone frame_header_obu(), or one embedded at the start of an OBU_FRAME per AV1 spec 5.10
// frame_obu()), but both start with frame_header_obu() bits at position 0 of the OBU's own payload,
// so both are handed to parseAv1FrameHeader the same way. A frame with no byteOffset, no located
// header OBU, or a parse failure is simply absent from the returned map -- this pass never aborts
// partway through the frame list (mirrors the error-handling convention established by
// extractAv1CRawSequenceHeader). Frame headers don't need to be parsed in decode order for
// correctness (see parseAv1FrameHeader's doc comment on why this plan's parsing is stateless); the
// loop below follows `frames`' own order purely because that's how the list already arrives.
fun analyzeAv1FrameHeaders(file: File, frames: List<FrameInfo>, seqHeader: Av1SequenceHeader): Map<Long, Av1FrameHeader> {
    val result = mutableMapOf<Long, Av1FrameHeader>()
    try {
        ByteReader.open(file).use { reader ->
            for (frame in frames) {
                val byteOffset = frame.byteOffset ?: continue
                if (frame.sizeBytes <= 0) continue
                val header = locateAndParseFrameHeader(reader, byteOffset, frame.sizeBytes, seqHeader)
                if (header != null) {
                    result[byteOffset] = header
                }
            }
        }
    } catch (e: Exception) {
        return result
    }
    return result
}

// Walks the OBUs in one sample's byte range [byteOffset, byteOffset + sizeBytes), looking for the
// first OBU_FRAME_HEADER or OBU_FRAME, then hands a bounded prefix of that OBU's own payload (not
// the whole OBU, which can be large -- the curated fields all fall within the first few dozen bits)
// to parseAv1FrameHeader. Mirrors extractAv1CRawSequenceHeader's OBU-walking loop shape.
private fun locateAndParseFrameHeader(reader: ByteReader, byteOffset: Long, sizeBytes: Int, seqHeader: Av1SequenceHeader): Av1FrameHeader? {
    return try {
        val sampleEnd = byteOffset + sizeBytes
        var pos = byteOffset
        while (pos < sampleEnd) {
            val header = parseObuHeader(reader, pos)
            if (!header.hasSizeField) return null // can't determine this OBU's length
            val (obuSize, obuPayloadStart) = readLeb128(reader, pos + header.headerSize)
            if (obuPayloadStart + obuSize > sampleEnd) return null
            if (header.obuType == OBU_TYPE_FRAME_HEADER || header.obuType == OBU_TYPE_FRAME) {
                val prefixLen = minOf(obuSize, MAX_FRAME_HEADER_PREFIX_BYTES.toLong()).toInt()
                val payload = reader.readBytes(obuPayloadStart, prefixLen)
                return parseAv1FrameHeader(payload, seqHeader)
            }
            pos = obuPayloadStart + obuSize
        }
        null
    } catch (e: Exception) {
        null
    }
}
