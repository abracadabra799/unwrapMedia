# APV Codec Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize APV (Advanced Professional Video, RFC 9924) in MP4 containers — real `apv1`/`apvC` box decoding in Structure Analyser (replacing today's generic/unparsed leaf-node rendering) and an "APV Frame Header" section in Detail Properties showing the selected frame's own `frame_header()` fields.

**Architecture:** Two new pure/synchronous parser files (`ApvPbu.kt`, `ApvFrameHeader.kt`) handle bitstream framing and field extraction with no I/O. A new `ApvCBoxDecoder.kt` (Structure Analyser) and an extraction function in `ApvParameterSetExtraction.kt` (per-frame Detail Properties) are the only two consumers of those pure functions — mirroring exactly how this codebase's `Av1CBoxDecoder`/`Av1ParameterSetExtraction` are two independent readers of the same underlying box. Unlike every other codec this app supports, APV needs **no stream-wide pre-parse at all**: every frame's `frame_header()` is self-contained, so Detail Properties resolves it lazily per selected frame via Compose's `produceState` — the exact same pattern H.264/HEVC already use for their per-frame PPS/SPS lookup, except APV needs no `TabState` fields and no `LaunchedEffect` in `VideoInspectorUI.kt` at all, since there's no stream-wide list to pre-parse first.

**Tech Stack:** Kotlin, Compose Desktop. Reuses `BitReader` (bit-level `u(n)` reads, no Exp-Golomb needed — APV has none), `ByteReader` (byte-aligned file I/O), `BoxDecoder`/`BoxRegistry` (Structure Analyser), `FrameInfo.byteOffset`/`sizeBytes` (existing frame enumeration). No new dependencies.

## Global Constraints

