# HEVC VPS/SPS/PPS Per-Frame Info — Design Spec

**Goal:** Extend the H.264 per-frame parameter-set feature (`docs/superpowers/specs/2026-08-14-h264-sps-pps-per-frame-design.md`) to HEVC streams: when a frame is selected in an HEVC video, resolve and show the actual VPS/SPS/PPS fields that frame's own slice segment header references, in the same Detail Properties location, alongside the existing Frame #/Type/Size/PTS/Byte Offset/GOP Position rows.

**Context:** This is a direct follow-up to the merged H.264 feature (`c37a652`), explicitly deferred at the time ("HEVC — separate follow-up once H.264 ships and is validated"). `BitReader` (`parser/BitReader.kt`) and `removeEmulationPreventionBytes` (`parser/NalEmulationPrevention.kt`) are both codec-agnostic (Annex B / Exp-Golomb bitstream mechanics, not H.264-specific) and are reused as-is — no new bit-level infrastructure is needed. `HvcCBoxDecoder.kt` already parses the video track's `hvcC` box structure (counts only); `HeifHevcThumbnail.kt`'s private `readHvcCInfo` already walks the identical `hvcC` array-of-arrays structure to collect raw VPS/SPS/PPS NAL bytes (for a different purpose — feeding a HEIF image item to ffmpeg) and is the direct structural pattern for this feature's own extraction, the same way `AvcCBoxDecoder.kt` was the pattern (not the source) for the H.264 feature's `extractAvcCRawParameterSets`.

## Scope

