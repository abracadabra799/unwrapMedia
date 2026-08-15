# HEVC VPS/SPS/PPS Per-Frame Info Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve and display the actual VPS/SPS/PPS fields a selected frame's own slice segment header references, in the Detail Properties panel, for HEVC streams — the direct HEVC follow-up to the merged H.264 feature.

**Architecture:** Two new pure HEVC parameter-set parsers (VPS, SPS, PPS) reuse the existing `BitReader` and `removeEmulationPreventionBytes`, fed by raw NAL bytes extracted from the video track's `hvcC` box. A separate pure function walks a specific frame's own length-prefixed sample bytes (via `FrameInfo.byteOffset`/`sizeBytes`) to find its first VCL NAL and read just enough of its slice segment header to learn which PPS it references — then a lookup chain resolves that PPS's own SPS, and that SPS's own VPS. All new bitstream logic lives in `com.multiviewer.parser` (alongside `HvcCBoxDecoder`, its natural home); only the final task touches `com.multiviewer.ui` for state and display.

**Tech Stack:** Kotlin, pure JVM (no new dependencies). Reuses `BitReader`, `removeEmulationPreventionBytes`, `ByteReader`/`BoxNode`/`findFirst` (existing parser infra), and `FrameInfo` (already carries `byteOffset`/`sizeBytes`).

Full technical background, the verified real byte fixtures, and their ffmpeg-`trace_headers`-confirmed field values are in `docs/superpowers/specs/2026-08-15-hevc-sps-pps-per-frame-design.md`.

## Global Constraints

- HEVC only — the H.264 feature is untouched; both sections are wired independently and never both show for the same stream (a stream is either avcC or hvcC).
- Core fields only, exactly as listed in the spec's Scope section — not an exhaustive VPS/SPS/PPS/VUI dump.
- `removeEmulationPreventionBytes` MUST be applied to every raw NAL byte array before bit-parsing (VPS/SPS/PPS extraction AND the per-frame slice-header-prefix walk) — verified via real extracted SPS bytes that contain genuine `00 00 03` emulation-prevention sequences. Unlike the H.264 feature (where this was added as a post-merge fix), it is built in from Task 1 here.
- `profile_tier_level()`'s sub-layer profile/level blocks are not supported: if `max_sub_layers_minus1 > 0`, the shared `parseProfileTierLevel` returns `null`. For VPS this yields a partial `HevcVps` (`ptl = null`, `ptlUnsupported = true`); for SPS, `parseHevcSps` returns `null` outright (its own `sps_seq_parameter_set_id` sits after `profile_tier_level()` in the bitstream and can't be recovered either).
- More than one `short_term_ref_pic_set()` in SPS (`num_short_term_ref_pic_sets > 1`) is not supported: `parseHevcSps` returns `null`. Index 0 alone (0 or 1 total sets) is always supported since `inter_ref_pic_set_prediction_flag` only exists for indices != 0.
- Explicit `scaling_list_data()` in SPS (`scaling_list_enabled_flag && sps_scaling_list_data_present_flag`) is not supported: `parseHevcSps` returns `null`.
- Every parsing entry point (`parseHevcVps`, `parseHevcSps`, `parseHevcPps`, `extractHvcCRawParameterSets`, `resolveActiveHevcPicParameterSetId`) catches its own exceptions internally and returns `null` on any failure — callers never need their own try/catch.
- Test fixtures for the bit-level parsing (Task 1) are the REAL byte sequences documented in the spec, verified by hand against `ffmpeg -bsf:v trace_headers` output — not synthetic guesses. Task 2's structural (`hvcC`-walking, NAL-length-prefix-walking) tests use synthetic byte fixtures, matching this codebase's existing `byteReaderOf`/`fileOf`-style box-decoder test convention (the `fileOf` helper already exists in `TestSupport.kt` from the H.264 feature — no need to re-add it).

---