- MP4/ISOBMFF only. No WebM/Matroska support — APV is a production/camera-workflow codec, not a realistic WebM use case.
- Only the **primary frame** (`pbu_type == 1`) is parsed. Non-primary/preview/depth/alpha frame PBU types (`pbu_type` 2, 25–27) and `au_info()`/`metadata()`/`filler()` PBU types (`pbu_type` 65–67) are out of scope.
- An MP4 sample's bytes are the **verbatim** raw access unit — same leading 4-byte length field, same `'aPv1'` signature, same `pbu_size`+`pbu_header`+payload structure as a raw `.apv` elementary-stream file. **Verified against a real file** (see below) — no MP4-specific offset handling needed anywhere in this plan's parsing code.
- Every parsing function returns `null` on any malformed/truncated input or unrecognized `pbu_type` — never throws — matching every other codec parser in this codebase (`parseAv1SequenceHeader`, `parseH264Sps`, etc.).
- `profile_idc` is shown as a named profile using this exact table (verified against `openapv`'s `inc/oapv.h`, not fabricated): `33→"422-10"`, `44→"422-12"`, `55→"444-10"`, `66→"444-12"`, `77→"4444-10"`, `88→"4444-12"`, `99→"400-10"`, `140→"444-16C12"`, `144→"4444-16C12"`. Any other value: no name, shown as a raw number.
- `chroma_format_idc` is shown as a named format (RFC 9924 Table 2): `0→"4:0:0"`, `2→"4:2:2"`, `3→"4:4:4"`, `4→"4:4:4:4"`. Values `1`, `5`–`7`: no name, shown as a raw number.
- `band_idc` is shown as a **raw number only** (valid range 0–3) — no official name-per-value mapping exists anywhere (confirmed absent from both RFC 9924 and `openapv`'s public headers). Do not invent band names.
- `NumTiles` is derived as `TileCols × TileRows`, where `TileCols = ceil(ceil(frameWidth / 16.0) / tileWidthInMbs)` and `TileRows = ceil(ceil(frameHeight / 16.0) / tileHeightInMbs)` (RFC 9924 §4.2's `MbWidth = MbHeight = 16` constant, §5.3.8's tile-grid derivation) — not the full `ColStarts`/`RowStarts` arrays.
- `quantization_matrix()` is not parsed. If `use_q_matrix == true` (bit value `1`) for a frame, `parseApvFrameHeader` returns `null` for that frame rather than risk misreading subsequent fields at the wrong bit offset — same bail-out philosophy as H.264's `seq_scaling_matrix_present_flag` case. (Not exercised by this plan's real fixture, which has `use_q_matrix == false`.)
- No hex-viewer click-to-jump for the "APV Frame Header" section — matches AV1's Frame Header section, which also has none.

## Technical Foundation (verified against a real file during planning)

A real raw `.apv` bitstream test vector (`qp_D.apv`, from `AcademySoftwareFoundation/openapv`'s `test/bitstream/`) was downloaded and hand-decoded byte-by-byte, cross-validated against `ffmpeg`/`ffprobe`'s independent probe output (`3840x2160`, `yuv422p10le`, `profile=33`, `level=123` — all matching), and remuxed into a real `apv1`-tagged MP4 via `ffmpeg -i qp_D.apv -c copy out.mp4` (bitstream copy, no re-encode needed) to confirm MP4-sample framing and extract real `apvC` box bytes. Full details and reasoning: `docs/superpowers/specs/2026-08-16-apv-codec-support-design.md`'s Technical Foundation section.

**Real fixture 1 — first access unit's leading 64 bytes** (identical whether read from the raw `.apv` file or from the remuxed MP4's first `mdat` sample — confirmed via direct byte comparison during planning):
```
00095f7c6150763100095f2601000100217b40000f00000870220000000000
400002000000000ab900140000000006b300000216000001dc333333009ddd
9073
```
(continuous 64-byte / 128-hex-char sequence — the three lines above are one unbroken value, split only for readability)

Decoded field-by-field (verified with a Python bit-reader during planning, not by eye):
- Bytes `[0:4]` = `00 09 5f 7c` — the access unit's own leading 4-byte length field (not consumed by this plan's parser; `FrameInfo.sizeBytes` already gives the total sample length from the MP4 track tables).
- Bytes `[4:8]` = `61 50 76 31` = `'aPv1'` signature.
- Bytes `[8:12]` = `00 09 5f 26` = `pbu_size` (u32) = 615718.
- Bytes `[12:16]` = `01 00 01 00` = `pbu_header`: `pbu_type` (u8) = **1** (primary frame), `group_id` (u16) = 1, `reserved_zero_8bits` (u8) = 0.
- Starting at byte 16, `frame_header()`'s `frame_info()` (12 bytes, byte-aligned): `profile_idc=33`, `level_idc=123`, `band_idc=2` (top 3 bits of the next byte; bottom 5 bits reserved = 0), `frame_width=3840` (u24), `frame_height=2160` (u24), `chroma_format_idc=2` (top 4 bits of next byte = "4:2:2"), `bit_depth_minus8=2` (bottom 4 bits → bit depth 10), `capture_time_distance=0`, one trailing `reserved_zero_8bits=0`.
- Continuing (bit-packed, verified with a Python `BitReader` during planning): one more `reserved_zero_8bits` byte (0), `color_description_present_flag=0` (so no color-description fields follow), `use_q_matrix=0` (so no `quantization_matrix()` follows), then `tile_info()`: `tile_width_in_mbs=16`, `tile_height_in_mbs=8`, `tile_size_present_in_fh_flag=0`.
- Applying the `NumTiles` formula above to this real frame: `FrameWidthInMbsY = ceil(3840/16) = 240`, `FrameHeightInMbsY = ceil(2160/16) = 135`, `TileCols = ceil(240/16) = 15`, `TileRows = ceil(135/8) = 17`, `NumTiles = 15 × 17 = 255`.

