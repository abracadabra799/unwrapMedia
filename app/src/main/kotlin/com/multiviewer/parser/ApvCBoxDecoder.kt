package com.multiviewer.parser

object ApvCBoxDecoder : BoxDecoder {
    // Byte offsets confirmed against a real apvC payload (see this plan's Technical Foundation
    // section, "Real fixture 2") via direct value-search, not assumed from documentation.
    private const val MIN_PAYLOAD_SIZE = 21
    private const val PROFILE_IDC_OFFSET = 9
    private const val LEVEL_IDC_OFFSET = 10
    private const val BAND_IDC_OFFSET = 11
    private const val FRAME_WIDTH_OFFSET = 12
    private const val FRAME_HEIGHT_OFFSET = 16
    private const val CHROMA_BITDEPTH_OFFSET = 20

    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < MIN_PAYLOAD_SIZE) {
            w.add("Box too short for apvC fixed fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val profileIdc = reader.readUInt8(payloadStart + PROFILE_IDC_OFFSET)
        val levelIdc = reader.readUInt8(payloadStart + LEVEL_IDC_OFFSET)
        val bandIdc = reader.readUInt8(payloadStart + BAND_IDC_OFFSET)
        val frameWidth = reader.readUInt32(payloadStart + FRAME_WIDTH_OFFSET)
        val frameHeight = reader.readUInt32(payloadStart + FRAME_HEIGHT_OFFSET)
        val chromaBitdepthByte = reader.readUInt8(payloadStart + CHROMA_BITDEPTH_OFFSET)
        val chromaFormatIdc = (chromaBitdepthByte shr 4) and 0x0F
        val bitDepth = (chromaBitdepthByte and 0x0F) + 8

        val fields = listOf(
            BoxField("profile_idc", profileIdc.toString(), payloadStart + PROFILE_IDC_OFFSET, 1),
            BoxField("level_idc", levelIdc.toString(), payloadStart + LEVEL_IDC_OFFSET, 1),
            BoxField("band_idc", bandIdc.toString(), payloadStart + BAND_IDC_OFFSET, 1),
            BoxField("frame_width", frameWidth.toString(), payloadStart + FRAME_WIDTH_OFFSET, 4),
            BoxField("frame_height", frameHeight.toString(), payloadStart + FRAME_HEIGHT_OFFSET, 4),
            BoxField("chroma_format_idc", chromaFormatIdc.toString(), payloadStart + CHROMA_BITDEPTH_OFFSET, 1),
            BoxField("bit_depth", bitDepth.toString(), payloadStart + CHROMA_BITDEPTH_OFFSET, 1),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$profileIdc, level=$levelIdc, ${frameWidth}x${frameHeight}",
        )
    }
}
