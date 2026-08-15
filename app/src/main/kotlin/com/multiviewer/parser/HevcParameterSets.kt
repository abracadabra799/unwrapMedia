package com.multiviewer.parser

data class HevcProfileTierLevel(
    val generalProfileSpace: Int,
    val generalTierFlag: Boolean,
    val generalProfileIdc: Int,
    val generalLevelIdc: Int,
)

data class HevcVps(
    val vpsId: Int,
    val maxSubLayersMinus1: Int,
    // Null (with ptlUnsupported=true) when maxSubLayersMinus1 > 0 -- see parseProfileTierLevel.
    val ptl: HevcProfileTierLevel?,
    val ptlUnsupported: Boolean = false,
)

// Only the VUI subfields this feature curates -- see the design spec's Scope section. Each is
// null when its own presence flag was false in the source stream (a real, meaningful "not
// signaled" rather than a parse failure).
data class HevcVui(
    val videoFullRangeFlag: Boolean?,
    val colourPrimaries: Int?,
    val transferCharacteristics: Int?,
    val matrixCoefficients: Int?,
)

data class HevcSps(
    val spsId: Int,
    val vpsId: Int,
    val maxSubLayersMinus1: Int,
    val ptl: HevcProfileTierLevel,
    val chromaFormatIdc: Int,
    val picWidth: Int,
    val picHeight: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val vui: HevcVui?,
)

data class HevcPps(
    val ppsId: Int,
    val spsId: Int,
    val dependentSliceSegmentsEnabledFlag: Boolean,
    val signDataHidingEnabledFlag: Boolean,
    val cabacInitPresentFlag: Boolean,
    val constrainedIntraPredFlag: Boolean,
    val transformSkipEnabledFlag: Boolean,
    val cuQpDeltaEnabledFlag: Boolean,
    val weightedPredFlag: Boolean,
    val weightedBipredFlag: Boolean,
    val tilesEnabledFlag: Boolean,
    val entropyCodingSyncEnabledFlag: Boolean,
    val deblockingFilterControlPresentFlag: Boolean,
    // Null when deblockingFilterControlPresentFlag is false (field not signaled in the bitstream).
    val ppsDeblockingFilterDisabledFlag: Boolean?,
)

// Shared by parseHevcVps and parseHevcSps -- identical profile_tier_level() layout in both,
// confirmed via real trace_headers output (VPS and SPS produced identical values from this block
// in the same source file: profile_space=0, tier_flag=false, profile_idc=1, level_idc=120).
// Reads the fixed 96-bit "general" portion (8 bits profile_space/tier_flag/profile_idc + 80 bits
// of compatibility/constraint/reserved bits, always fixed-width regardless of
// maxSubLayersMinus1 -- verified as bits 24..120 in both VPS and SPS of the real sample file -- +
// 8 bits level_idc) and returns null if maxSubLayersMinus1 > 0: the variable-width sub-layer
// profile/level blocks that follow in that case are not parsed (mirrors H.264's scaling-matrix
// bail-out). The reader's position after a null return is not meaningful to the caller --
// callers must stop parsing rather than continue from it.
private fun parseProfileTierLevel(reader: BitReader, maxSubLayersMinus1: Int): HevcProfileTierLevel? {
    val generalProfileSpace = reader.readBits(2)
    val generalTierFlag = reader.readFlag()
    val generalProfileIdc = reader.readBits(5)
    // 80 bits: 32 compatibility flags + 4 constraint flags + 44 reserved/inbld bits.
    repeat(5) { reader.readBits(16) }
    val generalLevelIdc = reader.readBits(8)
    if (maxSubLayersMinus1 > 0) return null
    return HevcProfileTierLevel(generalProfileSpace, generalTierFlag, generalProfileIdc, generalLevelIdc)
}

// nalBytes includes the 2-byte NAL header (skipped via startByteOffset=2). Returns null only on a
// genuine parse failure or empty input -- an unsupported sub-layer profile_tier_level still
// returns a partial, non-null result (ptlUnsupported=true).
fun parseHevcVps(nalBytes: ByteArray): HevcVps? {
    if (nalBytes.isEmpty()) return null
    return try {
        val reader = BitReader(removeEmulationPreventionBytes(nalBytes), startByteOffset = 2)
        val vpsId = reader.readBits(4)
        reader.readFlag() // vps_base_layer_internal_flag
        reader.readFlag() // vps_base_layer_available_flag
        reader.readBits(6) // vps_max_layers_minus1
        val maxSubLayersMinus1 = reader.readBits(3)
        reader.readFlag() // vps_temporal_id_nesting_flag
        reader.readBits(16) // vps_reserved_0xffff_16bits
        val ptl = parseProfileTierLevel(reader, maxSubLayersMinus1)
        HevcVps(vpsId, maxSubLayersMinus1, ptl, ptlUnsupported = maxSubLayersMinus1 > 0)
    } catch (e: Exception) {
        null
    }
}

