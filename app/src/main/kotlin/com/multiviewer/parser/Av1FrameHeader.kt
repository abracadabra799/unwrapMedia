package com.multiviewer.parser

enum class Av1FrameType { KEY, INTER, INTRA_ONLY, SWITCH }

data class Av1FrameHeader(
    val frameType: Av1FrameType,
    val showFrame: Boolean,
    val showableFrame: Boolean,
    val frameWidth: Int,
    val frameHeight: Int,
    val baseQIdx: Int,
    val tileCols: Int,
    val tileRows: Int,
    val refreshFrameFlags: Int,
    val orderHint: Int,
)

private const val NUM_REF_FRAMES = 8
private const val REFS_PER_FRAME = 7
private const val MAX_TILE_WIDTH = 4096
private const val MAX_TILE_AREA = 4096 * 2304
private const val MAX_TILE_COLS = 64
private const val MAX_TILE_ROWS = 64

// tile_log2(blkSize, target), AV1 spec 5.9.15: smallest k such that (blkSize << k) >= target.
private fun tileLog2(blkSize: Int, target: Int): Int {
    var k = 0
    while ((blkSize shl k) < target) k++
    return k
}

// Result of readFrameSize(): frame_size() (spec 5.9.5) + superres_params() (spec 5.9.6) also need
// to expose whether superres actually scaled this frame, since that determines (per spec 5.9.6)
// whether UpscaledWidth == FrameWidth still holds afterward -- see the allow_intrabc gating below.
private data class FrameSizeResult(val frameWidth: Int, val frameHeight: Int, val useSuperres: Boolean)

// frame_size() (spec 5.9.5) + superres_params() (spec 5.9.6), for the frame_size_override_flag ==
// false case only -- the true case is bailed out on by parseAv1FrameHeader before this is called,
// so the frame_width_minus_1/frame_height_minus_1 explicit-override branch (and
// frame_size_with_refs()'s cross-frame reference-dimension state) never needs implementing here.
private fun readFrameSize(reader: BitReader, seqHeader: Av1SequenceHeader): FrameSizeResult {
    var frameWidth = seqHeader.maxFrameWidth
    val frameHeight = seqHeader.maxFrameHeight
    val useSuperres = if (seqHeader.enableSuperres) reader.readFlag() else false
    if (useSuperres) {
        val codedDenom = reader.readBits(3) // SUPERRES_DENOM_BITS
        val superresDenom = codedDenom + 9 // SUPERRES_DENOM_MIN
        // UpscaledWidth = FrameWidth (unscaled) before this line; FrameWidth is then the
        // superres-downscaled value (SUPERRES_NUM = 8).
        frameWidth = (frameWidth * 8 + superresDenom / 2) / superresDenom
    }
    return FrameSizeResult(frameWidth, frameHeight, useSuperres)
}

// render_size() (spec 5.9.7). Render dimensions aren't a curated field -- only the bits are
// consumed here, to stay bit-aligned for the fields that follow.
private fun readRenderSize(reader: BitReader) {
    val renderAndFrameSizeDifferent = reader.readFlag()
    if (renderAndFrameSizeDifferent) {
        reader.readBits(16) // render_width_minus_1
        reader.readBits(16) // render_height_minus_1
    }
}

// tile_info() (spec 5.9.15), uniform-tile-spacing path only -- returns null (bail) when
// uniform_tile_spacing_flag is false; see this plan's Global Constraints bail-out list.
private fun readTileInfo(reader: BitReader, seqHeader: Av1SequenceHeader, frameWidth: Int, frameHeight: Int): Pair<Int, Int>? {
    val miCols = 2 * ((frameWidth + 7) shr 3)
    val miRows = 2 * ((frameHeight + 7) shr 3)
    val use128 = seqHeader.use128x128Superblock
    val sbCols = if (use128) (miCols + 31) shr 5 else (miCols + 15) shr 4
    val sbRows = if (use128) (miRows + 31) shr 5 else (miRows + 15) shr 4
    val sbShift = if (use128) 5 else 4
    val sbSize = sbShift + 2
    val maxTileWidthSb = MAX_TILE_WIDTH shr sbSize
    val maxTileAreaSb = MAX_TILE_AREA shr (2 * sbSize)
    val minLog2TileCols = tileLog2(maxTileWidthSb, sbCols)
    val maxLog2TileCols = tileLog2(1, minOf(sbCols, MAX_TILE_COLS))
    val maxLog2TileRows = tileLog2(1, minOf(sbRows, MAX_TILE_ROWS))
    val minLog2Tiles = maxOf(minLog2TileCols, tileLog2(maxTileAreaSb, sbRows * sbCols))

    val uniformTileSpacingFlag = reader.readFlag()
    if (!uniformTileSpacingFlag) return null

    var tileColsLog2 = minLog2TileCols
    while (tileColsLog2 < maxLog2TileCols) {
        if (reader.readFlag()) tileColsLog2++ else break // increment_tile_cols_log2
    }
    val tileWidthSb = (sbCols + (1 shl tileColsLog2) - 1) shr tileColsLog2
    var tileCols = 0
    var startSb = 0
    while (startSb < sbCols) {
        startSb += tileWidthSb
        tileCols++
    }

    val minLog2TileRows = maxOf(minLog2Tiles - tileColsLog2, 0)
    var tileRowsLog2 = minLog2TileRows
    while (tileRowsLog2 < maxLog2TileRows) {
        if (reader.readFlag()) tileRowsLog2++ else break // increment_tile_rows_log2
    }
    val tileHeightSb = (sbRows + (1 shl tileRowsLog2) - 1) shr tileRowsLog2
    var tileRows = 0
    startSb = 0
    while (startSb < sbRows) {
        startSb += tileHeightSb
        tileRows++
    }

    if (tileColsLog2 > 0 || tileRowsLog2 > 0) {
        reader.readBits(tileRowsLog2 + tileColsLog2) // context_update_tile_id
        reader.readBits(2) // tile_size_bytes_minus_1
    }
    return tileCols to tileRows
}