- HEVC only, added alongside (not replacing) the existing H.264 support — both sections are independently gated on their own tab state and never both show for the same stream.
- Core fields only, matching the H.264 feature's curation depth:
  - **VPS**: `vps_video_parameter_set_id`, `vps_max_sub_layers_minus1`, profile/tier/level (`general_profile_space`, `general_tier_flag`, `general_profile_idc`, `general_level_idc`, from `profile_tier_level()`).
  - **SPS**: `sps_seq_parameter_set_id`, `sps_video_parameter_set_id` (links to VPS), `sps_max_sub_layers_minus1`, profile/tier/level (same fields as VPS, from its own `profile_tier_level()`), `chroma_format_idc`, `pic_width_in_luma_samples`, `pic_height_in_luma_samples`, `bit_depth_luma_minus8`/`bit_depth_chroma_minus8`, VUI's `video_full_range_flag`, `colour_primaries`, `transfer_characteristics`, `matrix_coefficients` (when `vui_parameters_present_flag` and `colour_description_present_flag`).
  - **PPS**: `pps_pic_parameter_set_id`, `pps_seq_parameter_set_id` (links to SPS), `dependent_slice_segments_enabled_flag`, `sign_data_hiding_enabled_flag`, `cabac_init_present_flag`, `constrained_intra_pred_flag`, `transform_skip_enabled_flag`, `cu_qp_delta_enabled_flag`, `weighted_pred_flag`, `weighted_bipred_flag`, `tiles_enabled_flag`, `entropy_coding_sync_enabled_flag`, `deblocking_filter_control_present_flag` (+ `pps_deblocking_filter_disabled_flag` when present).
  - `profile_tier_level()`'s 32 individual `general_profile_compatibility_flag[i]` bits and 4+44 constraint/reserved bits are **not** individually exposed — confirmed via real `trace_headers` output that this "general" portion is a fixed 96 bits (bit 24 to bit 120 in both VPS and SPS in the verified sample file) when `max_sub_layers_minus1 == 0`, so the shared parser reads the 4 curated fields and skips the rest by fixed bit-count, without needing to decode each flag individually.
  - Two further uncommon SPS structures are also unsupported, bailing out the same way as the sub-layer PTL case (`parseHevcSps` returns `null`): more than one `short_term_ref_pic_set()` (parsing entries after index 0 requires inter-set prediction support; index 0 itself is always simple since `inter_ref_pic_set_prediction_flag` only exists for `stRpsIdx != 0`, so exactly zero or one short-term set is supported), and an explicit `scaling_list_data()` (i.e. `scaling_list_enabled_flag && sps_scaling_list_data_present_flag`). All other SPS structures before the curated VUI fields (`conformance_window`, PCM fields, long-term reference picture info) are fully parsed (skipped, not curated) since they're fixed- or simply-computed-width with no further bail-out risk. The real verified sample has exactly one short-term set and no scaling list data, so none of these bail-outs trigger for it.
  - `profile_tier_level()`'s **sub-layer** profile/level blocks (present only when `max_sub_layers_minus1 > 0`) are **not** supported — mirrors the H.264 feature's scaling-matrix bail-out precedent. If encountered, parsing stops there rather than risking silently wrong values for fields after it. For **VPS**, this yields a partial result (`vpsId`/`maxSubLayersMinus1` are read *before* `profile_tier_level()`, so they're always valid; only `ptl` is absent). For **SPS**, `profile_tier_level()` sits *before* `sps_seq_parameter_set_id` in the bitstream, so an unsupported PTL makes the SPS id itself unrecoverable — `parseHevcSps` returns `null` outright in that case rather than a partial object, since a SPS without a reliable id can't be matched during per-frame resolution anyway. This only affects temporally-scalable HEVC streams (uncommon for consumer-generated files; the verified real sample has `max_sub_layers_minus1 == 0`).
- Resolves the frame's actual referenced parameter-set chain by parsing just enough of that frame's own slice segment header (`first_slice_segment_in_pic_flag`, `no_output_of_prior_pics_flag` when IRAP, `slice_pic_parameter_set_id` — all before any CABAC-coded data) to get the PPS id, then follows `pps_seq_parameter_set_id` → SPS and `sps_video_parameter_set_id` → VPS. VPS resolution is best-effort: if the VPS id isn't found in the parsed list, the SPS/PPS fields still show (VPS section omitted) since VPS carries the least frame-relevant information of the three.

## Technical foundation (verified against a real file)

Extracted real VPS/SPS/PPS/slice-header bytes from a locally-recorded HEVC file (`hevc (Main)`, 1752x984) and cross-validated every field against `ffmpeg -bsf:v trace_headers` output, the same methodology used for H.264.

**VPS** (24 bytes, hex `40010c01ffff016000000300b00000030000030078ac0900`, NAL header `40 01`: `nal_unit_type=32`, `nuh_layer_id=0`, `nuh_temporal_id_plus1=1`):
```
vps_video_parameter_set_id=0
vps_base_layer_internal_flag=1, vps_base_layer_available_flag=1
vps_max_layers_minus1=0, vps_max_sub_layers_minus1=0
vps_temporal_id_nesting_flag=1
profile_tier_level: general_profile_space=0, general_tier_flag=0, general_profile_idc=1, general_level_idc=120
```

**SPS** (40 bytes, hex `420101016000000300b00000030000030078a00370803e1cb2e5aee4c92ea6e0a0c0a05da1425000`, NAL header `42 01`: `nal_unit_type=33`). Note: contains real emulation-prevention bytes (`00 00 03` sequences), confirming `removeEmulationPreventionBytes` reuse is required here exactly as for H.264:
```
profile_tier_level (identical layout/values to VPS's, confirmed same 96-bit fixed width):
  general_profile_space=0, general_tier_flag=0, general_profile_idc=1, general_level_idc=120
sps_video_parameter_set_id=0
sps_max_sub_layers_minus1=0, sps_temporal_id_nesting_flag=1
sps_seq_parameter_set_id=0
chroma_format_idc=1
pic_width_in_luma_samples=1760, pic_height_in_luma_samples=992
conformance_window_flag=1 (crop offsets present, not individually curated)
bit_depth_luma_minus8=0, bit_depth_chroma_minus8=0
vui_parameters_present_flag=1
  video_signal_type_present_flag=1, video_full_range_flag=1
  colour_description_present_flag=1, colour_primaries=5, transfer_characteristics=6, matrix_coefficients=5
```

**PPS** (11 bytes, hex `4401c1e30f0941ef612800`, NAL header `44 01`: `nal_unit_type=34`):
```
pps_pic_parameter_set_id=0, pps_seq_parameter_set_id=0
dependent_slice_segments_enabled_flag=0, output_flag_present_flag=0, num_extra_slice_header_bits=0
sign_data_hiding_enabled_flag=1, cabac_init_present_flag=1
init_qp_minus26=6
constrained_intra_pred_flag=0, transform_skip_enabled_flag=0
cu_qp_delta_enabled_flag=1
weighted_pred_flag=0, weighted_bipred_flag=0
tiles_enabled_flag=1, entropy_coding_sync_enabled_flag=0
deblocking_filter_control_present_flag=1
```

**Slice segment header prefix** (first IDR slice, bytes `28 01 af 09 a8 30 01 c5 46 7f...`, NAL header `28 01`: `nal_unit_type=20` = IDR_W_RADL, within the IRAP range 16-23):
```
first_slice_segment_in_pic_flag=1
no_output_of_prior_pics_flag=0   (present because nal_unit_type is IRAP)
slice_pic_parameter_set_id=0
```
Hand-decoded bit-by-bit directly from the raw bytes (byte 2 = `0xaf` = `1010 1111`: bit0=1→flag, bit1=0→flag, bit2=1→`ue(v)` prefix `1`→value 0) and confirmed to match `trace_headers`' own values exactly.

These byte sequences (with their full expected field sets) become the plan's test fixtures.

## Components

### 1. Reused as-is (no changes)

- `BitReader` (`parser/BitReader.kt`) — `readBits`/`readFlag`/`readUe`/`readSe`, fully codec-agnostic.
- `removeEmulationPreventionBytes` (`parser/NalEmulationPrevention.kt`) — Annex B mechanism, not H.264-specific.

### 2. `HevcParameterSets.kt` (new) — VPS/SPS/PPS field parsers

Mirrors `H264ParameterSets.kt`'s pattern (curated data classes, try/catch-return-null, early-return-on-unsupported-feature):

```kotlin
data class HevcProfileTierLevel(
    val generalProfileSpace: Int, val generalTierFlag: Boolean,
    val generalProfileIdc: Int, val generalLevelIdc: Int,
)
data class HevcVps(
    val vpsId: Int, val maxSubLayersMinus1: Int,
    val ptl: HevcProfileTierLevel?, val ptlUnsupported: Boolean = false,
)
data class HevcVui(
    val videoFullRangeFlag: Boolean?, val colourPrimaries: Int?,
    val transferCharacteristics: Int?, val matrixCoefficients: Int?,
)
data class HevcSps(
    val spsId: Int, val vpsId: Int, val maxSubLayersMinus1: Int,
    val ptl: HevcProfileTierLevel,
    val chromaFormatIdc: Int, val picWidth: Int, val picHeight: Int,
    val bitDepthLuma: Int, val bitDepthChroma: Int, val vui: HevcVui?,
)
data class HevcPps(
    val ppsId: Int, val spsId: Int,
    val dependentSliceSegmentsEnabledFlag: Boolean,
    val signDataHidingEnabledFlag: Boolean, val cabacInitPresentFlag: Boolean,
    val constrainedIntraPredFlag: Boolean, val transformSkipEnabledFlag: Boolean,
    val cuQpDeltaEnabledFlag: Boolean,
    val weightedPredFlag: Boolean, val weightedBipredFlag: Boolean,
    val tilesEnabledFlag: Boolean, val entropyCodingSyncEnabledFlag: Boolean,
    val deblockingFilterControlPresentFlag: Boolean,
    val ppsDeblockingFilterDisabledFlag: Boolean?, // null when deblockingFilterControlPresentFlag is false (not signaled)
)

// Shared by parseHevcVps and parseHevcSps -- identical profile_tier_level() layout in both.
// Reads the fixed 96-bit "general" profile/tier/level block (4 curated fields + fixed-width
// skips for compatibility/constraint bits, verified against real trace_headers output).
// Returns null (ptlUnsupported=true on the caller's result) if maxSubLayersMinus1 > 0 --
// sub-layer profile/level blocks are not parsed (mirrors H.264's scaling-matrix bail-out).
private fun parseProfileTierLevel(reader: BitReader, maxSubLayersMinus1: Int): HevcProfileTierLevel?

fun parseHevcVps(nalBytes: ByteArray): HevcVps?   // skips 2-byte NAL header, de-emulates first
fun parseHevcSps(nalBytes: ByteArray): HevcSps?   // skips 2-byte NAL header, de-emulates first
fun parseHevcPps(nalBytes: ByteArray): HevcPps?   // skips 2-byte NAL header, de-emulates first
```

### 3. `HevcParameterSetExtraction.kt` (new) — raw NAL extraction + per-frame resolution

Mirrors `H264ParameterSetExtraction.kt`, adapted for `hvcC`'s array-of-arrays structure (per `HvcCBoxDecoder.kt`/`HeifHevcThumbnail.kt`'s `readHvcCInfo`: 23-byte fixed header, then `num_arrays` repetitions of `[1-byte (array_completeness+reserved+nal_unit_type), 2-byte num_nalus, repeated (2-byte nal_length + raw bytes)]`) and HEVC's 2-byte NAL header:

