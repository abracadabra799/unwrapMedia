# AV1 Bitstream Check in Content Check

## Goal and dependency

Add an AV1 (`av01`) codec-bitstream rule runner to the Content Check workflow. For an ordinary MP4/MOV AV1 video, tell a Galaxy multimedia framework and codec engineer which implemented AV1 rules were checked, exactly where a violation occurred, and what remains outside coverage.

This spec depends on `2026-10-17-content-check-workflow-design.md` for lifecycle, progress, cancellation, and UI presentation, and follows the architectural model established in the HEVC and AVC bitstream specs. It does not claim full AV1 conformance or Android MediaCodec compatibility.

## Supported scope

- Start with MP4/MOV files whose media type is `VIDEO`, whose first video track has an `av01` sample entry and a valid `av1C` configuration box, and whose samples are indexed by non-fragmented `moov` sample tables.
- The runner checks every indexed sample of that track up to `MAX_VIDEO_INDEXED_SAMPLES` (1,000,000).
- If more samples exist, the file is fragmented (`moof`/`mvex`), sample tables disagree, data is encrypted (`sinf`/`encv`), a sample extends outside the file, or `av1C` cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another video track.
- If there are multiple video tracks, identify the checked track (e.g. `video track #1 (av01)`) and list each other track as `미검증 / Not checked`.
- Tied to [AOMedia AV1 Bitstream & Decoding Process Specification](https://aomediacodec.github.io/av1-spec/) (v1.0.0 with errata) for OBU syntax and AV1 Codec ISO Media File Format Binding for the `av01` sample entry and `av1C` boundary. Rules are labeled structural observations until exact normative clauses and real-byte fixtures are independently verified.

## Rules and evidence

The rule set is byte-verifiable and bounded:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| Sample and OBU framing (`av1.obu.framing`) | A sample's length-delimited OBUs fit exactly in the indexed sample extent using leb128 `obu_size`. | An oversize/truncated leb128 or OBU payload is a violation with sample and absolute byte offset. An unreadable sample is an inspection failure, never PASS. |
| OBU forbidden bit (`av1.obu.forbidden_bit`) | The 1st byte of the OBU header has bit 7 (`obu_forbidden_bit`) which must be 0 (AV1 spec 5.3.2). | Bit 7 == 1 is FAIL. Report observed header byte, track/sample/OBU index, and header byte range. |
| OBU reserved bit (`av1.obu.reserved_bit`) | The 1st byte of the OBU header has bit 0 (`obu_reserved_1bit`) which must be 0 (AV1 spec 5.3.2). | Bit 0 == 1 is FAIL. Report observed bits and 1-byte range. |
| Extension header reserved bits (`av1.obu.extension_reserved_bits`) | When `obu_extension_flag == 1`, the 2nd header byte bits 2..0 (`extension_header_reserved_3bits`) must be 0 (AV1 spec 5.3.3). | Bits 2..0 != 0 is FAIL. Report observed bits and 2-byte range. |
| Sequence Header in av1C (`av1.config.sequence_header`) | The `av1C` box includes initial presentation OBUs containing a valid Sequence Header OBU (type 1). | Missing or unparseable Sequence Header OBU in `av1C` marks codec stage `UNSUPPORTED`. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, expected and observed values, track/sample/OBU index, exact byte range when available, and bilingual KO/EN explanation. Total occurrence counts are tracked beyond the displayed/exported cap (100 findings).

## Execution and safety

- Reuse `IsoSampleTable.kt` for sample indexing, `Av1CBoxDecoder.kt`, `Av1Obu.kt`, and `Av1SequenceHeader.kt`.
- Entry point gates on `tab.type == MediaType.VIDEO` and presence of supported `av01` + `av1C`.
- Bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples/OBUs. Do not load whole samples into memory. Detect arithmetic overflow and bounds before reads.
- Report `checked / indexed samples`, current track/sample, and bounded issue count. Progress is indeterminate if the sample index is incomplete or has warnings.
- Findings publish immediately upon stage completion and update the conformance summary. Cancellation leaves already completed stages intact, marking the AV1 stage `Cancelled` or `Partial`, never PASS.
- Highlighting follows tree selection / `tab.parameterSetHighlightRange`.

## Fixtures and verification

- Generate small real `av01` MP4 fixtures with ffmpeg/libsvtav1 or libaom-av1. Record command versions, raw sample-entry bytes, `av1C` bytes, leb128 lengths, and OBU headers in `docs/validation/av1-conformance-fixtures/README.md`.
- Derive negative fixtures with Python mutation (`mutate.py`):
  - `forbidden-bit.mp4`: first OBU has bit 7 set to 1.
  - `reserved-bit.mp4`: first OBU has bit 0 set to 1.
  - `truncated-obu.mp4`: leb128 `obu_size` exceeds sample extent.
  - `malformed-av1c.mp4`: `av1C` marker bit or version corrupt.
  - `many-forbidden-headers.mp4`: 101 malformed OBUs in sample 1 for occurrence counting.
- Integration tests cover default `av01` availability, non-video exclusion, boundary coverage (fragments, encryption, multi-track), cancellation, and occurrence counting.

## Out of scope for this first runner

Full tile/transform decoding, CDF symbol decoding, Film Grain synthesis, CDEF/loop restoration filtering, all AV1 profiles (Professional/High), fragmented/encrypted MP4, other video tracks, and hardware MediaCodec execution. These remain explicitly labeled coverage gaps.