### Task 1: HEVC VPS/SPS/PPS field parsers

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/HevcParameterSets.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetsTest.kt`

**Interfaces:**
- Produces: `data class HevcProfileTierLevel(...)`, `data class HevcVui(...)`, `data class HevcVps(...)`, `data class HevcSps(...)`, `data class HevcPps(...)` (exact fields below).
- Produces: `fun parseHevcVps(nalBytes: ByteArray): HevcVps?`, `fun parseHevcSps(nalBytes: ByteArray): HevcSps?`, `fun parseHevcPps(nalBytes: ByteArray): HevcPps?` — all take raw NAL bytes INCLUDING the 2-byte NAL header (skipped internally via `BitReader(removeEmulationPreventionBytes(nalBytes), startByteOffset = 2)`). Task 3's UI wiring calls these (indirectly, via Task 2's extraction result) and reads the resulting data classes' fields for display.
- Consumes: `BitReader`, `removeEmulationPreventionBytes` (existing, from the H.264 feature).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetsTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HevcParameterSetsTest {
    // Real HEVC VPS/SPS/PPS, from a locally-recorded HEVC (Main) file, NAL headers included --
    // every field asserted below was cross-verified by hand against `ffmpeg -bsf:v trace_headers`
    // output (see the design spec).
    private val realVps = byteArrayOf(
        0x40, 0x01, 0x0c, 0x01, 0xff.toByte(), 0xff.toByte(), 0x01, 0x60,
        0x00, 0x00, 0x03, 0x00, 0xb0.toByte(), 0x00, 0x00, 0x03,
        0x00, 0x00, 0x03, 0x00, 0x78, 0xac.toByte(), 0x09, 0x00,
    )
    private val realSps = byteArrayOf(
        0x42, 0x01, 0x01, 0x01, 0x60, 0x00, 0x00, 0x03,
        0x00, 0xb0.toByte(), 0x00, 0x00, 0x03, 0x00, 0x00, 0x03,
        0x00, 0x78, 0xa0.toByte(), 0x03, 0x70, 0x80.toByte(), 0x3e, 0x1c,
        0xb2.toByte(), 0xe5.toByte(), 0xae.toByte(), 0xe4.toByte(), 0xc9.toByte(), 0x2e, 0xa6.toByte(), 0xe0.toByte(),
        0xa0.toByte(), 0xc0.toByte(), 0xa0.toByte(), 0x5d, 0xa1.toByte(), 0x42, 0x50, 0x00,
    )
    private val realPps = byteArrayOf(
        0x44, 0x01, 0xc1.toByte(), 0xe3.toByte(), 0x0f, 0x09, 0x41, 0xef.toByte(),
        0x61, 0x28, 0x00,
    )

    @Test
    fun `parseHevcVps extracts every curated field correctly from a real VPS`() {
        val vps = parseHevcVps(realVps)
        assertNotNull(vps)
        assertEquals(0, vps.vpsId)
        assertEquals(0, vps.maxSubLayersMinus1)
        assertFalse(vps.ptlUnsupported)
        val ptl = assertNotNull(vps.ptl)
        assertEquals(0, ptl.generalProfileSpace)
        assertFalse(ptl.generalTierFlag)
        assertEquals(1, ptl.generalProfileIdc)
        assertEquals(120, ptl.generalLevelIdc)
    }

    @Test
    fun `parseHevcSps extracts every curated field correctly from a real SPS`() {
        val sps = parseHevcSps(realSps)
        assertNotNull(sps)
        assertEquals(0, sps.spsId)
        assertEquals(0, sps.vpsId)
        assertEquals(0, sps.maxSubLayersMinus1)
        assertEquals(0, sps.ptl.generalProfileSpace)
        assertFalse(sps.ptl.generalTierFlag)
        assertEquals(1, sps.ptl.generalProfileIdc)
        assertEquals(120, sps.ptl.generalLevelIdc)
        assertEquals(1, sps.chromaFormatIdc)
        assertEquals(1760, sps.picWidth)
        assertEquals(992, sps.picHeight)
        assertEquals(8, sps.bitDepthLuma)
        assertEquals(8, sps.bitDepthChroma)
        val vui = assertNotNull(sps.vui)
        assertEquals(true, vui.videoFullRangeFlag)
        assertEquals(5, vui.colourPrimaries)
        assertEquals(6, vui.transferCharacteristics)
        assertEquals(5, vui.matrixCoefficients)
    }

    @Test
    fun `parseHevcPps extracts every curated field correctly from a real PPS`() {
        val pps = parseHevcPps(realPps)
        assertNotNull(pps)
        assertEquals(0, pps.ppsId)
        assertEquals(0, pps.spsId)
        assertFalse(pps.dependentSliceSegmentsEnabledFlag)
        assertTrue(pps.signDataHidingEnabledFlag)
        assertTrue(pps.cabacInitPresentFlag)
        assertFalse(pps.constrainedIntraPredFlag)
        assertFalse(pps.transformSkipEnabledFlag)
        assertTrue(pps.cuQpDeltaEnabledFlag)
        assertFalse(pps.weightedPredFlag)
        assertFalse(pps.weightedBipredFlag)
        assertTrue(pps.tilesEnabledFlag)
        assertFalse(pps.entropyCodingSyncEnabledFlag)
        assertTrue(pps.deblockingFilterControlPresentFlag)
        assertEquals(false, pps.ppsDeblockingFilterDisabledFlag)
    }

    @Test
    fun `parseHevcVps returns null for empty input`() {
        assertNull(parseHevcVps(ByteArray(0)))
    }

    @Test
    fun `parseHevcSps returns null for empty input`() {
        assertNull(parseHevcSps(ByteArray(0)))
    }

    @Test
    fun `parseHevcPps returns null for empty input`() {
        assertNull(parseHevcPps(ByteArray(0)))
    }

    @Test
    fun `parseHevcVps returns a partial result with ptlUnsupported when max_sub_layers_minus1 is nonzero`() {
        // Hand-constructed from the real VPS: vps_max_sub_layers_minus1 is a 3-bit field starting
        // at bit 12 relative to the VPS payload (after the 2-byte NAL header), which is bits 4-6 of
        // byte index 3 (0xf4001 header consumes bytes 0-1; payload byte 2 = index 2, byte 3 = index
        // 3). Traced mechanically: byte[3] = 0x01 = 00000001. The field's top bit is byte[3] bit 4
        // (0-indexed from MSB), worth 1 << (7-4) = 0x08. Setting that bit changes byte[3] to 0x09 =
        // 00001001, making vps_max_sub_layers_minus1 = bits 4-6 = "100" = 4 (was "000" = 0).
        val mutated = realVps.copyOf()
        mutated[3] = (mutated[3].toInt() or 0x08).toByte()
        val vps = parseHevcVps(mutated)
        assertNotNull(vps)
        assertEquals(4, vps.maxSubLayersMinus1)
        assertTrue(vps.ptlUnsupported)
        assertNull(vps.ptl)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.HevcParameterSetsTest"`
