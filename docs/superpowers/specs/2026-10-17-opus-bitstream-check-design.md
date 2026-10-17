# Opus Bitstream Check in Content Check

## Goal and dependency

Add an Opus (`Opus`) codec-bitstream rule runner to the Content Check workflow. For an MP4/MOV audio track containing an `Opus` sample entry and `dOps` (`OpusSpecificBox`), report which Opus rules were checked, exactly where a violation occurred, and what remains outside coverage.

This spec depends on `2026-10-17-content-check-workflow-design.md` for stage lifecycle, progress, cancellation, and UI presentation, and follows the architectural model established in the HEVC, AVC, AV1, VP9, APV, and AAC bitstream specs. It does not claim full psychoacoustic decoder compliance or complete speech/music synthesis conformance.

## Supported scope

- Start with MP4/MOV files whose first audio track has an `Opus` sample entry and a valid `dOps` configuration box, and whose samples are indexed by non-fragmented `moov` sample tables.
- Active primarily for `MediaType.AUDIO` (e.g. `.opus.mp4`, `.mp4` audio-only) where `CheckStage.CODEC` is enabled for audio codecs, and also applicable when checking audio tracks.
- The runner checks every indexed sample of that track up to `MAX_AUDIO_INDEXED_SAMPLES` (1,000,000).
- If more samples exist, the file is fragmented (`moof`/`mvex`), sample tables disagree, data is encrypted (`sinf`/`enca`), a sample extends outside the file, or `dOps` cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another audio track.
- If there are multiple audio tracks, identify the checked track (e.g. `audio track #1 (Opus)`) and list each other track as `미검증 / Not checked`.
- Tied to ISO/IEC 14496-12 (ISO Base Media File Format), IETF RFC 6716 (Definition of the Opus Audio Codec), and RFC 7845 / MP4RA Opus encapsulation (`OpusSpecificBox`). Rules are labeled structural observations until exact normative clauses and real-byte fixtures are independently verified.

## Rules and evidence

The rule set is byte-verifiable and bounded:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| dOps configuration (`opus.config.dops`) | `dOps` box exists, version == 0, `OutputChannelCount` in 1..255, `ChannelMappingFamily` in {0, 1, 255}; for family 0, channel count in {1, 2}; for family 1, `StreamCount > 0` and `CoupledCount <= StreamCount` (RFC 7845 §5.1.1, MP4RA `dOps`). | Missing or truncated `dOps`, version != 0, channel count == 0, family 0 channel count > 2, or invalid stream/coupled counts is FAIL. |
| Sample entry match (`opus.config.match`) | `Opus` AudioSampleEntry header `channelcount` matches `dOps.OutputChannelCount` and `samplerate` matches 48000 Hz (RFC 7845 §5.1, ISO/IEC 14496-12). | Discrepancy between box header and `dOps` parameters is FAIL. |
| Packet TOC (`opus.packet.toc`) | First byte is TOC: `config` in 0..31 (RFC 6716 Table 2); for mono stream (`OutputChannelCount == 1`), stereo flag `s` must be 0 (RFC 6716 §3.1). | Stereo flag `s == 1` for mono track is FAIL. Report observed TOC byte and byte range. |
| Packet framing (`opus.packet.framing`) | For Code 3 packets (frame count code `c == 3`), byte 1 frame count byte `M` in 1..120 (`M != 0` strictly forbidden per RFC 6716 §3.2.5), total packet duration `M * frame_duration <= 120 ms`, and padding bytes fit within sample size (RFC 6716 §3.2.5). | `M == 0`, total duration > 120 ms, or truncated padding is FAIL. Report observed bytes and byte range. |
| Sample framing (`opus.sample.framing`) | Audio sample payload fits within file / `mdat` bounds and has non-zero size (ISO/IEC 14496-12). | Truncated sample or sample extent outside file is FAIL with sample index. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, expected and observed values, track/sample index, exact byte range when available, and bilingual KO/EN explanation. Total occurrence counts are tracked beyond the displayed/exported cap (100 findings).

## Execution and safety

- Bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples. Read only bounded prefixes (e.g. up to 512 bytes per audio sample) rather than loading entire audio streams into memory. Detect arithmetic overflow and bounds before reads.
- Report `checked / indexed samples`, current track/sample, and bounded issue count. Progress is indeterminate if the sample index is incomplete or has warnings.
- Findings publish immediately upon stage completion and update the conformance summary. Cancellation leaves already completed stages intact, marking the Opus stage `Cancelled` or `Partial`, never PASS.
- Highlighting follows tree selection / `tab.parameterSetHighlightRange`.

## Fixtures and verification

- Generate minimal valid `Opus` MP4 fixture (`default-opus.mp4`) containing Opus samples with verified real Opus packets and valid `dOps` box.
- Derive negative fixtures with Python mutation (`generate.py`):
  - `bad-dops-version.mp4`: `dOps` version modified to 1 (invalid for ISO-BMFF).
  - `bad-channel-count.mp4`: `dOps` channel count modified to 0.
  - `bad-config-match.mp4`: AudioSampleEntry `channelcount` (2) differs from `dOps` `OutputChannelCount` (1).
  - `bad-mono-stereo-flag.mp4`: Mono stream sample TOC has stereo flag `s == 1`.
  - `bad-code3-zero-frames.mp4`: Code 3 packet frame count byte has `M == 0`.
  - `truncated-sample.mp4`: Audio sample size truncated in `stsz`.
  - `many-toc-failures.mp4`: 101 malformed sample headers for occurrence counting.

## Out of scope for this first runner

Full SILK/CELT LPC analysis, pitch prediction, MDCT spectral decoding, range decoder state validation, and Ogg/WebM container framing (WebM/Ogg Opus will be added in a subsequent runner). These remain explicitly labeled coverage gaps.