**Real fixture 2 — the `apvC` box payload from the remuxed MP4** (22 bytes, hex `000000000101010101217b0200000f00000008702200`), located and extracted with a Python script during planning:
```
byte  0- 8: 00 00 00 00 01 01 01 01 01   (fixed prefix -- see note below)
byte  9   : 21                            profile_idc (u8) = 33
byte 10   : 7b                            level_idc (u8) = 123
byte 11   : 02                            band_idc (u8) = 2  (full byte here, not bit-packed like the bitstream)
byte 12-15: 00 00 0f 00                   frame_width (u32) = 3840
byte 16-19: 00 00 08 70                   frame_height (u32) = 2160
byte 20   : 22                            chroma_format_idc (top 4 bits) = 2, bit_depth_minus8 (bottom 4 bits) = 2
byte 21   : 00                            trailing byte (not curated)
```
The `profile_idc`/`level_idc`/`band_idc`/`frame_width`/`frame_height`/`chroma_format_idc`/`bit_depth_minus8` byte positions above were found by direct value-search (searching the 22-byte payload for the byte sequence that decodes to the already-known-correct values `33`, `123`, `3840`, `2160`, `2`/`2`), not assumed from documentation — high confidence. The exact sub-field meaning of bytes 0–8 (config version / entry count / `pbu_type` marker, per `openapv`'s field-name list) is lower-confidence — `ApvCBoxDecoder` only needs to report the confirmed fields (9 onward) plus a generic byte dump or best-effort label for 0–8; do not invent precise names for those 9 bytes beyond what's justified.

## Components (file structure)

- **`app/src/main/kotlin/com/multiviewer/parser/ApvPbu.kt`** (new) — access-unit / PBU framing, no I/O.
- **`app/src/main/kotlin/com/multiviewer/parser/ApvFrameHeader.kt`** (new) — `frame_header()` field parser, no I/O.
- **`app/src/main/kotlin/com/multiviewer/parser/ApvCBoxDecoder.kt`** (new) — Structure Analyser box decoder for `apvC`.
- **`app/src/main/kotlin/com/multiviewer/parser/ApvParameterSetExtraction.kt`** (new) — per-frame MP4 sample extraction, the only file that touches `File`/`ByteReader`.
- **`app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`** (modified) — register `"apv1"`/`"apvC"`.
- **`app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`** (modified) — add `"apv1" to "APV"` display name.
- **`app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`** (modified) — "APV Frame Header" Detail Properties section.

---

### Task 1: `ApvPbu.kt` + `ApvFrameHeader.kt` — pure bitstream parsers

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/ApvPbu.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/ApvFrameHeader.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ApvPbuTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ApvFrameHeaderTest.kt`

**Interfaces:**
- Consumes: `BitReader` (existing, `parser/BitReader.kt` — `readBits(count: Int): Int` for `count` in `0..31`; every field needed here fits, largest is `u(24)`).
- Produces: `data class ApvPbuHeader(val pbuType: Int, val groupId: Int)`; `fun findApvPrimaryFramePbuPayload(accessUnitBytes: ByteArray): ByteArray?`; `enum class ApvChromaFormat { YUV_400, YUV_422, YUV_444, YUV_4444, RESERVED }`; `data class ApvFrameHeader(val profileIdc: Int, val profileName: String?, val levelIdc: Int, val bandIdc: Int, val frameWidth: Int, val frameHeight: Int, val chromaFormat: ApvChromaFormat, val bitDepth: Int, val colorPrimaries: Int?, val transferCharacteristics: Int?, val matrixCoefficients: Int?, val fullRangeFlag: Boolean?, val tileWidthInMbs: Int, val tileHeightInMbs: Int, val tileCount: Int)`; `fun parseApvFrameHeader(framePayload: ByteArray): ApvFrameHeader?` — Task 2's `resolveApvFrameHeader` calls both.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/ApvPbuTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApvPbuTest {
    // Real access-unit bytes from openapv's test/bitstream/qp_D.apv (first access unit), verified
    // byte-identical whether read from the raw .apv file or a real ffmpeg-remuxed apv1 MP4 sample --
    // see docs/superpowers/plans/2026-08-16-apv-codec-support.md's Technical Foundation section.
    private val realAccessUnitPrefix = hexToBytes(
        "00095f7c6150763100095f2601000100217b40000f00000870220000000000" +
            "400002000000000ab900140000000006b300000216000001dc333333009ddd" +
            "9073",
    )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    @Test
    fun `findApvPrimaryFramePbuPayload locates the primary-frame PBU and returns its frame() payload`() {
        val payload = findApvPrimaryFramePbuPayload(realAccessUnitPrefix)

        assertNotNull(payload)
        // The frame() payload starts right after the 4-byte pbu_header, i.e. at access-unit byte 16
        // (4-byte length + 4-byte 'aPv1' signature + 4-byte pbu_size + 4-byte pbu_header = 16).
        // Its first byte is profile_idc = 0x21 = 33.
        assertEquals(0x21, payload[0].toInt() and 0xFF)
    }

    @Test
    fun `findApvPrimaryFramePbuPayload returns null for truncated input`() {
        assertNull(findApvPrimaryFramePbuPayload(realAccessUnitPrefix.copyOfRange(0, 10)))
    }

    @Test
    fun `findApvPrimaryFramePbuPayload returns null when no primary-frame PBU is present`() {
        // Same leading length + signature, but pbu_type changed from 1 to 2 (non-primary frame) at
        // access-unit byte 12 -- no primary-frame PBU exists in this input.
        val mutated = realAccessUnitPrefix.copyOf()
        mutated[12] = 2
        assertNull(findApvPrimaryFramePbuPayload(mutated))
    }
}
```

Create `app/src/test/kotlin/com/multiviewer/parser/ApvFrameHeaderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApvFrameHeaderTest {
    // Same real access-unit bytes as ApvPbuTest.realAccessUnitPrefix -- see that file's comment for
    // provenance. The frame() payload (what parseApvFrameHeader consumes) starts at byte 16 of that
    // same sequence, defined independently here since each test file in this codebase's convention
    // owns its own fixture bytes rather than sharing them cross-file (see e.g. Av1CBoxDecoderTest's
    // own realAv1CPayload(), independent of Av1ObuTest's fixtures).
    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    private val realFramePayload = hexToBytes(
        "217b40000f00000870220000000000400002000000000ab900140000000006b300000216000001dc333333009ddd9073",
    )

    @Test
    fun `parseApvFrameHeader extracts every curated field matching the hand-verified real values`() {
        val header = parseApvFrameHeader(realFramePayload)

        assertNotNull(header)
        assertEquals(33, header.profileIdc)
        assertEquals("422-10", header.profileName)
        assertEquals(123, header.levelIdc)
        assertEquals(2, header.bandIdc)
        assertEquals(3840, header.frameWidth)
        assertEquals(2160, header.frameHeight)
        assertEquals(ApvChromaFormat.YUV_422, header.chromaFormat)
        assertEquals(10, header.bitDepth)
        assertNull(header.colorPrimaries) // color_description_present_flag was 0 in this real frame
        assertEquals(16, header.tileWidthInMbs)
        assertEquals(8, header.tileHeightInMbs)
        assertEquals(255, header.tileCount) // TileCols=15 * TileRows=17, per the NumTiles formula
    }

    @Test
    fun `parseApvFrameHeader returns an unnamed profile for an unrecognized profile_idc`() {
        val mutated = realFramePayload.copyOf()
        mutated[0] = 200.toByte() // not in the known profile table
        val header = parseApvFrameHeader(mutated)
        assertNotNull(header)
        assertEquals(200, header.profileIdc)
        assertEquals(null, header.profileName)
    }

    @Test
    fun `parseApvFrameHeader returns null for truncated input`() {
        assertNull(parseApvFrameHeader(realFramePayload.copyOfRange(0, 5)))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ApvPbuTest" --tests "com.multiviewer.parser.ApvFrameHeaderTest"`
Expected: FAIL — compile error, `findApvPrimaryFramePbuPayload`/`parseApvFrameHeader` don't exist yet.

- [ ] **Step 3: Create `ApvPbu.kt`**

```kotlin
package com.multiviewer.parser

private const val ACCESS_UNIT_PREFIX_LENGTH = 8 // 4-byte leading length field + 4-byte 'aPv1' signature
private const val PBU_SIZE_FIELD_LENGTH = 4
private const val PBU_HEADER_LENGTH = 4
private const val PBU_TYPE_PRIMARY_FRAME = 1

// Access-unit / PBU framing (RFC 9924 SS5.3.1/SS5.3.2), verified against a real access unit's bytes
// during planning (see docs/superpowers/plans/2026-08-16-apv-codec-support.md's Technical
// Foundation): [4-byte leading length]['aPv1' signature][pbu_size u(32)][pbu_header][payload]...,
// repeated per PBU. An MP4 sample's bytes are this exact structure verbatim -- no MP4-specific
// offset handling needed.
private fun parseApvPbuHeader(accessUnitBytes: ByteArray, offset: Int): ApvPbuHeader? {
    if (offset + PBU_HEADER_LENGTH > accessUnitBytes.size) return null
    val pbuType = accessUnitBytes[offset].toInt() and 0xFF
    val groupId = ((accessUnitBytes[offset + 1].toInt() and 0xFF) shl 8) or (accessUnitBytes[offset + 2].toInt() and 0xFF)
    return ApvPbuHeader(pbuType, groupId)
}

data class ApvPbuHeader(val pbuType: Int, val groupId: Int)

// Locates the first pbu_type == 1 (primary frame) PBU within one access unit's bytes and returns
// its frame() payload (frame_header() plus tile data together -- the caller, parseApvFrameHeader,
// only parses the header prefix and never touches tile/coefficient data). Returns null if the input
// is too short, malformed, or contains no primary-frame PBU.
fun findApvPrimaryFramePbuPayload(accessUnitBytes: ByteArray): ByteArray? {
    var pos = ACCESS_UNIT_PREFIX_LENGTH
    while (pos + PBU_SIZE_FIELD_LENGTH + PBU_HEADER_LENGTH <= accessUnitBytes.size) {
        val pbuSize = (
            ((accessUnitBytes[pos].toInt() and 0xFF).toLong() shl 24) or
                ((accessUnitBytes[pos + 1].toInt() and 0xFF).toLong() shl 16) or
                ((accessUnitBytes[pos + 2].toInt() and 0xFF).toLong() shl 8) or
                (accessUnitBytes[pos + 3].toInt() and 0xFF).toLong()
            )
        val pbuStart = pos + PBU_SIZE_FIELD_LENGTH
        val header = parseApvPbuHeader(accessUnitBytes, pbuStart) ?: return null
        val payloadStart = pbuStart + PBU_HEADER_LENGTH
        val payloadLength = pbuSize - PBU_HEADER_LENGTH
        if (payloadLength < 0 || payloadStart + payloadLength > accessUnitBytes.size) return null
        if (header.pbuType == PBU_TYPE_PRIMARY_FRAME) {
            return accessUnitBytes.copyOfRange(payloadStart, payloadStart + payloadLength.toInt())
        }
        pos = payloadStart + payloadLength.toInt()
    }
    return null
}
```

- [ ] **Step 4: Create `ApvFrameHeader.kt`**

```kotlin
package com.multiviewer.parser

private val PROFILE_NAMES = mapOf(
    33 to "422-10", 44 to "422-12", 55 to "444-10", 66 to "444-12",
    77 to "4444-10", 88 to "4444-12", 99 to "400-10",
    140 to "444-16C12", 144 to "4444-16C12",
)
private const val MB_SIZE = 16 // RFC 9924 SS4.2: MbWidth = MbHeight = 16

enum class ApvChromaFormat { YUV_400, YUV_422, YUV_444, YUV_4444, RESERVED }

private fun chromaFormatFor(chromaFormatIdc: Int): ApvChromaFormat = when (chromaFormatIdc) {
    0 -> ApvChromaFormat.YUV_400
    2 -> ApvChromaFormat.YUV_422
    3 -> ApvChromaFormat.YUV_444
    4 -> ApvChromaFormat.YUV_4444
    else -> ApvChromaFormat.RESERVED
}

data class ApvFrameHeader(
    val profileIdc: Int, val profileName: String?,
    val levelIdc: Int, val bandIdc: Int,
    val frameWidth: Int, val frameHeight: Int,
    val chromaFormat: ApvChromaFormat, val bitDepth: Int,
    val colorPrimaries: Int?, val transferCharacteristics: Int?, val matrixCoefficients: Int?, val fullRangeFlag: Boolean?,
    val tileWidthInMbs: Int, val tileHeightInMbs: Int, val tileCount: Int,
)

// Parses frame_header() (RFC 9924 SS5.3.5/SS5.3.6/SS5.3.8), verified field-by-field against a real
// frame's bytes during planning (see this plan's Technical Foundation section) -- stops right after
// tile_info(), never touches tile/coefficient data. Returns null on truncated input, or if
// use_q_matrix is set (quantization_matrix() isn't parsed -- see this plan's Global Constraints).
fun parseApvFrameHeader(framePayload: ByteArray): ApvFrameHeader? {
    return try {
        val reader = BitReader(framePayload)
        val profileIdc = reader.readBits(8)
        val levelIdc = reader.readBits(8)
        val bandIdc = reader.readBits(3)
        reader.readBits(5) // reserved_zero_5bits
        val frameWidth = reader.readBits(24)
        val frameHeight = reader.readBits(24)
        val chromaFormatIdc = reader.readBits(4)
        val bitDepthMinus8 = reader.readBits(4)
        reader.readBits(8) // capture_time_distance
        reader.readBits(8) // reserved_zero_8bits (end of frame_info())
        reader.readBits(8) // reserved_zero_8bits (frame_header()'s own)

        val colorDescriptionPresentFlag = reader.readFlag()
        var colorPrimaries: Int? = null
        var transferCharacteristics: Int? = null
        var matrixCoefficients: Int? = null
        var fullRangeFlag: Boolean? = null
        if (colorDescriptionPresentFlag) {
            colorPrimaries = reader.readBits(8)
            transferCharacteristics = reader.readBits(8)
            matrixCoefficients = reader.readBits(8)
            fullRangeFlag = reader.readFlag()
        }

        val useQMatrix = reader.readFlag()
        if (useQMatrix) return null // quantization_matrix() not parsed -- see Global Constraints

        val tileWidthInMbs = reader.readBits(20)
        val tileHeightInMbs = reader.readBits(20)
        // tile_size_present_in_fh_flag and any per-tile sizes are read by the caller only if needed
        // later; this parser stops here, matching the plan's "tile grid dimensions and a derived
        // count only" curation scope.

        val frameWidthInMbs = (frameWidth + MB_SIZE - 1) / MB_SIZE
        val frameHeightInMbs = (frameHeight + MB_SIZE - 1) / MB_SIZE
        val tileCols = (frameWidthInMbs + tileWidthInMbs - 1) / tileWidthInMbs
        val tileRows = (frameHeightInMbs + tileHeightInMbs - 1) / tileHeightInMbs

        ApvFrameHeader(
            profileIdc = profileIdc, profileName = PROFILE_NAMES[profileIdc],
            levelIdc = levelIdc, bandIdc = bandIdc,
            frameWidth = frameWidth, frameHeight = frameHeight,
            chromaFormat = chromaFormatFor(chromaFormatIdc), bitDepth = bitDepthMinus8 + 8,
            colorPrimaries = colorPrimaries, transferCharacteristics = transferCharacteristics,
            matrixCoefficients = matrixCoefficients, fullRangeFlag = fullRangeFlag,
            tileWidthInMbs = tileWidthInMbs, tileHeightInMbs = tileHeightInMbs, tileCount = tileCols * tileRows,
        )
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ApvPbuTest" --tests "com.multiviewer.parser.ApvFrameHeaderTest"`
Expected: PASS (6/6 tests — 3 in `ApvPbuTest`, 3 in `ApvFrameHeaderTest`).

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ApvPbu.kt \
        app/src/main/kotlin/com/multiviewer/parser/ApvFrameHeader.kt \
        app/src/test/kotlin/com/multiviewer/parser/ApvPbuTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/ApvFrameHeaderTest.kt
git commit -m "Add APV PBU framing and frame_header() bitstream parsers"
```

---

### Task 2: `ApvCBoxDecoder.kt` + `ApvParameterSetExtraction.kt` — container-level extraction

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/ApvCBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/ApvParameterSetExtraction.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ApvCBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `findApvPrimaryFramePbuPayload`, `parseApvFrameHeader`, `ApvFrameHeader` (Task 1); `BoxDecoder`/`BoxNode`/`BoxField`/`ByteReader` (existing, same shape `Av1CBoxDecoder.kt` already uses — see that file for the exact pattern being mirrored); `FrameInfo.byteOffset`/`sizeBytes` (existing, unchanged).
- Produces: `object ApvCBoxDecoder : BoxDecoder`; `fun resolveApvFrameHeader(file: File, byteOffset: Long, sizeBytes: Int): ApvFrameHeader?` — Task 3 calls this from `ImageInspectorUI.kt`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/parser/ApvCBoxDecoderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApvCBoxDecoderTest {
    // Real apvC box payload extracted from a real ffmpeg-remuxed apv1 MP4 (ffmpeg -i qp_D.apv -c
    // copy out.mp4) during planning -- see docs/superpowers/plans/2026-08-16-apv-codec-support.md's
    // Technical Foundation section for how these exact byte offsets were confirmed (direct
    // value-search, not assumed from documentation).
    private fun realApvCPayload(): ByteArray = ByteArray(22) { i ->
        val hex = "000000000101010101217b0200000f00000008702200"
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }

    // Mirrors Av1CBoxDecoderTest's exact decode() helper shape: fileOf() + ByteReader.open(),
    // headerSize-byte zero-padding prefix, offset=0.
    private fun decode(payload: ByteArray): BoxNode {
        val headerSize = 8
        val file = fileOf(ByteArray(headerSize) + payload)
        val reader = ByteReader.open(file)
        return ApvCBoxDecoder.decode(reader, "apvC", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong(), warnings = emptyList())
    }

    private fun fieldValue(node: BoxNode, name: String): String? = node.fields.find { it.name == name }?.value

    @Test
    fun `decode extracts profile level band and frame dimensions from a real apvC box`() {
        val node = decode(realApvCPayload())

        assertEquals("33", fieldValue(node, "profile_idc"))
        assertEquals("123", fieldValue(node, "level_idc"))
        assertEquals("2", fieldValue(node, "band_idc"))
        assertEquals("3840", fieldValue(node, "frame_width"))
        assertEquals("2160", fieldValue(node, "frame_height"))
        assertEquals("2", fieldValue(node, "chroma_format_idc"))
        assertEquals("10", fieldValue(node, "bit_depth"))
    }

    @Test
    fun `decode adds a warning and no fields when the box is too short`() {
        val node = decode(realApvCPayload().copyOfRange(0, 10)) // 10 bytes, needs at least 21
        assertTrue(node.warnings.isNotEmpty())
        assertTrue(node.fields.isEmpty())
    }
}
```

`fileOf(bytes: ByteArray): File` is this test source set's existing temp-file-backed fixture helper (used by `Av1CBoxDecoderTest`/sibling box-decoder tests — check its exact location/import if the compiler can't resolve it, but it should already be in scope the same way it is for `Av1CBoxDecoderTest.kt`).

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ApvCBoxDecoderTest"`
Expected: FAIL — compile error, `ApvCBoxDecoder` doesn't exist yet.

