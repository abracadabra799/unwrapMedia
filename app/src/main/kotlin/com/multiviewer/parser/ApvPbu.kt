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
            val endPos = minOf((payloadStart + payloadLength).toInt(), accessUnitBytes.size)
            return if (endPos > payloadStart) {
                accessUnitBytes.copyOfRange(payloadStart, endPos)
            } else {
                null
            }
        }
        // Only move to next PBU if we have the full payload available
        if (payloadStart + payloadLength > accessUnitBytes.size) return null
        pos = payloadStart + payloadLength.toInt()
    }
    return null
}
