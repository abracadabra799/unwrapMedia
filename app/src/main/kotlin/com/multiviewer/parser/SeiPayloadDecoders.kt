package com.multiviewer.parser

private val SEI_PAYLOAD_NAMES = mapOf(
    0 to "buffering_period", 1 to "pic_timing", 2 to "pan_scan_rect", 3 to "filler_payload",
    4 to "user_data_registered_itu_t_t35", 5 to "user_data_unregistered", 6 to "recovery_point",
    9 to "scene_info", 45 to "frame_packing_arrangement", 47 to "display_orientation",
    128 to "structure_of_pictures_info", 129 to "active_parameter_sets", 130 to "decoding_unit_info",
    131 to "temporal_sub_layer_zero_index", 132 to "decoded_picture_hash", 133 to "scalable_nesting",
    134 to "region_refresh_info", 137 to "mastering_display_colour_volume", 144 to "content_light_level_info",
    147 to "alternative_transfer_characteristics", 148 to "ambient_viewing_environment",
)

fun seiPayloadTypeName(type: Int): String = SEI_PAYLOAD_NAMES[type] ?: "sei_payload_$type"

fun decodeSeiPayload(codec: VideoCodec, message: SeiMessage): DecodedMetadata {
    message.malformedReason?.let { return DecodedMetadata.Malformed(it) }
    val p = message.payload
    return try {
        when (message.payloadType) {
            137 -> decodeMasteringDisplayPayload(p)?.let { DecodedMetadata.Mastering(it) }
                ?: DecodedMetadata.Malformed("mastering_display_colour_volume needs 24 bytes, got ${p.size}")
            144 -> decodeContentLightLevelPayload(p)?.let { DecodedMetadata.LightLevel(it) }
                ?: DecodedMetadata.Malformed("content_light_level_info needs 4 bytes, got ${p.size}")
            4 -> decodeItuT35(p)
            5 -> decodeUserDataUnregistered(p)
            6 -> decodeRecoveryPoint(codec, p)
            147 -> if (p.isNotEmpty()) DecodedMetadata.AlternativeTransfer(p[0].toInt() and 0xFF)
            else DecodedMetadata.Malformed("alternative_transfer_characteristics is empty")
            else -> DecodedMetadata.PresenceOnly
        }
    } catch (e: IllegalStateException) {
        DecodedMetadata.Malformed(e.message ?: "payload ended early")
    }
}

private fun u16(p: ByteArray, at: Int): Int = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)
private fun u32(p: ByteArray, at: Int): Long = (u16(p, at).toLong() shl 16) or u16(p, at + 2).toLong()

private const val SEI_CHROMA_STEP = 0.00002
private const val SEI_LUMINANCE_STEP = 0.0001

// SEI 137 and the ISOBMFF mdcv box share this 24-byte layout: three (x, y) u16 display primaries
// in G, B, R order (what x264/x265 write and ffmpeg's parser assumes), white point (x, y) u16,
// max and min luminance u32. Verified against x265/libx264 output and ffprobe.
fun decodeMasteringDisplayPayload(p: ByteArray): MasteringDisplay? {
    if (p.size < 24) return null
    return MasteringDisplay(
        redX = u16(p, 8) * SEI_CHROMA_STEP, redY = u16(p, 10) * SEI_CHROMA_STEP,
        greenX = u16(p, 0) * SEI_CHROMA_STEP, greenY = u16(p, 2) * SEI_CHROMA_STEP,
        blueX = u16(p, 4) * SEI_CHROMA_STEP, blueY = u16(p, 6) * SEI_CHROMA_STEP,
        whiteX = u16(p, 12) * SEI_CHROMA_STEP, whiteY = u16(p, 14) * SEI_CHROMA_STEP,
        maxLuminance = u32(p, 16) * SEI_LUMINANCE_STEP, minLuminance = u32(p, 20) * SEI_LUMINANCE_STEP,
        chromaStep = SEI_CHROMA_STEP, maxLuminanceStep = SEI_LUMINANCE_STEP, minLuminanceStep = SEI_LUMINANCE_STEP,
    )
}

// SEI 144 and the ISOBMFF clli box: max_content_light_level u16, max_pic_average_light_level u16.
fun decodeContentLightLevelPayload(p: ByteArray): ContentLightLevel? =
    if (p.size < 4) null else ContentLightLevel(u16(p, 0), u16(p, 2))

// `t35` starts at itu_t_t35_country_code (shared by SEI payload 4 and AV1 ITU_T_T35 metadata).
fun decodeItuT35(t35: ByteArray): DecodedMetadata {
    if (t35.isEmpty()) return DecodedMetadata.Malformed("empty ITU-T T.35 payload")
    decodeHdr10Plus(t35)?.let { return DecodedMetadata.Hdr10Plus(it) }
    if (isHdr10PlusT35(t35)) return DecodedMetadata.Malformed("HDR10+ payload ends early")
    val country = t35[0].toInt() and 0xFF
    val providerAt = if (country == 0xFF) 2 else 1
    val provider = if (t35.size >= providerAt + 2) u16(t35, providerAt) else null
    return DecodedMetadata.ItuT35(country, provider, hexPreview(t35))
}

fun hexPreview(bytes: ByteArray, maxBytes: Int = 32): String =
    bytes.take(maxBytes).joinToString("") { "%02x".format(it) } + if (bytes.size > maxBytes) "…" else ""

private fun decodeUserDataUnregistered(p: ByteArray): DecodedMetadata {
    if (p.size < 16) return DecodedMetadata.Malformed("user_data_unregistered needs a 16-byte UUID, got ${p.size}")
    val h = p.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
    val uuid = "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    val ascii = p.copyOfRange(16, minOf(p.size, 16 + 80))
        .map { b -> val c = b.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '.' }
        .joinToString("")
    return DecodedMetadata.UserDataUnregistered(uuid, ascii)
}

private fun decodeRecoveryPoint(codec: VideoCodec, p: ByteArray): DecodedMetadata {
    val r = BitReader(p)
    val count = if (codec == VideoCodec.H264) r.readUe() else r.readSe()
    val exact = r.readFlag()
    val broken = r.readFlag()
    return DecodedMetadata.RecoveryPoint(count, exact, broken)
}
