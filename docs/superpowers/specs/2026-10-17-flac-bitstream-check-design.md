# FLAC Bitstream Check in Content Check

## Goal and dependency

Add a FLAC (`fLaC`) codec-bitstream rule runner to the Content Check workflow. For an MP4/MOV audio track containing a `fLaC` sample entry and `dfLa` (`FLACSpecificBox`), report which FLAC rules were checked, exactly where a violation occurred, and what remains outside coverage.

This spec depends on `2026-10-17-content-check-workflow-design.md` for stage lifecycle, progress, cancellation, and UI presentation, and follows the architectural model established in the HEVC, AVC, AV1, VP9, APV, AAC, and Opus bitstream specs. It does not claim full audio decoding or lossless synthesis conformance.

## Supported scope

- Start with MP4/MOV files whose first audio track has a `fLaC` sample entry and a valid `dfLa` configuration box, and whose samples are indexed by non-fragmented `moov` sample tables.
- Active primarily for `MediaType.AUDIO` (e.g. `.mp4`, `.m4a` audio-only) where `CheckStage.CODEC` is enabled for audio codecs, and also applicable when checking audio tracks.
- The runner checks every indexed sample of that track up to `MAX_AUDIO_INDEXED_SAMPLES` (1,000,000).
- If more samples exist, the file is fragmented (`moof`/`mvex`), sample tables disagree, data is encrypted (`sinf`/`enca`), a sample extends outside the file, or `dfLa` cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another audio track.
- If there are multiple audio tracks, identify the checked track (e.g. `audio track #1 (fLaC)`) and list each other track as `미검증 / Not checked`.
- Tied to ISO/IEC 14496-12 (ISO Base Media File Format), FLAC-in-ISOBMFF specification, and IETF RFC / Xiph.Org FLAC format specification. Rules are labeled structural observations until exact normative clauses and real-byte fixtures are independently verified.

## Rules and evidence

The rule set is byte-verifiable and bounded:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| dfLa configuration (`flac.config.dfla`) | `dfLa` box exists, FullBox version == 0, flags == 0; first metadata block is `STREAMINFO` (`block_type == 0`) of exactly 34 bytes; `min_blocksize >= 16`, `max_blocksize >= min_blocksize`, `sample_rate > 0`, `channels in 0..7`, `bits_per_sample in 0..31` (FLAC-in-ISOBMFF §3, FLAC format spec §5). | Missing or truncated `dfLa`, non-zero version/flags, missing `STREAMINFO`, size != 34, or out-of-range stream parameters is FAIL. |
| Sample entry match (`flac.config.match`) | `fLaC` AudioSampleEntry header `channelcount`, `samplesize`, and `samplerate` match `STREAMINFO` channels ($n+1$), bits per sample ($n+1$), and sample rate (FLAC-in-ISOBMFF §3.1). | Discrepancy between box header and `STREAMINFO` parameters is FAIL. |
| Frame header (`flac.frame.header`) | Frame begins with 14-bit syncword `0x3FFE`, reserved bits are 0, blocksize code != 0, sample rate code != 15, channel assignment <= 10, sample size code != 3 and != 7, valid UTF-8 frame/sample number, and CRC-8 matches computed CRC-8 (polynomial 0x07) (FLAC format spec §6.1). | Invalid syncword, non-zero reserved bits, forbidden codes, malformed UTF-8, or CRC-8 mismatch is FAIL. Report observed bytes and byte range. |
| Frame CRC-16 (`flac.frame.crc`) | Last 2 bytes of the audio sample contain the 16-bit CRC of the frame, matching the computed CRC-16 (polynomial 0x8005) (FLAC format spec §6.3). | CRC-16 mismatch is FAIL. Report stored and computed CRC. |
| Sample framing (`flac.sample.framing`) | Audio sample payload fits within file / `mdat` bounds and has non-zero size (ISO/IEC 14496-12). | Truncated sample or sample extent outside file is FAIL with sample index. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, expected and observed values, track/sample index, exact byte range when available, and bilingual KO/EN explanation. Total occurrence counts are tracked beyond the displayed/exported cap (100 findings).

## Execution and safety

- Bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples. Read only bounded prefixes (e.g. up to 512 bytes per audio sample) for frame headers, and bounded sample extent for CRC verification. Detect arithmetic overflow and bounds before reads.
- Report `checked / indexed samples`, current track/sample, and bounded issue count. Progress is indeterminate if the sample index is incomplete or has warnings.
- Findings publish immediately upon stage completion and update the conformance summary. Cancellation leaves already completed stages intact, marking the FLAC stage `Cancelled` or `Partial`, never PASS.
- Highlighting follows tree selection / `tab.parameterSetHighlightRange`.

## Fixtures and verification

- Generate minimal valid `fLaC` MP4 fixture (`default-flac.mp4`) containing FLAC samples with verified real FLAC frames, valid `dfLa` box with `STREAMINFO`, and valid CRC-8/CRC-16.
- Derive negative fixtures with Python mutation (`generate.py`):
  - `bad-dfla-version.mp4`: `dfLa` version modified to 1 (invalid for ISO-BMFF).
  - `bad-streaminfo-size.mp4`: `STREAMINFO` block declared size modified to 32 bytes (must be exactly 34).
  - `bad-config-match.mp4`: AudioSampleEntry `channelcount` (2) differs from `STREAMINFO` (1).
  - `bad-frame-sync.mp4`: Sample 0 frame syncword corrupted.
  - `bad-frame-crc8.mp4`: Sample 0 frame header CRC-8 modified.
  - `bad-frame-crc16.mp4`: Sample 0 frame CRC-16 corrupted.
  - `truncated-sample.mp4`: Audio sample size truncated in `stsz`.
  - `many-sync-failures.mp4`: 101 malformed sample headers for occurrence counting.

## Out of scope for this first runner

Full subframe residual Rice/Golomb decoding, LPC coefficient verification, and inter-channel decorrelation. These remain explicitly labeled coverage gaps.
