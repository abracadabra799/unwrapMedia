# VP9 Bitstream Check in Content Check

## Goal and dependency

Add a VP9 (`vp09`) codec-bitstream rule runner to the Content Check workflow. For an ordinary MP4/MOV VP9 video, tell a Galaxy multimedia framework and codec engineer which implemented VP9 rules were checked, exactly where a violation occurred, and what remains outside coverage.

This spec depends on `2026-10-17-content-check-workflow-design.md` for lifecycle, progress, cancellation, and UI presentation, and follows the architectural model established in the HEVC, AVC, and AV1 bitstream specs. It does not claim full VP9 conformance or Android MediaCodec compatibility.

## Supported scope

- Start with MP4/MOV files whose media type is `VIDEO`, whose first video track has a `vp09` sample entry and a valid `vpcC` configuration box, and whose samples are indexed by non-fragmented `moov` sample tables.
- The runner checks every indexed sample of that track up to `MAX_VIDEO_INDEXED_SAMPLES` (1,000,000).
- If more samples exist, the file is fragmented (`moof`/`mvex`), sample tables disagree, data is encrypted (`sinf`/`encv`), a sample extends outside the file, or `vpcC` cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another video track.
- If there are multiple video tracks, identify the checked track (e.g. `video track #1 (vp09)`) and list each other track as `미검증 / Not checked`.
- Tied to [VP9 Bitstream & Decoding Process Specification](https://storage.googleapis.com/downloads.webmproject.org/docs/vp9/vp9-bitstream-specification-v0.6-20160331-draft.pdf) for uncompressed header syntax and VP9 Codec ISO Media File Format Binding v1.0 for the `vp09` sample entry and `vpcC` boundary. Rules are labeled structural observations until exact normative clauses and real-byte fixtures are independently verified.

## Rules and evidence

The rule set is byte-verifiable and bounded:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| Sample framing and Superframe (`vp9.frame.framing`) | Sample payload fits exactly as a single VP9 frame or valid VP9 Superframe (superframe index at end of sample). | Incomplete superframe index, invalid frame sizes, or unreadable sample extent is FAIL/NOT_CHECKED with sample and absolute byte offset. |
| Frame marker (`vp9.frame.marker`) | The 1st 2 bits of every VP9 frame header must be `0b10` (value 2) (`frame_marker` in VP9 spec 6.1). | `frame_marker != 2` is FAIL. Report observed bits, track/sample/frame index, and byte range. |
| Keyframe sync code (`vp9.frame.sync_code`) | When `frame_type == 0` (KEY_FRAME), the 24-bit sync code must be `0x49 0x83 0x42` (VP9 spec 6.1). | `sync_code != 0x498342` is FAIL. Report observed 3 bytes and byte range. |
| Profile 3 reserved bit (`vp9.frame.profile_reserved_bit`) | When `profile == 3`, the reserved bit `reserved_zero` must be 0 (VP9 spec 6.1). | `reserved_zero != 0` is FAIL. Report observed bit and byte range. |
| vpcC configuration box (`vp9.config.vpcc`) | `vpcC` box exists, FullBox version is 1 (or 0), valid profile (0..3), level, bitDepth (8, 10, 12), and matches keyframe parameters. | Missing, truncated, or invalid `vpcC` marks stage `UNSUPPORTED` or emits a finding. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, expected and observed values, track/sample/frame index, exact byte range when available, and bilingual KO/EN explanation. Total occurrence counts are tracked beyond the displayed/exported cap (100 findings).

## Execution and safety

- Extend `VideoCodec` with `VP9` and `SAMPLE_ENTRY_CODECS` with `vp09`. Create `VpcCBoxDecoder.kt` for structure display and validation.
- Entry point gates on `tab.type == MediaType.VIDEO` and presence of supported `vp09` + `vpcC`.
- Bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples/frames. Do not load whole samples into memory. Detect arithmetic overflow and bounds before reads.
- Report `checked / indexed samples`, current track/sample, and bounded issue count. Progress is indeterminate if the sample index is incomplete or has warnings.
- Findings publish immediately upon stage completion and update the conformance summary. Cancellation leaves already completed stages intact, marking the VP9 stage `Cancelled` or `Partial`, never PASS.
- Highlighting follows tree selection / `tab.parameterSetHighlightRange`.

## Fixtures and verification

- Generate small real `vp09` MP4 fixtures with ffmpeg/libvpx-vp9. Record command versions, raw sample-entry bytes, `vpcC` bytes, packet offsets/sizes, and frame headers in `docs/validation/vp9-conformance-fixtures/README.md`.
- Derive negative fixtures with Python mutation (`mutate.py`):
  - `bad-marker.mp4`: first frame has bits 7..6 altered (`frame_marker != 2`).
  - `bad-sync-code.mp4`: first keyframe sync code altered from `0x498342`.
  - `profile3-reserved-bit.mp4`: profile 3 frame with reserved bit set to 1.
  - `malformed-vpcc.mp4`: `vpcC` box truncated or version invalid.
  - `truncated-frame.mp4`: sample size truncated in `stsz`.
  - `many-marker-failures.mp4`: 101 malformed frame headers in sample 0 for occurrence counting.
- Integration tests cover default `vp09` availability, non-video exclusion, boundary coverage (fragments, encryption, multi-track), cancellation, and occurrence counting.

## Out of scope for this first runner

Full uncompressed header parse beyond frame size/sync (tile columns/rows, quantizer, segmentation, loop filter), boolean entropy (compressed header / probability updates), transform and residual decoding, and hardware MediaCodec execution. These remain explicitly labeled coverage gaps.
