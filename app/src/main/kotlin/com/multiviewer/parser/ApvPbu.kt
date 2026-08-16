package com.multiviewer.parser

private const val ACCESS_UNIT_PREFIX_LENGTH = 8 // 4-byte leading length field + 4-byte 'aPv1' signature
private const val PBU_SIZE_FIELD_LENGTH = 4
private const val PBU_HEADER_LENGTH = 4
private const val PBU_TYPE_PRIMARY_FRAME = 1

// Access-unit / PBU framing (RFC 9924 SS5.3.1/SS5.3.2), verified against a real access unit's bytes
// during planning (see docs/superpowers/plans/2026-08-16-apv-codec-support.md's Technical
// Foundation): [4-byte leading length]['aPv1' signature][pbu_size u(32)][pbu_header][payload]...,
// repeated per PBU. An MP4 sample's bytes are this exact structure verbatim -- no MP4-specific
// offset handling needed.
private fun parseApvPbuHeader(accessUnitBytes: ByteArray, offset: Int): ApvPbuHeader? {
    if (offset + PBU_HEADER_LENGTH > accessUnitBytes.size) return null
    val pbuType = accessUnitBytes[offset].toInt() and 0xFF
    val groupId = ((accessUnitBytes[offset + 1].toInt() and 0xFF) shl 8) or (accessUnitBytes[offset + 2].toInt() and 0xFF)
    return ApvPbuHeader(pbuType, groupId)
}

data class ApvPbuHeader(val pbuType: Int, val groupId: Int)

// Locates the first pbu_type == 1 (primary frame) PBU within one access unit's bytes and returns
// its frame() payload (frame_header() plus tile data together -- the caller, parseApvFrameHeader,
// only parses the header prefix and never touches tile/coefficient data). Returns null if the input
// is too short, malformed, or contains no primary-frame PBU.
fun findApvPrimaryFramePbuPayload(accessUnitBytes: ByteArray): ByteArray? {
    var pos = ACCESS_UNIT_PREFIX_LENGTH
    while (pos + PBU_SIZE_FIELD_LENGTH + PBU_HEADER_LENGTH <= accessUnitBytes.size) {
        val pbuSize = (
            ((accessUnitBytes[pos].toInt() and 0xFF).toLong() shl 24) or
                ((accessUnitBytes[pos + 1].toInt() and 0xFF).toLong() shl 16) or
                ((accessUnitBytes[pos + 2].toInt() and 0xFF).toLong() shl 8) or
                (accessUnitBytes[pos + 3].toInt() and 0xFF).toLong()
            )
        val pbuStart = pos + PBU_SIZE_FIELD_LENGTH
        val header = parseApvPbuHeader(accessUnitBytes, pbuStart) ?: return null
        val payloadStart = pbuStart + PBU_HEADER_LENGTH
        val payloadLength = pbuSize - PBU_HEADER_LENGTH
        if (payloadLength < 0) return null
        if (header.pbuType == PBU_TYPE_PRIMARY_FRAME) {
            // Clamp the payload to available bytes instead of rejecting truncated input.
            // The pbu_size field includes both frame_header() (which we parse) and tile/coefficient
            // data (which we don't). For real production frames, tile data can be hundreds of KB,
            // even though the header alone is ~30-40 bytes. A truncated tile section is benign
            // for callers (like parseApvFrameHeader) that only need the header.
            // Real header corruption is still caught downstream: parseApvFrameHeader uses
            // BitReader, which throws on read-past-end and is caught, returning null.
            val endPos = minOf(payloadStart.toLong() + payloadLength, accessUnitBytes.size.toLong()).toInt()
            return if (endPos > payloadStart) {
                accessUnitBytes.copyOfRange(payloadStart, endPos)
            } else {
                null
            }
        }
        // Only move to next PBU if we have the full payload available
        if (payloadStart + payloadLength > accessUnitBytes.size) return null
        pos = (payloadStart.toLong() + payloadLength).toInt()
    }
    return null
}
