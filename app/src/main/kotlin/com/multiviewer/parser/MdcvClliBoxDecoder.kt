package com.multiviewer.parser

import java.util.Locale

private const val MDCV_PAYLOAD_BYTES = 24
private const val CLLI_PAYLOAD_BYTES = 4

// ISO/IEC 23001-8 / ISOBMFF mdcv (plain Box, not FullBox): same 24-byte layout as SEI 137.
object MdcvBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val payloadStart = offset + headerSize
        val available = size - headerSize
        if (available < MDCV_PAYLOAD_BYTES) {
            return BoxNode(type, offset, headerSize, size, warnings = warnings + "mdcv needs $MDCV_PAYLOAD_BYTES payload bytes, has $available")
        }
        val md = decodeMasteringDisplayPayload(reader.readBytes(payloadStart, MDCV_PAYLOAD_BYTES))!!
        fun xy(x: Double, y: Double) = String.format(Locale.US, "%.5f, %.5f", x, y)
        fun nits(v: Double) = String.format(Locale.US, "%.4f cd/m²", v)
        val fields = listOf(
            BoxField("display_primaries G (x, y)", xy(md.greenX, md.greenY), payloadStart, 4),
            BoxField("display_primaries B (x, y)", xy(md.blueX, md.blueY), payloadStart + 4, 4),
            BoxField("display_primaries R (x, y)", xy(md.redX, md.redY), payloadStart + 8, 4),
            BoxField("white_point (x, y)", xy(md.whiteX, md.whiteY), payloadStart + 12, 4),
            BoxField("max_display_mastering_luminance", nits(md.maxLuminance), payloadStart + 16, 4),
            BoxField("min_display_mastering_luminance", nits(md.minLuminance), payloadStart + 20, 4),
        )
        val summary = String.format(Locale.US, "max %.0f / min %.4f cd/m²", md.maxLuminance, md.minLuminance)
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = warnings, summary = summary)
    }
}

// ISOBMFF clli: same 4-byte layout as SEI 144.
object ClliBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val payloadStart = offset + headerSize
        val available = size - headerSize
        if (available < CLLI_PAYLOAD_BYTES) {
            return BoxNode(type, offset, headerSize, size, warnings = warnings + "clli needs $CLLI_PAYLOAD_BYTES payload bytes, has $available")
        }
        val cll = decodeContentLightLevelPayload(reader.readBytes(payloadStart, CLLI_PAYLOAD_BYTES))!!
        val fields = listOf(
            BoxField("max_content_light_level", cll.maxCll.toString(), payloadStart, 2),
            BoxField("max_pic_average_light_level", cll.maxFall.toString(), payloadStart + 2, 2),
        )
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = warnings, summary = "MaxCLL ${cll.maxCll} / MaxFALL ${cll.maxFall}")
    }
}

data class ContainerHdrStatic(val masteringDisplay: MasteringDisplay?, val contentLightLevel: ContentLightLevel?)

fun readContainerHdrStatic(reader: ByteReader, root: BoxNode): ContainerHdrStatic {
    val mdcv = findFirst(root) { it.type == "mdcv" }?.takeIf { it.size - it.headerSize >= MDCV_PAYLOAD_BYTES }
    val clli = findFirst(root) { it.type == "clli" }?.takeIf { it.size - it.headerSize >= CLLI_PAYLOAD_BYTES }
    return ContainerHdrStatic(
        mdcv?.let { decodeMasteringDisplayPayload(reader.readBytes(it.offset + it.headerSize, MDCV_PAYLOAD_BYTES)) },
        clli?.let { decodeContentLightLevelPayload(reader.readBytes(it.offset + it.headerSize, CLLI_PAYLOAD_BYTES)) },
    )
}
