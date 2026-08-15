# AV1 Codec Support — Design Spec

**Goal:** Extend the per-frame parameter-set feature (H.264: `docs/superpowers/specs/2026-08-14-h264-sps-pps-per-frame-design.md`, HEVC: `docs/superpowers/specs/2026-08-15-hevc-sps-pps-per-frame-design.md`) to AV1 streams, in both MP4 and WebM containers: show the stream's sequence header fields (profile/level/bit depth/color config — AV1's closest analog to SPS) plus, for the currently selected frame, that frame's own frame header fields (frame type, quantization, tiling, etc.), in the same Detail Properties location.

**Context:** AV1 differs structurally from H.264/HEVC in ways that break the existing pattern's assumptions:

- AV1 uses **OBUs (Open Bitstream Units)**, not NAL units — a different framing scheme (`leb128()`-encoded `obu_size`, no emulation-prevention bytes). The existing `BitReader` (`parser/BitReader.kt`) is codec-agnostic bit-level I/O and is reused as-is; `removeEmulationPreventionBytes` (`parser/NalEmulationPrevention.kt`) is H.264/HEVC-specific and is **not** reused.
- There is no PPS-style id-cross-referenced parameter set per frame. There's one `sequence_header_obu` (stream-wide, like SPS) and, optionally, one `frame_header_obu` per frame. KEY_FRAMEs' headers are fully self-contained; INTER frames' headers require reference-frame state (`RefOrderHint[]`, `RefFrameId[]`, `RefFrameType[]`) accumulated sequentially from prior frames per the AV1 spec's §7.20 reference frame update process — they cannot be parsed in isolation the way H.264/HEVC's PPS-id lookup can.
- AV1 is commonly delivered in **WebM/Matroska**, a structurally separate container implementation in this codebase (`EbmlWalker.kt`) from the ISOBMFF box walker (`BoxWalker.kt`) that H.264/HEVC's `avcC`/`hvcC` extraction relies on. This feature covers both containers.
- Conveniently, WebM's `CodecPrivate` for an AV1 track (`CodecID == "V_AV01"`) is defined as exactly the same binary layout as MP4's `av1C` box payload (the `AV1CodecConfigurationRecord`), so the sequence-header payload parser is shared between both containers — only the container-level *extraction* (locating those bytes) differs.
- No `av1C` box decoder exists yet anywhere in this codebase; `av01`/`av1C` currently render as generic/unparsed nodes in Structure Analyser (explicitly noted as out-of-scope in `docs/superpowers/plans/2026-07-21-tiff-avif-support*.md`).

## Scope