- [ ] **Step 3: Create `ApvCBoxDecoder.kt`**

Mirrors `Av1CBoxDecoder.kt`'s exact shape (read that file first to confirm the `BoxDecoder`/`BoxField`/`BoxNode` types still match this signature before implementing — if `Av1CBoxDecoder.kt` has drifted from what's shown below, follow its current real shape instead):

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ApvCBoxDecoderTest"`
Expected: PASS (1/1 test).

- [ ] **Step 5: Create `ApvParameterSetExtraction.kt`**

```kotlin
package com.multiviewer.parser

import java.io.File

// Reads one frame's raw MP4 sample bytes (FrameInfo.byteOffset/sizeBytes) and parses its APV frame
// header. Lazy, on-demand, per frame -- no whole-stream pass needed, since every APV frame_header()
// is self-contained (see this plan's Architecture section for why this differs from AV1's approach).
fun resolveApvFrameHeader(file: File, byteOffset: Long, sizeBytes: Int): ApvFrameHeader? {
    return try {
        ByteReader.open(file).use { reader ->
            val accessUnitBytes = reader.readBytes(byteOffset, sizeBytes)
            val framePayload = findApvPrimaryFramePbuPayload(accessUnitBytes) ?: return@use null
            parseApvFrameHeader(framePayload)
        }
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 6: Register `apv1`/`apvC` in `Decoders.kt`**

In `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`, add after the existing `BoxRegistry.register("av01", VisualSampleEntryDecoder)` line (line 17):

```kotlin
    BoxRegistry.register("apv1", VisualSampleEntryDecoder)
