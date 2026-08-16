# APV Codec Support — Design Spec

**Goal:** Extend the per-frame parameter-set feature (H.264: `docs/superpowers/specs/2026-08-14-h264-sps-pps-per-frame-design.md`, HEVC: `docs/superpowers/specs/2026-08-15-hevc-sps-pps-per-frame-design.md`, AV1: `docs/superpowers/specs/2026-08-15-av1-codec-support-design.md`) to APV (Advanced Professional Video, ISO/IEC 23056 / RFC 9924) streams in MP4 containers: recognize `apv1`/`apvC` in Structure Analyser with real field decoding (replacing today's generic/unparsed leaf-node rendering), and show the selected frame's own `frame_header()` fields in Detail Properties.

**Context:** APV is a professional, intra-frame-only mezzanine codec (positioned similarly to Apple ProRes/Avid DNxHD) recently added to ffmpeg (decoder present on this dev machine; no encoder without `libopenapv`, confirmed via `ffmpeg -encoders`/`ffmpeg -h encoder=apv`). No file in this codebase currently mentions APV; `apv1`/`apvC` boxes render as generic/unparsed nodes today, exactly the state `av01`/`av1C` were in before the AV1 work.

APV differs structurally from every codec this app already supports in one way that **simplifies** the design versus AV1: **there is no sequence-header concept**. APV's bitstream syntax (verified against RFC 9924, the reference implementation `AcademySoftwareFoundation/openapv`, and a real downloaded/remuxed APV file — see Technical Foundation) has no separate "sequence header" PBU type; every `frame_header()` is fully self-contained per frame, with no persistent state (no reference-frame tracking, no cross-frame lookups) the way AV1's inter frames or H.264/HEVC's PPS-id references require. This means APV's per-frame resolution can follow **H.264's simpler lazy-per-selected-frame pattern** (parse just the selected frame's own bytes, on demand, no whole-stream sequential pass, no stateful analyzer) rather than AV1's mandatory eager `Av1FrameHeaderAnalyzer`-style whole-stream pass.

## Scope

