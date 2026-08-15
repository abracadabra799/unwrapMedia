# AV1 MP4 Frame Header Implementation Plan (Phase 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For AV1 streams in MP4 containers, extract and display the currently-selected frame's own Frame Header fields (frame type, show/showable flags, frame size, base quantizer index, tile layout, reference-refresh flags, order hint) in Detail Properties, alongside the stream-wide Sequence Header shipped in Phase 1.

**Architecture:** A new `Av1FrameHeader.kt` adds a stateless bit-level parser, `parseAv1FrameHeader(payload: ByteArray, seqHeader: Av1SequenceHeader): Av1FrameHeader?`, that reads AV1's `uncompressed_header()` syntax (spec §5.9.2) from an `OBU_FRAME_HEADER`/`OBU_FRAME` OBU's own payload, stopping right after `quantization_params()`'s `base_q_idx` — everything this plan curates. A new `Av1FrameHeaderAnalyzer.kt` walks every already-probed frame (`List<FrameInfo>`, from the existing `FrameTypeAnalyzer.probeFrameTypes`) once per tab, locates each frame's header OBU via Phase 1's `Av1Obu.kt` primitives (`parseObuHeader`/`readLeb128`), and hands a bounded prefix of it to the parser — producing a `Map<Long, Av1FrameHeader>` keyed by `FrameInfo.byteOffset`, mirroring the map-like shape of `av1SequenceHeader`'s stream-wide singleton but per-frame. `Av1SequenceHeader` itself (Phase 1) gains 7 additional internal fields the Frame Header parser needs to stay bit-aligned with this stream's Sequence Header — not new UI-curated fields, just previously-discarded bitstream values the existing parser already walked past.

**Tech Stack:** Kotlin, pure JVM (no new dependencies). Reuses `BitReader` (bit-level parsing), `ByteReader` (file I/O), Phase 1's `Av1Obu.kt` (`parseObuHeader`/`readLeb128`) and `Av1SequenceHeader.kt`.

This is Phase 2 of the 3-phase split described in `docs/superpowers/specs/2026-08-15-av1-codec-support-design.md` (Phase 1: MP4 Sequence Header, shipped; Phase 2: this plan, per-frame Frame Header, MP4 only; Phase 3: WebM support for both, separate later plan). **This plan deviates from that design spec's Frame Header architecture in one significant way** — see Global Constraints below.

Every byte fixture used in this plan's tests is real: captured from a `libsvtav1`-encoded 64×64, 5-frame, low-delay (IPPP) MP4:

```bash
ffmpeg -y -f lavfi -i "testsrc=size=64x64:rate=10:duration=0.5" -c:v libsvtav1 -pix_fmt yuv420p -g 5 \
  -svtav1-params pred-struct=1:enable-overlays=0 av1_frames_test.mp4
```

Every field asserted in this plan's tests was hand-decoded bit-by-bit against the AV1 spec's `uncompressed_header()`/`tile_info()`/`quantization_params()` syntax, cross-checked with an independent from-scratch Python implementation of the same syntax (not derived from the Kotlin under test), verified against `ffprobe`'s independent `pict_type`/`key_frame` output for frame type and decode order (`ffprobe -show_frames -show_entries frame=pict_type,key_frame,pkt_size,pkt_pts`), and against `dav1d`'s successful independent decode of the raw extracted bitstream (`ffmpeg -i av1_frames_test.mp4 -c copy -f obu raw.obu && dav1d -i raw.obu -o /dev/null --muxer null -q`, exit code 0, confirming the captured bytes are spec-valid, not merely self-consistently parsed).

## Global Constraints

