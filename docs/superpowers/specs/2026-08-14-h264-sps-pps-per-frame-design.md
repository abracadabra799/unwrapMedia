# H.264 SPS/PPS Per-Frame Info — Design Spec

**Goal:** When a frame is selected (GOP bar chart, thumbnail filmstrip, or arrow-key stepping in either), show the actual SPS/PPS/VUI fields that frame's own slice header references — not just the stream's first parameter set — in the Detail Properties panel alongside the existing Frame #/Type/Size/PTS/Byte Offset/GOP Position rows.

**Context:** `AvcCBoxDecoder.kt` already parses the video track's `avcC` box, but only counts SPS/PPS entries and validates bounds — it never retains the raw parameter-set bytes (documented as an intentional deferral in `docs/superpowers/specs/2026-07-17-box-detail-parsing-design.md`). No bit-level (Exp-Golomb) bitstream reader exists anywhere in this codebase; every existing reader (`ByteReader`) is byte-aligned only. `FrameInfo` (just added alongside the thumbnail filmstrip work) now carries `byteOffset`/`sizeBytes`, giving exact file-position access to any frame's raw sample bytes without needing ffmpeg.

## Scope

- H.264 only (not HEVC) — VPS doesn't exist in H.264, and SPS/PPS bit layouts differ from HEVC's, so combining both up front doubles the parsing surface for a first version. HEVC is a natural follow-up once this ships and is validated.
- Core fields only, not an exhaustive bit-for-bit dump:
  - **SPS**: profile_idc, level_idc, seq_parameter_set_id, chroma_format_idc, bit_depth_luma/chroma, pic_order_cnt_type, max_num_ref_frames, frame_cropping (flag + values if present), VUI's aspect_ratio_idc/sar_width/height, video_full_range_flag, colour_primaries/transfer_characteristics/matrix_coefficients, timing_info (num_units_in_tick/time_scale).
  - **PPS**: pic_parameter_set_id, seq_parameter_set_id, entropy_coding_mode_flag (CAVLC/CABAC), deblocking_filter_control_present_flag, transform_8x8_mode_flag.
  - High-profile SPS scaling-matrix parsing (`seq_scaling_matrix_present_flag`) is **not** supported — if present, SPS parsing stops there and reports a partial result with a note, rather than silently producing wrong values for fields after it. This only affects High/High-444 profile content with explicit custom scaling lists (uncommon).
- Resolves the frame's *actual* referenced PPS→SPS chain by parsing just enough of that frame's own slice header (first_mb_in_slice, slice_type, pic_parameter_set_id — all Exp-Golomb, all before any CABAC-coded data starts) — not a full slice-header parse, and nowhere near a full slice/macroblock decode.

## Technical foundation (verified against real files)

Extracted real SPS/PPS/slice-header bytes from a locally-generated H.264 file and cross-validated every field against ffmpeg's own `-bsf:v trace_headers` output (a full field-by-field bitstream header dump) — confirming the exact bit layout and Exp-Golomb decoding by hand before committing to this design:

**SPS** (25 bytes, hex `67f4000d919b28283f6022000003000200000300641e28532c`, NAL header `0x67` = nal_ref_idc 3, type 7):
```
profile_idc=244, constraint flags=0, level_idc=13
seq_parameter_set_id=0
chroma_format_idc=3, separate_colour_plane_flag=0
bit_depth_luma_minus8=0, bit_depth_chroma_minus8=0
qpprime_y_zero_transform_bypass_flag=0, seq_scaling_matrix_present_flag=0
log2_max_frame_num_minus4=0
pic_order_cnt_type=0, log2_max_pic_order_cnt_lsb_minus4=2
max_num_ref_frames=4
gaps_in_frame_num_allowed_flag=0
pic_width_in_mbs_minus1=19, pic_height_in_map_units_minus1=14
frame_mbs_only_flag=1, direct_8x8_inference_flag=1
frame_cropping_flag=0
vui_parameters_present_flag=1
  aspect_ratio_info_present_flag=1, aspect_ratio_idc=1
  overscan_info_present_flag=0
  video_signal_type_present_flag=0
  chroma_loc_info_present_flag=0
  timing_info_present_flag=1
```

**PPS** (6 bytes, hex `68ebe3c44844`, NAL header `0x68` = type 8):
```
pic_parameter_set_id=0, seq_parameter_set_id=0
entropy_coding_mode_flag=1 (CABAC)
bottom_field_pic_order_in_frame_present_flag=0
num_slice_groups_minus1=0
num_ref_idx_l0_default_active_minus1=2, num_ref_idx_l1_default_active_minus1=0
weighted_pred_flag=1, weighted_bipred_idc=2
pic_init_qp_minus26=-3, pic_init_qs_minus26=0
chroma_qp_index_offset=4
deblocking_filter_control_present_flag=1
constrained_intra_pred_flag=0, redundant_pic_cnt_present_flag=0
transform_8x8_mode_flag=1
```

**Slice header prefix** (first IDR slice, bytes `65 88 84 00 37...`, NAL header `0x65` = type 5):
```
first_mb_in_slice=0, slice_type=7, pic_parameter_set_id=0
```
Hand-decoded bit-by-bit from the raw bytes and confirmed to match `trace_headers`' own values exactly for all three fields (Exp-Golomb `ue(v)`: count leading zero bits `k`, then `value = 2^k - 1 + next k bits read as an unsigned integer`).

