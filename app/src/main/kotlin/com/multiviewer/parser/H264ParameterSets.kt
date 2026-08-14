package com.multiviewer.parser

// Profile IDs whose SPS includes the chroma_format_idc/bit_depth/scaling-matrix block --
// H.264 spec §7.3.2.1.1's exact condition on profile_idc.
private val HIGH_PROFILE_IDCS = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

data class FrameCropping(val left: Int, val right: Int, val top: Int, val bottom: Int)

// Only the VUI subfields this feature curates -- see the design spec's Scope section. Each is
// null when its own presence flag was false in the source stream (a real, meaningful "not
// signaled" rather than a parse failure).
data class H264Vui(
    val aspectRatioIdc: Int?,
    val sarWidth: Int?,
    val sarHeight: Int?,
    val videoFullRangeFlag: Boolean?,
    val colourPrimaries: Int?,
    val transferCharacteristics: Int?,
    val matrixCoefficients: Int?,
    val numUnitsInTick: Long?,
    val timeScale: Long?,
)

data class H264Sps(
    val seqParameterSetId: Int,
    val profileIdc: Int,
    val levelIdc: Int,
    val chromaFormatIdc: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val picOrderCntType: Int,
    val maxNumRefFrames: Int,
    val frameCropping: FrameCropping?,
    val vui: H264Vui?,
    // True when seq_scaling_matrix_present_flag was set -- parsing stopped there (custom scaling
    // lists are not supported), so every field above reflects only what was read before that
    // point (profile_idc/levelIdc/seqParameterSetId/chromaFormatIdc/bitDepth are always valid;
    // picOrderCntType/maxNumRefFrames/frameCropping/vui are NOT populated in this case and hold
    // their default/zero values).
    val scalingMatrixUnsupported: Boolean = false,
)

data class H264Pps(
    val picParameterSetId: Int,
    val seqParameterSetId: Int,
    val entropyCodingModeFlag: Boolean,
    // Both null when num_slice_groups_minus1 > 0 (legacy FMO slice groups, not supported --
    // parsing stopped there) rather than a genuine parse failure of the whole PPS.
    val deblockingFilterControlPresentFlag: Boolean?,
    val transform8x8ModeFlag: Boolean?,
)

// nalBytes includes the 1-byte NAL header (skipped via startByteOffset=1). Returns null only on
// a genuine parse failure (BitReader ran out of data, or nalBytes is empty) -- a recognized but
// unsupported feature (scaling matrix) still returns a partial, non-null result.
fun parseH264Sps(nalBytes: ByteArray): H264Sps? {
    if (nalBytes.isEmpty()) return null
    return try {
        val reader = BitReader(nalBytes, startByteOffset = 1)
        val profileIdc = reader.readBits(8)
        reader.readBits(8) // constraint_set0..5_flag (6 bits) + reserved_zero_2bits (2 bits)
        val levelIdc = reader.readBits(8)
        val seqParameterSetId = reader.readUe()

        var chromaFormatIdc = 1
        var bitDepthLuma = 8
        var bitDepthChroma = 8
        if (profileIdc in HIGH_PROFILE_IDCS) {
            chromaFormatIdc = reader.readUe()
            if (chromaFormatIdc == 3) reader.readFlag() // separate_colour_plane_flag
            bitDepthLuma = reader.readUe() + 8
            bitDepthChroma = reader.readUe() + 8
            reader.readFlag() // qpprime_y_zero_transform_bypass_flag
            if (reader.readFlag()) { // seq_scaling_matrix_present_flag
                return H264Sps(
                    seqParameterSetId, profileIdc, levelIdc, chromaFormatIdc, bitDepthLuma, bitDepthChroma,
                    picOrderCntType = 0, maxNumRefFrames = 0, frameCropping = null, vui = null,
                    scalingMatrixUnsupported = true,
                )
            }
        }

        reader.readUe() // log2_max_frame_num_minus4
        val picOrderCntType = reader.readUe()
        if (picOrderCntType == 0) {
            reader.readUe() // log2_max_pic_order_cnt_lsb_minus4
        } else if (picOrderCntType == 1) {
            reader.readFlag() // delta_pic_order_always_zero_flag
            reader.readSe() // offset_for_non_ref_pic
            reader.readSe() // offset_for_top_to_bottom_field
            val cycleLength = reader.readUe() // num_ref_frames_in_pic_order_cnt_cycle
            repeat(cycleLength) { reader.readSe() } // offset_for_ref_frame[i]
        }
        val maxNumRefFrames = reader.readUe()
        reader.readFlag() // gaps_in_frame_num_value_allowed_flag
        reader.readUe() // pic_width_in_mbs_minus1
        reader.readUe() // pic_height_in_map_units_minus1
        val frameMbsOnlyFlag = reader.readFlag()
        if (!frameMbsOnlyFlag) reader.readFlag() // mb_adaptive_frame_field_flag
        reader.readFlag() // direct_8x8_inference_flag
        val frameCropping = if (reader.readFlag()) {
            FrameCropping(reader.readUe(), reader.readUe(), reader.readUe(), reader.readUe())
        } else {
            null
        }
        val vui = if (reader.readFlag()) parseVui(reader) else null

        H264Sps(
            seqParameterSetId, profileIdc, levelIdc, chromaFormatIdc, bitDepthLuma, bitDepthChroma,
            picOrderCntType, maxNumRefFrames, frameCropping, vui,
        )
    } catch (e: Exception) {
        null
    }
}