- MP4/`av1C`-based samples only in this plan — no WebM support (that's Phase 3, a separate later plan).
- Curated field set only, per the design spec: `frameType`, `showFrame`, `showableFrame`, `frameWidth`, `frameHeight`, `baseQIdx`, `tileCols`, `tileRows`, `refreshFrameFlags`, `orderHint` — not an exhaustive `uncompressed_header()` dump. Parsing stops immediately after `quantization_params()`'s `base_q_idx`; nothing later in the syntax (segmentation, loop filter, CDEF, restoration, TX mode, global motion, film grain) is read.
- **Deviation from the design spec's `Av1RefFrameState` architecture:** the design spec (`docs/superpowers/specs/2026-08-15-av1-codec-support-design.md`, Component 6) specifies a mutable, stateful class tracking `RefOrderHint[]`/`RefValid[]`/`RefFrameType[]` across frames in strict decode order, mirroring the AV1 spec's own §7.20 reference-frame update process. This plan implements Frame Header parsing as a **stateless, pure function** instead (`parseAv1FrameHeader(payload, seqHeader)`, no cross-call state). This was verified during planning by tracing every bitstream read in `uncompressed_header()` between bit 0 and `base_q_idx` against the AV1 spec: none of this plan's curated fields' bit-widths depend on cross-frame reference state, given the bail-out list below. The one path that genuinely requires real cross-frame state — `frame_size_with_refs()`, reached when `frame_size_override_flag` is set on an inter frame, which copies a stored reference slot's width/height — is itself bailed out on (see below), so it never needs implementing. This was cross-validated by running the equivalent stateless logic against all 5 real captured frames (1 KEY + 4 INTER) from the test fixture stream and confirming sane, spec-consistent output matching `ffprobe`'s independent frame-type/order ground truth. Net effect: frames can be parsed independently, in any order — no strict decode-order driving requirement, unlike the design spec's original architecture.
- Bail-out list (mirrors the H.264/HEVC/Phase 1 "stop rather than guess" precedent) — `parseAv1FrameHeader` returns `null` when:
  - `show_existing_frame == true` — this frame repeats a previously-decoded frame's contents (AV1 spec §7.20) rather than carrying its own header fields.
  - `seqHeader.frameIdNumbersPresentFlag == true` — adds `current_frame_id`/`delta_frame_id` bitstream fields this plan doesn't track (uncommon; mainly used for scalable/low-latency signaling).
  - `frame_size_override_flag == true`, whether explicitly signaled or implied by `frame_type == SWITCH_FRAME` — the inter-frame variant (`frame_size_with_refs()`) is the one path in this syntax range that requires real cross-frame reference-dimension state (see the architecture deviation above); the intra-frame variant is a rare explicit-resolution-change case not worth a separate code path.
  - `tile_info()`'s `uniform_tile_spacing_flag == false` — explicit per-tile-size signaling via `ns()` variable-length-coded values; uncommon, most encoders emit uniform tile spacing.
- Every new/modified parsing entry point (`parseAv1FrameHeader`, `analyzeAv1FrameHeaders`, the modified `parseAv1SequenceHeader`) catches its own exceptions internally and returns `null`/an empty result on failure — callers never need their own try/catch (matches Phase 1's Global Constraint).
- The 7 new `Av1SequenceHeader` fields added by Task 1 are internal parsing state, not new UI-curated fields — the "AV1 Sequence Header" Detail Properties section (Phase 1, `ImageInspectorUI.kt`) is unchanged and still shows only its original 15 fields.
- The "AV1 Frame Header" section is **not** clickable — no hex-jump `onClick` — it's already within the currently-selected frame's own bytes, reachable via existing frame-row navigation (GOP bar / filmstrip / arrow-key stepping). This matches the design spec's Scope and mirrors how H.264/HEVC's own per-frame fields (Frame #/Type/Size/PTS/...) aren't independently clickable either.
- `tab.gopFrames` (the `List<FrameInfo>` frame-analysis result) is **not** populated automatically — it stays `null` until the user clicks the app's "Analyze Frames" button (`AppState.analyzeFrames`, `AppState.kt:649`). This differs from `tab.root` (available as soon as the box tree parses) and from the design spec's implicit assumption that frame data is ready alongside the Sequence Header. Task 4's `LaunchedEffect` populating `tab.av1FrameHeaders` must therefore be keyed on `tab.gopFrames` (in addition to `tab.av1SequenceHeader`), re-running (as a no-op until both are non-null) as either becomes available, in either order.
- All new bitstream/extraction logic lives in `com.multiviewer.parser`, matching Phase 1 placement. Only the final task touches `com.multiviewer.ui`.
- The design spec lists "superres-specific fields" as out of scope/deferred. This plan's `readFrameSize` (Task 2) still fully implements `superres_params()`'s bit consumption (`use_superres`/`coded_denom`) — that's necessary to keep bit alignment correct and to compute the right value for `frameWidth`, which **is** a curated field; getting this wrong for any stream with `enable_superres == true` would silently corrupt `frameWidth` and misalign every subsequent read (`tileCols`/`tileRows`/`baseQIdx`). No new field is added to expose superres details themselves (no `superresDenom`/`upscaledWidth` in `Av1FrameHeader`) — this is a correctness requirement for existing curated output, not new curated surface.

---

### Task 1: Extend `Av1SequenceHeader` with fields needed for Frame Header parsing

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Av1SequenceHeader.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/Av1SequenceHeaderTest.kt`

**Interfaces:**
- Modifies: `Av1SequenceHeader` data class — adds 7 new fields (below); all 15 existing fields unchanged.
- Modifies: `parseAv1SequenceHeader(payload: ByteArray): Av1SequenceHeader?` — same signature, same existing bail-out behavior (`reduced_still_picture_header`/`timing_info_present_flag` still return `null`); captures bitstream values the function already reads but previously discarded, and fixes a spec-compliance gap in `seq_force_integer_mv` (see Step 3).
- Produces (consumed by Task 2's `parseAv1FrameHeader`): `frameIdNumbersPresentFlag: Boolean`, `enableOrderHint: Boolean`, `orderHintBitsMinus1: Int`, `seqForceScreenContentTools: Int`, `seqForceIntegerMv: Int`, `enableSuperres: Boolean`, `enableRefFrameMvs: Boolean`.
- Produces (new top-level constant, was `private`): `SELECT_SCREEN_CONTENT_TOOLS` (now non-private so Task 2's file can compare `seqForceScreenContentTools` against it); adds a new non-private `SELECT_INTEGER_MV` constant for the same reason.

- [ ] **Step 1: Update the failing test**

Replace the full contents of `app/src/test/kotlin/com/multiviewer/parser/Av1SequenceHeaderTest.kt` with:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Av1SequenceHeaderTest {
    // Real Sequence Header OBU payload (11 bytes, OBU header/leb128-size prefix already stripped),
    // captured from a libsvtav1-encoded 320x240 MP4. Hand-decoded bit-by-bit against the AV1
    // spec's sequence_header_obu() syntax -- every field asserted below was traced this way, and
    // maxFrameWidth/maxFrameHeight were independently confirmed against the source encode's actual
    // 320x240 dimensions (also cross-checked via `ffmpeg -v verbose -i out.mp4 -f null -`, which
    // decodes via libdav1d and independently reported Main profile / 320x240 / yuv420p for the same
    // file -- see the design spec's Testing section and the Phase 1 plan's own intro).
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
        // Fields added for Frame Header parsing (Phase 2) -- hand-decoded from the same real bytes
        // as everything else above, cross-checked with an independent Python implementation of the
        // same sequence_header_obu() syntax.
        assertFalse(seqHeader.frameIdNumbersPresentFlag)
        assertTrue(seqHeader.enableOrderHint)
        assertEquals(6, seqHeader.orderHintBitsMinus1)
        assertEquals(SELECT_SCREEN_CONTENT_TOOLS, seqHeader.seqForceScreenContentTools)
        assertEquals(SELECT_INTEGER_MV, seqHeader.seqForceIntegerMv)
        assertFalse(seqHeader.enableSuperres)
        assertTrue(seqHeader.enableRefFrameMvs)
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

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1SequenceHeaderTest"`
Expected: FAIL — compile error, `Av1SequenceHeader` has no `frameIdNumbersPresentFlag`/`enableOrderHint`/`orderHintBitsMinus1`/`seqForceScreenContentTools`/`seqForceIntegerMv`/`enableSuperres`/`enableRefFrameMvs` properties yet, and `SELECT_INTEGER_MV` is unresolved.

- [ ] **Step 3: Modify `Av1SequenceHeader.kt`**

Replace the full contents of `app/src/main/kotlin/com/multiviewer/parser/Av1SequenceHeader.kt` with:

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
    // Fields below are internal parsing state consumed by Av1FrameHeader.kt's parseAv1FrameHeader
    // to stay bit-aligned with this stream's Sequence Header while parsing a Frame Header -- they
    // are NOT shown in Detail Properties, which still displays only the 15 fields above.
    val frameIdNumbersPresentFlag: Boolean,
    val enableOrderHint: Boolean,
    val orderHintBitsMinus1: Int,
    val seqForceScreenContentTools: Int,
    val seqForceIntegerMv: Int,
    val enableSuperres: Boolean,
    val enableRefFrameMvs: Boolean,
)

private const val CP_BT_709 = 1
private const val CP_UNSPECIFIED = 2
private const val TC_UNSPECIFIED = 2
private const val TC_SRGB = 13
private const val MC_IDENTITY = 0
private const val MC_UNSPECIFIED = 2
const val SELECT_SCREEN_CONTENT_TOOLS = 2
const val SELECT_INTEGER_MV = 2

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

        val frameIdNumbersPresentFlag = reader.readFlag()
        if (frameIdNumbersPresentFlag) {
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
        var enableRefFrameMvs = false
        if (enableOrderHint) {
            reader.readFlag() // enable_jnt_comp
            enableRefFrameMvs = reader.readFlag()
        }
        val seqForceScreenContentTools = if (reader.readFlag()) { // seq_choose_screen_content_tools
            SELECT_SCREEN_CONTENT_TOOLS
        } else {
            reader.readBits(1)
        }
        // AV1 spec 5.5.1: when seq_force_screen_content_tools == 0, seq_force_integer_mv is set to
        // SELECT_INTEGER_MV directly, without reading a seq_choose_integer_mv flag at all -- there's
        // nothing to choose between, since screen content tools (and therefore forced integer MV)
        // are off. The previous version of this function read the seq_choose_integer_mv/
        // seq_force_integer_mv bits but never captured or defaulted the resolved value.
        val seqForceIntegerMv = if (seqForceScreenContentTools > 0) {
            if (reader.readFlag()) { // seq_choose_integer_mv
                SELECT_INTEGER_MV
            } else {
                reader.readBits(1)
            }
        } else {
            SELECT_INTEGER_MV
        }
        val orderHintBitsMinus1 = if (enableOrderHint) {
            reader.readBits(3)
        } else {
            0
        }

        val enableSuperres = reader.readFlag()
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
            frameIdNumbersPresentFlag = frameIdNumbersPresentFlag,
            enableOrderHint = enableOrderHint,
            orderHintBitsMinus1 = orderHintBitsMinus1,
            seqForceScreenContentTools = seqForceScreenContentTools,
            seqForceIntegerMv = seqForceIntegerMv,
            enableSuperres = enableSuperres,
            enableRefFrameMvs = enableRefFrameMvs,
        )
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1SequenceHeaderTest"`
Expected: PASS (4/4 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions (this changes a data class used by Phase 1's `Av1CBoxDecoder`/UI code only by construction call sites within this same file — no other file constructs `Av1SequenceHeader` directly, so no other call sites need updating)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Av1SequenceHeader.kt \
        app/src/test/kotlin/com/multiviewer/parser/Av1SequenceHeaderTest.kt
git commit -m "Extend Av1SequenceHeader with fields needed for Frame Header parsing"
```

---

### Task 2: `Av1FrameHeader.kt` — stateless Frame Header field parser

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av1FrameHeader.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/Av1FrameHeaderTest.kt`

**Interfaces:**
- Consumes: `Av1SequenceHeader` (Task 1, all 7 new fields plus `maxFrameWidth`/`maxFrameHeight`/`use128x128Superblock`), `BitReader` (existing), `SELECT_SCREEN_CONTENT_TOOLS`/`SELECT_INTEGER_MV` (Task 1).
- Produces: `enum class Av1FrameType { KEY, INTER, INTRA_ONLY, SWITCH }`; `data class Av1FrameHeader(val frameType: Av1FrameType, val showFrame: Boolean, val showableFrame: Boolean, val frameWidth: Int, val frameHeight: Int, val baseQIdx: Int, val tileCols: Int, val tileRows: Int, val refreshFrameFlags: Int, val orderHint: Int)`; `fun parseAv1FrameHeader(payload: ByteArray, seqHeader: Av1SequenceHeader): Av1FrameHeader?` — Task 3 calls this once per frame.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/Av1FrameHeaderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Av1FrameHeaderTest {
    // Real Sequence Header OBU payload (11 bytes) from the same libsvtav1-encoded 64x64, 5-frame,
    // low-delay (IPPP) MP4 used for every frame fixture below (`ffmpeg -f lavfi -i
    // testsrc=size=64x64:rate=10:duration=0.5 -c:v libsvtav1 -pix_fmt yuv420p -g 5 -svtav1-params
    // pred-struct=1:enable-overlays=0 out.mp4`) -- hand-decoded bit-by-bit against the AV1 spec's
    // sequence_header_obu() syntax, independently cross-checked via a from-scratch Python
    // implementation of the same syntax, and against dav1d's successful independent decode of the
    // raw extracted bitstream (see this plan's intro).
    private val realSeqHeaderBytes = byteArrayOf(
        0x02, 0x00, 0x00, 0x04, 0xd5.toByte(), 0x7f, 0xfc.toByte(), 0x6a, 0xf9.toByte(), 0x80.toByte(), 0x40,
    )
    private val seqHeader = parseAv1SequenceHeader(realSeqHeaderBytes)!!

    @Test
    fun `parseAv1FrameHeader extracts every curated field from a real KEY frame's OBU_FRAME payload`() {
        // Real bytes: Frame 0 of the 5-frame capture (ffprobe: key_frame=1, pict_type=I), truncated
        // to the first 5 bytes -- the bit parser consumes exactly 27 bits (< 4 bytes) to reach
        // base_q_idx for this frame; truncated and full 870-byte payload parse identically (verified
        // during planning).
        val payload = byteArrayOf(0x14, 0x00, 0xa5.toByte(), 0xa0.toByte(), 0x40)
        val header = parseAv1FrameHeader(payload, seqHeader)
        assertNotNull(header)
        assertEquals(Av1FrameType.KEY, header.frameType)
        assertEquals(true, header.showFrame)
        assertEquals(false, header.showableFrame)
        assertEquals(64, header.frameWidth)
        assertEquals(64, header.frameHeight)
        assertEquals(45, header.baseQIdx)
        assertEquals(1, header.tileCols)
        assertEquals(1, header.tileRows)
        assertEquals(255, header.refreshFrameFlags)
        assertEquals(0, header.orderHint)
    }

    @Test
    fun `parseAv1FrameHeader extracts every curated field from a real INTER frame's OBU_FRAME payload`() {
        // Real bytes: Frame 1 of the same capture (ffprobe: key_frame=0, pict_type=P), truncated to
        // the first 9 bytes (63 bits consumed to reach base_q_idx).
        val payload = byteArrayOf(0x30, 0x02, 0x00, 0x00, 0x00, 0xdb.toByte(), 0x3b, 0x18, 0x00)
        val header = parseAv1FrameHeader(payload, seqHeader)
        assertNotNull(header)
        assertEquals(Av1FrameType.INTER, header.frameType)
        assertEquals(true, header.showFrame)
        assertEquals(true, header.showableFrame)
        assertEquals(64, header.frameWidth)
        assertEquals(64, header.frameHeight)
        assertEquals(140, header.baseQIdx)
        assertEquals(1, header.tileCols)
        assertEquals(1, header.tileRows)
        assertEquals(0, header.refreshFrameFlags)
        assertEquals(1, header.orderHint)
    }

    @Test
    fun `parseAv1FrameHeader extracts a second real INTER frame with different quantization and refresh flags`() {
        // Real bytes: Frame 2 of the same capture (ffprobe: key_frame=0, pict_type=P), truncated to
        // the first 9 bytes (63 bits consumed) -- distinct base_q_idx/refresh_frame_flags/order_hint
        // from Frame 1 above, confirming these aren't accidentally hardcoded.
        val payload = byteArrayOf(0x30, 0x04, 0x04, 0x00, 0x00, 0xdb.toByte(), 0x3b, 0x06, 0x00)
        val header = parseAv1FrameHeader(payload, seqHeader)
        assertNotNull(header)
        assertEquals(Av1FrameType.INTER, header.frameType)
        assertEquals(true, header.showFrame)
        assertEquals(true, header.showableFrame)
        assertEquals(64, header.frameWidth)
        assertEquals(64, header.frameHeight)
        assertEquals(131, header.baseQIdx)
        assertEquals(1, header.tileCols)
        assertEquals(1, header.tileRows)
        assertEquals(16, header.refreshFrameFlags)
        assertEquals(2, header.orderHint)
    }

    @Test
    fun `parseAv1FrameHeader returns null for empty input`() {
        assertNull(parseAv1FrameHeader(ByteArray(0), seqHeader))
    }

    @Test
    fun `parseAv1FrameHeader returns null when show_existing_frame is set`() {
        // show_existing_frame=1 -> byte0 = 1000 0000 = 0x80. This frame repeats a previously
        // decoded frame's contents (AV1 spec 7.20) rather than carrying its own header fields.
        assertNull(parseAv1FrameHeader(byteArrayOf(0x80.toByte()), seqHeader))
    }

    @Test
    fun `parseAv1FrameHeader returns null when the sequence header has frame_id_numbers_present_flag set`() {
        // seqHeader with frameIdNumbersPresentFlag forced true (this stream's real sequence header
        // has it false -- Av1SequenceHeader is a data class, so .copy() constructs a variant with
        // just this one field changed). Payload bits: show_existing_frame=0, frame_type=00 (KEY),
        // show_frame=1, disable_cdf_update=0, allow_screen_content_tools=0 (seq_force_screen_content_
        // tools is SELECT in this stream, so this bit is read) -> 0 00 1 0 0 -> 00010000 = 0x10. The
        // parser bails right after reading this prefix, before reading anything else.
        val seqHeaderWithFrameIds = seqHeader.copy(frameIdNumbersPresentFlag = true)
        assertNull(parseAv1FrameHeader(byteArrayOf(0x10), seqHeaderWithFrameIds))
    }

    @Test
    fun `parseAv1FrameHeader returns null for a SWITCH_FRAME (frame_size_override_flag forced true)`() {
        // show_existing_frame=0, frame_type=11 (SWITCH), show_frame=1, disable_cdf_update=0,
        // allow_screen_content_tools=0 -> 0 11 1 0 0 -> 01110000 = 0x70. SWITCH_FRAME forces
        // frame_size_override_flag = 1 without reading a bit for it, so the parser bails
        // immediately once frame_type is known to be SWITCH_FRAME.
        assertNull(parseAv1FrameHeader(byteArrayOf(0x70), seqHeader))
    }

    @Test
    fun `parseAv1FrameHeader returns null when tile_info signals non-uniform tile spacing`() {
        // A full, valid KEY-frame header prefix (show_existing_frame=0, frame_type=00, show_frame=1,
        // disable_cdf_update=0, allow_screen_content_tools=0, frame_size_override_flag=0,
        // order_hint=0000000 [7 bits, OrderHintBits=7 for this stream], render_and_frame_size_
        // different=0, disable_frame_end_update_cdf=0 -- 16 bits total) followed by tile_info()'s
        // uniform_tile_spacing_flag=0 (1 more bit) -- 17 bits, packed as 0x10 0x00 0x00.
        assertNull(parseAv1FrameHeader(byteArrayOf(0x10, 0x00, 0x00), seqHeader))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1FrameHeaderTest"`
Expected: FAIL — compile error, `Av1FrameHeader.kt` doesn't exist yet (`Av1FrameType`/`Av1FrameHeader`/`parseAv1FrameHeader` unresolved).

- [ ] **Step 3: Create `Av1FrameHeader.kt`**

```kotlin
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
private const val PRIMARY_REF_NONE = 7
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

// frame_size() (spec 5.9.5) + superres_params() (spec 5.9.6), for the frame_size_override_flag ==
// false case only -- the true case is bailed out on by parseAv1FrameHeader before this is called,
// so the frame_width_minus_1/frame_height_minus_1 explicit-override branch (and
// frame_size_with_refs()'s cross-frame reference-dimension state) never needs implementing here.
private fun readFrameSize(reader: BitReader, seqHeader: Av1SequenceHeader): Pair<Int, Int> {
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
    return frameWidth to frameHeight
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
            val (fw, fh) = readFrameSize(reader, seqHeader)
            frameWidth = fw
            frameHeight = fh
            readRenderSize(reader)
            if (allowScreenContentTools) {
                reader.readFlag() // allow_intrabc -- valid here since UpscaledWidth == FrameWidth
                                   // always holds (superres is fully resolved inside readFrameSize).
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
            val (fw, fh) = readFrameSize(reader, seqHeader)
            frameWidth = fw
            frameHeight = fh
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1FrameHeaderTest"`
Expected: PASS (8/8 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Av1FrameHeader.kt \
        app/src/test/kotlin/com/multiviewer/parser/Av1FrameHeaderTest.kt
git commit -m "Add stateless AV1 Frame Header field parser"
```

---

### Task 3: `Av1FrameHeaderAnalyzer.kt` — per-tab sequential pass over all frames

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av1FrameHeaderAnalyzer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/Av1FrameHeaderAnalyzerTest.kt`

**Interfaces:**
- Consumes: `com.multiviewer.ui.FrameInfo` (existing, `ui/FrameTypeAnalyzer.kt`: `data class FrameInfo(val index: Int, val type: Char, val sizeBytes: Int, val ptsSeconds: Double, val byteOffset: Long? = null)`), `ByteReader` (existing, `readBytes(offset: Long, len: Int): ByteArray`), `parseObuHeader`/`readLeb128` (Phase 1, `Av1Obu.kt`), `Av1SequenceHeader`/`Av1FrameHeader`/`parseAv1FrameHeader` (Task 1/Task 2), `fileOf` test helper (existing, `TestSupport.kt`).
- Produces: `fun analyzeAv1FrameHeaders(file: File, frames: List<FrameInfo>, seqHeader: Av1SequenceHeader): Map<Long, Av1FrameHeader>` — Task 4 calls this once per tab, off the main thread.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/Av1FrameHeaderAnalyzerTest.kt`:

```kotlin
package com.multiviewer.parser

import com.multiviewer.ui.FrameInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Av1FrameHeaderAnalyzerTest {
    // Same real Sequence Header used by Av1FrameHeaderTest -- see that file's fixture comment for
    // provenance (libsvtav1-encoded 64x64, 5-frame, low-delay MP4).
    private val realSeqHeaderBytes = byteArrayOf(
        0x02, 0x00, 0x00, 0x04, 0xd5.toByte(), 0x7f, 0xfc.toByte(), 0x6a, 0xf9.toByte(), 0x80.toByte(), 0x40,
    )
    private val seqHeader = parseAv1SequenceHeader(realSeqHeaderBytes)!!

    // A sample containing: a Temporal Delimiter OBU (obu_type=2, has_size_field=1, obu_size=0 ->
    // header byte 0x12, size byte 0x00), then an OBU_FRAME (obu_type=6, has_size_field=1 -> header
    // byte 0x32, matching this stream's real captured OBU_FRAME header byte) whose leb128 size is
    // set to 9 -- matching Av1FrameHeaderTest's real, truncated Frame-1 payload length, rather than
    // that frame's true 35-byte size. This test is about locating and bounding the OBU, not about
    // re-proving bit-level field values, which Av1FrameHeaderTest already covers with real,
    // untruncated framing.
    private fun sampleBytes(): ByteArray = byteArrayOf(
        0x12, 0x00, // Temporal Delimiter OBU
        0x32, 0x09, // OBU_FRAME header + leb128 size=9
        0x30, 0x02, 0x00, 0x00, 0x00, 0xdb.toByte(), 0x3b, 0x18, 0x00, // real, truncated Frame-1 payload
    )

    private fun fileWithSample(): java.io.File {
        val headerSize = 8 // irrelevant filler, matches Av1ParameterSetExtractionTest's convention
        return fileOf(ByteArray(headerSize) + sampleBytes())
    }

    @Test
    fun `analyzeAv1FrameHeaders locates the OBU_FRAME past a leading Temporal Delimiter and parses it`() {
        val file = fileWithSample()
        val frames = listOf(FrameInfo(index = 0, type = 'P', sizeBytes = sampleBytes().size, ptsSeconds = 0.1, byteOffset = 8L))
        val result = analyzeAv1FrameHeaders(file, frames, seqHeader)
        assertEquals(setOf(8L), result.keys)
        val header = result.getValue(8L)
        assertEquals(Av1FrameType.INTER, header.frameType)
        assertEquals(140, header.baseQIdx)
        assertEquals(1, header.orderHint)
    }

    @Test
    fun `analyzeAv1FrameHeaders skips a frame with no byteOffset`() {
        val file = fileWithSample()
        val frames = listOf(
            FrameInfo(index = 0, type = 'P', sizeBytes = sampleBytes().size, ptsSeconds = 0.0, byteOffset = null),
            FrameInfo(index = 1, type = 'P', sizeBytes = sampleBytes().size, ptsSeconds = 0.1, byteOffset = 8L),
        )
        val result = analyzeAv1FrameHeaders(file, frames, seqHeader)
        assertEquals(setOf(8L), result.keys)
    }

    @Test
    fun `analyzeAv1FrameHeaders omits a frame whose sample has no FRAME or FRAME_HEADER OBU`() {
        // Just a Temporal Delimiter, nothing else -- no OBU_FRAME/OBU_FRAME_HEADER to find.
        val onlyTd = byteArrayOf(0x12, 0x00)
        val file = fileOf(ByteArray(8) + onlyTd)
        val frames = listOf(FrameInfo(index = 0, type = 'P', sizeBytes = onlyTd.size, ptsSeconds = 0.0, byteOffset = 8L))
        val result = analyzeAv1FrameHeaders(file, frames, seqHeader)
        assertTrue(result.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1FrameHeaderAnalyzerTest"`
Expected: FAIL — compile error, `Av1FrameHeaderAnalyzer.kt` doesn't exist yet (`analyzeAv1FrameHeaders` unresolved).

- [ ] **Step 3: Create `Av1FrameHeaderAnalyzer.kt`**

```kotlin
package com.multiviewer.parser

import com.multiviewer.ui.FrameInfo
import java.io.File

private const val OBU_TYPE_FRAME_HEADER = 3
private const val OBU_TYPE_FRAME = 6
private const val MAX_FRAME_HEADER_PREFIX_BYTES = 4096

// Walks `frames` (decode order, from FrameTypeAnalyzer.probeFrameTypes) and, for each frame with a
// known byteOffset, locates its OBU_FRAME_HEADER or OBU_FRAME OBU -- the wrapper differs (a
// standalone frame_header_obu(), or one embedded at the start of an OBU_FRAME per AV1 spec 5.10
// frame_obu()), but both start with frame_header_obu() bits at position 0 of the OBU's own payload,
// so both are handed to parseAv1FrameHeader the same way. A frame with no byteOffset, no located
// header OBU, or a parse failure is simply absent from the returned map -- this pass never aborts
// partway through the frame list (mirrors the error-handling convention established by
// extractAv1CRawSequenceHeader). Frame headers don't need to be parsed in decode order for
// correctness (see parseAv1FrameHeader's doc comment on why this plan's parsing is stateless); the
// loop below follows `frames`' own order purely because that's how the list already arrives.
fun analyzeAv1FrameHeaders(file: File, frames: List<FrameInfo>, seqHeader: Av1SequenceHeader): Map<Long, Av1FrameHeader> {
    val result = mutableMapOf<Long, Av1FrameHeader>()
    try {
        ByteReader.open(file).use { reader ->
            for (frame in frames) {
                val byteOffset = frame.byteOffset ?: continue
                if (frame.sizeBytes <= 0) continue
                val header = locateAndParseFrameHeader(reader, byteOffset, frame.sizeBytes, seqHeader)
                if (header != null) {
                    result[byteOffset] = header
                }
            }
        }
    } catch (e: Exception) {
        return result
    }
    return result
}

// Walks the OBUs in one sample's byte range [byteOffset, byteOffset + sizeBytes), looking for the
// first OBU_FRAME_HEADER or OBU_FRAME, then hands a bounded prefix of that OBU's own payload (not
// the whole OBU, which can be large -- the curated fields all fall within the first few dozen bits)
// to parseAv1FrameHeader. Mirrors extractAv1CRawSequenceHeader's OBU-walking loop shape.
private fun locateAndParseFrameHeader(reader: ByteReader, byteOffset: Long, sizeBytes: Int, seqHeader: Av1SequenceHeader): Av1FrameHeader? {
    return try {
        val sampleEnd = byteOffset + sizeBytes
        var pos = byteOffset
        while (pos < sampleEnd) {
            val header = parseObuHeader(reader, pos)
            if (!header.hasSizeField) return null // can't determine this OBU's length
            val (obuSize, obuPayloadStart) = readLeb128(reader, pos + header.headerSize)
            if (obuPayloadStart + obuSize > sampleEnd) return null
            if (header.obuType == OBU_TYPE_FRAME_HEADER || header.obuType == OBU_TYPE_FRAME) {
                val prefixLen = minOf(obuSize, MAX_FRAME_HEADER_PREFIX_BYTES.toLong()).toInt()
                val payload = reader.readBytes(obuPayloadStart, prefixLen)
                return parseAv1FrameHeader(payload, seqHeader)
            }
            pos = obuPayloadStart + obuSize
        }
        null
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.Av1FrameHeaderAnalyzerTest"`
Expected: PASS (3/3 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Av1FrameHeaderAnalyzer.kt \
        app/src/test/kotlin/com/multiviewer/parser/Av1FrameHeaderAnalyzerTest.kt
git commit -m "Add AV1 Frame Header sequential per-tab analyzer"
```

---

### Task 4: Wire into `TabState` and the Detail Properties panel

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (add one `TabState` field after `av1SequenceHeaderOffset`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` (add a second AV1 `LaunchedEffect`, right after the existing av1C one)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` (`DetailPropertiesTabContent` — display `tab.av1FrameHeaders[selectedFrame.byteOffset]`, no hex-jump)

**Interfaces:**
- Consumes: `com.multiviewer.parser.analyzeAv1FrameHeaders` (Task 3), `com.multiviewer.parser.Av1FrameHeader` (Task 2), `tab.gopFrames: List<FrameInfo>?` (existing, `AppState.kt:151`), `tab.av1SequenceHeader` (Phase 1), `tab.selectedFrame: FrameInfo?` (existing, `AppState.kt:153`).

No new automated tests in this task — UI wiring only, matching this codebase's established convention (verified via manual app testing, same as Phase 1's own final task and the H.264/HEVC features' final tasks).

- [ ] **Step 1: Add the `TabState` field**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, immediately after the existing:

```kotlin
    var av1SequenceHeader: com.multiviewer.parser.Av1SequenceHeader? by mutableStateOf(null)
    var av1SequenceHeaderOffset: LongRange? by mutableStateOf(null)
```

insert:

```kotlin

    // AV1 Frame Header, per selected frame (see Av1FrameHeader.kt / Av1FrameHeaderAnalyzer.kt) --
    // unlike av1SequenceHeader (stream-wide), this is resolved per frame; populated all at once by
    // a sequential pass over every frame once both the parsed av1SequenceHeader and gopFrames (the
    // user-triggered frame analysis, see AppState.analyzeFrames) are available, keyed by
    // FrameInfo.byteOffset to match how selectedFrame is looked up elsewhere in this class.
    var av1FrameHeaders: Map<Long, com.multiviewer.parser.Av1FrameHeader> by mutableStateOf(emptyMap())
```

- [ ] **Step 2: Populate `av1FrameHeaders` once both inputs are available, in `VideoInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`, immediately after the existing av1C `LaunchedEffect(tab.root)` block:

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

insert:

```kotlin

    // Runs the AV1 Frame Header sequential pass once both the parsed Sequence Header and the
    // user-triggered frame analysis (tab.gopFrames, populated by AppState.analyzeFrames -- see
    // FrameTypeAnalyzer.kt) are available. Unlike the av1C LaunchedEffect above (keyed on tab.root,
    // available as soon as the file's box tree is parsed), gopFrames is NOT populated automatically
    // -- it stays null until the user clicks "Analyze Frames" -- so this effect is keyed on
    // tab.gopFrames too, and reruns (a no-op until both are non-null) as either becomes available,
    // in either order.
    LaunchedEffect(tab.gopFrames, tab.av1SequenceHeader) {
        val frames = tab.gopFrames ?: return@LaunchedEffect
        val seqHeader = tab.av1SequenceHeader ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            tab.av1FrameHeaders = com.multiviewer.parser.analyzeAv1FrameHeaders(tab.file, frames, seqHeader)
        }
    }
```

- [ ] **Step 3: Display the Frame Header in `ImageInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`'s `DetailPropertiesTabContent`, find the "AV1 Sequence Header" block that starts with:

```kotlin
                            tab.av1SequenceHeader?.let { seqHeader ->
```

and reads through to its own matching closing `}` (ending with the `PropertyRow("Film Grain Present", ...)` line). Immediately after that block's closing `}`, and still before the outer `item { ... }` block's own closing `}`, insert:

```kotlin
                            val av1SelectedFrameByteOffset = selectedFrame.byteOffset
                            if (av1SelectedFrameByteOffset != null) {
                                tab.av1FrameHeaders[av1SelectedFrameByteOffset]?.let { frameHeader ->
                                    Spacer(Modifier.height(8.dp))
                                    Text("AV1 Frame Header", style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue))
                                    PropertyRow("Frame Type", frameHeader.frameType.name)
                                    PropertyRow("Show Frame", if (frameHeader.showFrame) "Yes" else "No")
                                    PropertyRow("Showable Frame", if (frameHeader.showableFrame) "Yes" else "No")
                                    PropertyRow("Frame Size", "${frameHeader.frameWidth} x ${frameHeader.frameHeight}")
                                    PropertyRow("Base Q Index", frameHeader.baseQIdx.toString())
                                    PropertyRow("Tile Cols / Rows", "${frameHeader.tileCols} / ${frameHeader.tileRows}")
                                    PropertyRow("Refresh Frame Flags", "0x${frameHeader.refreshFrameFlags.toString(16).uppercase()}")
                                    PropertyRow("Order Hint", frameHeader.orderHint.toString())
                                }
                            }
```

Note this section has no `onClick` on any `PropertyRow` (unlike the Sequence Header's "Profile / Level / Tier" row) — per this plan's Global Constraints, Frame Header fields are not independently hex-jumpable; they're already within the currently-selected frame's own bytes, reachable via existing frame-row navigation.

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Manual verification**

Generate the same 64x64, 5-frame, low-delay AV1 MP4 used throughout this plan's tests:

```bash
ffmpeg -y -f lavfi -i "testsrc=size=64x64:rate=10:duration=0.5" -c:v libsvtav1 -pix_fmt yuv420p -g 5 \
  -svtav1-params pred-struct=1:enable-overlays=0 /tmp/av1_frame_header_manual_test.mp4
```

Launch the app (`./gradlew :app:run`), open `/tmp/av1_frame_header_manual_test.mp4`, click "Analyze Frames" (or the codebase's equivalent frame-analysis trigger button), then select different frames (GOP bar, filmstrip, or arrow-key stepping) and confirm:
- Before clicking "Analyze Frames": the "AV1 Sequence Header" section already appears (unchanged from Phase 1) but no "AV1 Frame Header" section appears yet, even with a frame selected (since `tab.gopFrames` is still `null`).
- After clicking "Analyze Frames": selecting the first frame shows an "AV1 Frame Header" section with Frame Type = KEY, Base Q Index = 45, Tile Cols/Rows = 1 / 1, Refresh Frame Flags = 0xFF, Order Hint = 0.
- Selecting subsequent frames shows Frame Type = INTER with different Base Q Index/Refresh Frame Flags/Order Hint values per frame, while the "AV1 Sequence Header" section above it stays identical across all frame selections (stream-wide, per Phase 1's own Global Constraint).
- No `onClick`/hex-jump affordance on any "AV1 Frame Header" row (unlike the Sequence Header's "Profile / Level / Tier" row, which still jumps).
- Opening an H.264 or HEVC file still shows only their respective sections (no regression); opening a file with none of the three shows none of the sections.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt \
        app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Show the AV1 Frame Header in Detail Properties per selected frame"
```