```kotlin
data class HvcCRawParameterSets(
    val lengthSize: Int,
    val vpsList: List<ByteArray>, val spsList: List<ByteArray>, val ppsList: List<ByteArray>,
)
fun extractHvcCRawParameterSets(file: File, hvcCNode: BoxNode): HvcCRawParameterSets?

// Walks length-prefixed NALs in the frame's sample bytes (using hvcC's declared length_size),
// finds the first VCL NAL (nal_unit_type < 32 -- HEVC's non-VCL types start at 32, unlike
// H.264's two explicit type checks), reads its 2-byte header, de-emulates a short prefix, then
// reads first_slice_segment_in_pic_flag / no_output_of_prior_pics_flag (if nal_unit_type in
// 16..23) / slice_pic_parameter_set_id.
fun resolveActiveHevcPicParameterSetId(file: File, byteOffset: Long, sizeBytes: Int, lengthSize: Int): Int?

// PPS lookup by id, then SPS lookup by the PPS's own pps_seq_parameter_set_id, then VPS lookup
// (best-effort) by the SPS's own sps_video_parameter_set_id. Returns null only if PPS or SPS
// can't be resolved; VPS is nullable in the returned triple.
fun resolveActiveHevcParameterSets(
    vpsList: List<HevcVps>, spsList: List<HevcSps>, ppsList: List<HevcPps>, picParameterSetId: Int,
): Triple<HevcVps?, HevcSps, HevcPps>?
```