```

And add after the existing `BoxRegistry.register("av1C", Av1CBoxDecoder)` line (line 21):

```kotlin
    BoxRegistry.register("apvC", ApvCBoxDecoder)
```

- [ ] **Step 7: Add APV display name to `MediaSummaryBuilder.kt`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, add `"apv1" to "APV",` to the `CODEC_DISPLAY_NAMES` map (after the existing `"av01" to "AV1",` line, line 10).

- [ ] **Step 8: Compile and run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ApvCBoxDecoder.kt \
        app/src/main/kotlin/com/multiviewer/parser/ApvParameterSetExtraction.kt \
        app/src/main/kotlin/com/multiviewer/parser/Decoders.kt \
        app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/ApvCBoxDecoderTest.kt
git commit -m "Add apvC Structure Analyser box decoder and per-frame extraction"
```

---

### Task 3: `ImageInspectorUI.kt` — "APV Frame Header" Detail Properties section

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`

**Interfaces:**
- Consumes: `resolveApvFrameHeader`, `ApvFrameHeader`, `ApvChromaFormat` (Task 2/Task 1).

No new automated tests in this task — UI wiring only, matching this codebase's established convention (H.264/HEVC/AV1's own Detail Properties wiring tasks also have none).

**Design decision locked in for this task:** unlike H.264/HEVC/AV1, this needs **no `TabState` field and no `LaunchedEffect` in `VideoInspectorUI.kt`** — there's no stream-wide list to pre-parse (see Architecture above). Resolution is entirely local to `DetailPropertiesTabContent`, via `produceState` keyed on `selectedFrame`, mirroring the exact shape of the existing `resolvedH264Params`/`resolvedHevcParams` locals in this same file (read those first for the precise pattern) but simpler (no dependency on any pre-parsed `TabState` list — only `tab.file` and the selected frame's own `byteOffset`/`sizeBytes`).

- [ ] **Step 1: Add the `resolvedApvFrameHeader` local**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, inside `DetailPropertiesTabContent`, immediately after the existing `resolvedHevcParams` block (ending at line 419, right before `LaunchedEffect(tab.selectedFrame) { tab.parameterSetHighlightRange = null }` on line 420), insert:

```kotlin
        val resolvedApvFrameHeader = if (selectedFrame != null) {
            val byteOffset = selectedFrame.byteOffset
            produceState<com.multiviewer.parser.ApvFrameHeader?>(null, selectedFrame) {
                value = if (byteOffset != null) {
                    withContext(Dispatchers.IO) {
                        com.multiviewer.parser.resolveApvFrameHeader(tab.file, byteOffset, selectedFrame.sizeBytes)
                    }
                } else {
                    null
                }
            }.value
        } else {
            null
        }
