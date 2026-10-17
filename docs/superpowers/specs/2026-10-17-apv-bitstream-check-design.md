# APV Bitstream Check in Content Check

## Goal and dependency

Add an APV (`apv1`) codec-bitstream rule runner to the Content Check workflow. For an ordinary MP4/MOV APV video, tell a Galaxy multimedia framework and codec engineer which implemented APV rules were checked, exactly where a violation occurred, and what remains outside coverage.

This spec depends on `2026-10-17-content-check-workflow-design.md` for lifecycle, progress, cancellation, and UI presentation, and follows the architectural model established in the HEVC, AVC, AV1, and VP9 bitstream specs. It does not claim full APV compliance or decoder conformance.

## Supported scope

- Start with MP4/MOV files whose media type is `VIDEO`, whose first video track has an `apv1` sample entry and a valid `apvC` configuration box, and whose samples are indexed by non-fragmented `moov` sample tables.
- The runner checks every indexed sample of that track up to `MAX_VIDEO_INDEXED_SAMPLES` (1,000,000).
- If more samples exist, the file is fragmented (`moof`/`mvex`), sample tables disagree, data is encrypted (`sinf`/`encv`), a sample extends outside the file, or `apvC` cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another video track.
- If there are multiple video tracks, identify the checked track (e.g. `video track #1 (apv1)`) and list each other track as `미검증 / Not checked`.
- Tied to [RFC 9924 (Advanced Professional Video)](https://datatracker.ietf.org/doc/rfc9924/) and the openapv ISO Media File Format binding for the `apv1` sample entry and `apvC` boundary. Rules are labeled structural observations until exact normative clauses and real-byte fixtures are independently verified.

## Rules and evidence

The rule set is byte-verifiable and bounded:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| Access unit signature (`apv.au.signature`) | Byte 4..7 of each access unit must be `'aPv1'` (`0x61507631`) (RFC 9924 §5.3.1). | `signature != 0x61507631` is FAIL. Report observed 4 bytes, track/sample index, and byte range. |
| Access unit PBU framing (`apv.pbu.framing`) | Sample payload parses valid PBU records with `pbu_size >= 4` and contains at least one primary frame (`pbu_type == 1`) (RFC 9924 §5.3.1, §5.3.2). | Incomplete PBU, `pbu_size` extending past sample boundary, or missing primary frame is FAIL with sample and byte offset. |
| PBU header reserved byte (`apv.pbu.reserved_zero`) | The 4th byte of `pbu_header` (byte 3) is `reserved_zero_8bits` and must be 0 (RFC 9924 §5.3.2). | `reserved_zero_8bits != 0` is FAIL. Report observed byte and byte range. |
| Primary frame profile and band (`apv.frame.profile`) | `band_idc` must be in range 0..3, and `reserved_zero_5bits` in `frame_info()` must be 0 (RFC 9924 §5.3.6). | `band_idc > 3` or `reserved_zero_5bits != 0` is FAIL. Report observed bits and byte range. |
| Primary frame reserved bytes (`apv.frame.reserved_zero`) | `reserved_zero_8bits` at end of `frame_info()` and in `frame_header()` must be 0 (RFC 9924 §5.3.6). | `reserved_zero_8bits != 0` is FAIL. Report observed byte and byte range. |
| apvC configuration box (`apv.config.apvc`) | `apvC` box exists, payload size >= 21, and profile/dimensions match primary frame. | Missing, truncated, or invalid `apvC` marks stage `UNSUPPORTED` or emits a finding. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, expected and observed values, track/sample/frame index, exact byte range when available, and bilingual KO/EN explanation. Total occurrence counts are tracked beyond the displayed/exported cap (100 findings).

## Execution and safety

- Extend `VideoCodec` with `APV` and `SAMPLE_ENTRY_CODECS` with `apv1`.
- Entry point gates on `tab.type == MediaType.VIDEO` and presence of supported `apv1` + `apvC`.
- Bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples. Read only bounded prefixes (e.g. up to 4096 bytes per sample) rather than loading entire multi-megabyte intra frames into memory. Detect arithmetic overflow and bounds before reads.
- Report `checked / indexed samples`, current track/sample, and bounded issue count. Progress is indeterminate if the sample index is incomplete or has warnings.
- Findings publish immediately upon stage completion and update the conformance summary. Cancellation leaves already completed stages intact, marking the APV stage `Cancelled` or `Partial`, never PASS.
- Highlighting follows tree selection / `tab.parameterSetHighlightRange`.

## Fixtures and verification

- Generate minimal valid `apv1` MP4 fixture (`default-apv1.mp4`) containing 8 samples with verified real APV access unit bytes from `qp_D.apv` (422-10, 3840x2160) and valid `apvC`.
- Derive negative fixtures with Python mutation (`mutate.py`):
  - `bad-signature.mp4`: byte 4..7 signature altered from `'aPv1'`.
  - `bad-pbu-reserved.mp4`: PBU header byte 3 (`reserved_zero_8bits`) altered to `0xFF`.
  - `bad-band-reserved.mp4`: `band_idc` set to 7 or `reserved_zero_5bits` set to non-zero.
  - `malformed-apvc.mp4`: `apvC` box truncated or profile invalid.
  - `truncated-sample.mp4`: sample size truncated in `stsz`.
  - `many-signature-failures.mp4`: 101 malformed access unit signatures for occurrence counting.
- Integration tests cover default `apv1` availability, non-video exclusion, boundary coverage (fragments, encryption, multi-track), cancellation, and occurrence counting.

## Out of scope for this first runner

Full entropy / coefficient decoding, transform / reconstruction, quantization matrix decoding (`use_q_matrix == 1`), non-primary/depth/preview frame decoding, and hardware MediaCodec execution. These remain explicitly labeled coverage gaps.