// Parses AV1 spec 5.9.2 uncompressed_header() (the syntax carried by an OBU_FRAME_HEADER, or the
// leading portion of an OBU_FRAME per spec 5.10 frame_obu()) from bit 0 of `payload`, stopping
// right after quantization_params()'s base_q_idx (spec 5.9.12) -- this plan's curated fields never
// need anything parsed later in the syntax (segmentation/loop-filter/CDEF/restoration/film-grain),
// so parsing simply stops there.
//
// Deliberately stateless: unlike the AV1 spec's own reference-frame bookkeeping (section 7.20,
// tracked via RefOrderHint[]/RefValid[]/RefFrameType[] across frames in decode order), none of this
// function's curated output fields' bit-widths actually depend on that cross-frame state --
// verified by tracing every bitstream read between bit 0 and base_q_idx against the spec. The one
// path that would require real cross-frame state (frame_size_with_refs(), which copies a stored
// reference frame's width/height when frame_size_override_flag is set on an inter frame) is
// avoided by this function's own frame_size_override_flag bail-out below. That means each frame's
// header can be parsed independently, in any order -- callers do not need to drive frames
// sequentially in decode order the way the AV1 spec's own reference-state process requires.
//
// Returns null on a genuine parse failure, or when any of the following (uncommon,
// out-of-scope-for-this-plan) conditions hold -- mirrors the "stop rather than guess" precedent
// established by Av1SequenceHeader.kt's reduced_still_picture_header/timing_info_present_flag
// bail-outs:
//   - show_existing_frame == true (this frame repeats a previously decoded frame's contents per
//     spec 7.20, rather than carrying its own header fields).
//   - seqHeader.frameIdNumbersPresentFlag == true (adds current_frame_id/delta_frame_id fields
//     this function doesn't track).
//   - frame_size_override_flag == true, whether explicitly signaled or implied by frame_type ==
//     SWITCH_FRAME (the inter-frame variant requires the cross-frame reference-dimension state
//     described above; the intra-frame variant is a rare explicit-resolution-change case).
//   - tile_info()'s uniform_tile_spacing_flag == false (explicit per-tile-size signaling via
//     ns()-coded values -- uncommon; most encoders emit uniform tile spacing).
fun parseAv1FrameHeader(payload: ByteArray, seqHeader: Av1SequenceHeader): Av1FrameHeader? {
    if (payload.isEmpty()) return null
    return try {
        val reader = BitReader(payload)

        val showExistingFrame = reader.readFlag()
        if (showExistingFrame) return null

        val frameTypeCode = reader.readBits(2)
        val frameType = when (frameTypeCode) {
            0 -> Av1FrameType.KEY
            1 -> Av1FrameType.INTER
            2 -> Av1FrameType.INTRA_ONLY
            else -> Av1FrameType.SWITCH
        }
        val frameIsIntra = frameType == Av1FrameType.KEY || frameType == Av1FrameType.INTRA_ONLY
        val showFrame = reader.readFlag()
        // decoder_model_info_present_flag is always false (guaranteed by Av1SequenceHeader.kt's own
        // timing_info_present_flag bail-out), so temporal_point_info() is never present here.
        val showableFrame = if (showFrame) {
            frameType != Av1FrameType.KEY
        } else {
            reader.readFlag()
        }
        val errorResilientMode = if (frameType == Av1FrameType.SWITCH || (frameType == Av1FrameType.KEY && showFrame)) {
            true
        } else {
            reader.readFlag()
        }

        val disableCdfUpdate = reader.readFlag()
        val allowScreenContentTools = if (seqHeader.seqForceScreenContentTools == SELECT_SCREEN_CONTENT_TOOLS) {
            reader.readFlag()
        } else {
            seqHeader.seqForceScreenContentTools == 1
        }
        var forceIntegerMv = if (allowScreenContentTools) {
            if (seqHeader.seqForceIntegerMv == SELECT_INTEGER_MV) {
                reader.readFlag()
            } else {
                seqHeader.seqForceIntegerMv == 1
            }
        } else {
            false
        }
        if (frameIsIntra) {
            forceIntegerMv = true
        }

        if (seqHeader.frameIdNumbersPresentFlag) return null

        val frameSizeOverrideFlag = if (frameType == Av1FrameType.SWITCH) {
            true
        } else {
            reader.readFlag()
        }
        if (frameSizeOverrideFlag) return null

        val orderHintBits = if (seqHeader.enableOrderHint) seqHeader.orderHintBitsMinus1 + 1 else 0
        val orderHint = reader.readBits(orderHintBits)

        if (!(frameIsIntra || errorResilientMode)) {
            reader.readBits(3) // primary_ref_frame -- state-only afterward (load_cdfs/load_previous
                                // vs. init_non_coeff_cdfs/setup_past_independence), no further bit
                                // consumption in this function depends on its value.
        }

        val allFrames = (1 shl NUM_REF_FRAMES) - 1
        val refreshFrameFlags = if (frameType == Av1FrameType.SWITCH || (frameType == Av1FrameType.KEY && showFrame)) {
            allFrames
        } else {
            reader.readBits(8)
        }

        if (!frameIsIntra || refreshFrameFlags != allFrames) {
            if (errorResilientMode && seqHeader.enableOrderHint) {
                repeat(NUM_REF_FRAMES) { reader.readBits(orderHintBits) } // ref_order_hint[i]
            }
        }

        val frameWidth: Int
        val frameHeight: Int
        if (frameIsIntra) {
            val frameSize = readFrameSize(reader, seqHeader)
            frameWidth = frameSize.frameWidth
            frameHeight = frameSize.frameHeight
            readRenderSize(reader)
            // allow_intrabc (spec 5.9.2) is only read when allow_screen_content_tools &&
            // UpscaledWidth == FrameWidth. Per superres_params() (spec 5.9.6), UpscaledWidth is set
            // equal to FrameWidth *before* FrameWidth is potentially downscaled by SuperresDenom, so
            // UpscaledWidth == FrameWidth holds iff superres did NOT actually scale this frame (i.e.
            // useSuperres == false -- when useSuperres == true, SuperresDenom is always >=
            // SUPERRES_DENOM_MIN(9) > SUPERRES_NUM(8), so FrameWidth is always strictly downscaled
            // below UpscaledWidth).
            if (allowScreenContentTools && !frameSize.useSuperres) {
                reader.readFlag() // allow_intrabc
            }
        } else {
            val frameRefsShortSignaling = if (seqHeader.enableOrderHint) reader.readFlag() else false
            if (frameRefsShortSignaling) {
                reader.readBits(3) // last_frame_idx
                reader.readBits(3) // gold_frame_idx
                // set_frame_refs() (spec 7.8) derives ref_frame_idx[] from stored reference-frame
                // order hints -- a pure computation over state, not a bitstream read, so it doesn't
                // change how many further bits this function consumes.
            }
            repeat(REFS_PER_FRAME) {
                if (!frameRefsShortSignaling) reader.readBits(3) // ref_frame_idx[i]
                // frameIdNumbersPresentFlag is guaranteed false here (bailed out above), so no
                // delta_frame_id_minus_1 field follows each ref_frame_idx.
            }
            val frameSize = readFrameSize(reader, seqHeader)
            frameWidth = frameSize.frameWidth
            frameHeight = frameSize.frameHeight
            readRenderSize(reader)
            if (!forceIntegerMv) {
                reader.readFlag() // allow_high_precision_mv
            }
            val isFilterSwitchable = reader.readFlag()
            if (!isFilterSwitchable) {
                reader.readBits(2) // interpolation_filter
            }
            reader.readFlag() // is_motion_mode_switchable
            if (!errorResilientMode && seqHeader.enableRefFrameMvs) {
                reader.readFlag() // use_ref_frame_mvs
            }
            // OrderHints[]/RefFrameSignBias derivation (spec 5.9.2) reads no further bits -- it's
            // computed from ref_frame_idx[] and stored reference-frame order hints, neither of
            // which this stateless parser tracks or needs for the curated fields below.
        }

        if (!disableCdfUpdate) {
            reader.readFlag() // disable_frame_end_update_cdf
        }
        // primary_ref_frame's cdf-init-vs-load branch and motion_field_estimation() (only reachable
        // when the never-set use_ref_frame_mvs is true) are pure state operations -- no further
        // bitstream reads before tile_info().

        val (tileCols, tileRows) = readTileInfo(reader, seqHeader, frameWidth, frameHeight) ?: return null
        val baseQIdx = reader.readBits(8)

        Av1FrameHeader(
            frameType = frameType,
            showFrame = showFrame,
            showableFrame = showableFrame,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            baseQIdx = baseQIdx,
            tileCols = tileCols,
            tileRows = tileRows,
            refreshFrameFlags = refreshFrameFlags,
            orderHint = orderHint,
        )
    } catch (e: Exception) {
        null
    }
}
