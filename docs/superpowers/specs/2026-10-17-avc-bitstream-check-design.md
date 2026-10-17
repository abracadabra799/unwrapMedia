# H.264 / AVC Bitstream Check in Content Check

## Goal and dependency

Add an H.264 / AVC (`avc1`) codec-bitstream rule runner to the redesigned Content Check workflow. For an ordinary MP4/MOV H.264 video, tell a Galaxy multimedia framework and codec engineer which implemented H.264 rules were checked, exactly where a violation occurred, and what remains outside coverage.

This spec depends on `2026-10-17-content-check-workflow-design.md` for stage lifecycle, progress, cancellation, and UI presentation, and follows the architectural model established in `2026-10-17-hevc-bitstream-check-design.md`. It does not claim full H.264 conformance or Android MediaCodec compatibility.

## Supported scope

- Start with MP4/MOV files whose media type is `VIDEO`, whose first video track has an `avc1` sample entry and a valid `avcC` configuration box, and whose samples are indexed by non-fragmented `moov` sample tables.
- The runner checks every indexed sample of that track up to `MAX_VIDEO_INDEXED_SAMPLES` (1,000,000).
- If more samples exist, the file is fragmented (`moof`/`mvex`), sample tables disagree, data is encrypted (`sinf`/`encv`), a sample extends outside the file, or `avcC` cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another video track.
- If there are multiple video tracks, identify the checked track (e.g. `video track #1 (avc1)`) and list each other track as `미검증 / Not checked`.
- Tied to [ITU-T H.264 (08/21)](https://www.itu.int/rec/T-REC-H.264) / ISO/IEC 14496-10 for AVC syntax and ISO/IEC 14496-15 for the AVC sample entry and `avcC` configuration boundary. A rule is labeled normative only after its exact clause, profile applicability, and byte interpretation are independently verified; otherwise it is labeled a structural observation.

## Rules and evidence

The rule set is byte-verifiable and bounded:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| Sample and NAL framing (`avc.nal.framing`) | A sample's length-prefixed NALs fit exactly in the indexed sample extent using the `avcC` length size (1, 2, or 4 bytes). | An oversize/truncated prefix or NAL is a violation with sample and absolute byte offset. A zero-length NAL has no header to validate and is `NOT_CHECKED`. An unreadable sample is an inspection failure, never PASS. |
| NAL header forbidden bit (`avc.nal.forbidden_zero_bit`) | The 1-byte NAL header's bit 7 (`forbidden_zero_bit`) must be 0 (H.264 clause 7.3.1). | Bit 7 == 1 is FAIL. Report observed header byte, track/sample/NAL index, and the 1-byte range. |
| IDR reference indicator (`avc.nal.idr_ref_idc`) | For NAL unit type 5 (IDR slice), `nal_ref_idc` (bits 6..5) must not be 0 (H.264 clause 7.4.1). | `nal_unit_type == 5` and `nal_ref_idc == 0` is FAIL. Report observed bits and 1-byte range. |
| Parameter-set syntax and references (`avc.param.reference`) | Parse SPS and PPS in `avcC` (and inline parameter sets if present); check that slice headers refer to an existing PPS ID, and PPS refers to an existing SPS ID. | A known-missing reference is FAIL. An unsupported SPS/PPS syntax extension or truncated slice header is `NOT_CHECKED` with the reason, never a guessed FAIL or PASS. |
| Configuration consistency (`avc.config.avcc`) | `avcC` has `configurationVersion == 1`, valid `lengthSizeMinusOne in 0..3`, at least one valid SPS and PPS, and no conflicting array counts. | Corrupted/truncated `avcC` marks the codec stage `UNSUPPORTED`. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, expected and observed values, track/sample/NAL index, exact byte range when available, and bilingual KO/EN explanation. Total occurrence counts are tracked beyond the displayed/exported cap (100 findings).

## Execution and safety

- Reuse `IsoSampleTable.kt` for sample indexing, `AvcCBoxDecoder.kt`, `H264ParameterSetExtraction.kt`, and `H264ParameterSets.kt`.
- Entry point gates on `tab.type == MediaType.VIDEO` and presence of supported `avc1` + `avcC`. Image formats (e.g. motion photo containers or HEIF) are excluded.
- Bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples/NALs. Do not load whole samples into memory. Detect arithmetic overflow and bounds before reads.
- Report `checked / indexed samples`, current track/sample, and bounded issue count. Progress is indeterminate if the sample index is incomplete or has warnings.
- Findings publish immediately upon stage completion and update the conformance summary. Cancellation leaves already completed stages intact, marking the AVC stage `Cancelled` or `Partial`, never PASS.
- Highlighting follows tree selection / `tab.parameterSetHighlightRange`.

## Fixtures and verification

- Generate small real `avc1` MP4 fixtures with ffmpeg/x264. Record command versions, raw sample-entry bytes, `avcC` bytes, length prefixes, and NAL headers in `docs/validation/avc-conformance-fixtures/README.md`.
- Derive negative fixtures with Python mutation (`mutate.py`):
  - `forbidden-bit.mp4`: first NAL has bit 7 set to 1.
  - `idr-zero-ref.mp4`: IDR slice with `nal_ref_idc == 0`.
  - `truncated-prefix.mp4`: sample ends mid-prefix.
  - `oversize-nal.mp4`: length prefix exceeds sample extent.
  - `malformed-avcc.mp4`: `configurationVersion != 1` or truncated.
  - `many-forbidden-headers.mp4`: 101 malformed NALs in sample 1 for occurrence counting.
- Integration tests cover default `avc1` availability, non-video exclusion, boundary coverage (fragments, encryption, multi-track), cancellation, and occurrence counting.

## Out of scope for this first runner

Full macroblock/slice payload decoding, CABAC/CAVLC entropy decoding, DPB/HRD simulation, all H.264 profiles/extensions (SVC/MVC), fragmented/encrypted MP4, other video tracks, and hardware MediaCodec execution. These remain explicitly labeled coverage gaps.