```

- [ ] **Step 2: Render the "APV Frame Header" section**

In the same file, right after the existing AV1 Frame Header block (the `if (av1SelectedFrameByteOffset != null) { tab.av1FrameHeaders[...]... }` block, ending at line 552, right before the closing `}` of the `item {` block at line 553), insert:

```kotlin
                            resolvedApvFrameHeader?.let { frameHeader ->
                                Spacer(Modifier.height(8.dp))
                                Text("APV Frame Header", style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue))
                                PropertyRow("Profile", frameHeader.profileName ?: frameHeader.profileIdc.toString())
                                PropertyRow("Level", frameHeader.levelIdc.toString())
                                PropertyRow("Band", frameHeader.bandIdc.toString())
                                PropertyRow("Frame Size", "${frameHeader.frameWidth} x ${frameHeader.frameHeight}")
                                PropertyRow(
                                    "Chroma Format",
                                    when (frameHeader.chromaFormat) {
                                        com.multiviewer.parser.ApvChromaFormat.YUV_400 -> "4:0:0"
                                        com.multiviewer.parser.ApvChromaFormat.YUV_422 -> "4:2:2"
                                        com.multiviewer.parser.ApvChromaFormat.YUV_444 -> "4:4:4"
                                        com.multiviewer.parser.ApvChromaFormat.YUV_4444 -> "4:4:4:4"
                                        com.multiviewer.parser.ApvChromaFormat.RESERVED -> "reserved"
                                    },
                                )
                                PropertyRow("Bit Depth", frameHeader.bitDepth.toString())
                                PropertyRow("Tile Grid (Width/Height in MBs)", "${frameHeader.tileWidthInMbs} / ${frameHeader.tileHeightInMbs}")
                                PropertyRow("Tile Count", frameHeader.tileCount.toString())
                                frameHeader.colorPrimaries?.let { PropertyRow("Color Primaries", it.toString()) }
                                frameHeader.transferCharacteristics?.let { PropertyRow("Transfer Characteristics", it.toString()) }
                                frameHeader.matrixCoefficients?.let { PropertyRow("Matrix Coefficients", it.toString()) }
                                frameHeader.fullRangeFlag?.let { PropertyRow("Full Range", if (it) "Yes" else "No") }
                            }