These three byte sequences (with their full expected field sets) become the plan's test fixtures — real, ground-truth-verified, not synthetic guesses.

## Components

### 1. `BitReader.kt` (new) — Exp-Golomb / fixed-width bit reader

```kotlin
class BitReader(private val data: ByteArray, private val startByteOffset: Int = 0) {
    private var bitPosition = 0 // bits consumed since startByteOffset

    fun readBits(count: Int): Int { ... } // u(n): MSB-first fixed-width unsigned
    fun readFlag(): Boolean = readBits(1) == 1 // u(1)
    fun readUe(): Int { ... } // ue(v): unsigned Exp-Golomb
    fun readSe(): Int { ... } // se(v): signed Exp-Golomb, mapped from readUe() per spec §9.1.1
    fun bitsRemaining(): Int
}
```

### 2. Raw SPS/PPS NAL extraction from the main video track's `avcC` box

A new function (co-located with `AvcCBoxDecoder.kt` or a new sibling file) that walks the same `avcC` structure `AvcCBoxDecoder` already parses (length_size_minus_one, num_sps, then repeated `sps_length` u16 + raw bytes, num_pps, then repeated `pps_length` u16 + raw bytes) but **collects** the raw bytes instead of only counting/validating them. Reads directly via the existing `ByteReader` at the box's known file offset — no ffmpeg involved, matching how the rest of this app's box parsing already works.

### 3. `H264SpsParser.kt` / `H264PpsParser.kt` (new)

Pure functions taking the raw NAL bytes (including the 1-byte NAL header, which they skip) and returning a curated data class of the fields listed in Scope above, using `BitReader`. Returns a partial result (fields parsed so far + a flag noting the stop point) if `seq_scaling_matrix_present_flag` is encountered, per the Scope's stated limitation.

### 4. Per-frame active-PPS resolution

Given a `FrameInfo` with a non-null `byteOffset`, read its `sizeBytes` raw bytes from the file (avcC-style length-prefixed NALs, using the same `length_size` the `avcC` box itself declares — typically 4). Walk the length-prefixed NALs within that span, find the first VCL NAL (`nal_unit_type` 1 or 5), skip its 1-byte header, and read just `first_mb_in_slice` (ue), `slice_type` (ue), `pic_parameter_set_id` (ue) via `BitReader` — stopping immediately after, well before any CABAC/CAVLC-coded slice data. Returns the resolved `pic_parameter_set_id`, used to look up the matching parsed PPS (and from it, the matching SPS) from the lists parsed once per video (cached on `TabState`, parsed lazily the same way `videoCodecName`/`gopFrames` already are).

### 5. `TabState` additions (`AppState.kt`)

```kotlin
var avcSpsList: List<H264Sps> by mutableStateOf(emptyList())
var avcPpsList: List<H264Pps> by mutableStateOf(emptyList())
```
Populated once per video tab (parsed from the `avcC` box's raw SPS/PPS bytes), independent of any specific frame selection.

### 6. UI (`ImageInspectorUI.kt`'s `DetailPropertiesTabContent`)

When a frame is selected and its active PPS/SPS can be resolved (H.264 stream, successful slice-header-prefix parse, matching PPS/SPS found in the parsed lists), add a labeled section below the existing frame rows showing the curated SPS/PPS fields. If resolution fails at any step (not H.264, extraction/parse failure, PPS id not found in the list), show nothing extra rather than a broken/partial row — the existing Frame #/Type/Size/PTS/Byte Offset/GOP Position rows are unaffected either way.

## Error handling

Every parsing step (`BitReader` running past `bitsRemaining()`, malformed NAL length prefixes, an unresolvable PPS id) returns null/a partial result rather than throwing — mirrors this codebase's established box-decoder convention (`AvcCBoxDecoder` and siblings already bail with warnings rather than crash on malformed input) and the ffmpeg-decoder convention (`decodeSingleFrameToBitmap`'s null-on-failure) used everywhere else in this app.

## Testing

- `BitReader`: unit tested directly against the hand-verified real bit sequences above (`readUe`/`readSe`/`readBits` on the actual SPS/PPS/slice-header bytes, asserting the exact ffmpeg-`trace_headers`-confirmed values).
- `H264SpsParser`/`H264PpsParser`: unit tested against the real 25-byte SPS and 6-byte PPS fixtures above, asserting every curated field matches the documented `trace_headers` ground truth exactly.
- The avcC raw-bytes extraction and per-frame slice-header-prefix walk (NAL length-prefix parsing) are unit tested with synthetic byte fixtures (matching this codebase's existing `byteReaderOf`-style convention for box-decoder tests), since they don't need a real ffmpeg-encoded file to verify correctly — only the bit-level SPS/PPS/slice-header semantics needed real ground truth.
- Manual verification: open a real H.264 file, select frames across different GOPs, confirm the shown SPS/PPS fields are stable (same PPS/SPS id) across all frames in a typical single-parameter-set stream, and confirm the values match what `ffmpeg -bsf:v trace_headers` independently reports for the same file.

## Out of scope (deferred)

- HEVC (VPS/SPS/PPS) — separate follow-up once H.264 ships and is validated.
- High-profile SPS scaling-matrix fields.
- Exhaustive field coverage (HRD parameters, bitstream_restriction, slice-group map details, full VUI).
- A tree-node/hex-highlight presentation (BoxNode-style) — this iteration shows fields as `PropertyRow`s in Detail Properties only, per the earlier UI-placement decision.
