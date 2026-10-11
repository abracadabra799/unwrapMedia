# SEI / HDR Metadata Analysis — Design Spec

**Goal:** Parse and present in-band SEI (H.264/HEVC) and AV1 metadata OBUs, with first-class decoding of HDR static metadata (mastering display colour volume, content light level) and HDR10+ dynamic metadata (SMPTE ST 2094-40), plus the container-level `mdcv`/`clli` boxes. Two surfaces: a per-frame "SEI / Metadata" section in Detail Properties, and a standalone whole-stream analysis window with a trend graph, per-frame table, anomaly list and CSV export.

**Context:** This is item 1 of a benchmarking roadmap against Elecard StreamEye, MediaInfo, ExifTool and similar tools (2026-10-12). Today the H.264/HEVC parameter-set extraction code explicitly *skips* SEI NALs (`H264ParameterSetExtraction.kt:48`, `HevcParameterSetExtraction.kt:64`); no file parses SEI payloads, `mdcv`/`clli` boxes render as generic leaves, and HDR10+ is not recognized anywhere. The primary user is a Galaxy multimedia framework / codec engineer validating HDR10+ recordings, so HDR10+ per-frame behaviour across a whole stream is the headline use case, with general SEI debugging as the base layer.

## Scope

- Containers: **MP4/MOV (ISOBMFF) only** — the containers whose sample tables this app already walks for per-frame data.
- Codecs: **H.264, HEVC, AV1.**
  - H.264: SEI NAL type 6.
  - HEVC: prefix SEI (39) and suffix SEI (40).
  - AV1: metadata OBU (`obu_type` 5) with `metadata_type` HDR_CLL (1), HDR_MDCV (2), ITU_T_T35 (4); other metadata types listed by type/size only.
- Container boxes: `mdcv`, `clli` (ISOBMFF), and the QuickTime-era `SmDm`, `CoLL` equivalents, registered in `BoxRegistry` with real field decoding.
- SEI payloads decoded field-by-field:
  - 137 mastering_display_colour_volume
  - 144 content_light_level_info
  - 4 user_data_registered_itu_t_t35 → HDR10+ when country 0xB5 / provider 0x003C / oriented 0x0001 / application_identifier 4; other T.35 payloads shown as country/provider + hex
  - 5 user_data_unregistered → UUID + printable-ASCII preview (x264/x265 version strings, vendor UUIDs)
  - 6 recovery_point
  - 147 alternative_transfer_characteristics
  - 1 pic_timing → presence + size only (its syntax depends on VUI/HRD state; detailed field decoding is out of scope)
  - every other payloadType → name (from a payloadType→name table per codec) + size + offset
- Dolby Vision: no RPU parsing. HEVC NAL types 62/63 (unspecified, used by DV RPU/EL) are reported as "present" only.

## Out of scope

Dolby Vision RPU decoding, HDR Vivid (CUVA T.35), VVC, Matroska/WebM, raw Annex-B elementary streams, H.264/HEVC pic_timing / buffering_period field decoding, integrating findings into Content Check (may follow later), editing/injecting metadata.

## Architecture

### Parser layer (`parser/`, pure, no UI dependencies)

| File | Responsibility |
|---|---|
| `SeiParser.kt` | Given one sample's bytes + NAL length size + codec, walks length-prefixed NALs, and for each SEI NAL removes emulation prevention (reusing `removeEmulationPreventionBytes`) and splits it into `SeiMessage(payloadType, payloadSize, fileOffset, nalType, payload: ByteArray)` using the ff-byte-extended type/size coding. Stops at the first VCL NAL for prefix SEI, but continues past it for HEVC suffix SEI (40). Malformed sizes yield a `SeiMessage` flagged `malformed` and terminate that NAL's walk. |
| `SeiPayloadDecoders.kt` | `decodeSeiPayload(codec, message): DecodedSei` — sealed result per payload kind listed in Scope, plus a `payloadTypeName(codec, type)` table. |
| `Hdr10PlusDecoder.kt` | Decodes the ST 2094-40 `user_data_registered_itu_t_t35` payload (after the T.35 country/provider/oriented header) into `Hdr10PlusMetadata`: application_version, num_windows, targeted_system_display_maximum_luminance, maxscl[3], average_maxrgb, distribution_maxrgb (percentages + percentiles), fraction_bright_pixels, tone_mapping_flag, knee_point_x/y, bezier_curve_anchors. Shared by the SEI and AV1 paths. Only window 0 is surfaced in the UI; additional windows are parsed and counted but not graphed. |
| `Av1MetadataObu.kt` | Walks an AV1 sample's OBUs (reusing existing `Av1Obu.kt` OBU header parsing) and decodes metadata OBUs: HDR_CLL, HDR_MDCV, ITU_T_T35 (→ `Hdr10PlusDecoder`). |
| `MdcvClliBoxDecoder.kt` | `BoxDecoder`s for `mdcv`/`SmDm` and `clli`/`CoLL`, registered in `BoxRegistry`. Shows raw values and human units (chromaticity ×0.00002, luminance ×0.0001 cd/m²). Also exposes a pure `readContainerHdrStatic(root): ContainerHdrStatic?` used by the analysis window. |
| `HdrMetadataScanner.kt` | Sequential whole-stream pass producing `List<FrameHdrRecord>` (frame index, pts, frame type/IRAP flag, list of decoded messages, HDR10+ window-0 values, static MDCV/CLL if present in that frame). Reads only NAL/OBU headers + SEI/metadata payloads (each payload read capped at 64 KB), never VCL/tile data beyond its header. Cancellable, reports progress. |
| `HdrAnomalyRules.kt` | Pure `detectHdrAnomalies(records, containerStatic, thresholds): List<HdrAnomaly>` implementing the rules below. |

