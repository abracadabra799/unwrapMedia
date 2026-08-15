package com.multiviewer.parser

// AV1's own bitstream framing unit -- distinct from H.264/HEVC's NAL units, with no
// emulation-prevention scheme. An OBU header is 1 byte, or 2 if obu_extension_flag is set (the
// extra byte carries temporal_id/spatial_id, not needed by anything in this codebase yet, so it's
// skipped rather than decoded). headerSize lets a caller know how many bytes to skip to reach
// whatever follows the header (a leb128 obu_size field, if hasSizeField, then the OBU's payload).
data class ObuHeader(
    val obuType: Int,
    val extensionFlag: Boolean,
    val hasSizeField: Boolean,
    val headerSize: Int,
)

// AV1 spec 5.3.2 obu_header(). Reads the OBU header at absolute file position `pos`.
fun parseObuHeader(reader: ByteReader, pos: Long): ObuHeader {
    val byte0 = reader.readUInt8(pos)
    val obuType = (byte0 shr 3) and 0x0F
    val extensionFlag = (byte0 shr 2) and 0x01 == 1
    val hasSizeField = (byte0 shr 1) and 0x01 == 1
    val headerSize = if (extensionFlag) 2 else 1
    return ObuHeader(obuType, extensionFlag, hasSizeField, headerSize)
}

// AV1 spec 4.10.5 leb128() -- little-endian base-128: up to 8 bytes, 7 payload bits per byte
// (LSB group first), continuation flag is each byte's MSB. Returns the decoded value and the
// absolute file position immediately after the last leb128 byte.
fun readLeb128(reader: ByteReader, pos: Long): Pair<Long, Long> {
    var value = 0L
    var p = pos
    for (i in 0 until 8) {
        val b = reader.readUInt8(p)
        p += 1
        value = value or ((b.toLong() and 0x7F) shl (i * 7))
        if (b and 0x80 == 0) break
    }
    return value to p
}
