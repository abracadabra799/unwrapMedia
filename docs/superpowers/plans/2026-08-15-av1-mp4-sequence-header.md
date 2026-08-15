# AV1 MP4 Sequence Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For AV1 streams in MP4 containers, extract and display the stream's Sequence Header fields (profile, level, bit depth, color config, max frame size — AV1's closest analog to H.264/HEVC's SPS) in Detail Properties, with hex-viewer click-to-jump on the section, and a proper field breakdown for the `av1C` box in Structure Analyser (replacing its current generic/unparsed rendering).

**Architecture:** A new OBU (Open Bitstream Unit) framing reader (`Av1Obu.kt`) provides `leb128()` and OBU-header parsing on top of the existing byte-level `ByteReader` — AV1 has no NAL/emulation-prevention scheme, so this is a new primitive, not a reuse of the H.264/HEVC NAL machinery. A new bit-level Sequence Header field parser (`Av1SequenceHeader.kt`) reuses the existing codec-agnostic `BitReader` directly. Extraction (`Av1ParameterSetExtraction.kt`) walks the video track's `av1C` box's `configOBUs` field to locate and slice out the Sequence Header OBU's own payload bytes and file offset, mirroring `extractAvcCRawParameterSets`/`extractHvcCRawParameterSets`. A new `Av1CBoxDecoder` gives Structure Analyser a real field breakdown for `av1C` (currently unregistered, falls back to a generic leaf node). Unlike H.264/HEVC's PPS-id-driven per-frame resolution, AV1's Sequence Header is stream-wide, not per-frame — so display is driven directly by tab-level state (`tab.av1SequenceHeader`), not a `produceState` keyed on the selected frame.

**Tech Stack:** Kotlin, pure JVM (no new dependencies). Reuses `BitReader`, `ByteReader`/`BoxNode`/`findFirst`/`RawNal` (existing parser infra).

Full technical background is in `docs/superpowers/specs/2026-08-15-av1-codec-support-design.md`. This plan covers Phase 1 of that spec's 3-phase split (MP4 Sequence Header only). Phase 2 (per-frame Frame Header parsing) and Phase 3 (WebM support) are separate, later plans.

Every byte fixture used in this plan's tests is real: captured from a `libsvtav1`-encoded 320×240 MP4 (`ffmpeg -f lavfi -i testsrc=size=320x240:rate=10:duration=1 -c:v libsvtav1 -pix_fmt yuv420p -g 5 out.mp4`), hand-decoded bit-by-bit against the AV1 spec's `sequence_header_obu()` syntax, and cross-checked two ways: (1) `max_frame_width`/`max_frame_height` matched the source encode's actual 320×240 dimensions exactly, and (2) `ffmpeg -v verbose -i out.mp4 -f null -` (which uses `libdav1d` to decode) independently reported `Main` profile, `320x240`, `yuv420p` for the same file.

## Global Constraints