// nalBytes includes the 2-byte NAL header (skipped via startByteOffset=2). Returns null on a
// genuine parse failure or empty input, OR when profile_tier_level's sub-layer blocks are
// unsupported (unlike VPS, sps_seq_parameter_set_id itself sits AFTER profile_tier_level in the
// bitstream, so an unsupported PTL means even the SPS's own id can't be recovered -- see the
// design spec), OR when more than one short_term_ref_pic_set is declared, OR when explicit
// scaling_list_data() is present -- all three mirror H.264's "stop rather than guess" precedent.
fun parseHevcSps(nalBytes: ByteArray): HevcSps? {
    if (nalBytes.isEmpty()) return null
    return try {
        val reader = BitReader(removeEmulationPreventionBytes(nalBytes), startByteOffset = 2)
        val vpsId = reader.readBits(4)
        val maxSubLayersMinus1 = reader.readBits(3)
        reader.readFlag() // sps_temporal_id_nesting_flag
        val ptl = parseProfileTierLevel(reader, maxSubLayersMinus1) ?: return null
        val spsId = reader.readUe()
        val chromaFormatIdc = reader.readUe()
        if (chromaFormatIdc == 3) reader.readFlag() // separate_colour_plane_flag
        val picWidth = reader.readUe()
        val picHeight = reader.readUe()
        if (reader.readFlag()) { // conformance_window_flag
            reader.readUe() // conf_win_left_offset
            reader.readUe() // conf_win_right_offset
            reader.readUe() // conf_win_top_offset
            reader.readUe() // conf_win_bottom_offset
        }
        val bitDepthLuma = reader.readUe() + 8
        val bitDepthChroma = reader.readUe() + 8
        val log2MaxPicOrderCntLsbMinus4 = reader.readUe()
        reader.readFlag() // sps_sub_layer_ordering_info_present_flag
        // maxSubLayersMinus1 == 0 is guaranteed here (parseProfileTierLevel already returned null
        // and this function bailed above otherwise), so this loop always runs exactly once for
        // i=0 regardless of the flag just read (spec: loop starts at (flag ? 0 : maxSubLayersMinus1),
        // ends at maxSubLayersMinus1 -- both bounds are 0 when maxSubLayersMinus1 is 0).
        reader.readUe() // sps_max_dec_pic_buffering_minus1[0]
        reader.readUe() // sps_max_num_reorder_pics[0]
        reader.readUe() // sps_max_latency_increase_plus1[0]
        reader.readUe() // log2_min_luma_coding_block_size_minus3
        reader.readUe() // log2_diff_max_min_luma_coding_block_size
        reader.readUe() // log2_min_luma_transform_block_size_minus2
        reader.readUe() // log2_diff_max_min_luma_transform_block_size
        reader.readUe() // max_transform_hierarchy_depth_inter
        reader.readUe() // max_transform_hierarchy_depth_intra
        if (reader.readFlag()) { // scaling_list_enabled_flag
            if (reader.readFlag()) return null // sps_scaling_list_data_present_flag -- not supported
        }
        reader.readFlag() // amp_enabled_flag
        reader.readFlag() // sample_adaptive_offset_enabled_flag
        if (reader.readFlag()) { // pcm_enabled_flag
            reader.readBits(4) // pcm_sample_bit_depth_luma_minus1
            reader.readBits(4) // pcm_sample_bit_depth_chroma_minus1
            reader.readUe() // log2_min_pcm_luma_coding_block_size_minus3
            reader.readUe() // log2_diff_max_min_pcm_luma_coding_block_size
            reader.readFlag() // pcm_loop_filter_disabled_flag
        }
        val numShortTermRefPicSets = reader.readUe()
        if (numShortTermRefPicSets > 1) return null // st_ref_pic_set(idx>0) prediction not supported
        if (numShortTermRefPicSets == 1) {
            val numNegativePics = reader.readUe()
            val numPositivePics = reader.readUe()
            repeat(numNegativePics) {
                reader.readUe() // delta_poc_s0_minus1[i]
                reader.readFlag() // used_by_curr_pic_s0_flag[i]
            }
            repeat(numPositivePics) {
                reader.readUe() // delta_poc_s1_minus1[i]
                reader.readFlag() // used_by_curr_pic_s1_flag[i]
            }
        }
        if (reader.readFlag()) { // long_term_ref_pics_present_flag
            val numLongTerm = reader.readUe()
            repeat(numLongTerm) {
                reader.readBits(log2MaxPicOrderCntLsbMinus4 + 4) // lt_ref_pic_poc_lsb_sps[i]
                reader.readFlag() // used_by_curr_pic_lt_sps_flag[i]
            }
        }
        reader.readFlag() // sps_temporal_mvp_enabled_flag
        reader.readFlag() // strong_intra_smoothing_enabled_flag
        var vui: HevcVui? = null
        if (reader.readFlag()) { // vui_parameters_present_flag
            if (reader.readFlag()) { // aspect_ratio_info_present_flag
                val aspectRatioIdc = reader.readBits(8)
                if (aspectRatioIdc == 255) { // EXTENDED_SAR
                    reader.readBits(16) // sar_width
                    reader.readBits(16) // sar_height
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
            vui = HevcVui(videoFullRangeFlag, colourPrimaries, transferCharacteristics, matrixCoefficients)
        }
        HevcSps(spsId, vpsId, maxSubLayersMinus1, ptl, chromaFormatIdc, picWidth, picHeight, bitDepthLuma, bitDepthChroma, vui)
    } catch (e: Exception) {
        null
    }
}

fun parseHevcPps(nalBytes: ByteArray): HevcPps? {
    if (nalBytes.isEmpty()) return null
    return try {
        val reader = BitReader(removeEmulationPreventionBytes(nalBytes), startByteOffset = 2)
        val ppsId = reader.readUe()
        val spsId = reader.readUe()
        val dependentSliceSegmentsEnabledFlag = reader.readFlag()
        reader.readFlag() // output_flag_present_flag
        reader.readBits(3) // num_extra_slice_header_bits
        val signDataHidingEnabledFlag = reader.readFlag()
        val cabacInitPresentFlag = reader.readFlag()
        reader.readUe() // num_ref_idx_l0_default_active_minus1
        reader.readUe() // num_ref_idx_l1_default_active_minus1
        reader.readSe() // init_qp_minus26
        val constrainedIntraPredFlag = reader.readFlag()
        val transformSkipEnabledFlag = reader.readFlag()
        val cuQpDeltaEnabledFlag = reader.readFlag()
        if (cuQpDeltaEnabledFlag) reader.readUe() // diff_cu_qp_delta_depth
        reader.readSe() // pps_cb_qp_offset
        reader.readSe() // pps_cr_qp_offset
        reader.readFlag() // pps_slice_chroma_qp_offsets_present_flag
        val weightedPredFlag = reader.readFlag()
        val weightedBipredFlag = reader.readFlag()
        reader.readFlag() // transquant_bypass_enabled_flag
        val tilesEnabledFlag = reader.readFlag()
        val entropyCodingSyncEnabledFlag = reader.readFlag()
        if (tilesEnabledFlag) {
            val numTileColumnsMinus1 = reader.readUe()
            val numTileRowsMinus1 = reader.readUe()
            val uniformSpacingFlag = reader.readFlag()
            if (!uniformSpacingFlag) {
                repeat(numTileColumnsMinus1) { reader.readUe() } // column_width_minus1[i]
                repeat(numTileRowsMinus1) { reader.readUe() } // row_height_minus1[i]
            }
            reader.readFlag() // loop_filter_across_tiles_enabled_flag
        }
        reader.readFlag() // pps_loop_filter_across_slices_enabled_flag
        val deblockingFilterControlPresentFlag = reader.readFlag()
        var ppsDeblockingFilterDisabledFlag: Boolean? = null
        if (deblockingFilterControlPresentFlag) {
            reader.readFlag() // deblocking_filter_override_enabled_flag
            val disabled = reader.readFlag() // pps_deblocking_filter_disabled_flag
            ppsDeblockingFilterDisabledFlag = disabled
            if (!disabled) {
                reader.readSe() // pps_beta_offset_div2
                reader.readSe() // pps_tc_offset_div2
            }
        }
        HevcPps(
            ppsId, spsId, dependentSliceSegmentsEnabledFlag, signDataHidingEnabledFlag, cabacInitPresentFlag,
            constrainedIntraPredFlag, transformSkipEnabledFlag, cuQpDeltaEnabledFlag,
            weightedPredFlag, weightedBipredFlag, tilesEnabledFlag, entropyCodingSyncEnabledFlag,
            deblockingFilterControlPresentFlag, ppsDeblockingFilterDisabledFlag,
        )
    } catch (e: Exception) {
        null
    }
}