- APV in **MP4/ISOBMFF only** — `apv1` sample entry + `apvC` (`APVCodecConfigurationBox`) config box. No WebM/Matroska support: APV is a production/camera-workflow codec (verified via RFC 9924 §1, "professional level high quality video recording and post production"), not a web-streaming codec, and isn't a realistic WebM use case — unlike AV1, which explicitly needed both containers.
- A new `ApvCBoxDecoder` for Structure Analyser (mirrors `Av1CBoxDecoder`/`AvcCBoxDecoder`/`HvcCBoxDecoder`), replacing `apvC`'s current generic-leaf rendering with a real field breakdown of the `APVDecoderConfigurationRecord` (profile/level/band/dimensions/chroma/bit-depth per configuration entry).
- `"apv1"` registered against the existing generic `VisualSampleEntryDecoder` (same as `av01`/`avc1`/`hvc1` — no APV-specific sample-entry fields beyond what that shared decoder already shows).
- One Detail Properties section, **"APV Frame Header"**, rendered when a frame is selected on an APV stream: curated `frame_header()` fields (see `ApvFrameHeader` in Components below), resolved lazily per selected frame — no stateful whole-stream pass needed, since every frame's header is self-contained.
- Only the **primary frame** (`pbu_type == 1`) within an access unit is parsed. An access unit may also contain non-primary/preview/depth/alpha frame PBUs (`pbu_type` 2, 25–27 per RFC 9924 Table 3) — these are out of scope; this app's frame list (from the existing `FrameTypeAnalyzer`/track-sample enumeration) already only enumerates one entry per video sample, matching the primary frame.
- Curated field set (matching every other codec's curation depth), not exhaustive coverage:
  - **From `frame_info()`:** `profile_idc` (shown as a named profile — see mapping below, verified against `openapv`'s `inc/oapv.h`, not fabricated), `level_idc`, `band_idc` (raw number 0–3; no official name-per-band mapping exists in RFC 9924 or `openapv`'s public headers, verified by direct source inspection — see Technical Foundation), `frame_width`, `frame_height`, `chroma_format_idc` (named: 4:0:0/4:2:2/4:4:4/4:4:4:4), `bit_depth_minus8 + 8` (actual bit depth).
  - **From `frame_header()`, when present:** `color_primaries`/`transfer_characteristics`/`matrix_coefficients`/`full_range_flag` (only when `color_description_present_flag` is set).
  - **From `tile_info()`:** `tile_width_in_mbs`, `tile_height_in_mbs`, and a derived tile count (columns × rows from frame dimensions ÷ tile dimensions) — not the full per-tile `ColStarts`/`RowStarts` arrays or `tile_size_in_fh[]` byte offsets.
- No hex-viewer click-to-jump for this iteration (matches AV1's Frame Header section, which also has none — only AV1's stream-wide Sequence Header got a jump row, and APV has no stream-wide section at all to jump to).

## Technical Foundation

**Verified against a real APV file**, resolving the exception noted during brainstorming (this dev machine's ffmpeg has APV decode support but no encoder). `AcademySoftwareFoundation/openapv`'s GitHub repo publishes real raw `.apv` bitstream test vectors (`test/bitstream/*.apv`); `qp_D.apv` (1.8MB, downloaded directly during planning) was hand-decoded byte-by-byte against the syntax below and cross-validated three independent ways:

1. **Hand-decoding matches internally.** Bytes `21 7b 40 00 0f 00 00 08 70 22 00 00` (the `frame_info()` for the first frame) decode to `profile_idc=33`, `level_idc=123`, `band_idc=2`, `frame_width=3840`, `frame_height=2160`, `chroma_format_idc=2`, `bit_depth_minus8=2`. `profile_idc=33` is named `"422-10"` in the mapping below — and the independently-decoded `chroma_format_idc=2` ("4:2:2") and `bit_depth=10` (`2+8`) are *exactly* what a stream literally named "422-10" should have. All three fields agree with each other despite being read from separate bit positions — strong evidence the field widths/offsets above are correct, not coincidentally plausible.
2. **ffmpeg's own probe agrees independently.** `ffmpeg -i qp_D.apv` reports `3840x2160`, `yuv422p10le`, matching the hand-decode exactly (ffmpeg's APV decoder exists on this machine even without an encoder, so this cross-check was available). `ffprobe -show_streams` on a remuxed copy additionally reports `profile=33, level=123`, again matching.
3. **The MP4-container open item (raised during brainstorming) is now resolved, not just theorized.** `ffmpeg -i qp_D.apv -c copy out.mp4` (bitstream copy, no re-encode — ffmpeg has an `apv1` muxer even without an `apv` encoder) produced a real `apv1`-tagged MP4. Its first `mdat` sample's bytes were byte-for-byte **identical** to the raw `.apv` file's own leading bytes (both starting `00095f7c 61507631 00095f26 01000100 217b4000 0f000008 7022...`) — confirmed via a direct Python byte comparison, not inspection by eye. **This means an MP4 sample is the verbatim raw access unit, including its own leading 4-byte length field and the `'aPv1'` signature — nothing is stripped or reformatted for MP4 storage.** The same parser handles both the raw elementary-stream form and the MP4-sample form with zero special-casing; earlier concern about a possible offset difference between the two was unfounded.
4. The `apvC` box was also located and extracted from the same remuxed MP4 (payload `000000000101010101217b0200000f00000008702200`, 22 bytes) — it visibly contains `21 7b` (`profile_idc`/`level_idc`, same 33/123 values) plus `0f 00 00 08 70` (the same 3840/2160 dimensions) embedded within it, confirming the box does redundantly carry `frame_info()`-shaped fields as documented below. Exact byte-for-field mapping of every `apvC` field is left to be pinned down with a proper fixture-based unit test in the implementation plan (this check only needed to confirm the box exists, is reachable, and contains the expected values somewhere in its payload) — not a remaining open risk, just normal implementation-time precision.

**Bitstream nesting (RFC 9924 §5.3.1–5.3.6), from outermost to innermost:**
```
access_unit(au_size) {
    signature                 f(32)   // 'aPv1' = 0x61507631
    do {
        pbu_size               u(32)  // byte length of the following pbu()
        pbu()
    } while (more data)
}

pbu() {
    pbu_header() {
        pbu_type                u(8)  // 1=primary frame, 2=non-primary, 25-27=preview/depth/alpha, 65-67=au_info/metadata/filler
        group_id                 u(16)
        reserved_zero_8bits       u(8)
    }
    if (pbu_type in {1,2,25,26,27}) frame() { frame_header(); <tile data>; filler() }
    else if (pbu_type == 65) au_info()
    else if (pbu_type == 66) metadata()
    else if (pbu_type == 67) filler()
}

frame_header() {
    frame_info() {
        profile_idc              u(8)
        level_idc                 u(8)
        band_idc                  u(3)
        reserved_zero_5bits       u(5)
        frame_width               u(24)
        frame_height              u(24)
        chroma_format_idc         u(4)
        bit_depth_minus8          u(4)
        capture_time_distance     u(8)
        reserved_zero_8bits       u(8)
    }
    reserved_zero_8bits
    color_description_present_flag   u(1)
    if (present) { color_primaries u(8); transfer_characteristics u(8); matrix_coefficients u(8); full_range_flag u(1) }
    use_q_matrix                     u(1)
    if (use_q_matrix) quantization_matrix()   // not curated -- see Out of scope
    tile_info() {
        tile_width_in_mbs               u(20)
        tile_height_in_mbs              u(20)
        tile_size_present_in_fh_flag    u(1)
        if (present) tile_size_in_fh[i] u(32)  // per tile -- not curated
    }
    reserved_zero_8bits
    byte_alignment()
}
```
Confirmed intra-frame-only per RFC 9924 §1: "Low complexity and high throughput intra frame only coding without inter frame coding" — no reference-frame or motion-compensation syntax exists anywhere in the bitstream, confirming the lazy-per-frame (not stateful sequential-pass) design above is correct, not just simpler.

**`profile_idc` → name mapping** (RFC 9924 §9.3 names the profiles but doesn't publish numeric assignments in the fetched text; the actual integers come from `openapv`'s `inc/oapv.h`, fetched directly from `AcademySoftwareFoundation/openapv`'s `main` branch):
```
33 -> "422-10"    44 -> "422-12"    55 -> "444-10"    66 -> "444-12"
77 -> "4444-10"   88 -> "4444-12"   99 -> "400-10"
140 -> "444-16C12"   144 -> "4444-16C12"
```
Any other value: shown as a raw number with no name (covers the `openapv`-specific `*_UNCONST` profile extensions, which aren't part of the public RFC and shouldn't be presented as if they were standard).

**`chroma_format_idc` → name mapping** (RFC 9924 Table 2, confirmed): `0` = 4:0:0, `2` = 4:2:2, `3` = 4:4:4, `4` = 4:4:4:4. Values `1`, `5`–`7` are reserved — shown as a raw number.

**`band_idc`**: valid range 0–3 (RFC 9924 §5.3.6: "MUST be in the range of 0 to 3"). No public name-per-value mapping exists in the RFC or in `openapv`'s headers (confirmed by direct grep of `inc/oapv.h` — only an unrelated encoder-param sentinel `OAPVE_PARAM_BAND_IDC_AUTO = 4` exists, which is not a bitstream value). Shown as a raw number; do not fabricate band names.

**ISOBMFF container mapping** (from `openapv`'s `readme/apv_isobmff.md`, confirmed against the real remuxed MP4 above): sample entry fourcc is **`apv1`** (`APV1SampleEntry`); the config box is **`apvC`** (`APVCodecConfigurationBox`, containing an `APVDecoderConfigurationRecord` with per-configuration-entry `profile_idc`/`level_idc`/`band_idc`/`frame_width`/`frame_height`/`chroma_format_idc`/`bit_depth_minus8` — the same `frame_info()` fields as the bitstream itself, present redundantly for fast container-level inspection). Each MP4 sample holds one access unit, **verbatim** — the sample's bytes are byte-for-byte identical to the raw elementary-stream form (leading 4-byte length field, `'aPv1'` signature, and all — see point 3 above). Container-level per-frame extraction therefore needs no MP4-specific offset handling: read the sample's raw bytes and hand them to the exact same parsing path used for a raw `.apv` stream.

## Components

### 1. Reused as-is

- `BitReader` (`parser/BitReader.kt`) — same generic MSB-first bit I/O AV1 already reuses; APV's `u(n)` primitives layer directly on top, no new bit-reading primitive needed (APV has no Exp-Golomb fields, unlike H.264/HEVC — every field above is a fixed-width `u(n)`).
- `VisualSampleEntryDecoder` — registered for `"apv1"` in `Decoders.kt` alongside the existing `"avc1"`/`"hvc1"`/`"av01"` registrations.

### 2. `ApvPbu.kt` (new) — access-unit / PBU framing

```kotlin
data class ApvPbuHeader(val pbuType: Int, val groupId: Int)
fun parseApvPbuHeader(reader: BitReader): ApvPbuHeader
// Locates the first pbu_type == 1 (primary frame) PBU within one access unit's bytes, per the
// [pbu_size u(32)][pbu_header][payload] loop (RFC 9924 §5.3.1/§5.3.2), returning that PBU's
// frame() payload bytes (header + tile data together; the caller only parses the header prefix).
fun findApvPrimaryFramePbuPayload(accessUnitBytes: ByteArray): ByteArray?
```

### 3. `ApvFrameHeader.kt` (new) — frame header field parser

```kotlin
enum class ApvChromaFormat { MONOCHROME, YUV_422, YUV_444, YUV_4444, RESERVED }

data class ApvFrameHeader(
    val profileIdc: Int, val profileName: String?,  // null if not in the known-name table above
    val levelIdc: Int, val bandIdc: Int,
    val frameWidth: Int, val frameHeight: Int,
    val chromaFormat: ApvChromaFormat, val bitDepth: Int,
    val colorPrimaries: Int?, val transferCharacteristics: Int?, val matrixCoefficients: Int?, val fullRangeFlag: Boolean?,
    val tileWidthInMbs: Int, val tileHeightInMbs: Int, val tileCount: Int,
)
fun parseApvFrameHeader(framePayload: ByteArray): ApvFrameHeader?
```
Pure function: parses `frame_header()` directly per the syntax in Technical Foundation, stopping right after `tile_info()` — never touches tile/coefficient data. Returns `null` on any malformed/truncated input (matching every other parser in this codebase), including a `use_q_matrix` value it doesn't need to fully skip correctly on the first pass (see Out of scope: `quantization_matrix()` byte length must still be known to reach `tile_info()` — if this proves non-trivial to skip without full parsing, this is a genuine risk the implementation plan must resolve, e.g. by checking whether `use_q_matrix` is realistically always `0` for typical production content, or by parsing the matrix structure enough to skip it correctly).

### 4. `ApvParameterSetExtraction.kt` (new) — container/per-frame extraction

```kotlin
// MP4: walks the apvC box's APVDecoderConfigurationRecord for Structure Analyser use (see
// ApvCBoxDecoder below) -- independent of the per-frame path, mirroring how avcC/hvcC/av1C's
// config-box reader and their per-frame extraction are two separate, purpose-specific readers of
// the same underlying box (AV1 spec's own precedent, Av1CBoxDecoder vs Av1ParameterSetExtraction).

// Per-frame: given a FrameInfo's byteOffset/sizeBytes, reads that MP4 sample's raw bytes (one
// access unit), locates the primary-frame PBU via findApvPrimaryFramePbuPayload, and parses it.
// Lazy, on-demand, no whole-stream pass -- see Context for why this is sufficient (unlike AV1).
fun resolveApvFrameHeader(file: File, byteOffset: Long, sizeBytes: Int): ApvFrameHeader?
```

### 5. `TabState` additions (`AppState.kt`)

```kotlin
// No stream-wide/sequence-level field -- APV has none (see Context). Only a per-frame cache,
// populated lazily as frames are selected (mirrors how H.264's avcSpsList/avcPpsList are parsed
// once but PPS *resolution* per frame is lazy; here even the parse itself is fully lazy per frame,
// since there's no shared parameter-set list to pre-parse).
var apvFrameHeaderCache: MutableMap<Long, ApvFrameHeader?> by mutableStateOf(mutableMapOf())
```

### 6. `VideoInspectorUI.kt` / `ImageInspectorUI.kt` wiring

No `LaunchedEffect` needed at tab-open time (unlike AV1/H.264, which pre-parse a stream-wide parameter-set list) — `DetailPropertiesTabContent` calls `resolveApvFrameHeader` directly when a frame is selected on an APV stream (detected via the existing `videoCodecName`-style codec-name check, extended to recognize `"apv1"`/`"APV"` from `MediaSummaryBuilder.kt`'s fourcc-to-name mapping), caching the result in `apvFrameHeaderCache` keyed by `byteOffset` so re-selecting an already-seen frame doesn't re-read/re-parse the file. Renders as an "APV Frame Header" section of plain `PropertyRow`s (no `onClick`/hex-jump), same rendering shape as AV1's Frame Header section.

### 7. `ApvCBoxDecoder.kt` (new) — Structure Analyser parity

Mirrors `Av1CBoxDecoder.kt`: parses the `apvC` box's `APVDecoderConfigurationRecord` (configuration version + per-entry `profile_idc`/`level_idc`/`band_idc`/`frame_width`/`frame_height`/`chroma_format_idc`/`bit_depth_minus8`, per Technical Foundation) for display as a box-tree node, registered in `Decoders.kt` for the `"apvC"` box type.

## Error Handling

Same convention as every other codec parser in this app: any malformed/truncated input, an unresolvable primary-frame PBU, or a `use_q_matrix`-present frame this parser can't safely skip past returns `null` — the "APV Frame Header" section simply doesn't render for that frame, exactly like AV1's Frame Header section already handles a parse failure on an individual frame (that frame absent from the map) and H.264's per-frame PPS/SPS resolution failure (section doesn't render).

## Testing

- Real ground-truth bytes are now available: `qp_D.apv` (downloaded from `openapv`'s `test/bitstream/`) and its ffmpeg-remuxed `apv1` MP4 form (see Technical Foundation) — both byte-identical for sample framing. The implementation plan should re-download `qp_D.apv` (or another `test/bitstream/*.apv` file) and derive its test fixtures directly from real bytes, the same way the H.264 plan's fixtures came from a real generated file, rather than hand-constructing synthetic bytes from the syntax tables alone.
- `ApvPbu.kt`/`ApvFrameHeader.kt` unit tests follow this codebase's established convention: real byte fixtures (sliced from `qp_D.apv`'s first access unit, whose exact bytes and decoded field values are already recorded in Technical Foundation above), one assertion per curated field, plus null-on-malformed-input cases (truncated PBU, unrecognized `pbu_type`, out-of-range `chroma_format_idc`).
- `ApvCBoxDecoder` and the container-extraction functions get tested against the real remuxed MP4's actual `apvC`/`apv1` box bytes (also already extracted and recorded in Technical Foundation above) for the box-layout logic, following the `avcC`/`hvcC`/`av1C` extraction test convention.
- Manual verification: open a real APV MP4 (e.g. ffmpeg-remuxed from an `openapv` test-bitstream file, as done during brainstorming), select frames, confirm shown fields are internally consistent (e.g. `frame_width`/`frame_height` matching the container's own track dimensions) and match `ffprobe -show_streams`'s independently-reported `profile`/`width`/`height`/`pix_fmt` for the same file.

## Out of Scope (Deferred)

- `quantization_matrix()` field-level parsing — only enough to determine its byte length to skip past it (or, if that proves non-trivial, `use_q_matrix == true` frames simply return `null` for their header, same bail-out philosophy as H.264's SPS scaling-matrix case).
- Full per-tile detail (`ColStarts`/`RowStarts` derivation, `tile_size_in_fh[]` per-tile byte offsets) — only tile grid dimensions and a derived count.
- Non-primary/preview/depth/alpha frame PBU types (`pbu_type` 2, 25–27) — primary frame only.
- `au_info()`/`metadata()`/`filler()` PBU types (`pbu_type` 65–67) — not exposed anywhere in the UI.
- WebM/Matroska container support — not a realistic APV use case (see Context).
- A tree-node/hex-highlight presentation beyond `ApvCBoxDecoder`'s existing Structure Analyser node — Frame Header fields show as plain `PropertyRow`s only, no click-to-jump (matches AV1 Frame Header's same limitation).
- Pixel/tile/coefficient decoding of any kind.
