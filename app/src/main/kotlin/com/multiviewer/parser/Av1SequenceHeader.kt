package com.multiviewer.parser

data class Av1SequenceHeader(
    val seqProfile: Int,
    val stillPicture: Boolean,
    val seqLevelIdx0: Int,
    val seqTierIdx0: Int,
    val bitDepth: Int,
    val monochrome: Boolean,
    val chromaSubsamplingX: Int,
    val chromaSubsamplingY: Int,
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int,
    val maxFrameWidth: Int,
    val maxFrameHeight: Int,
    val use128x128Superblock: Boolean,
    val filmGrainParamsPresent: Boolean,
)

private const val CP_BT_709 = 1
private const val CP_UNSPECIFIED = 2
private const val TC_UNSPECIFIED = 2
private const val TC_SRGB = 13
private const val MC_IDENTITY = 0
private const val MC_UNSPECIFIED = 2
private const val SELECT_SCREEN_CONTENT_TOOLS = 2

// Parses AV1 spec 5.5.1 sequence_header_obu() from bit 0 of `payload` (the OBU's own payload
// bytes -- see this plan's Global Constraints on what extractAv1CRawSequenceHeader hands this).
// Returns null on a genuine parse failure or empty input, OR when reduced_still_picture_header or
// timing_info_present_flag is set -- neither of those paths is implemented here (mirrors the
// H.264/HEVC "stop rather than guess" bail-out precedent; see this plan's Global Constraints).
fun parseAv1SequenceHeader(payload: ByteArray): Av1SequenceHeader? {
    if (payload.isEmpty()) return null
    return try {
        val reader = BitReader(payload)
        val seqProfile = reader.readBits(3)
        val stillPicture = reader.readFlag()
        val reducedStillPictureHeader = reader.readFlag()
        if (reducedStillPictureHeader) return null

        val timingInfoPresentFlag = reader.readFlag()
        if (timingInfoPresentFlag) return null // timing_info()/decoder_model_info() not supported

        val initialDisplayDelayPresentFlag = reader.readFlag()
        val operatingPointsCntMinus1 = reader.readBits(5)

        var seqLevelIdx0 = 0
        var seqTierIdx0 = 0
        for (i in 0..operatingPointsCntMinus1) {
            reader.readBits(12) // operating_point_idc[i]
            val seqLevelIdx = reader.readBits(5)
            val seqTier = if (seqLevelIdx > 7) reader.readBits(1) else 0
            // decoder_model_info_present_flag is always false here (bailed out above), so
            // decoder_model_present_for_this_op[i] is never signaled in the bitstream.
            if (initialDisplayDelayPresentFlag) {
                if (reader.readFlag()) { // initial_display_delay_present_for_this_op[i]
                    reader.readBits(4) // initial_display_delay_minus_1[i]
                }
            }
            if (i == 0) {
                seqLevelIdx0 = seqLevelIdx
                seqTierIdx0 = seqTier
            }
        }

        val frameWidthBitsMinus1 = reader.readBits(4)
        val frameHeightBitsMinus1 = reader.readBits(4)
        val maxFrameWidth = reader.readBits(frameWidthBitsMinus1 + 1) + 1
        val maxFrameHeight = reader.readBits(frameHeightBitsMinus1 + 1) + 1

        if (reader.readFlag()) { // frame_id_numbers_present_flag
            reader.readBits(4) // delta_frame_id_length_minus_2
            reader.readBits(3) // additional_frame_id_length_minus_1
        }

        val use128x128Superblock = reader.readFlag()
        reader.readFlag() // enable_filter_intra
        reader.readFlag() // enable_intra_edge_filter

        reader.readFlag() // enable_interintra_compound
        reader.readFlag() // enable_masked_compound
        reader.readFlag() // enable_warped_motion
        reader.readFlag() // enable_dual_filter
        val enableOrderHint = reader.readFlag()
        if (enableOrderHint) {
            reader.readFlag() // enable_jnt_comp
            reader.readFlag() // enable_ref_frame_mvs
        }
        val seqForceScreenContentTools = if (reader.readFlag()) { // seq_choose_screen_content_tools
            SELECT_SCREEN_CONTENT_TOOLS
        } else {
            reader.readBits(1)
        }
        if (seqForceScreenContentTools > 0) {
            val seqChooseIntegerMv = reader.readFlag()
            if (!seqChooseIntegerMv) {
                reader.readBits(1) // seq_force_integer_mv
            }
        }
        if (enableOrderHint) {
            reader.readBits(3) // order_hint_bits_minus_1
        }

        reader.readFlag() // enable_superres
        reader.readFlag() // enable_cdef
        reader.readFlag() // enable_restoration

        // color_config()
        val highBitdepth = reader.readFlag()
        val bitDepth = if (seqProfile == 2 && highBitdepth) {
            if (reader.readFlag()) 12 else 10 // twelve_bit
        } else {
            if (highBitdepth) 10 else 8
        }
        val monochrome = if (seqProfile == 1) false else reader.readFlag()
        val colorDescriptionPresentFlag = reader.readFlag()
        val colorPrimaries: Int
        val transferCharacteristics: Int
        val matrixCoefficients: Int
        if (colorDescriptionPresentFlag) {
            colorPrimaries = reader.readBits(8)
            transferCharacteristics = reader.readBits(8)
            matrixCoefficients = reader.readBits(8)
        } else {
            colorPrimaries = CP_UNSPECIFIED
            transferCharacteristics = TC_UNSPECIFIED
            matrixCoefficients = MC_UNSPECIFIED
        }
        var chromaSubsamplingX = 0
        var chromaSubsamplingY = 0
        if (monochrome) {
            reader.readFlag() // color_range
            chromaSubsamplingX = 1
            chromaSubsamplingY = 1
        } else if (colorPrimaries == CP_BT_709 && transferCharacteristics == TC_SRGB && matrixCoefficients == MC_IDENTITY) {
            chromaSubsamplingX = 0
            chromaSubsamplingY = 0
            reader.readFlag() // separate_uv_delta_q
        } else {
            reader.readFlag() // color_range
            when (seqProfile) {
                0 -> {
                    chromaSubsamplingX = 1
                    chromaSubsamplingY = 1
                }
                1 -> {
                    chromaSubsamplingX = 0
                    chromaSubsamplingY = 0
                }
                else -> {
                    if (bitDepth == 12) {
                        chromaSubsamplingX = reader.readBits(1)
                        chromaSubsamplingY = if (chromaSubsamplingX == 1) reader.readBits(1) else 0
                    } else {
                        chromaSubsamplingX = 1
                        chromaSubsamplingY = 0
                    }
                }
            }
            if (chromaSubsamplingX == 1 && chromaSubsamplingY == 1) {
                reader.readBits(2) // chroma_sample_position
            }
            reader.readFlag() // separate_uv_delta_q
        }

        val filmGrainParamsPresent = reader.readFlag()

        Av1SequenceHeader(
            seqProfile = seqProfile,
            stillPicture = stillPicture,
            seqLevelIdx0 = seqLevelIdx0,
            seqTierIdx0 = seqTierIdx0,
            bitDepth = bitDepth,
            monochrome = monochrome,
            chromaSubsamplingX = chromaSubsamplingX,
            chromaSubsamplingY = chromaSubsamplingY,
            colorPrimaries = colorPrimaries,
            transferCharacteristics = transferCharacteristics,
            matrixCoefficients = matrixCoefficients,
            maxFrameWidth = maxFrameWidth,
            maxFrameHeight = maxFrameHeight,
            use128x128Superblock = use128x128Superblock,
            filmGrainParamsPresent = filmGrainParamsPresent,
        )
    } catch (e: Exception) {
        null
    }
}