- MP4/`av1C` only in this plan — no WebM support (that's Phase 3, a separate later plan). `av1C` extraction and parsing are otherwise container-format-agnostic and Phase 3 will reuse `parseAv1SequenceHeader` as-is.
- No per-frame Frame Header parsing in this plan (that's Phase 2). This plan only touches the stream-wide Sequence Header.
- Curated fields only, per the design spec: `seqProfile`, `stillPicture`, `seqLevelIdx0`, `seqTierIdx0`, `bitDepth`, `monochrome`, `chromaSubsamplingX`/`Y`, `colorPrimaries`/`transferCharacteristics`/`matrixCoefficients`, `maxFrameWidth`/`maxFrameHeight`, `use128x128Superblock`, `filmGrainParamsPresent` — not an exhaustive `sequence_header_obu()` dump.
- `parseAv1SequenceHeader` returns `null` (bails out) in two cases, both beyond what the design spec called out explicitly — added here because implementing their full syntax (`timing_info()`/`decoder_model_info()`, the further-reduced field layout) would add real parsing surface for uncommon streams without being needed for this plan's real test fixture, mirroring the H.264/HEVC bail-out precedent (H.264's scaling-matrix bail-out, HEVC's sub-layer PTL bail-out):
  - `reduced_still_picture_header == true` (an alternate, further-reduced field layout — explicitly called out in the design spec).
  - `timing_info_present_flag == true` (would require implementing `timing_info()` and `decoder_model_info()`, neither of which this plan implements).
- Every parsing/extraction entry point (`parseObuHeader`, `readLeb128`, `parseAv1SequenceHeader`, `extractAv1CRawSequenceHeader`) catches its own exceptions internally where applicable and returns `null`/a safe result on failure — callers never need their own try/catch.
- `RawNal.bytes` for the Sequence Header (as returned by `extractAv1CRawSequenceHeader`) is the `sequence_header_obu()` payload ONLY — NOT including the OBU header byte(s) or the `leb128`-encoded `obu_size` field. `parseAv1SequenceHeader` parses starting from bit 0 of exactly what's returned here. This differs from the H.264/HEVC convention where `RawNal.bytes` includes the NAL header (parsed by `parseH264Sps`/`parseHevcSps`, which skip it internally) — AV1's OBU framing is extraction's job, not the field-parser's job, since the OBU header's `obu_size` is needed by extraction to know how many bytes to slice in the first place.
- The Sequence Header section's CONTENT is not gated on which frame is selected (unlike the H.264/HEVC Parameter Sets sections, which resolve different PPS/SPS/VPS values per frame via `produceState`) — it reads `tab.av1SequenceHeader` directly, with the same value regardless of which frame is selected, since AV1's Sequence Header is stream-wide. It still lives inside the same `selectedFrame != null` item block as the Frame #/Type/Size/... rows and the H.264/HEVC sections — Detail Properties shows nothing until something is selected (a frame or a tree node), and this section follows that same established convention rather than becoming the first "always visible regardless of selection" content in the panel. (Resolved 2026-08-15: an earlier draft of this constraint overclaimed full frame-selection independence; the human confirmed matching the existing H.264/HEVC gating behavior is correct.)
- All new bitstream/extraction logic lives in `com.multiviewer.parser`, matching existing H.264/HEVC/avcC/hvcC file placement. Only the final task touches `com.multiviewer.ui`.

---

### Task 1: OBU framing primitives (`leb128` and OBU header parsing)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av1Obu.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/Av1ObuTest.kt`

**Interfaces:**
- Produces: `data class ObuHeader(val obuType: Int, val extensionFlag: Boolean, val hasSizeField: Boolean, val headerSize: Int)`
- Produces: `fun parseObuHeader(reader: ByteReader, pos: Long): ObuHeader` — reads the OBU header at absolute file position `pos` (1 byte, or 2 if `extensionFlag`). Task 3 calls this.
- Produces: `fun readLeb128(reader: ByteReader, pos: Long): Pair<Long, Long>` — returns `(value, nextPos)`. Task 3 calls this.
- Consumes: `ByteReader` (existing — `readUInt8(offset: Long): Int`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/Av1ObuTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Av1ObuTest {
    @Test
    fun `parseObuHeader decodes a Sequence Header OBU header with no extension`() {
        // Real byte from a libsvtav1 encode: obu_forbidden_bit=0, obu_type=1 (OBU_SEQUENCE_HEADER),
        // obu_extension_flag=0, obu_has_size_field=1, obu_reserved_1bit=0 -> 0 0001 0 1 0 = 0x0a.
        val reader = byteReaderOf(byteArrayOf(0x0a))
        val header = parseObuHeader(reader, 0)
        assertEquals(1, header.obuType)
        assertFalse(header.extensionFlag)
        assertTrue(header.hasSizeField)
        assertEquals(1, header.headerSize)
    }

    @Test
    fun `parseObuHeader accounts for the extra byte when extension_flag is set`() {
        // obu_type=6 (OBU_FRAME), obu_extension_flag=1, obu_has_size_field=1 -> 0 0110 1 1 0 = 0x36.
        val reader = byteReaderOf(byteArrayOf(0x36, 0xAA.toByte()))
        val header = parseObuHeader(reader, 0)
        assertEquals(6, header.obuType)
        assertTrue(header.extensionFlag)
        assertTrue(header.hasSizeField)
        assertEquals(2, header.headerSize)
    }

    @Test
    fun `readLeb128 decodes a real single-byte value`() {
        // Real byte from the same encode: leb128-encoded obu_size=11, single byte (MSB clear).
        val reader = byteReaderOf(byteArrayOf(0x0b))
        val (value, nextPos) = readLeb128(reader, 0)
        assertEquals(11L, value)
        assertEquals(1L, nextPos)
    }

    @Test
    fun `readLeb128 decodes a multi-byte value`() {
        // 300 = 0b1_0010_1100. leb128 groups 7 bits at a time, LSB group first, continuation bit
        // set on every byte but the last: group0 = 300 and 0x7F = 0x2C, continuation set -> 0xAC;
        // group1 = 300 shr 7 = 2, no continuation -> 0x02.
        val reader = byteReaderOf(byteArrayOf(0xAC.toByte(), 0x02))
        val (value, nextPos) = readLeb128(reader, 0)
        assertEquals(300L, value)
        assertEquals(2L, nextPos)
    }

    @Test
    fun `readLeb128 starts reading at the given position, not always at 0`() {
        val reader = byteReaderOf(byteArrayOf(0xFF.toByte(), 0x0b, 0x00))
        val (value, nextPos) = readLeb128(reader, 1)
        assertEquals(11L, value)
        assertEquals(2L, nextPos)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1ObuTest"`
Expected: FAIL — compile error, `Av1Obu.kt` doesn't exist yet (`parseObuHeader`/`readLeb128`/`ObuHeader` unresolved).

- [ ] **Step 3: Create `Av1Obu.kt`**

```kotlin
package com.multiviewer.parser

// AV1's own bitstream framing unit -- distinct from H.264/HEVC's NAL units, with no
// emulation-prevention scheme. An OBU header is 1 byte, or 2 if obu_extension_flag is set (the
// extra byte carries temporal_id/spatial_id, not needed by anything in this codebase yet, so it's
// skipped rather than decoded). headerSize lets a caller know how many bytes to skip to reach
// whatever follows the header (a leb128 obu_size field, if hasSizeField, then the OBU's payload).
data class ObuHeader(
    val obuType: Int,
    val extensionFlag: Boolean,
    val hasSizeField: Boolean,
    val headerSize: Int,
)

// AV1 spec 5.3.2 obu_header(). Reads the OBU header at absolute file position `pos`.
fun parseObuHeader(reader: ByteReader, pos: Long): ObuHeader {
    val byte0 = reader.readUInt8(pos)
    val obuType = (byte0 shr 3) and 0x0F
    val extensionFlag = (byte0 shr 2) and 0x01 == 1
    val hasSizeField = (byte0 shr 1) and 0x01 == 1
    val headerSize = if (extensionFlag) 2 else 1
    return ObuHeader(obuType, extensionFlag, hasSizeField, headerSize)
}

// AV1 spec 4.10.5 leb128() -- little-endian base-128: up to 8 bytes, 7 payload bits per byte
// (LSB group first), continuation flag is each byte's MSB. Returns the decoded value and the
// absolute file position immediately after the last leb128 byte.
fun readLeb128(reader: ByteReader, pos: Long): Pair<Long, Long> {
    var value = 0L
    var p = pos
    for (i in 0 until 8) {
        val b = reader.readUInt8(p)
        p += 1
        value = value or ((b.toLong() and 0x7F) shl (i * 7))
        if (b and 0x80 == 0) break
    }
    return value to p
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1ObuTest"`
Expected: PASS (5/5 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Av1Obu.kt \
        app/src/test/kotlin/com/multiviewer/parser/Av1ObuTest.kt
git commit -m "Add AV1 OBU header and leb128 framing primitives"
```

---

### Task 2: AV1 Sequence Header field parser

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av1SequenceHeader.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/Av1SequenceHeaderTest.kt`

**Interfaces:**
- Produces: `data class Av1SequenceHeader(val seqProfile: Int, val stillPicture: Boolean, val seqLevelIdx0: Int, val seqTierIdx0: Int, val bitDepth: Int, val monochrome: Boolean, val chromaSubsamplingX: Int, val chromaSubsamplingY: Int, val colorPrimaries: Int, val transferCharacteristics: Int, val matrixCoefficients: Int, val maxFrameWidth: Int, val maxFrameHeight: Int, val use128x128Superblock: Boolean, val filmGrainParamsPresent: Boolean)`
- Produces: `fun parseAv1SequenceHeader(payload: ByteArray): Av1SequenceHeader?` — takes the Sequence Header OBU's own payload bytes (NOT including OBU header/leb128 size — see Global Constraints). Task 5's UI wiring calls this (indirectly, via Task 3's extraction result) and reads the resulting data class's fields for display.
- Consumes: `BitReader` (existing).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/Av1SequenceHeaderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Av1SequenceHeaderTest {
    // Real Sequence Header OBU payload (11 bytes, OBU header/leb128-size prefix already stripped),
    // captured from a libsvtav1-encoded 320x240 MP4. Hand-decoded bit-by-bit against the AV1
    // spec's sequence_header_obu() syntax -- every field asserted below was traced this way, and
    // maxFrameWidth/maxFrameHeight were independently confirmed against the source encode's actual
    // 320x240 dimensions (also cross-checked via `ffmpeg -v verbose -i out.mp4 -f null -`, which
    // decodes via libdav1d and independently reported Main profile / 320x240 / yuv420p for the same
    // file -- see the design spec's Testing section and this plan's own intro).
    private val realSeqHeader = byteArrayOf(
        0x02, 0x00, 0x00, 0x05, 0x61, 0xe7.toByte(), 0xfd.toByte(), 0xe0.toByte(), 0x17, 0xc0.toByte(), 0x02,
    )

    @Test
    fun `parseAv1SequenceHeader extracts every curated field correctly from a real Sequence Header OBU`() {
        val seqHeader = parseAv1SequenceHeader(realSeqHeader)
        assertNotNull(seqHeader)
        assertEquals(0, seqHeader.seqProfile)
        assertFalse(seqHeader.stillPicture)
        assertEquals(0, seqHeader.seqLevelIdx0)
        assertEquals(0, seqHeader.seqTierIdx0)
        assertEquals(8, seqHeader.bitDepth)
        assertFalse(seqHeader.monochrome)
        assertEquals(1, seqHeader.chromaSubsamplingX)
        assertEquals(1, seqHeader.chromaSubsamplingY)
        assertEquals(2, seqHeader.colorPrimaries) // CP_UNSPECIFIED -- color_description_present_flag=0 in this encode
        assertEquals(2, seqHeader.transferCharacteristics) // TC_UNSPECIFIED
        assertEquals(2, seqHeader.matrixCoefficients) // MC_UNSPECIFIED
        assertEquals(320, seqHeader.maxFrameWidth)
        assertEquals(240, seqHeader.maxFrameHeight)
        assertFalse(seqHeader.use128x128Superblock)
        assertFalse(seqHeader.filmGrainParamsPresent)
    }

    @Test
    fun `parseAv1SequenceHeader returns null for empty input`() {
        assertNull(parseAv1SequenceHeader(ByteArray(0)))
    }

    @Test
    fun `parseAv1SequenceHeader returns null when reduced_still_picture_header is set`() {
        // seq_profile=000, still_picture=0, reduced_still_picture_header=1 -> byte0 = 00001000 = 0x08.
        assertNull(parseAv1SequenceHeader(byteArrayOf(0x08)))
    }

    @Test
    fun `parseAv1SequenceHeader returns null when timing_info_present_flag is set`() {
        // seq_profile=000, still_picture=0, reduced_still_picture_header=0, timing_info_present_flag=1
        // -> byte0 = 00000100 = 0x04.
        assertNull(parseAv1SequenceHeader(byteArrayOf(0x04)))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1SequenceHeaderTest"`
Expected: FAIL — compile error, `Av1SequenceHeader.kt` doesn't exist yet.

- [ ] **Step 3: Create `Av1SequenceHeader.kt`**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1SequenceHeaderTest"`
Expected: PASS (4/4 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Av1SequenceHeader.kt \
        app/src/test/kotlin/com/multiviewer/parser/Av1SequenceHeaderTest.kt
git commit -m "Add AV1 Sequence Header field parser"
```

---

### Task 3: Raw Sequence Header OBU extraction from `av1C`

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av1ParameterSetExtraction.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/Av1ParameterSetExtractionTest.kt`

**Interfaces:**
- Produces: `fun extractAv1CRawSequenceHeader(file: java.io.File, av1CNode: BoxNode): RawNal?` — Task 5 calls this once per video tab.
- Consumes: `ObuHeader`, `parseObuHeader`, `readLeb128` (Task 1); `RawNal` (existing, `data class RawNal(val bytes: ByteArray, val offset: Long)`); `ByteReader`, `BoxNode` (existing); `fileOf` test helper (existing, in `TestSupport.kt`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/Av1ParameterSetExtractionTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Av1ParameterSetExtractionTest {
    // Real av1C payload (17 bytes) captured from a libsvtav1-encoded 320x240 MP4: 4-byte
    // AV1CodecConfigurationRecord fixed header (marker=1, version=1, seq_profile=0,
    // seq_level_idx_0=0, seq_tier_0=0, high_bitdepth=0, twelve_bit=0, monochrome=0,
    // chroma_subsampling_x=1, chroma_subsampling_y=1, chroma_sample_position=0,
    // initial_presentation_delay_present=0), then configOBUs: a single Sequence Header OBU
    // (obu_type=1, no extension, has_size_field=1, leb128 obu_size=11) with an 11-byte payload.
    private fun av1CPayload(): ByteArray = byteArrayOf(
        0x81.toByte(), 0x00, 0x0c, 0x00, // fixed header
        0x0a, 0x0b, // OBU header (type=1, has_size_field=1) + leb128 size=11
        0x02, 0x00, 0x00, 0x05, 0x61, 0xe7.toByte(), 0xfd.toByte(), 0xe0.toByte(), 0x17, 0xc0.toByte(), 0x02,
    )

    private fun av1CBoxNode(payload: ByteArray): Pair<BoxNode, java.io.File> {
        val headerSize = 8
        val header = ByteArray(headerSize) // irrelevant filler, box parsing reads by absolute offset
        val file = fileOf(header + payload)
        val node = BoxNode(type = "av1C", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong())
        return node to file
    }

    @Test
    fun `extractAv1CRawSequenceHeader finds the Sequence Header OBU payload and its file offset`() {
        val (node, file) = av1CBoxNode(av1CPayload())
        val result = extractAv1CRawSequenceHeader(file, node)
        assertNotNull(result)
        val expectedPayload = byteArrayOf(
            0x02, 0x00, 0x00, 0x05, 0x61, 0xe7.toByte(), 0xfd.toByte(), 0xe0.toByte(), 0x17, 0xc0.toByte(), 0x02,
        )
        assertEquals(expectedPayload.toList(), result.bytes.toList())
        // headerSize=8 + payload index 6 (seq header payload starts right after the 2-byte OBU
        // header+leb128-size at payload index 4-5, itself right after the 4-byte fixed header) = 14.
        assertEquals(14L, result.offset)
    }

    @Test
    fun `extractAv1CRawSequenceHeader returns null when the box is too short for its fixed header`() {
        val (node, file) = av1CBoxNode(byteArrayOf(0x81.toByte(), 0x00, 0x0c)) // only 3 bytes, needs 4
        assertNull(extractAv1CRawSequenceHeader(file, node))
    }

    @Test
    fun `extractAv1CRawSequenceHeader returns null when no Sequence Header OBU is present`() {
        // configOBUs contains only a Temporal Delimiter OBU: obu_type=2, has_size_field=1,
        // obu_reserved_1bit=0 -> 0 0010 0 1 0 = 0x12, followed by leb128 obu_size=0.
        val payload = byteArrayOf(0x81.toByte(), 0x00, 0x0c, 0x00, 0x12, 0x00)
        val (node, file) = av1CBoxNode(payload)
        assertNull(extractAv1CRawSequenceHeader(file, node))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1ParameterSetExtractionTest"`
Expected: FAIL — compile error, `Av1ParameterSetExtraction.kt` doesn't exist yet.

- [ ] **Step 3: Create `Av1ParameterSetExtraction.kt`**

```kotlin
package com.multiviewer.parser

import java.io.File

private const val AV1C_FIXED_HEADER_SIZE = 4
private const val OBU_TYPE_SEQUENCE_HEADER = 1

// Reads av1C's 4-byte AV1CodecConfigurationRecord fixed header (see Av1CBoxDecoder, which decodes
// the same header for Structure Analyser display -- this function only uses it to find where
// configOBUs starts), then walks the OBUs in configOBUs (AV1 Codec ISO Media File Format Binding
// sec 2.2) looking for the first Sequence Header OBU -- typically the only OBU there, but not
// guaranteed. Returns just that OBU's own payload bytes and their absolute file offset as a
// RawNal (a generic bytes+offset pair despite its H.264-flavored name -- see RawNal.kt); the
// returned bytes do NOT include the OBU header or leb128 size prefix (see this plan's Global
// Constraints) -- parseAv1SequenceHeader parses straight from bit 0 of what's returned here.
fun extractAv1CRawSequenceHeader(file: File, av1CNode: BoxNode): RawNal? {
    return try {
        ByteReader.open(file).use { reader ->
            val payloadStart = av1CNode.offset + av1CNode.headerSize
            val payloadEnd = av1CNode.offset + av1CNode.size
            if (payloadEnd - payloadStart < AV1C_FIXED_HEADER_SIZE) return@use null
            var pos = payloadStart + AV1C_FIXED_HEADER_SIZE
            while (pos < payloadEnd) {
                val header = parseObuHeader(reader, pos)
                if (!header.hasSizeField) return@use null // can't determine this OBU's length
                val (obuSize, obuPayloadStart) = readLeb128(reader, pos + header.headerSize)
                if (obuPayloadStart + obuSize > payloadEnd) return@use null
                if (header.obuType == OBU_TYPE_SEQUENCE_HEADER) {
                    return@use RawNal(reader.readBytes(obuPayloadStart, obuSize.toInt()), obuPayloadStart)
                }
                pos = obuPayloadStart + obuSize
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1ParameterSetExtractionTest"`
Expected: PASS (3/3 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Av1ParameterSetExtraction.kt \
        app/src/test/kotlin/com/multiviewer/parser/Av1ParameterSetExtractionTest.kt
git commit -m "Add av1C Sequence Header OBU extraction"
```

---

### Task 4: `Av1CBoxDecoder` for Structure Analyser

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av1CBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt` (register `"av1C"`)
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt` (add `"av1C"` to the must-have-a-decoder list)
- Test: `app/src/test/kotlin/com/multiviewer/parser/Av1CBoxDecoderTest.kt`

**Interfaces:**
- Produces: `object Av1CBoxDecoder : BoxDecoder` — registered for box type `"av1C"`.
- Consumes: `BoxDecoder`, `BoxNode`, `BoxField`, `ByteReader`, `BoxRegistry` (existing).

This task is independent of Tasks 1-3 — it's a second, parallel reader of the same `av1C` box for a different purpose (Structure Analyser's generic field display vs. Task 3's Sequence-Header-specific extraction), exactly mirroring how `AvcCBoxDecoder`/`HvcCBoxDecoder` coexist with `extractAvcCRawParameterSets`/`extractHvcCRawParameterSets`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/Av1CBoxDecoderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Av1CBoxDecoderTest {
    // Same real av1C fixed header used in Av1ParameterSetExtractionTest -- see that file's comment
    // for the full field-by-field derivation. configOBUs content doesn't matter here; the decoder
    // only reads the first 4 bytes.
    private fun realAv1CPayload(): ByteArray = byteArrayOf(
        0x81.toByte(), 0x00, 0x0c, 0x00,
        0x0a, 0x0b, 0x02, 0x00, 0x00, 0x05, 0x61, 0xe7.toByte(), 0xfd.toByte(), 0xe0.toByte(), 0x17, 0xc0.toByte(), 0x02,
    )

    private fun decode(payload: ByteArray): BoxNode {
        val headerSize = 8
        val file = fileOf(ByteArray(headerSize) + payload)
        val reader = ByteReader.open(file)
        return Av1CBoxDecoder.decode(reader, "av1C", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong(), warnings = emptyList())
    }

    private fun fieldValue(node: BoxNode, name: String): String? = node.fields.find { it.name == name }?.value

    @Test
    fun `decode reads every fixed-header field from a real av1C payload`() {
        val node = decode(realAv1CPayload())
        assertEquals("1", fieldValue(node, "marker"))
        assertEquals("1", fieldValue(node, "version"))
        assertEquals("0", fieldValue(node, "seq_profile"))
        assertEquals("0", fieldValue(node, "seq_level_idx_0"))
        assertEquals("0", fieldValue(node, "seq_tier_0"))
        assertEquals("0", fieldValue(node, "high_bitdepth"))
        assertEquals("0", fieldValue(node, "twelve_bit"))
        assertEquals("0", fieldValue(node, "monochrome"))
        assertEquals("1", fieldValue(node, "chroma_subsampling_x"))
        assertEquals("1", fieldValue(node, "chroma_subsampling_y"))
        assertEquals("0", fieldValue(node, "chroma_sample_position"))
        assertEquals("0", fieldValue(node, "initial_presentation_delay_present"))
    }

    @Test
    fun `decode adds a warning and no fields when the box is too short for its fixed header`() {
        val node = decode(byteArrayOf(0x81.toByte(), 0x00, 0x0c)) // only 3 bytes, needs 4
        assertTrue(node.warnings.any { it.contains("too short") })
        assertTrue(node.fields.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1CBoxDecoderTest"`
Expected: FAIL — compile error, `Av1CBoxDecoder` doesn't exist yet.

- [ ] **Step 3: Create `Av1CBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object Av1CBoxDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 4

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
        if (payloadEnd - payloadStart < FIXED_HEADER_SIZE) {
            w.add("Box too short for av1C fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val byte0 = reader.readUInt8(payloadStart)
        val marker = (byte0 shr 7) and 0x01
        val version = byte0 and 0x7F
        val byte1 = reader.readUInt8(payloadStart + 1)
        val seqProfile = (byte1 shr 5) and 0x07
        val seqLevelIdx0 = byte1 and 0x1F
        val byte2 = reader.readUInt8(payloadStart + 2)
        val seqTier0 = (byte2 shr 7) and 0x01
        val highBitdepth = (byte2 shr 6) and 0x01
        val twelveBit = (byte2 shr 5) and 0x01
        val monochrome = (byte2 shr 4) and 0x01
        val chromaSubsamplingX = (byte2 shr 3) and 0x01
        val chromaSubsamplingY = (byte2 shr 2) and 0x01
        val chromaSamplePosition = byte2 and 0x03
        val byte3 = reader.readUInt8(payloadStart + 3)
        val initialPresentationDelayPresent = (byte3 shr 4) and 0x01

        val fields = listOf(
            BoxField("marker", marker.toString(), payloadStart, 1),
            BoxField("version", version.toString(), payloadStart, 1),
            BoxField("seq_profile", seqProfile.toString(), payloadStart + 1, 1),
            BoxField("seq_level_idx_0", seqLevelIdx0.toString(), payloadStart + 1, 1),
            BoxField("seq_tier_0", seqTier0.toString(), payloadStart + 2, 1),
            BoxField("high_bitdepth", highBitdepth.toString(), payloadStart + 2, 1),
            BoxField("twelve_bit", twelveBit.toString(), payloadStart + 2, 1),
            BoxField("monochrome", monochrome.toString(), payloadStart + 2, 1),
            BoxField("chroma_subsampling_x", chromaSubsamplingX.toString(), payloadStart + 2, 1),
            BoxField("chroma_subsampling_y", chromaSubsamplingY.toString(), payloadStart + 2, 1),
            BoxField("chroma_sample_position", chromaSamplePosition.toString(), payloadStart + 2, 1),
            BoxField("initial_presentation_delay_present", initialPresentationDelayPresent.toString(), payloadStart + 3, 1),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$seqProfile, level=$seqLevelIdx0, ${chromaSubsamplingX}:${chromaSubsamplingY} chroma",
        )
    }
}
```

- [ ] **Step 4: Register `av1C` in `Decoders.kt`**

In `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`, immediately after:

```kotlin
    BoxRegistry.register("hvcC", HvcCBoxDecoder)
```

insert:

```kotlin
    BoxRegistry.register("av1C", Av1CBoxDecoder)
```

- [ ] **Step 5: Add `"av1C"` to `DecodersRegistrationTest.kt`**

In `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt`, change:

```kotlin
        val typesThatMustHaveADecoder = listOf(
            "avc1", "hvc1", "av01", "mp4a", "avcC", "hvcC", "elst",
            "dref", "url ", "urn ", "colr", "pasp", "iinf", "infe", "mpvd", "sefd", "iloc",
        )
```

to:

```kotlin
        val typesThatMustHaveADecoder = listOf(
            "avc1", "hvc1", "av01", "mp4a", "avcC", "hvcC", "av1C", "elst",
            "dref", "url ", "urn ", "colr", "pasp", "iinf", "infe", "mpvd", "sefd", "iloc",
        )
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1CBoxDecoderTest" --tests "com.multiviewer.parser.DecodersRegistrationTest"`
Expected: PASS (3/3 tests)

- [ ] **Step 7: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Av1CBoxDecoder.kt \
        app/src/main/kotlin/com/multiviewer/parser/Decoders.kt \
        app/src/test/kotlin/com/multiviewer/parser/Av1CBoxDecoderTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt
git commit -m "Add Av1CBoxDecoder for Structure Analyser field display"
```

---

### Task 5: Wire into `TabState` and the Detail Properties panel

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (add two `TabState` fields after the existing `hevcLengthSize`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` (add a third `LaunchedEffect` populating the Sequence Header once per tab, parallel to the existing `avcC`/`hvcC` ones)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` (`DetailPropertiesTabContent` — display `tab.av1SequenceHeader`, with hex-jump on click)

**Interfaces:**
- Consumes: `com.multiviewer.parser.extractAv1CRawSequenceHeader` (Task 3), `com.multiviewer.parser.parseAv1SequenceHeader` (Task 2), `com.multiviewer.parser.Av1SequenceHeader` (Task 2), `com.multiviewer.parser.findFirst` (existing).

No new automated tests in this task — UI wiring only, matching this codebase's established convention (verified via manual app testing, same as the H.264 and HEVC features' own final tasks).

- [ ] **Step 1: Add `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, immediately after the existing:

```kotlin
    var hevcVpsList: List<com.multiviewer.parser.HevcVps> by mutableStateOf(emptyList())
    var hevcSpsList: List<com.multiviewer.parser.HevcSps> by mutableStateOf(emptyList())
    var hevcPpsList: List<com.multiviewer.parser.HevcPps> by mutableStateOf(emptyList())
    var hevcLengthSize: Int? by mutableStateOf(null)
```

insert:

```kotlin

    // AV1 Sequence Header (see Av1SequenceHeader.kt / Av1ParameterSetExtraction.kt) -- parsed once
    // per video tab from the av1C box's configOBUs field, independent of any specific frame
    // selection. AV1 has one stream-wide Sequence Header, not an id-addressable set like
    // avcC/hvcC's SPS/PPS/VPS, so this is a single nullable object + one offset range, not a
    // List/Map keyed by id.
    var av1SequenceHeader: com.multiviewer.parser.Av1SequenceHeader? by mutableStateOf(null)
    var av1SequenceHeaderOffset: LongRange? by mutableStateOf(null)
```

- [ ] **Step 2: Populate the parsed Sequence Header once per tab in `VideoInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`, immediately after the existing:

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

insert:

```kotlin

    // Parses the video track's av1C box once per tab -- mirrors the avcC/hvcC LaunchedEffects
    // above. Unlike those, there's no per-id offset map: AV1 has one stream-wide Sequence Header.
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val av1CNode = com.multiviewer.parser.findFirst(root) { it.type == "av1C" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractAv1CRawSequenceHeader(tab.file, av1CNode) ?: return@withContext
            val seqHeader = com.multiviewer.parser.parseAv1SequenceHeader(raw.bytes) ?: return@withContext
            tab.av1SequenceHeader = seqHeader
            tab.av1SequenceHeaderOffset = raw.offset until raw.offset + raw.bytes.size
        }
    }
```

- [ ] **Step 3: Display the Sequence Header in `ImageInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`'s `DetailPropertiesTabContent`, find the block that starts with:

```kotlin
                            resolvedHevcParams?.let { (vps, sps, pps) ->
```

and reads through to its own matching closing `}` (the "HEVC Parameter Sets" section, ending with the `sps.vui?.let { vui -> ... }` block). Immediately after that block's closing `}`, and still before the outer `item { ... }` block's own closing `}`, insert:

```kotlin
                            tab.av1SequenceHeader?.let { seqHeader ->
                                Spacer(Modifier.height(8.dp))
                                Text("AV1 Sequence Header", style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue))
                                PropertyRow(
                                    "Profile / Level / Tier",
                                    "${seqHeader.seqProfile} / ${seqHeader.seqLevelIdx0} / ${seqHeader.seqTierIdx0}",
                                    onClick = tab.av1SequenceHeaderOffset?.let { range -> { tab.parameterSetHighlightRange = range } },
                                )
                                PropertyRow("Bit Depth", seqHeader.bitDepth.toString())
                                PropertyRow("Monochrome", if (seqHeader.monochrome) "Yes" else "No")
                                PropertyRow("Chroma Subsampling", "${seqHeader.chromaSubsamplingX}:${seqHeader.chromaSubsamplingY}")
                                PropertyRow("Color Primaries", seqHeader.colorPrimaries.toString())
                                PropertyRow("Transfer Characteristics", seqHeader.transferCharacteristics.toString())
                                PropertyRow("Matrix Coefficients", seqHeader.matrixCoefficients.toString())
                                PropertyRow("Max Frame Size", "${seqHeader.maxFrameWidth} x ${seqHeader.maxFrameHeight}")
                                PropertyRow("128x128 Superblock", if (seqHeader.use128x128Superblock) "Yes" else "No")
                                PropertyRow("Film Grain Present", if (seqHeader.filmGrainParamsPresent) "Yes" else "No")
                            }
```

Note this section is deliberately NOT wrapped in a `resolvedXxxParams?.let` sourced from a `produceState` the way the H.264/HEVC sections are — it reads `tab.av1SequenceHeader` directly (see this plan's Global Constraints: the Sequence Header is stream-wide, not resolved per selected frame).

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Manual verification**

Generate a small real AV1 MP4 test file (matches this plan's own test fixtures — reuse it for manual QA too):

```bash
ffmpeg -y -f lavfi -i testsrc=size=320x240:rate=10:duration=1 -c:v libsvtav1 -pix_fmt yuv420p -g 5 /tmp/av1_manual_test.mp4
```

Launch the app (`./gradlew :app:run`), open `/tmp/av1_manual_test.mp4`, select any frame (GOP bar, filmstrip, or arrow-key stepping), and confirm:
- An "AV1 Sequence Header" section appears below the existing Frame #/Type/Size/PTS/Byte Offset/GOP Position rows, showing Profile=0, Level=0, Tier=0, Bit Depth=8, Chroma Subsampling=1:1, Max Frame Size=320 x 240.
- The section's values are identical regardless of which frame is selected (it's stream-wide, not per-frame).
- Clicking the "Profile / Level / Tier" row scrolls/highlights the hex viewer to the Sequence Header OBU's actual bytes inside the `av1C` box.
- In Structure Analyser, the `av1C` box under `moov/trak/mdia/minf/stbl/stsd/av01` now shows a field breakdown (marker, version, seq_profile, etc.) instead of rendering as a generic/empty leaf node.
- Opening an H.264 or HEVC file still shows only their respective sections (no regression), and opening a file with none of the three shows none of the sections.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt \
        app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Show the AV1 Sequence Header in Detail Properties with hex-jump"
```