- AV1 in **MP4/ISOBMFF** (`av1C` box) and **WebM/Matroska** (`CodecPrivate` on a `V_AV01` track).
- Two independent display sections, gated separately: **AV1 Sequence Header** (stream-wide, always the same regardless of selected frame) and **AV1 Frame Header** (per selected frame, requires a full sequential whole-stream pass — see below).
- Frame headers are parsed for **every frame**, key or inter, via one sequential pass per tab that maintains AV1's reference-frame state as it walks frames in decode order — not the lazy per-selected-frame resolution H.264/HEVC use, since inter-frame fields aren't resolvable in isolation.
- Hex-viewer click-to-jump: only the **Sequence Header** section gets a clickable row (mirrors the SPS/PPS/VPS id-row pattern). The Frame Header section is not clickable — it's already within the currently-selected frame's own bytes, reachable via existing frame-row navigation.
- A new `Av1CBoxDecoder` for Structure Analyser (mirrors `AvcCBoxDecoder`/`HvcCBoxDecoder`), replacing `av1C`'s current generic-leaf rendering with an actual field breakdown, for parity with the other two codecs' box decoders.
- Curated field sets only (matching H.264/HEVC's curation depth), not exhaustive spec coverage — see Data model below and Out of scope.

## Components

### 1. Reused as-is

- `BitReader` (`parser/BitReader.kt`) — `readBits`/`readFlag`, both fully generic MSB-first bit I/O; AV1's `f(n)` and `uvlc()` primitives layer directly on top.

### 2. `Av1Obu.kt` (new) — OBU framing

```kotlin
data class ObuHeader(
    val obuType: Int, val extensionFlag: Boolean, val hasSizeField: Boolean,
    val temporalId: Int, val spatialId: Int,
)
fun leb128(reader: BitReader): Long   // AV1 spec 4.10.5, little-endian base-128
fun parseObuHeader(reader: BitReader): ObuHeader
```
No emulation-prevention step — OBU bytes are used as-is. `obu_size` (when `hasSizeField`) is read via `leb128()` immediately after the header (and after the optional extension byte).

### 3. `Av1SequenceHeader.kt` (new) — sequence header field parser

```kotlin
data class Av1SequenceHeader(
    val seqProfile: Int, val stillPicture: Boolean,
    val seqLevelIdx0: Int, val seqTierIdx0: Int,
    val bitDepth: Int, val monochrome: Boolean,
    val chromaSubsamplingX: Int, val chromaSubsamplingY: Int,
    val colorPrimaries: Int, val transferCharacteristics: Int, val matrixCoefficients: Int,
    val maxFrameWidth: Int, val maxFrameHeight: Int,
    val use128x128Superblock: Boolean,
    val filmGrainParamsPresent: Boolean,
)
fun parseAv1SequenceHeader(obuPayload: ByteArray): Av1SequenceHeader?
```
Parses the `sequence_header_obu()` syntax directly (AV1 spec §5.5): `seq_profile`, `still_picture`, `reduced_still_picture_header` (bails to null if set — a further-reduced field layout, uncommon for consumer content, same bail-out philosophy as the HEVC sub-layer PTL case), operating-point loop (only operating point 0's level/tier are curated), `frame_width_bits`/`frame_height_bits`-driven max frame dimensions, `color_config()` (bit depth, monochrome, subsampling, primaries/transfer/matrix), and `film_grain_params_present`.

### 4. `Av1FrameHeader.kt` (new) — frame header field parser + reference state

```kotlin
data class Av1FrameHeader(
    val frameType: Av1FrameType, // KEY, INTER, INTRA_ONLY, SWITCH
    val showFrame: Boolean, val showableFrame: Boolean,
    val frameWidth: Int, val frameHeight: Int,
    val baseQIdx: Int,
    val tileCols: Int, val tileRows: Int,
    val refreshFrameFlags: Int,
    val orderHint: Int,
)

class Av1RefFrameState {
    // RefOrderHint[8], RefFrameId[8], RefFrameType[8] -- AV1 spec §7.20 reference frame update process
    fun parseNextFrameHeader(payload: ByteArray, seqHeader: Av1SequenceHeader): Av1FrameHeader?
    // parses one frame_header_obu() against current state, then updates state per refresh_frame_flags
}
```
`Av1RefFrameState` is mutable and stateful by design — it must be driven strictly in decode order, one frame at a time, matching how the AV1 spec itself defines reference frame management as a running process. It is not safe to call `parseNextFrameHeader` out of order or skip frames.

### 5. `Av1ParameterSetExtraction.kt` (new) — container-level extraction

```kotlin
data class RawAv1SequenceHeader(val bytes: ByteArray, val offset: Long)  // mirrors RawNal

// MP4: walks the av1C box's fixed header + configOBUs field (AV1 spec's
// "AV1 Codec ISO Media File Format Binding", section 2.2), extracting the sequence_header_obu
// and its absolute file offset.
fun extractAv1CRawSequenceHeader(file: File, av1CNode: BoxNode): RawAv1SequenceHeader?

// WebM: finds the TrackEntry with CodecID == "V_AV01" in EbmlWalker's parsed tree, reads its
// CodecPrivate element (same AV1CodecConfigurationRecord binary layout as av1C's payload).
fun extractWebmAv1RawSequenceHeader(file: File, root: EbmlNode): RawAv1SequenceHeader?
```
Both funnel their extracted `configOBUs` bytes into the same `parseAv1SequenceHeader`.

**Open item:** `extractWebmAv1RawSequenceHeader`'s file-offset capture (needed for hex-jump) depends on `EbmlWalker` exposing element byte offsets. This needs verification during implementation — if `EbmlWalker` doesn't currently track offsets, either a small preceding addition to it is needed, or the WebM path ships without hex-jump for its first version (parsing/display still work either way).

### 6. `Av1FrameHeaderAnalyzer.kt` (new) — sequential whole-stream pass

```kotlin
fun analyzeAv1FrameHeaders(
    file: File, frames: List<FrameInfo>, seqHeader: Av1SequenceHeader,
): Map<Long, Av1FrameHeader>   // keyed by FrameInfo.byteOffset
```
Walks `frames` (already in decode order, from the existing `FrameTypeAnalyzer` pass) sequentially, reading a bounded prefix (a few KB — enough for any header, not the full frame) of each frame's sample bytes, and calls `Av1RefFrameState.parseNextFrameHeader` once per frame. A parse failure on one frame doesn't abort the pass — that frame is simply absent from the returned map, and the state carries forward best-effort for subsequent frames. Runs off the main/UI thread (this is meaningfully more I/O than the existing lazy per-frame resolution, since every frame's leading bytes are read once per tab open).

### 7. `TabState` additions (`AppState.kt`)

```kotlin
var av1SequenceHeader: Av1SequenceHeader? by mutableStateOf(null)
var av1SequenceHeaderOffset: LongRange? by mutableStateOf(null)
var av1FrameHeaders: Map<Long, Av1FrameHeader> by mutableStateOf(emptyMap())
```

### 8. `VideoInspectorUI.kt` wiring

A third `LaunchedEffect(tab.root)`, parallel to the existing `avcC`/`hvcC` ones, gated on `findFirst(root) { it.type == "av1C" }` (MP4) or a WebM-specific check for a `V_AV01` track. Populates `av1SequenceHeader`/`av1SequenceHeaderOffset` via extraction + `parseAv1SequenceHeader`, then kicks off `analyzeAv1FrameHeaders` to populate `av1FrameHeaders`.

### 9. `ImageInspectorUI.kt` UI wiring (`DetailPropertiesTabContent`)

- "AV1 Sequence Header" section: renders when `tab.av1SequenceHeader != null`, `PropertyRow`s for the curated fields, with one row's `onClick` set to `{ tab.parameterSetHighlightRange = tab.av1SequenceHeaderOffset }` (same mechanism as the existing SPS/PPS/VPS id-row jump).
- "AV1 Frame Header" section: renders when `tab.av1FrameHeaders[selectedFrame.byteOffset] != null`, plain `PropertyRow`s (no `onClick`).
- Both are independently gated; at most one codec's sections show for a given stream, same as the existing H.264-vs-HEVC coexistence.

### 10. `Av1CBoxDecoder.kt` (new) — Structure Analyser parity

Mirrors `AvcCBoxDecoder.kt`/`HvcCBoxDecoder.kt`: parses `av1C`'s fixed header fields (marker, version, seq_profile, seq_level_idx_0, seq_tier_0, high_bitdepth, twelve_bit, monochrome, chroma_subsampling_x/y, chroma_sample_position) for display as a generic box-tree node, registered in `Decoders.kt` for the `av1C` box type. Independent of the per-frame extraction path (which separately reads the same box's `configOBUs` field), matching the existing avcC/hvcC precedent of two parallel, purpose-specific readers of the same box.

## Error handling

Same convention as H.264/HEVC: any parsing step that hits malformed data, an unsupported syntax element (e.g. `reduced_still_picture_header`), or runs past available bits returns `null`/omits that frame from the map rather than throwing. A missing or empty `av1C`/`CodecPrivate` `configOBUs` field (both containers allow this) is treated as "no sequence header available" — the section doesn't render, same as a stream with no `avcC`/`hvcC`.

## Testing

Unlike the H.264/HEVC specs, this design was **not** validated against real captured bitstream bytes during brainstorming — no AV1 encode was available in this session. The implementation plan needs to:

- Acquire a real AV1-encoded test file (both an MP4/`av1C` sample and a WebM/`CodecPrivate` sample) and cross-verify every curated field against a reference tool's independent output. `ffmpeg -bsf:v trace_headers` does **not** support AV1 — use `aomdec --verbose` or `dav1d`'s header-dump output instead (confirm tool availability in the dev environment first).
- For the sequential-pass / reference-state logic specifically (the one part of this feature with no H.264/HEVC precedent), a short multi-frame GOP fixture (one key frame + a couple of inter frames) is needed to verify reference state actually carries forward correctly across frames — a single-frame fixture can't exercise this.
- `Av1SequenceHeader`/`Av1FrameHeader`/`Av1Obu` unit tests follow the existing convention: real, hand-verified byte fixtures, one assertion per curated field, plus null-on-malformed-input cases.
- `Av1CBoxDecoder` and the extraction functions get synthetic byte-fixture tests (matching the avcC/hvcC extraction test convention) for the container-level framing logic (leb128 sizes, box header layout, WebM element walking).

## Out of scope (deferred)

- `reduced_still_picture_header` sequence headers.
- Non-zero operating points (only operating point 0's level/tier are curated).
- Segmentation and loop-filter delta fields on the frame header (`FeatureEnabled`/`FeatureData`, ref deltas) — these also carry state across frames and add real parsing surface without being what's typically checked first; can follow up later if wanted.
- Film grain parameter fields (only the presence flag is curated).
- Superres-specific fields.
- A tree-node/hex-highlight presentation beyond the single Sequence Header jump row — Frame Header fields show as plain `PropertyRow`s only.