Expected: FAIL — `parseHevcVps`/`parseHevcSps`/`parseHevcPps` unresolved (compile error), since `HevcParameterSets.kt` doesn't exist yet.

- [ ] **Step 3: Create `HevcParameterSets.kt`**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.HevcParameterSetsTest"`
Expected: PASS (7/7 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HevcParameterSets.kt \
        app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetsTest.kt
git commit -m "Add HEVC VPS/SPS/PPS field parsers"
```

---

### Task 2: Raw NAL extraction from `hvcC`, and per-frame active-PPS resolution

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/HevcParameterSetExtraction.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetExtractionTest.kt`

**Interfaces:**
- Produces: `data class HvcCRawParameterSets(val lengthSize: Int, val vpsList: List<ByteArray>, val spsList: List<ByteArray>, val ppsList: List<ByteArray>)`
- Produces: `fun extractHvcCRawParameterSets(file: java.io.File, hvcCNode: BoxNode): HvcCRawParameterSets?` — Task 3 calls this once per video tab.
- Produces: `fun resolveActiveHevcPicParameterSetId(file: java.io.File, byteOffset: Long, sizeBytes: Int, lengthSize: Int): Int?` — Task 3 calls this per selected frame.
- Produces: `fun resolveActiveHevcParameterSets(vpsList: List<HevcVps>, spsList: List<HevcSps>, ppsList: List<HevcPps>, picParameterSetId: Int): Triple<HevcVps?, HevcSps, HevcPps>?` — pure lookup, Task 3 calls this after the above.
- Consumes: `BitReader`, `removeEmulationPreventionBytes`, `HevcVps`, `HevcSps`, `HevcPps` (existing / Task 1); `ByteReader`, `BoxNode` (existing); `fileOf` test helper (existing, in `TestSupport.kt`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetExtractionTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HevcParameterSetExtractionTest {
    // Synthetic hvcC payload (structure only -- VPS/SPS/PPS contents don't need to be real, valid
    // bitstream since extractHvcCRawParameterSets never parses them, only slices them out by their
    // declared lengths): 23-byte fixed header (length_size_minus1=3 -> length_size=4, num_arrays=3),
    // then one VPS array (1 NAL, 3 bytes), one SPS array (1 NAL, 3 bytes), one PPS array (1 NAL, 2
    // bytes). Array-type bytes 0x20/0x21/0x22 encode array_completeness=0, reserved=0, and
    // nal_unit_type 32/33/34 in the low 6 bits (VPS/SPS/PPS respectively).
    private fun hvcCPayload(): ByteArray = byteArrayOf(
        0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x1e, 0xf0.toByte(), 0x00, 0xfc.toByte(),
        0xfd.toByte(), 0xf8.toByte(), 0xf8.toByte(), 0x00, 0x00, 0x03, 0x03,
        0x20, 0x00, 0x01, 0x00, 0x03, 0x40, 0xaa.toByte(), 0xbb.toByte(),
        0x21, 0x00, 0x01, 0x00, 0x03, 0x42, 0xcc.toByte(), 0xdd.toByte(),
        0x22, 0x00, 0x01, 0x00, 0x02, 0x44, 0xee.toByte(),
    )

    private fun hvcCBoxNode(payload: ByteArray): Pair<BoxNode, java.io.File> {
        val headerSize = 8
        val header = ByteArray(headerSize) // irrelevant filler, box parsing reads by absolute offset
        val file = fileOf(header + payload)
        val node = BoxNode(type = "hvcC", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong())
        return node to file
    }

    @Test
    fun `extractHvcCRawParameterSets reads length_size and the declared VPS, SPS, and PPS NAL bytes`() {
        val (node, file) = hvcCBoxNode(hvcCPayload())
        val result = extractHvcCRawParameterSets(file, node)
        assertNotNull(result)
        assertEquals(4, result.lengthSize) // length_size_minus_one=3 -> 3+1=4
        assertEquals(1, result.vpsList.size)
        assertEquals(byteArrayOf(0x40, 0xaa.toByte(), 0xbb.toByte()).toList(), result.vpsList[0].toList())
        assertEquals(1, result.spsList.size)
        assertEquals(byteArrayOf(0x42, 0xcc.toByte(), 0xdd.toByte()).toList(), result.spsList[0].toList())
        assertEquals(1, result.ppsList.size)
        assertEquals(byteArrayOf(0x44, 0xee.toByte()).toList(), result.ppsList[0].toList())
    }

    @Test
    fun `extractHvcCRawParameterSets returns null when the box is too short for its fixed header`() {
        val (node, file) = hvcCBoxNode(byteArrayOf(0x01, 0x01, 0x00)) // only 3 bytes, needs 23
        assertNull(extractHvcCRawParameterSets(file, node))
    }

    // Length-prefixed samples (hvcC-style, length_size=4): one 3-byte non-VCL NAL (type 39, prefix
    // SEI) followed by a 5-byte VCL NAL (type 20, IDR_W_RADL) whose RBSP starts with a real
    // slice-segment-header prefix (first_slice_segment_in_pic_flag=1,
    // no_output_of_prior_pics_flag=0, slice_pic_parameter_set_id=0 -- same bytes verified in
    // HevcParameterSetsTest's design-spec source file).
    private fun sampleBytes(): ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x03, 0x4e, 0x01, 0xaa.toByte(), // 3-byte non-VCL NAL (type 39)
        0x00, 0x00, 0x00, 0x05, 0x28, 0x01, 0xaf.toByte(), 0x09, 0xa8.toByte(), // 5-byte slice NAL (type 20)
    )

    @Test
    fun `resolveActiveHevcPicParameterSetId skips non-VCL NALs and decodes the first VCL slice header`() {
        val file = fileOf(sampleBytes())
        val picParameterSetId = resolveActiveHevcPicParameterSetId(file, byteOffset = 0, sizeBytes = sampleBytes().size, lengthSize = 4)
        assertEquals(0, picParameterSetId)
    }

    @Test
    fun `resolveActiveHevcPicParameterSetId returns null when no VCL NAL is present in range`() {
        val onlyNonVcl = byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x4e, 0x01, 0xaa.toByte())
        val file = fileOf(onlyNonVcl)
        assertNull(resolveActiveHevcPicParameterSetId(file, byteOffset = 0, sizeBytes = onlyNonVcl.size, lengthSize = 4))
    }

    private val dummyPtl = HevcProfileTierLevel(0, false, 1, 120)

    @Test
    fun `resolveActiveHevcParameterSets looks up the matching PPS, then its SPS, then its VPS`() {
        val vps0 = HevcVps(0, 0, dummyPtl)
        val vps1 = HevcVps(1, 0, dummyPtl)
        val sps0 = HevcSps(0, 1, 0, dummyPtl, 1, 1760, 992, 8, 8, null) // references vps1, not vps0
        val pps0 = HevcPps(0, 0, false, true, true, false, false, true, false, false, true, false, true, false)
        val result = resolveActiveHevcParameterSets(listOf(vps0, vps1), listOf(sps0), listOf(pps0), picParameterSetId = 0)
        assertNotNull(result)
        val (vps, sps, pps) = result
        assertEquals(1, vps?.vpsId)
        assertEquals(0, sps.spsId)
        assertEquals(0, pps.ppsId)
    }

    @Test
    fun `resolveActiveHevcParameterSets returns a null VPS when its id has no match, without failing the whole lookup`() {
        val sps0 = HevcSps(0, 99, 0, dummyPtl, 1, 1760, 992, 8, 8, null) // vpsId 99 doesn't exist
        val pps0 = HevcPps(0, 0, false, true, true, false, false, true, false, false, true, false, true, false)
        val result = resolveActiveHevcParameterSets(emptyList(), listOf(sps0), listOf(pps0), picParameterSetId = 0)
        assertNotNull(result)
        assertNull(result.first)
        assertEquals(0, result.second.spsId)
    }

    @Test
    fun `resolveActiveHevcParameterSets returns null when the pic parameter set id has no match`() {
        assertNull(resolveActiveHevcParameterSets(emptyList(), emptyList(), emptyList(), picParameterSetId = 0))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.HevcParameterSetExtractionTest"`
Expected: FAIL — compile error, `HevcParameterSetExtraction.kt` doesn't exist yet.

- [ ] **Step 3: Create `HevcParameterSetExtraction.kt`**

```kotlin
package com.multiviewer.parser

import java.io.File

data class HvcCRawParameterSets(
    val lengthSize: Int,
    val vpsList: List<ByteArray>,
    val spsList: List<ByteArray>,
    val ppsList: List<ByteArray>,
)

private const val HVCC_FIXED_HEADER_SIZE = 23
private const val HEVC_NAL_TYPE_VPS = 32
private const val HEVC_NAL_TYPE_SPS = 33
private const val HEVC_NAL_TYPE_PPS = 34

// Mirrors HvcCBoxDecoder's own walk of this exact box structure (and HeifHevcThumbnail.kt's
// private readHvcCInfo, which walks the same structure for a different purpose -- feeding a HEIF
// image item to ffmpeg as one concatenated Annex-B buffer), but COLLECTS the raw VPS/SPS/PPS
// bytes as three separate lists instead.
fun extractHvcCRawParameterSets(file: File, hvcCNode: BoxNode): HvcCRawParameterSets? {
    return try {
        ByteReader.open(file).use { reader ->
            val payloadStart = hvcCNode.offset + hvcCNode.headerSize
            val payloadEnd = hvcCNode.offset + hvcCNode.size
            if (payloadEnd - payloadStart < HVCC_FIXED_HEADER_SIZE) return@use null
            val lengthSize = (reader.readUInt8(payloadStart + 21) and 0x03) + 1
            val numArrays = reader.readUInt8(payloadStart + 22)

            val vpsList = mutableListOf<ByteArray>()
            val spsList = mutableListOf<ByteArray>()
            val ppsList = mutableListOf<ByteArray>()
            var pos = payloadStart + HVCC_FIXED_HEADER_SIZE
            var arraysWalked = 0
            while (arraysWalked < numArrays && pos + 3 <= payloadEnd) {
                val nalType = reader.readUInt8(pos) and 0x3F
                val numNalus = reader.readUInt16(pos + 1)
                pos += 3
                var nalusWalked = 0
                while (nalusWalked < numNalus && pos + 2 <= payloadEnd) {
                    val nalLength = reader.readUInt16(pos)
                    pos += 2
                    if (pos + nalLength > payloadEnd) break
                    val nalBytes = reader.readBytes(pos, nalLength)
                    when (nalType) {
                        HEVC_NAL_TYPE_VPS -> vpsList.add(nalBytes)
                        HEVC_NAL_TYPE_SPS -> spsList.add(nalBytes)
                        HEVC_NAL_TYPE_PPS -> ppsList.add(nalBytes)
                    }
                    pos += nalLength
                    nalusWalked++
                }
                arraysWalked++
            }
            HvcCRawParameterSets(lengthSize, vpsList, spsList, ppsList)
        }
    } catch (e: Exception) {
        null
    }
}

// Reads a specific frame's own length-prefixed sample bytes (FrameInfo.byteOffset/sizeBytes) and
// walks its NALs looking for the first VCL one (nal_unit_type < 32 -- HEVC's non-VCL types start
// at 32, unlike H.264's two explicit type checks), skipping non-VCL NALs (SEI, etc.) that can
// precede it in the same sample. Reads only a small fixed prefix of that NAL --
// first_slice_segment_in_pic_flag/no_output_of_prior_pics_flag/slice_pic_parameter_set_id are all
// within a handful of bits past the 2-byte NAL header and, for any realistic
// single-slice-per-frame encode, comfortably fit in a handful of bytes; 16 bytes is a generous
// safety margin, well short of the full NAL, and this never touches any actual CABAC-coded slice
// data.
fun resolveActiveHevcPicParameterSetId(file: File, byteOffset: Long, sizeBytes: Int, lengthSize: Int): Int? {
    return try {
        ByteReader.open(file).use { reader ->
            val sampleEnd = byteOffset + sizeBytes
            var pos = byteOffset
            while (pos + lengthSize <= sampleEnd) {
                val nalLength = when (lengthSize) {
                    1 -> reader.readUInt8(pos).toLong()
                    2 -> reader.readUInt16(pos).toLong()
                    4 -> reader.readUInt32(pos)
                    else -> return@use null
                }
                pos += lengthSize
                if (nalLength <= 0 || pos + nalLength > sampleEnd) break
                val nalUnitType = (reader.readUInt8(pos) shr 1) and 0x3F
                if (nalUnitType < 32) {
                    val prefixLength = minOf(nalLength, 16L).toInt()
                    val nalBytes = reader.readBytes(pos, prefixLength)
                    val bitReader = BitReader(removeEmulationPreventionBytes(nalBytes), startByteOffset = 2)
                    return@use try {
                        bitReader.readFlag() // first_slice_segment_in_pic_flag
                        if (nalUnitType in 16..23) bitReader.readFlag() // no_output_of_prior_pics_flag (IRAP only)
                        bitReader.readUe() // slice_pic_parameter_set_id
                    } catch (e: Exception) {
                        null
                    }
                }
                pos += nalLength
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}

fun resolveActiveHevcParameterSets(
    vpsList: List<HevcVps>,
    spsList: List<HevcSps>,
    ppsList: List<HevcPps>,
    picParameterSetId: Int,
): Triple<HevcVps?, HevcSps, HevcPps>? {
    val pps = ppsList.find { it.ppsId == picParameterSetId } ?: return null
    val sps = spsList.find { it.spsId == pps.spsId } ?: return null
    val vps = vpsList.find { it.vpsId == sps.vpsId }
    return Triple(vps, sps, pps)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.HevcParameterSetExtractionTest"`
Expected: PASS (7/7 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HevcParameterSetExtraction.kt \
        app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetExtractionTest.kt
git commit -m "Add hvcC raw NAL extraction and per-frame active-PPS resolution"
```

---

### Task 3: Wire into TabState and the Detail Properties panel

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (add four `TabState` fields after the existing `avcLengthSize`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` (add a second `LaunchedEffect` populating the parsed VPS/SPS/PPS lists once per tab, parallel to the existing `avcC` one)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` (`DetailPropertiesTabContent` — resolve and display the selected frame's active VPS/SPS/PPS)

**Interfaces:**
- Consumes: `com.multiviewer.parser.extractHvcCRawParameterSets`, `com.multiviewer.parser.parseHevcVps`, `com.multiviewer.parser.parseHevcSps`, `com.multiviewer.parser.parseHevcPps` (Tasks 1-2) in `VideoInspectorUI.kt`.
- Consumes: `com.multiviewer.parser.resolveActiveHevcPicParameterSetId`, `com.multiviewer.parser.resolveActiveHevcParameterSets`, `com.multiviewer.parser.HevcVps`, `com.multiviewer.parser.HevcSps`, `com.multiviewer.parser.HevcPps` (Task 2) in `ImageInspectorUI.kt`.
- Consumes: `com.multiviewer.parser.findFirst` (existing).

No new automated tests in this task — UI wiring only, matching this codebase's established convention (verified via manual app testing, same as the H.264 feature's own final task).

- [ ] **Step 1: Add `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, immediately after the existing:

```kotlin
    var avcSpsList: List<com.multiviewer.parser.H264Sps> by mutableStateOf(emptyList())
    var avcPpsList: List<com.multiviewer.parser.H264Pps> by mutableStateOf(emptyList())
    var avcLengthSize: Int? by mutableStateOf(null)
```

insert:

```kotlin

    // HEVC VPS/SPS/PPS (see HevcParameterSets.kt / HevcParameterSetExtraction.kt) -- parsed once
    // per video tab from the hvcC box, independent of any specific frame selection. Same
    // empty-list-means-"not applicable" convention as the avc* fields above; a stream is either
    // H.264 or HEVC, so at most one of the two field groups is ever populated for a given tab.
    var hevcVpsList: List<com.multiviewer.parser.HevcVps> by mutableStateOf(emptyList())
    var hevcSpsList: List<com.multiviewer.parser.HevcSps> by mutableStateOf(emptyList())
    var hevcPpsList: List<com.multiviewer.parser.HevcPps> by mutableStateOf(emptyList())
    var hevcLengthSize: Int? by mutableStateOf(null)
```

- [ ] **Step 2: Populate the parsed lists once per tab in `VideoInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`, immediately after the existing:

```kotlin
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val avcCNode = com.multiviewer.parser.findFirst(root) { it.type == "avcC" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractAvcCRawParameterSets(tab.file, avcCNode) ?: return@withContext
            tab.avcLengthSize = raw.lengthSize
            tab.avcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseH264Sps(it) }
            tab.avcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseH264Pps(it) }
        }
    }
```

insert:

```kotlin

    // Parses the video track's hvcC box once per tab -- mirrors the avcC LaunchedEffect above.
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val hvcCNode = com.multiviewer.parser.findFirst(root) { it.type == "hvcC" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractHvcCRawParameterSets(tab.file, hvcCNode) ?: return@withContext
            tab.hevcLengthSize = raw.lengthSize
            tab.hevcVpsList = raw.vpsList.mapNotNull { com.multiviewer.parser.parseHevcVps(it) }
            tab.hevcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseHevcSps(it) }
            tab.hevcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseHevcPps(it) }
        }
    }
```

- [ ] **Step 3: Resolve and display the selected frame's active VPS/SPS/PPS in `ImageInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`'s `DetailPropertiesTabContent`, find the existing block that starts with:

```kotlin
        val resolvedH264Params = if (selectedFrame != null) {
```

and ends with:

```kotlin
            }.value
        } else {
            null
        }
```

(this whole block assigns `resolvedH264Params`, calling `produceState` with `resolveActivePicParameterSetId`/`resolveActiveParameterSets`). Immediately after that block's closing `}`, and still before the `Box(modifier = Modifier.fillMaxSize()) {` line that follows it, insert:

```kotlin
        val resolvedHevcParams = if (selectedFrame != null) {
            produceState<Triple<com.multiviewer.parser.HevcVps?, com.multiviewer.parser.HevcSps, com.multiviewer.parser.HevcPps>?>(
                null, selectedFrame, tab.hevcSpsList, tab.hevcPpsList, tab.hevcLengthSize,
            ) {
                value = null
                val byteOffset = selectedFrame.byteOffset
                val lengthSize = tab.hevcLengthSize
                if (byteOffset != null && lengthSize != null && tab.hevcPpsList.isNotEmpty()) {
                    value = withContext(Dispatchers.IO) {
                        val picParameterSetId = com.multiviewer.parser.resolveActiveHevcPicParameterSetId(
                            tab.file, byteOffset, selectedFrame.sizeBytes, lengthSize,
                        ) ?: return@withContext null
                        com.multiviewer.parser.resolveActiveHevcParameterSets(
                            tab.hevcVpsList, tab.hevcSpsList, tab.hevcPpsList, picParameterSetId,
                        )
                    }
                }
            }.value
        } else {
            null
        }
```

Then, inside the `selectedFrame != null ->` item block, find the existing block that starts with:

```kotlin
                            resolvedH264Params?.let { (sps, pps) ->
```

and reads through several `PropertyRow(...)` calls and a final `sps.vui?.let { vui -> ... }`, down to its own matching closing `}` (this whole block is the "H.264 Parameter Sets" section). Immediately after that block's closing `}`, and still before the outer `item { ... }` block's own closing `}`, insert a parallel block for HEVC:

```kotlin
                            resolvedHevcParams?.let { (vps, sps, pps) ->
                                Spacer(Modifier.height(8.dp))
                                Text("HEVC Parameter Sets", style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue))
                                PropertyRow("VPS ID / SPS ID / PPS ID", "${vps?.vpsId ?: "-"} / ${sps.spsId} / ${pps.ppsId}")
                                PropertyRow(
                                    "Profile / Tier / Level",
                                    "${sps.ptl.generalProfileIdc} / ${if (sps.ptl.generalTierFlag) "High" else "Main"} / ${sps.ptl.generalLevelIdc}",
                                )
                                PropertyRow("Chroma Format", "4:${if (sps.chromaFormatIdc == 0) "0:0" else if (sps.chromaFormatIdc == 1) "2:0" else if (sps.chromaFormatIdc == 2) "2:2" else "4:4"}")
                                PropertyRow("Resolution", "${sps.picWidth} x ${sps.picHeight}")
                                PropertyRow("Bit Depth (Luma/Chroma)", "${sps.bitDepthLuma} / ${sps.bitDepthChroma}")
                                PropertyRow("Dependent Slice Segments", if (pps.dependentSliceSegmentsEnabledFlag) "Enabled" else "Disabled")
                                PropertyRow("Sign Data Hiding", if (pps.signDataHidingEnabledFlag) "Enabled" else "Disabled")
                                PropertyRow("CABAC Init Present", if (pps.cabacInitPresentFlag) "Yes" else "No")
                                PropertyRow("Constrained Intra Pred", if (pps.constrainedIntraPredFlag) "Enabled" else "Disabled")
                                PropertyRow("Transform Skip", if (pps.transformSkipEnabledFlag) "Enabled" else "Disabled")
                                PropertyRow("CU QP Delta", if (pps.cuQpDeltaEnabledFlag) "Enabled" else "Disabled")
                                PropertyRow("Weighted Pred / Bipred", "${if (pps.weightedPredFlag) "Yes" else "No"} / ${if (pps.weightedBipredFlag) "Yes" else "No"}")
                                PropertyRow("Tiles Enabled", if (pps.tilesEnabledFlag) "Yes" else "No")
                                PropertyRow("Entropy Coding Sync (WPP)", if (pps.entropyCodingSyncEnabledFlag) "Enabled" else "Disabled")
                                PropertyRow(
                                    "Deblocking Filter",
                                    if (!pps.deblockingFilterControlPresentFlag) "Default"
                                    else if (pps.ppsDeblockingFilterDisabledFlag == true) "Disabled" else "Enabled",
                                )
                                sps.vui?.let { vui ->
                                    vui.colourPrimaries?.let { PropertyRow("Colour Primaries", it.toString()) }
                                    vui.transferCharacteristics?.let { PropertyRow("Transfer Characteristics", it.toString()) }
                                    vui.matrixCoefficients?.let { PropertyRow("Matrix Coefficients", it.toString()) }
                                    vui.videoFullRangeFlag?.let { PropertyRow("Full Range", if (it) "Yes" else "No") }
                                }
                            }
```

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Manual verification**

Launch the app (`./gradlew :app:run`), open a real HEVC file (e.g. the same test file the design spec's ground truth came from, or any other HEVC video), select a frame (GOP bar, filmstrip, or arrow-key stepping), confirm:
- An "HEVC Parameter Sets" section appears below the existing Frame #/Type/Size/PTS/Byte Offset/GOP Position rows (and below the H.264 section's slot, which stays empty for this stream).
- The shown values (profile/tier/level, chroma format, resolution, bit depth, PPS flags, colour fields) match what `ffmpeg -bsf:v trace_headers` independently reports for the same file.
- Selecting frames across different GOPs still shows the same VPS/SPS/PPS ids in a typical single-parameter-set stream (most real files).
- Opening an H.264 file still shows only the "H.264 Parameter Sets" section (no regression), and opening a file with neither codec shows neither section.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt \
        app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Show a selected frame's actual HEVC VPS/SPS/PPS fields in Detail Properties"
```