Per-frame sample location reuses the existing `FrameInfo.byteOffset`/`sizeBytes` plus the NAL length size already extracted from `avcC`/`hvcC`.

### UI layer

- **Detail Properties → "SEI / Metadata" section.** When a frame is selected on an H.264/HEVC/AV1 MP4 stream, lazily parse just that frame's sample (same lazy `produceState` pattern as the APV frame header) and list each message: name, payloadType, size; expandable decoded fields. Clicking a row sets `tab.parameterSetHighlightRange` to the message's byte range (hex-viewer jump). Gated on codec presence (`avcC`/`hvcC`/`av1C` found in the tree) so non-video files never trigger a sample read — the APV lesson.
- **Analyze → "HDR/SEI 메타데이터 분석" (HDR/SEI Metadata Analysis) window.** Standalone `Window`, enabled only when the current tab is a supported video. Contents:
  - Scan progress bar + cancel; closing the window cancels the scan.
  - Summary header: container static values, first-seen in-band static values, HDR10+ coverage (% of frames), PASS/WARN/FAIL badge (same visual language as Content Check).
  - Trend graph over frame index/time: MaxSCL (max of R/G/B, nits), average_maxrgb (nits), targeted_system_display_maximum_luminance, with horizontal reference lines for MDCV max luminance, MaxCLL and MaxFALL. Built on/extending `MetricGraph`; anomaly frames marked.
  - Per-frame table (frame, pts, type, SEI/OBU list, HDR10+ key values).
  - Anomaly list; selecting an anomaly scrolls the table/graph to that frame.
  - Threshold controls for the "abrupt change" rule.
  - CSV export of the per-frame table and anomaly list (hand-written CSV, matching `QualityExport.kt`).
- Strings in `I18n.kt` (KO/EN). All text uses `androidx.compose.material3.Text`.

## Anomaly rules

Units: HDR10+ maxscl and average_maxrgb are in 0.1 cd/m² units in the bitstream (normalized to nits for display and comparison); MDCV luminance in 0.0001 cd/m²; CLL in cd/m².

| Rule | Condition (defaults) | Severity |
|---|---|---|
| HDR10+ missing | Stream has HDR10+ on >0% but <100% of frames → report missing frames grouped into contiguous ranges. | ERROR if any missing frame is IRAP/keyframe, otherwise WARN |
| Abrupt change | Between adjacent frames, MaxSCL or average_maxrgb changes by **≥50% AND ≥100 nits** (both thresholds user-adjustable). | WARN; downgraded to INFO when the later frame is an IDR/keyframe (likely scene cut) |
| Static ↔ dynamic mismatch | (a) any frame MaxSCL > MDCV max luminance; (b) stream-max MaxSCL > MaxCLL; (c) stream-max average_maxrgb > MaxFALL; (d) targeted_system_display_maximum_luminance == 0 or > MDCV max luminance. Each sub-rule only fires when both sides are present. | WARN |
| Container ↔ in-band mismatch | `mdcv`/`clli` box values differ from in-band SEI/OBU MDCV/CLL → WARN. Compare after normalizing to physical units (chromaticity, cd/m²), because AV1's HDR_MDCV uses different fixed-point encodings (0.16 chromaticity, 24.8 max / 18.14 min luminance) than HEVC/H.264 SEI and `mdcv` (0.00002 / 0.0001); tolerance = one quantization step of the coarser encoding; present on only one side → INFO. In-band values that change mid-stream are also reported (INFO). | WARN / INFO |

Overall badge: FAIL if any ERROR, WARN if any WARN, else PASS.

## Error handling

- Truncated/malformed SEI or OBU: recorded as a `malformed` message with its offset; parsing continues with the next NAL/OBU and the scan never aborts on one bad frame.
- Unsupported codec/container: menu item disabled; Detail Properties section absent.
- Large files: per-payload read cap of 64 KB; header-only NAL/OBU walking; scan cancellable; any external process (none expected) must use `readProcessOutputWithTimeout`.
- Scan cancelled on window close (Quality Compare lesson).

## Testing

- **Real-fixture verification first (plan task 1)**, before writing Kotlin — the field layouts and ffprobe output format below have *not* been validated against real files yet:
  - HEVC HDR10+: `x265 --master-display ... --max-cll ... --dhdr10-info <json>` → mux to MP4; confirm SEI 137/144/4 present and cross-check values with `ffprobe -show_frames -show_entries frame=side_data_list`.
  - H.264: libx264 with SEI (at minimum user_data_unregistered; verify whether this x264 build supports mastering-display/CLL options).
  - AV1: SVT-AV1 with mastering-display / content-light options → MP4; confirm metadata OBUs.
  - A file with `mdcv`/`clli` boxes (verify which muxer writes them, e.g. ffmpeg mov muxer from side data).
  - Optional: a real Galaxy HDR10+ recording supplied by the user for final manual verification.
- Unit tests with byte-array fixtures for: SEI type/size ff-extension coding, each decoded payload kind, HDR10+ decoder (incl. multiple windows and bezier anchors), AV1 metadata OBUs, `mdcv`/`clli` decoders, suffix-SEI-after-VCL handling, malformed sizes.
- Anomaly rules: synthetic `FrameHdrRecord` lists exercising each rule at and just past its threshold, IDR downgrade, and the "both sides present" guards.
- Final review must explicitly ask: does anything break on Windows (paths, file handles), and does selecting a frame in a non-video/non-supported file trigger any sample read.