```

- [ ] **Step 3: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 5: Manual verification**

Generate a real APV test file (mirrors exactly what was done during planning):

```bash
curl -sL -o /tmp/apv_test.apv "https://raw.githubusercontent.com/AcademySoftwareFoundation/openapv/main/test/bitstream/qp_D.apv"
ffmpeg -y -i /tmp/apv_test.apv -c copy -frames:v 5 /tmp/apv_test.mp4
```

Launch the app (`./gradlew :app:run`), open `/tmp/apv_test.mp4`, and confirm:
- The video opens without error and shows as "APV" (not a raw `apv1` fourcc or "Unknown") wherever the app displays codec name.
- Structure Analyser: the `apv1` sample-entry node and `apvC` box show real decoded fields (not a generic/unparsed leaf) — `apvC`'s fields should show `profile_idc=33`, `level_idc=123`, `frame_width=3840`, `frame_height=2160`.
- Selecting a frame in the filmstrip/GOP view shows an "APV Frame Header" section in Detail Properties with Profile "422-10", Level 123, Band 2, Frame Size "3840 x 2160", Chroma Format "4:2:2", Bit Depth 10, and a Tile Count of 255.
- Selecting different frames updates the section correctly (each frame's own header, not a cached stale value).
- Opening a non-APV file (any existing supported format) afterward shows no "APV Frame Header" section and no regression in that format's own Detail Properties fields.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Add APV Frame Header section to Detail Properties"
```
