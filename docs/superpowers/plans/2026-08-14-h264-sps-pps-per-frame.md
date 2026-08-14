# H.264 SPS/PPS Per-Frame Info Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve and display the actual SPS/PPS fields a selected frame's own slice header references, in the Detail Properties panel.

**Architecture:** A new Exp-Golomb bit reader (this codebase's first bit-level parser) underlies two pure H.264 parameter-set parsers (SPS, PPS), fed by raw NAL bytes extracted from the video track's `avcC` box. A separate pure function walks a specific frame's own length-prefixed sample bytes (via `FrameInfo.byteOffset`/`sizeBytes`) to find its first VCL NAL and read just enough of its slice header to learn which PPS it references — then a lookup resolves that PPS's own SPS. All new bitstream logic lives in `com.multiviewer.parser` (alongside `AvcCBoxDecoder`, its natural home); only the final task touches `com.multiviewer.ui` for state and display.

**Tech Stack:** Kotlin, pure JVM (no new dependencies). Reuses `ByteReader`/`BoxNode`/`findFirst` (existing parser infra) and `FrameInfo` (already carries `byteOffset`/`sizeBytes`).

Full technical background, the verified real byte fixtures, and their ffmpeg-`trace_headers`-confirmed field values are in `docs/superpowers/specs/2026-08-14-h264-sps-pps-per-frame-design.md`.

## Global Constraints

- H.264 only — no HEVC in this plan (separate follow-up).
- Core fields only, exactly as listed in the spec's Scope section — not an exhaustive SPS/PPS/VUI dump.
- High-profile SPS scaling-matrix content (`seq_scaling_matrix_present_flag == true`) is not supported: `parseH264Sps` returns a partial `H264Sps` with `scalingMatrixUnsupported = true` and no fields parsed after that point, rather than guessing.
- `num_slice_groups_minus1 > 0` in PPS (legacy FMO slice groups) is not supported: `parseH264Pps` returns a partial `H264Pps` with `deblockingFilterControlPresentFlag`/`transform8x8ModeFlag` both `null`, rather than guessing.
- Every parsing entry point (`parseH264Sps`, `parseH264Pps`, `extractAvcCRawParameterSets`, `resolveActivePicParameterSetId`) catches its own exceptions internally and returns `null` on any failure — callers never need their own try/catch.
- Test fixtures for the bit-level parsing (Tasks 1 and 2) are the REAL byte sequences documented in the spec, verified by hand against `ffmpeg -bsf:v trace_headers` output — not synthetic guesses. Task 3's structural (avcC-walking, NAL-length-prefix-walking) tests use synthetic byte fixtures, matching this codebase's existing `byteReaderOf`-style box-decoder test convention, since that logic doesn't need real-codec ground truth to construct correctly.

---

### Task 1: `BitReader` — Exp-Golomb / fixed-width bit reader

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/BitReader.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/BitReaderTest.kt`

**Interfaces:**
- Produces: `class BitReader(private val data: ByteArray, startByteOffset: Int = 0)` with `fun readBits(count: Int): Int`, `fun readBits32(): Long`, `fun readFlag(): Boolean`, `fun readUe(): Int`, `fun readSe(): Int`, `fun bitsRemaining(): Int`. Tasks 2 and 3 construct and call this directly.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/BitReaderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitReaderTest {
    // Real H.264 SPS bytes (25 bytes, NAL header 0x67 at index 0) -- from an x264-encoded file,
    // field values cross-verified by hand against `ffmpeg -bsf:v trace_headers` output (see the
    // design spec). profile_idc=244, level_idc=13 confirmed at byte offsets 1 and 3.
    private val realSps = byteArrayOf(
        0x67, 0xf4.toByte(), 0x00, 0x0d, 0x91.toByte(), 0x9b.toByte(), 0x28, 0x28,
        0x3f, 0x60, 0x22, 0x00, 0x00, 0x03, 0x00, 0x02,
        0x00, 0x00, 0x03, 0x00, 0x64, 0x1e, 0x28, 0x53.toByte(), 0x2c,
    )

    @Test
    fun `readBits reads MSB-first fixed-width values, matching real SPS profile_idc and level_idc`() {
        // Skip the 1-byte NAL header (startByteOffset=1) -- profile_idc is the next full byte.
        val reader = BitReader(realSps, startByteOffset = 1)
        assertEquals(244, reader.readBits(8)) // profile_idc
        reader.readBits(8) // constraint_set0..5_flag (6 bits) + reserved_zero_2bits (2 bits)
        assertEquals(13, reader.readBits(8)) // level_idc
    }

    // Real H.264 slice header prefix (first IDR slice, NAL header 0x65 at index 0) -- same source
    // file, verified against trace_headers: first_mb_in_slice=0, slice_type=7,
    // pic_parameter_set_id=0, decoded here as three sequential ue(v) reads on the same reader.
    private val realSliceHeaderPrefix = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00)

    @Test
    fun `readUe decodes three sequential Exp-Golomb values from a real slice header`() {
        val reader = BitReader(realSliceHeaderPrefix, startByteOffset = 1)
        assertEquals(0, reader.readUe()) // first_mb_in_slice
        assertEquals(7, reader.readUe()) // slice_type
        assertEquals(0, reader.readUe()) // pic_parameter_set_id
    }

    @Test
    fun `readSe maps Exp-Golomb codeNum to signed values per the H264 spec's table 9-3`() {
        // codeNum 0->1 bit "1", 1->3 bits "010", 2->"011", 3->"00100", 4->"00101"
        // ue(v) mapping: codeNum even -> -(codeNum/2), odd -> (codeNum+1)/2
        assertEquals(0, BitReader(byteArrayOf(0b10000000.toByte())).readSe())
        assertEquals(1, BitReader(byteArrayOf(0b01000000.toByte())).readSe())
        assertEquals(-1, BitReader(byteArrayOf(0b01100000.toByte())).readSe())
        assertEquals(2, BitReader(byteArrayOf(0b00100000.toByte())).readSe())
        assertEquals(-2, BitReader(byteArrayOf(0b00101000.toByte())).readSe())
    }

    @Test
    fun `readFlag reads a single bit as a boolean`() {
        val reader = BitReader(byteArrayOf(0b10100000.toByte()))
        assertTrue(reader.readFlag())
        assertFalse(reader.readFlag())
        assertTrue(reader.readFlag())
    }

    @Test
    fun `readBits32 assembles a full unsigned 32-bit value without sign overflow`() {
        // 0xFFFFFFFF as a plain Int would be -1 -- readBits32 must return it as a positive Long.
        val reader = BitReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(4294967295L, reader.readBits32())
    }

    @Test
    fun `bitsRemaining reflects consumed bits`() {
        val reader = BitReader(byteArrayOf(0x00, 0x00))
        assertEquals(16, reader.bitsRemaining())
        reader.readBits(5)
        assertEquals(11, reader.bitsRemaining())
    }

    @Test
    fun `readBits throws once past the end of the data instead of returning garbage`() {
        val reader = BitReader(byteArrayOf(0x00))
        reader.readBits(8)
        assertTrue(runCatching { reader.readBits(1) }.isFailure)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.BitReaderTest"`
Expected: FAIL — `BitReader` is unresolved (compile error), since the class doesn't exist yet.

- [ ] **Step 3: Create `BitReader.kt`**

```kotlin
package com.multiviewer.parser

// This codebase's first bit-level (as opposed to byte-aligned) bitstream reader -- needed because
// H.264 SPS/PPS/slice-header fields are packed as MSB-first fixed-width integers (u(n)) and
// Exp-Golomb codes (ue(v)/se(v)), unlike every existing ByteReader consumer which only reads
// whole bytes. Bit layout and Exp-Golomb decoding verified by hand against real H.264 bytes,
// cross-checked field-by-field with ffmpeg's own `-bsf:v trace_headers` output (see the design
// spec) before this was written.
class BitReader(private val data: ByteArray, startByteOffset: Int = 0) {
    private var bytePos = startByteOffset
    private var bitPos = 0 // 0..7 within the current byte; bit 0 is that byte's MSB.

    fun bitsRemaining(): Int = (data.size - bytePos) * 8 - bitPos

    // u(n): MSB-first fixed-width unsigned integer, count in 0..31 (fits an Int without sign
    // overflow -- 32-bit fields use readBits32 instead).
    fun readBits(count: Int): Int {
        require(count in 0..31) { "readBits count must be 0..31, got $count" }
        var result = 0
        repeat(count) {
            check(bytePos < data.size) { "BitReader ran past the end of its data" }
            val byte = data[bytePos].toInt() and 0xFF
            val bit = (byte shr (7 - bitPos)) and 1
            result = (result shl 1) or bit
            bitPos++
            if (bitPos == 8) {
                bitPos = 0
                bytePos++
            }
        }
        return result
    }

    // u(32): assembled from two 16-bit reads rather than one 32-bit accumulation, so a value with
    // its top bit set (e.g. 0xFFFFFFFF) comes back as the correct positive Long instead of
    // overflowing a signed Int.
    fun readBits32(): Long = (readBits(16).toLong() shl 16) or readBits(16).toLong()

    fun readFlag(): Boolean = readBits(1) == 1

    // ue(v), per H.264 spec §9.1: count leading zero bits (the "prefix"), then read that many more
    // bits as the "suffix" -- value = 2^leadingZeroBits - 1 + suffix.
    fun readUe(): Int {
        var leadingZeroBits = 0
        while (readBits(1) == 0) {
            leadingZeroBits++
            check(leadingZeroBits <= 31) { "Exp-Golomb prefix too long -- likely corrupt data" }
        }
        if (leadingZeroBits == 0) return 0
        val suffix = readBits(leadingZeroBits)
        return (1 shl leadingZeroBits) - 1 + suffix
    }

    // se(v), per H.264 spec §9.1.1 table 9-3: maps ue(v)'s unsigned codeNum to a signed value --
    // even codeNum -> -(codeNum/2), odd codeNum -> (codeNum+1)/2.
    fun readSe(): Int {
        val codeNum = readUe()
        return if (codeNum % 2 == 0) -(codeNum / 2) else (codeNum + 1) / 2
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.BitReaderTest"`
Expected: PASS (7/7 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BitReader.kt \
        app/src/test/kotlin/com/multiviewer/parser/BitReaderTest.kt
git commit -m "Add BitReader: Exp-Golomb and fixed-width bit-level reader"
```

---

### Task 2: H.264 SPS/PPS field parsers

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/H264ParameterSets.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetsTest.kt`

**Interfaces:**
- Produces: `data class H264Vui(...)`, `data class FrameCropping(val left: Int, val right: Int, val top: Int, val bottom: Int)`, `data class H264Sps(...)`, `data class H264Pps(...)` (exact fields below).
- Produces: `fun parseH264Sps(nalBytes: ByteArray): H264Sps?`, `fun parseH264Pps(nalBytes: ByteArray): H264Pps?` — both take raw NAL bytes INCLUDING the 1-byte NAL header (they skip it internally via `BitReader(nalBytes, startByteOffset = 1)`). Task 4's UI wiring calls these (indirectly, via Task 3's extraction result) and reads the resulting data classes' fields for display.
- Consumes: `BitReader` (Task 1).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetsTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H264ParameterSetsTest {
    // Real H.264 SPS (25 bytes) and PPS (6 bytes), from the same x264-encoded file, NAL headers
    // included -- every field asserted below was cross-verified by hand against
    // `ffmpeg -bsf:v trace_headers` output (see the design spec).
    private val realSps = byteArrayOf(
        0x67, 0xf4.toByte(), 0x00, 0x0d, 0x91.toByte(), 0x9b.toByte(), 0x28, 0x28,
        0x3f, 0x60, 0x22, 0x00, 0x00, 0x03, 0x00, 0x02,
        0x00, 0x00, 0x03, 0x00, 0x64, 0x1e, 0x28, 0x53.toByte(), 0x2c,
    )
    private val realPps = byteArrayOf(0x68, 0xeb.toByte(), 0xe3.toByte(), 0xc4.toByte(), 0x48, 0x44)

    @Test
    fun `parseH264Sps extracts every curated field correctly from a real SPS`() {
        val sps = parseH264Sps(realSps)
        assertNotNull(sps)
        assertEquals(0, sps.seqParameterSetId)
        assertEquals(244, sps.profileIdc)
        assertEquals(13, sps.levelIdc)
        assertEquals(3, sps.chromaFormatIdc)
        assertEquals(8, sps.bitDepthLuma)
        assertEquals(8, sps.bitDepthChroma)
        assertFalse(sps.scalingMatrixUnsupported)
        assertEquals(0, sps.picOrderCntType)
        assertEquals(4, sps.maxNumRefFrames)
        assertNull(sps.frameCropping) // frame_cropping_flag=0 in this file
        val vui = assertNotNull(sps.vui)
        assertEquals(1, vui.aspectRatioIdc)
        assertNull(vui.sarWidth) // aspect_ratio_idc=1 is not 255 (Extended_SAR), so no SAR fields
        assertNull(vui.videoFullRangeFlag) // video_signal_type_present_flag=0 in this file
    }

    @Test
    fun `parseH264Pps extracts every curated field correctly from a real PPS`() {
        val pps = parseH264Pps(realPps)
        assertNotNull(pps)
        assertEquals(0, pps.picParameterSetId)
        assertEquals(0, pps.seqParameterSetId)
        assertTrue(pps.entropyCodingModeFlag) // CABAC
        assertEquals(true, pps.deblockingFilterControlPresentFlag)
        assertEquals(true, pps.transform8x8ModeFlag)
    }

    @Test
    fun `parseH264Sps returns null for empty input`() {
        assertNull(parseH264Sps(ByteArray(0)))
    }

    @Test
    fun `parseH264Pps returns null for empty input`() {
        assertNull(parseH264Pps(ByteArray(0)))
    }

    @Test
    fun `parseH264Sps returns a partial result with scalingMatrixUnsupported when the scaling matrix flag is set`() {
        // Hand-constructed: same profile_idc=244 (triggers the chroma_format_idc/bit_depth block)
        // as the real SPS, with seq_scaling_matrix_present_flag forced to 1. Traced mechanically
        // (a standalone bit-reader simulation, not just by hand) that this flag lands at byte
        // index 5, bit index 2 (0 = MSB) of the real SPS -- byte[5] = 0x9b = 10011011, flipping
        // bit 2 from 0 to 1 gives 10111011 = 0xBB.
        val truncated = realSps.copyOf(12)
        truncated[5] = 0xBB.toByte()
        val sps = parseH264Sps(truncated)
        assertNotNull(sps)
        assertTrue(sps.scalingMatrixUnsupported)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.H264ParameterSetsTest"`
Expected: FAIL — `parseH264Sps`/`parseH264Pps` unresolved (compile error), since `H264ParameterSets.kt` doesn't exist yet.

- [ ] **Step 3: Create `H264ParameterSets.kt`**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.H264ParameterSetsTest"`
Expected: PASS (5/5 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/H264ParameterSets.kt \
        app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetsTest.kt
git commit -m "Add H.264 SPS/PPS field parsers"
```

---

### Task 3: Raw NAL extraction from `avcC`, and per-frame active-PPS resolution

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/H264ParameterSetExtraction.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/TestSupport.kt` (add a `fileOf` helper alongside the existing `byteReaderOf`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetExtractionTest.kt`

**Interfaces:**
- Produces: `data class AvcCRawParameterSets(val lengthSize: Int, val spsList: List<ByteArray>, val ppsList: List<ByteArray>)`
- Produces: `fun extractAvcCRawParameterSets(file: java.io.File, avcCNode: BoxNode): AvcCRawParameterSets?` — Task 4 calls this once per video tab.
- Produces: `fun resolveActivePicParameterSetId(file: java.io.File, byteOffset: Long, sizeBytes: Int, lengthSize: Int): Int?` — Task 4 calls this per selected frame.
- Produces: `fun resolveActiveParameterSets(spsList: List<H264Sps>, ppsList: List<H264Pps>, picParameterSetId: Int): Pair<H264Sps, H264Pps>?` — pure lookup, Task 4 calls this after the above.
- Consumes: `BitReader`, `H264Sps`, `H264Pps` (Tasks 1-2); `ByteReader`, `BoxNode` (existing).

- [ ] **Step 1: Add the `fileOf` test helper**

In `app/src/test/kotlin/com/multiviewer/parser/TestSupport.kt`, add alongside the existing `byteReaderOf`:

```kotlin
fun fileOf(bytes: ByteArray): File {
    val tmp = File.createTempFile("multiviewer-test", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return tmp
}
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetExtractionTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class H264ParameterSetExtractionTest {
    // Synthetic avcC payload (structure only -- SPS/PPS contents don't need to be real, valid
    // bitstream since extractAvcCRawParameterSets never parses them, only slices them out by
    // their declared lengths): configuration_version=1, avc_profile_indication=100,
    // profile_compatibility=0, avc_level_indication=30, length_size_minus_one=3 (-> length_size=4),
    // num_sps=1 (declared in the low 5 bits), one 3-byte SPS, num_pps=1, one 2-byte PPS.
    private fun avcCPayload(): ByteArray = byteArrayOf(
        0x01, 0x64, 0x00, 0x1e, 0xFF.toByte(), 0xE1.toByte(),
        0x00, 0x03, 0x67, 0xAA.toByte(), 0xBB.toByte(), // num_sps=1 implied by 0xE1 low 5 bits; one SPS, length=3
        0x01, 0x00, 0x02, 0x68, 0xCC.toByte(), // num_pps=1, one PPS, length=2
    )

    private fun avcCBoxNode(payload: ByteArray): Pair<BoxNode, java.io.File> {
        val headerSize = 8
        val header = ByteArray(headerSize) // irrelevant filler, box parsing reads by absolute offset
        val file = fileOf(header + payload)
        val node = BoxNode(type = "avcC", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong())
        return node to file
    }

    @Test
    fun `extractAvcCRawParameterSets reads length_size and the declared SPS and PPS byte ranges`() {
        val (node, file) = avcCBoxNode(avcCPayload())
        val result = extractAvcCRawParameterSets(file, node)
        assertNotNull(result)
        assertEquals(4, result.lengthSize) // length_size_minus_one=3 -> 3+1=4
        assertEquals(1, result.spsList.size)
        assertEquals(byteArrayOf(0x67, 0xAA.toByte(), 0xBB.toByte()).toList(), result.spsList[0].toList())
        assertEquals(1, result.ppsList.size)
        assertEquals(byteArrayOf(0x68, 0xCC.toByte()).toList(), result.ppsList[0].toList())
    }

    @Test
    fun `extractAvcCRawParameterSets returns null when the box is too short for its fixed header`() {
        val (node, file) = avcCBoxNode(byteArrayOf(0x01, 0x64, 0x00)) // only 3 bytes, needs 6
        assertNull(extractAvcCRawParameterSets(file, node))
    }

    // Length-prefixed samples (avcC-style, length_size=4): one 5-byte non-VCL NAL (type 6, SEI)
    // followed by a 6-byte VCL NAL (type 5, IDR slice) whose RBSP starts with a real slice-header
    // prefix (first_mb_in_slice=0, slice_type=7, pic_parameter_set_id=0 -- same bytes verified in
    // BitReaderTest/H264ParameterSetsTest).
    private fun sampleBytes(): ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x03, 0x06, 0xAA.toByte(), 0xBB.toByte(), // 3-byte SEI NAL (type 6)
        0x00, 0x00, 0x00, 0x04, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00, // 4-byte slice NAL (type 5)
    )

    @Test
    fun `resolveActivePicParameterSetId skips non-VCL NALs and decodes the first VCL slice header`() {
        val file = fileOf(sampleBytes())
        val picParameterSetId = resolveActivePicParameterSetId(file, byteOffset = 0, sizeBytes = sampleBytes().size, lengthSize = 4)
        assertEquals(0, picParameterSetId)
    }

    @Test
    fun `resolveActivePicParameterSetId returns null when no VCL NAL is present in range`() {
        val onlyNonVcl = byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x06, 0xAA.toByte(), 0xBB.toByte())
        val file = fileOf(onlyNonVcl)
        assertNull(resolveActivePicParameterSetId(file, byteOffset = 0, sizeBytes = onlyNonVcl.size, lengthSize = 4))
    }

    @Test
    fun `resolveActiveParameterSets looks up the matching PPS then its matching SPS`() {
        val sps0 = H264Sps(0, 66, 30, 1, 8, 8, 0, 1, null, null)
        val sps1 = H264Sps(1, 66, 30, 1, 8, 8, 0, 1, null, null)
        val pps0 = H264Pps(0, 1, true, true, true) // references sps1, not sps0
        val result = resolveActiveParameterSets(listOf(sps0, sps1), listOf(pps0), picParameterSetId = 0)
        assertNotNull(result)
        assertEquals(1, result.first.seqParameterSetId)
        assertEquals(0, result.second.picParameterSetId)
    }

    @Test
    fun `resolveActiveParameterSets returns null when the pic parameter set id has no match`() {
        assertNull(resolveActiveParameterSets(emptyList(), emptyList(), picParameterSetId = 0))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.H264ParameterSetExtractionTest"`
Expected: FAIL — compile error, `H264ParameterSetExtraction.kt` doesn't exist yet.

- [ ] **Step 4: Create `H264ParameterSetExtraction.kt`**

```kotlin
package com.multiviewer.parser

import java.io.File

data class AvcCRawParameterSets(val lengthSize: Int, val spsList: List<ByteArray>, val ppsList: List<ByteArray>)

// Mirrors AvcCBoxDecoder's own walk of this exact box structure, but COLLECTS the raw SPS/PPS
// bytes instead of only counting/validating them -- AvcCBoxDecoder deliberately doesn't retain
// them (see docs/superpowers/specs/2026-07-17-box-detail-parsing-design.md).
fun extractAvcCRawParameterSets(file: File, avcCNode: BoxNode): AvcCRawParameterSets? {
    return try {
        ByteReader.open(file).use { reader ->
            val payloadStart = avcCNode.offset + avcCNode.headerSize
            val payloadEnd = avcCNode.offset + avcCNode.size
            if (payloadEnd - payloadStart < 6) return@use null
            val lengthSize = (reader.readUInt8(payloadStart + 4) and 0x03) + 1
            val declaredSps = reader.readUInt8(payloadStart + 5) and 0x1F

            var pos = payloadStart + 6
            val spsList = mutableListOf<ByteArray>()
            while (spsList.size < declaredSps && pos + 2 <= payloadEnd) {
                val spsLength = reader.readUInt16(pos)
                if (pos + 2 + spsLength > payloadEnd) break
                spsList.add(reader.readBytes(pos + 2, spsLength))
                pos += 2 + spsLength
            }

            val ppsList = mutableListOf<ByteArray>()
            if (pos < payloadEnd) {
                val declaredPps = reader.readUInt8(pos)
                pos += 1
                while (ppsList.size < declaredPps && pos + 2 <= payloadEnd) {
                    val ppsLength = reader.readUInt16(pos)
                    if (pos + 2 + ppsLength > payloadEnd) break
                    ppsList.add(reader.readBytes(pos + 2, ppsLength))
                    pos += 2 + ppsLength
                }
            }
            AvcCRawParameterSets(lengthSize, spsList, ppsList)
        }
    } catch (e: Exception) {
        null
    }
}

// Reads a specific frame's own length-prefixed sample bytes (FrameInfo.byteOffset/sizeBytes) and
// walks its NALs looking for the first VCL one (nal_unit_type 1 = non-IDR slice, 5 = IDR slice),
// skipping non-VCL NALs (SEI, etc.) that can precede it in the same sample. Reads only a small
// fixed prefix of that NAL -- first_mb_in_slice/slice_type/pic_parameter_set_id are all Exp-Golomb
// and, for any realistic single-slice-per-frame encode, comfortably fit in a handful of bytes;
// 16 bytes is a generous safety margin, well short of the full NAL, and this never touches any
// actual CABAC/CAVLC-coded slice data.
fun resolveActivePicParameterSetId(file: File, byteOffset: Long, sizeBytes: Int, lengthSize: Int): Int? {
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
                val nalUnitType = reader.readUInt8(pos) and 0x1F
                if (nalUnitType == 1 || nalUnitType == 5) {
                    val prefixLength = minOf(nalLength, 16L).toInt()
                    val nalBytes = reader.readBytes(pos, prefixLength)
                    val bitReader = BitReader(nalBytes, startByteOffset = 1)
                    return@use try {
                        bitReader.readUe() // first_mb_in_slice
                        bitReader.readUe() // slice_type
                        bitReader.readUe() // pic_parameter_set_id
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

fun resolveActiveParameterSets(spsList: List<H264Sps>, ppsList: List<H264Pps>, picParameterSetId: Int): Pair<H264Sps, H264Pps>? {
    val pps = ppsList.find { it.picParameterSetId == picParameterSetId } ?: return null
    val sps = spsList.find { it.seqParameterSetId == pps.seqParameterSetId } ?: return null
    return sps to pps
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.H264ParameterSetExtractionTest"`
Expected: PASS (6/6 tests)

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/H264ParameterSetExtraction.kt \
        app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetExtractionTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/TestSupport.kt
git commit -m "Add avcC raw NAL extraction and per-frame active-PPS resolution"
```

---

### Task 4: Wire into TabState and the Detail Properties panel

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:177` (add three `TabState` fields after `videoCodecName`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` (add a `LaunchedEffect` populating the parsed SPS/PPS lists once per tab)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt:358-395` (`DetailPropertiesTabContent` — resolve and display the selected frame's active SPS/PPS)

**Interfaces:**
- Consumes: `com.multiviewer.parser.extractAvcCRawParameterSets`, `com.multiviewer.parser.parseH264Sps`, `com.multiviewer.parser.parseH264Pps` (Tasks 2-3) in `VideoInspectorUI.kt`.
- Consumes: `com.multiviewer.parser.resolveActivePicParameterSetId`, `com.multiviewer.parser.resolveActiveParameterSets`, `com.multiviewer.parser.H264Sps`, `com.multiviewer.parser.H264Pps` (Task 3) in `ImageInspectorUI.kt`.
- Consumes: `com.multiviewer.parser.findFirst` (existing).

No new automated tests in this task — UI wiring only, matching this codebase's established convention (verified via manual app testing, same as every other UI-integration task this session).

- [ ] **Step 1: Add `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, immediately after line 177 (`var videoCodecName: String? by mutableStateOf(null)`), insert:

```kotlin

    // H.264 SPS/PPS (see H264ParameterSets.kt / H264ParameterSetExtraction.kt) -- parsed once per
    // video tab from the avcC box, independent of any specific frame selection. Empty lists (not
    // null) mean "not H.264, no avcC box, or nothing parsed successfully" -- the Detail Properties
    // panel shows nothing extra either way, so no separate "not yet probed" state is needed here
    // (unlike videoCodecName, nothing else needs to distinguish those cases).
    var avcSpsList: List<com.multiviewer.parser.H264Sps> by mutableStateOf(emptyList())
    var avcPpsList: List<com.multiviewer.parser.H264Pps> by mutableStateOf(emptyList())
    var avcLengthSize: Int? by mutableStateOf(null)
```

- [ ] **Step 2: Populate the parsed lists once per tab in `VideoInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`, immediately after the existing:

```kotlin
    LaunchedEffect(tab.file) {
        tab.videoCodecName = withContext(Dispatchers.IO) { probeVideoCodecName(tab.file) }
    }
```

insert:

```kotlin

    // Parses the video track's avcC box once per tab -- independent of tab.videoCodecName's own
    // probe above (this just checks whether an avcC box exists in the tree at all, the same gate
    // parseH264Sps/parseH264Pps's own callers rely on implicitly via an empty list otherwise).
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

- [ ] **Step 3: Resolve and display the selected frame's active SPS/PPS in `ImageInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`'s `DetailPropertiesTabContent`, immediately after the existing:

```kotlin
        val warnings = if (selectedFrame == null && selectedNode == null && root != null) {
            remember(root) { collectWarnings(root) }
        } else {
            emptyList()
        }
```

insert:

```kotlin
        // Resolved OUTSIDE the LazyColumn below for the same reason `warnings` above is -- a
        // LazyListScope builder lambda isn't itself a @Composable context, so produceState (like
        // remember) has to run here instead.
        val resolvedH264Params = if (selectedFrame != null) {
            produceState<Pair<com.multiviewer.parser.H264Sps, com.multiviewer.parser.H264Pps>?>(
                null, selectedFrame, tab.avcSpsList, tab.avcPpsList, tab.avcLengthSize,
            ) {
                value = null
                val byteOffset = selectedFrame.byteOffset
                val lengthSize = tab.avcLengthSize
                if (byteOffset != null && lengthSize != null && tab.avcPpsList.isNotEmpty()) {
                    value = withContext(Dispatchers.IO) {
                        val picParameterSetId = com.multiviewer.parser.resolveActivePicParameterSetId(
                            tab.file, byteOffset, selectedFrame.sizeBytes, lengthSize,
                        ) ?: return@withContext null
                        com.multiviewer.parser.resolveActiveParameterSets(tab.avcSpsList, tab.avcPpsList, picParameterSetId)
                    }
                }
            }.value
        } else {
            null
        }
```

Then replace the existing `selectedFrame != null ->` item block:

```kotlin
                    selectedFrame != null -> {
                        item {
                            PropertyRow("Frame #", selectedFrame.index.toString())
                            PropertyRow("Type", selectedFrame.type.toString())
                            PropertyRow("Size", "${selectedFrame.sizeBytes} bytes")
                            PropertyRow("PTS", "${selectedFrame.ptsSeconds}s")
                            selectedFrame.byteOffset?.let { offset ->
                                PropertyRow("Byte Offset", "0x${offset.toString(16).uppercase()} (${offset})")
                            }
                            tab.gopFrames?.let { frames -> gopPositionOf(frames, selectedFrame.index) }?.let { gop ->
                                PropertyRow(
                                    "GOP Position",
                                    if (gop.distanceFromKeyframe == 0) "Keyframe (I-frame)"
                                    else "+${gop.distanceFromKeyframe} from keyframe #${gop.keyframeIndex}",
                                )
                            }
                        }
```

with:

```kotlin
                    selectedFrame != null -> {
                        item {
                            PropertyRow("Frame #", selectedFrame.index.toString())
                            PropertyRow("Type", selectedFrame.type.toString())
                            PropertyRow("Size", "${selectedFrame.sizeBytes} bytes")
                            PropertyRow("PTS", "${selectedFrame.ptsSeconds}s")
                            selectedFrame.byteOffset?.let { offset ->
                                PropertyRow("Byte Offset", "0x${offset.toString(16).uppercase()} (${offset})")
                            }
                            tab.gopFrames?.let { frames -> gopPositionOf(frames, selectedFrame.index) }?.let { gop ->
                                PropertyRow(
                                    "GOP Position",
                                    if (gop.distanceFromKeyframe == 0) "Keyframe (I-frame)"
                                    else "+${gop.distanceFromKeyframe} from keyframe #${gop.keyframeIndex}",
                                )
                            }
                            resolvedH264Params?.let { (sps, pps) ->
                                Spacer(Modifier.height(8.dp))
                                Text("H.264 Parameter Sets", style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue))
                                PropertyRow("SPS ID / PPS ID", "${sps.seqParameterSetId} / ${pps.picParameterSetId}")
                                PropertyRow("Profile / Level", "${sps.profileIdc} / ${sps.levelIdc}")
                                PropertyRow("Chroma Format", "4:${if (sps.chromaFormatIdc == 0) "0:0" else if (sps.chromaFormatIdc == 1) "2:0" else if (sps.chromaFormatIdc == 2) "2:2" else "4:4"}")
                                PropertyRow("Bit Depth (Luma/Chroma)", "${sps.bitDepthLuma} / ${sps.bitDepthChroma}")
                                if (sps.scalingMatrixUnsupported) {
                                    PropertyRow("Note", "Custom scaling matrix present -- further SPS fields not parsed")
                                } else {
                                    PropertyRow("POC Type", sps.picOrderCntType.toString())
                                    PropertyRow("Max Ref Frames", sps.maxNumRefFrames.toString())
                                }
                                PropertyRow(
                                    "Entropy Coding",
                                    if (pps.entropyCodingModeFlag) "CABAC" else "CAVLC",
                                )
                                pps.deblockingFilterControlPresentFlag?.let {
                                    PropertyRow("Deblocking Filter Control", if (it) "Present" else "Absent")
                                }
                                pps.transform8x8ModeFlag?.let {
                                    PropertyRow("8x8 Transform Mode", if (it) "Enabled" else "Disabled")
                                }
                                sps.vui?.let { vui ->
                                    vui.colourPrimaries?.let { PropertyRow("Colour Primaries", it.toString()) }
                                    vui.transferCharacteristics?.let { PropertyRow("Transfer Characteristics", it.toString()) }
                                    vui.matrixCoefficients?.let { PropertyRow("Matrix Coefficients", it.toString()) }
                                    vui.videoFullRangeFlag?.let { PropertyRow("Full Range", if (it) "Yes" else "No") }
                                }
                            }
                        }
```

Check `ImageInspectorUI.kt`'s existing imports already cover `produceState`, `Dispatchers`, `withContext`, `Spacer`, `Text` — if any is missing, add it (this file already uses `LaunchedEffect`/`remember`/coroutines patterns elsewhere per the Byte Offset/GOP Position work, so most are likely already present; `produceState` specifically may need `import androidx.compose.runtime.produceState` added if not already there).

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Manual verification**

Launch the app (`./gradlew :app:run`), open a real H.264 file (e.g. the same test file the design spec's ground truth came from, or any other H.264 video), select a frame (GOP bar, filmstrip, or arrow-key stepping), confirm:
- An "H.264 Parameter Sets" section appears below the existing Frame #/Type/Size/PTS/Byte Offset/GOP Position rows.
- The shown values (profile/level, chroma format, bit depth, entropy coding mode, etc.) match what `ffmpeg -bsf:v trace_headers` independently reports for the same file.
- Selecting frames across different GOPs still shows the same SPS/PPS ids in a typical single-parameter-set stream (most real files).
- Opening an HEVC or non-H.264 file shows no extra section (no error, just absent, same as before this change).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt \
        app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Show a selected frame's actual H.264 SPS/PPS fields in Detail Properties"
```
