package com.multiviewer.parser

fun av1MetadataTypeName(type: Long): String = when (type) {
    1L -> "HDR_CLL"
    2L -> "HDR_MDCV"
    3L -> "SCALABILITY"
    4L -> "ITU_T_T35"
    5L -> "TIMECODE"
    else -> "metadata_type_$type"
}

// AV1 spec 5.8.1 metadata_obu(): metadata_type leb128, then the type-specific payload. Trailing
// bits stay in the body; every decoder below reads fixed-size fields and ignores what follows.
fun decodeAv1MetadataPayload(payload: ByteArray): Pair<Long, DecodedMetadata> {
    var type = 0L
    var pos = 0
    while (pos < payload.size && pos < 8) {
        val b = payload[pos].toInt() and 0xFF
        type = type or ((b and 0x7F).toLong() shl (7 * pos))
        pos++
        if (b and 0x80 == 0) break
    }
    val body = payload.copyOfRange(pos, payload.size)
    val decoded = when (type) {
        1L -> decodeContentLightLevelPayload(body)?.let { DecodedMetadata.LightLevel(it) }
            ?: DecodedMetadata.Malformed("HDR_CLL needs 4 bytes, got ${body.size}")
        2L -> decodeAv1Mdcv(body)?.let { DecodedMetadata.Mastering(it) }
            ?: DecodedMetadata.Malformed("HDR_MDCV needs 24 bytes, got ${body.size}")
        4L -> decodeItuT35(body)
        else -> DecodedMetadata.PresenceOnly
    }
    return type to decoded
}

private const val AV1_CHROMA_STEP = 1.0 / 65536   // 0.16 fixed point
private const val AV1_MAX_LUMINANCE_STEP = 1.0 / 256  // 24.8 fixed point
private const val AV1_MIN_LUMINANCE_STEP = 1.0 / 16384 // 18.14 fixed point

// AV1 spec 5.8.4 metadata_hdr_mdcv(): primary_chromaticity_x/y for i = 0..2 in R, G, B order,
// white point, luminance_max u32, luminance_min u32. Verified against SVT-AV1 output and ffprobe.
fun decodeAv1Mdcv(body: ByteArray): MasteringDisplay? {
    if (body.size < 24) return null
    fun u16(at: Int) = ((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)
    fun u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    return MasteringDisplay(
        redX = u16(0) * AV1_CHROMA_STEP, redY = u16(2) * AV1_CHROMA_STEP,
        greenX = u16(4) * AV1_CHROMA_STEP, greenY = u16(6) * AV1_CHROMA_STEP,
        blueX = u16(8) * AV1_CHROMA_STEP, blueY = u16(10) * AV1_CHROMA_STEP,
        whiteX = u16(12) * AV1_CHROMA_STEP, whiteY = u16(14) * AV1_CHROMA_STEP,
        maxLuminance = u32(16) * AV1_MAX_LUMINANCE_STEP, minLuminance = u32(20) * AV1_MIN_LUMINANCE_STEP,
        chromaStep = AV1_CHROMA_STEP, maxLuminanceStep = AV1_MAX_LUMINANCE_STEP, minLuminanceStep = AV1_MIN_LUMINANCE_STEP,
    )
}
