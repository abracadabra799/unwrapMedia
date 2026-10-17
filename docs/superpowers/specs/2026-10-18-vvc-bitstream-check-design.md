# VVC (H.266) Bitstream Check in Content Check

## Goal and dependency

Add a VVC (`vvc1` / `vvi1`) codec-bitstream rule runner to the Content Check workflow. For an MP4/MOV video track containing a `vvc1` or `vvi1` sample entry and `vvcC` (`VvcConfigurationBox`), report which VVC rules were checked, exactly where a violation occurred, and what remains outside coverage.

This spec depends on `2026-10-17-content-check-workflow-design.md` for stage lifecycle, progress, cancellation, and UI presentation, and follows the architectural model established in the HEVC, AVC, AV1, VP9, APV, AAC, Opus, and FLAC bitstream specs. It does not claim full VVC decoding, CABAC parsing, or reconstruction conformance.

## Supported scope

- Start with MP4/MOV files whose media type is `VIDEO`, whose first video track has a `vvc1` or `vvi1` sample entry and a valid `vvcC` (`VvcConfigurationBox`), and whose samples are indexed by non-fragmented `moov` sample tables.
  - `vvc1`: NAL units in samples do not require in-band parameter sets (DCI/VPS/SPS/PPS in `vvcC`).
  - `vvi1`: NAL units in samples may require in-band parameter sets.
- The runner checks every indexed sample of that track up to `MAX_VIDEO_INDEXED_SAMPLES` (1,000,000).
- If more samples exist, the file is fragmented (`moof`/`mvex`), sample tables disagree, data is encrypted (`sinf`/`encv`), a sample extends outside the file, or `vvcC` cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another video track.
- If there are multiple video tracks, identify the checked track (e.g. `video track #1 (vvc1)`) and list each other track as `미검증 / Not checked`.
- Tied to ISO/IEC 14496-15 (§11 VVC elementary streams in the ISO base media file format) and ITU-T H.266 / ISO/IEC 23090-3 (VVC specification). Rules are labeled structural observations until exact normative clauses and real-byte fixtures are independently verified.

## Rules and evidence

The rule set is byte-verifiable and bounded:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| vvcC configuration (`vvc.config.vvcc`) | `vvcC` box exists, FullBox version == 0, flags == 0; `LengthSizeMinusOne` in 0..3 (signaling 1, 2, 3, or 4 bytes length prefix); `ptl_present_flag` structure; `num_of_arrays` valid (ISO/IEC 14496-15 §11.2). | Missing or truncated `vvcC`, non-zero version/flags, invalid length size, or truncated record is FAIL. |
| NAL framing (`vvc.nal.framing`) | A sample's length-prefixed NAL units fit exactly in the indexed sample extent using the `vvcC` length size (ISO/IEC 14496-15 §11.3). | An oversize/truncated prefix or NAL is FAIL with sample index and byte offset. Zero-length NAL or unreadable sample is an inspection failure, never PASS. |
| forbidden_zero_bit (`vvc.nal.forbidden_zero_bit`) | Bit 7 of the 2-byte NAL unit header is 0 (`forbidden_zero_bit == 0`) (ITU-T H.266 §7.3.1.2). | Non-zero `forbidden_zero_bit` is FAIL. Report observed header bytes and range. |
| reserved_zero_bit (`vvc.nal.reserved_zero_bit`) | Bit 6 of the 2-byte NAL unit header is 0 (`nuh_reserved_zero_bit == 0`) (ITU-T H.266 §7.3.1.2). | Non-zero `nuh_reserved_zero_bit` is FAIL. Report observed header bytes and range. |
| temporal_id_plus1 (`vvc.nal.temporal_id_plus1`) | Bits 2..0 of second byte of NAL unit header: `nuh_temporal_id_plus1 != 0` (0 is forbidden; TemporalId is `nuh_temporal_id_plus1 - 1`, range 0..6) (ITU-T H.266 §7.4.2.2). | `nuh_temporal_id_plus1 == 0` is FAIL. Report observed bits and range. |
| Sample framing (`vvc.sample.framing`) | Video sample payload fits within file / `mdat` bounds and has non-zero size (ISO/IEC 14496-12). | Truncated sample or sample extent outside file is FAIL with sample index. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, expected and observed values, track/sample/NAL index, exact byte range when available, and bilingual KO/EN explanation. Total occurrence counts are tracked beyond the displayed/exported cap (100 findings).

## Execution and safety

- Bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples/NALs. Read only bounded prefixes for NAL headers and check arithmetic overflow and bounds before reads.
- Report `checked / indexed samples`, current track/sample, and bounded issue count. Progress is indeterminate if the sample index is incomplete or has warnings.
- Findings publish immediately upon stage completion and update the conformance summary. Cancellation leaves already completed stages intact, marking the VVC stage `Cancelled` or `Partial`, never PASS.
- Highlighting follows tree selection / `tab.parameterSetHighlightRange`.

## Fixtures and verification

- Generate minimal valid VVC MP4 fixtures (`default-vvc.mp4`) containing VVC samples with verified real VVC NAL units (TRAIL, IDR, DCI, OPI, VPS, SPS, PPS), valid `vvcC` box, and length prefixes.
- Derive negative fixtures with Python mutation (`generate.py`):
  - `bad-vvcc-box.mp4`: `vvcC` version/flags modified or truncated record.
  - `bad-forbidden-bit.mp4`: Sample 0 NAL unit header `forbidden_zero_bit` corrupted to 1.
  - `bad-reserved-bit.mp4`: Sample 0 NAL unit header `nuh_reserved_zero_bit` corrupted to 1.
  - `bad-temporal-id.mp4`: Sample 0 NAL unit header `nuh_temporal_id_plus1` set to 0 (forbidden).
  - `truncated-sample.mp4`: Video sample size in `stsz` exceeds actual sample bytes.
  - `many-nal-failures.mp4`: 101 malformed NAL unit headers for occurrence counting.

## Out of scope for this first runner

Full VVC entropy-coded slice decoding, subpicture parsing, ALF/LMCS/LFNST/MTS transform verification, and reference picture list reconstruction. These remain explicitly labeled coverage gaps.