private fun parseVui(reader: BitReader): H264Vui {
    var aspectRatioIdc: Int? = null
    var sarWidth: Int? = null
    var sarHeight: Int? = null
    if (reader.readFlag()) { // aspect_ratio_info_present_flag
        aspectRatioIdc = reader.readBits(8)
        if (aspectRatioIdc == 255) { // Extended_SAR
            sarWidth = reader.readBits(16)
            sarHeight = reader.readBits(16)
        }
    }
    if (reader.readFlag()) reader.readFlag() // overscan_info_present_flag -> overscan_appropriate_flag

    var videoFullRangeFlag: Boolean? = null
    var colourPrimaries: Int? = null
    var transferCharacteristics: Int? = null
    var matrixCoefficients: Int? = null
    if (reader.readFlag()) { // video_signal_type_present_flag
        reader.readBits(3) // video_format
        videoFullRangeFlag = reader.readFlag()
        if (reader.readFlag()) { // colour_description_present_flag
            colourPrimaries = reader.readBits(8)
            transferCharacteristics = reader.readBits(8)
            matrixCoefficients = reader.readBits(8)
        }
    }
    if (reader.readFlag()) { // chroma_loc_info_present_flag
        reader.readUe() // chroma_sample_loc_type_top_field
        reader.readUe() // chroma_sample_loc_type_bottom_field
    }
    var numUnitsInTick: Long? = null
    var timeScale: Long? = null
    if (reader.readFlag()) { // timing_info_present_flag
        numUnitsInTick = reader.readBits32()
        timeScale = reader.readBits32()
    }
    return H264Vui(
        aspectRatioIdc, sarWidth, sarHeight, videoFullRangeFlag,
        colourPrimaries, transferCharacteristics, matrixCoefficients, numUnitsInTick, timeScale,
    )
}

fun parseH264Pps(nalBytes: ByteArray): H264Pps? {
    if (nalBytes.isEmpty()) return null
    return try {
        val reader = BitReader(nalBytes, startByteOffset = 1)
        val picParameterSetId = reader.readUe()
        val seqParameterSetId = reader.readUe()
        val entropyCodingModeFlag = reader.readFlag()
        reader.readFlag() // bottom_field_pic_order_in_frame_present_flag
        val numSliceGroupsMinus1 = reader.readUe()
        if (numSliceGroupsMinus1 > 0) {
            // Slice group mapping (legacy FMO) is complex and rare -- stop here rather than guess.
            return H264Pps(picParameterSetId, seqParameterSetId, entropyCodingModeFlag, null, null)
        }
        reader.readUe() // num_ref_idx_l0_default_active_minus1
        reader.readUe() // num_ref_idx_l1_default_active_minus1
        reader.readFlag() // weighted_pred_flag
        reader.readBits(2) // weighted_bipred_idc
        reader.readSe() // pic_init_qp_minus26
        reader.readSe() // pic_init_qs_minus26
        reader.readSe() // chroma_qp_index_offset
        val deblockingFilterControlPresentFlag = reader.readFlag()
        reader.readFlag() // constrained_intra_pred_flag
        reader.readFlag() // redundant_pic_cnt_present_flag
        // transform_8x8_mode_flag is part of PPS's optional more_rbsp_data() extension -- only
        // attempt it if there's a comfortable margin of bits left (a real trailing-bits pattern
        // is much shorter than this), otherwise leave it null rather than risk misreading padding.
        val transform8x8ModeFlag = if (reader.bitsRemaining() > 8) reader.readFlag() else null
        H264Pps(picParameterSetId, seqParameterSetId, entropyCodingModeFlag, deblockingFilterControlPresentFlag, transform8x8ModeFlag)
    } catch (e: Exception) {
        null
    }
}