### 4. `TabState` additions (`AppState.kt`)

```kotlin
var hevcVpsList: List<com.multiviewer.parser.HevcVps> by mutableStateOf(emptyList())
var hevcSpsList: List<com.multiviewer.parser.HevcSps> by mutableStateOf(emptyList())
var hevcPpsList: List<com.multiviewer.parser.HevcPps> by mutableStateOf(emptyList())
var hevcLengthSize: Int? by mutableStateOf(null)
```
Populated once per video tab (parsed from the `hvcC` box's raw VPS/SPS/PPS bytes), independent of any specific frame selection -- same pattern as the existing `avcSpsList`/`avcPpsList`/`avcLengthSize`.

### 5. `VideoInspectorUI.kt` wiring

A second `LaunchedEffect(tab.root)`, parallel to the existing `avcC` one, that finds the first `hvcC` box instead and populates the fields above via `extractHvcCRawParameterSets` + `parseHevcVps`/`parseHevcSps`/`parseHevcPps`.

### 6. `ImageInspectorUI.kt` UI wiring (`DetailPropertiesTabContent`)

A second `produceState` block, parallel to `resolvedH264Params`, keyed on `selectedFrame`/`tab.hevcSpsList`/`tab.hevcPpsList`/`tab.hevcLengthSize`, calling `resolveActiveHevcPicParameterSetId` + `resolveActiveHevcParameterSets`. When resolved, a "HEVC Parameter Sets" section (same visual treatment as the existing "H.264 Parameter Sets" section) shows VPS id / SPS id / PPS id, Profile/Tier/Level, Chroma Format, Bit Depth, resolution, and the curated PPS flags, with the same `ptlUnsupported`-driven partial-result note as H.264's `scalingMatrixUnsupported`. Since a stream is either H.264 or HEVC, at most one of the two sections ever has data to show; both `produceState` blocks and both `let{}` blocks coexist harmlessly (the non-matching one simply resolves to `null`).

## Error handling

Identical convention to the H.264 feature: every parsing step (`BitReader` running past `bitsRemaining()`, malformed NAL length prefixes, an unresolvable PPS/SPS/VPS id) returns null/a partial result rather than throwing.

## Testing

- `HevcParameterSets`: unit tested against the real 24-byte VPS, 40-byte SPS, and 11-byte PPS fixtures above, asserting every curated field matches the documented `trace_headers` ground truth exactly, including the shared `parseProfileTierLevel` producing identical values from both the VPS and SPS fixtures.
- `HevcParameterSetExtraction`: the `hvcC` raw-bytes extraction and per-frame slice-header-prefix walk are unit tested with synthetic byte fixtures (matching the H.264 feature's convention), plus one test using the real slice-header-prefix bytes above to confirm the `first_slice_segment_in_pic_flag` / `no_output_of_prior_pics_flag` / `slice_pic_parameter_set_id` walk against real ground truth.
- Manual verification: open the real HEVC file used for this spec's verification, select frames across different GOPs, confirm the shown VPS/SPS/PPS fields are stable across all frames in this single-parameter-set stream, and match `ffmpeg -bsf:v trace_headers`' independently-reported values.

## Out of scope (deferred)

- `profile_tier_level()` sub-layer profile/level blocks (streams with `max_sub_layers_minus1 > 0`).
- Exhaustive field coverage (short-term/long-term reference picture sets, scaling list data, PCM parameters, tiles' column/row width arrays, full VUI incl. HRD parameters).
- A tree-node/hex-highlight presentation -- fields show as `PropertyRow`s in Detail Properties only, matching the H.264 feature's placement decision.
