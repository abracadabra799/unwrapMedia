# AAC Bitstream Check in Content Check

## Goal and dependency

Add an AAC (`mp4a`) codec-bitstream rule runner to the Content Check workflow. For an MP4/MOV/M4A audio track, tell a Galaxy multimedia framework and audio engineer which implemented AAC rules were checked, exactly where a violation occurred, and what remains outside coverage.

This spec depends on `2026-10-17-content-check-workflow-design.md` for lifecycle, progress, cancellation, and UI presentation, and follows the architectural model established in the HEVC, AVC, AV1, VP9, and APV bitstream specs. It does not claim full MPEG-4 Audio compliance or complete psychoacoustic decoder conformance.

## Supported scope

- Start with MP4/MOV/M4A files whose first audio track has an `mp4a` sample entry and a valid `esds` configuration box, and whose samples are indexed by non-fragmented `moov` sample tables.
- Active primarily for `MediaType.AUDIO` (e.g. `.m4a`, audio-only MP4) where `CheckStage.CODEC` is enabled for audio codecs, and also applicable when checking audio tracks.
- The runner checks every indexed sample of that track up to `MAX_AUDIO_INDEXED_SAMPLES` (1,000,000).
- If more samples exist, the file is fragmented (`moof`/`mvex`), sample tables disagree, data is encrypted (`sinf`/`enca`), a sample extends outside the file, or `esds` cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another audio track.
- If there are multiple audio tracks, identify the checked track (e.g. `audio track #1 (mp4a)`) and list each other track as `미검증 / Not checked`.
- Tied to ISO/IEC 14496-1 (MPEG-4 Systems descriptors), ISO/IEC 14496-3 (MPEG-4 Audio / AAC syntax), and ISO/IEC 14496-14 (MP4 file format). Rules are labeled structural observations until exact normative clauses and real-byte fixtures are independently verified.

## Rules and evidence

The rule set is byte-verifiable and bounded:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| esds configuration (`aac.config.esds`) | `esds` box exists, FullBox version == 0, parses valid `ES_Descriptor` (tag 0x03) and `DecoderConfigDescriptor` (tag 0x04) with `streamType == 5` (AudioStream) (ISO/IEC 14496-1 §7.2.6). | Missing or truncated `esds`, unrecognized descriptors, or `streamType != 5` is FAIL. |
| AudioSpecificConfig (`aac.config.asc`) | `DecoderSpecificInfo` (tag 0x05) contains valid `AudioSpecificConfig` with supported AOT (1..4, 5, 29), valid sampling frequency index (0..12 or 15; 13 and 14 are reserved/invalid), and valid channel configuration (1..7) (ISO/IEC 14496-3 §1.6.2.1). | Invalid AOT, reserved sampling frequency index (13, 14), or channel config > 7 is FAIL. |
| Sample entry match (`aac.config.match`) | `mp4a` AudioSampleEntry header `channelcount` and `samplerate` match `AudioSpecificConfig` channel count and sample rate (ISO/IEC 14496-14 §5.6). | Discrepancy between box header and ASC parameters is FAIL. |
| ADTS guard (`aac.frame.adts_guard`) | Raw AAC access unit in MP4 `mp4a` must not start with 12-bit syncword `0xFFF` (ISO/IEC 14496-14 §5.6; raw blocks do not carry ADTS transport headers). | First 12 bits == `0xFFF` is FAIL. Report observed bytes and byte range. |
| Syntactic element start (`aac.frame.element`) | Raw data block begins with a valid syntactic element ID (ID_SCE = 0 for mono, ID_CPE = 1 for stereo, or ID_FIL / ID_PCE) with valid element instance tag 0..15 (ISO/IEC 14496-3 §4.4.1, §4.4.2). | Mismatched element ID or invalid tag is FAIL. Report observed bits and byte range. |
| ICS reserved bit (`aac.frame.ics_reserved`) | In `individual_channel_stream` (`ics_info`), `ics_reserved_bit` (1 bit) must be 0 (ISO/IEC 14496-3 §4.4.2.3). | `ics_reserved_bit != 0` is FAIL. Report observed bit and byte range. |
| Sample framing (`aac.sample.framing`) | Sample payload fits within file / `mdat` bounds and has non-zero size (ISO/IEC 14496-14). | Truncated sample or sample extent outside file is FAIL with sample index. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, expected and observed values, track/sample index, exact byte range when available, and bilingual KO/EN explanation. Total occurrence counts are tracked beyond the displayed/exported cap (100 findings).

## Execution and safety

- Bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples. Read only bounded prefixes (e.g. up to 512 bytes per audio sample) rather than loading entire audio streams into memory. Detect arithmetic overflow and bounds before reads.
- Report `checked / indexed samples`, current track/sample, and bounded issue count. Progress is indeterminate if the sample index is incomplete or has warnings.
- Findings publish immediately upon stage completion and update the conformance summary. Cancellation leaves already completed stages intact, marking the AAC stage `Cancelled` or `Partial`, never PASS.
- Highlighting follows tree selection / `tab.parameterSetHighlightRange`.

## Fixtures and verification

- Generate minimal valid `mp4a` MP4/M4A fixture (`default-mp4a.m4a` / `default-mp4a.mp4`) containing 8 AAC LC samples with verified real AAC raw data blocks and valid `esds` / ASC.
- Derive negative fixtures with Python mutation (`generate.py`):
  - `bad-adts-leaked.m4a`: raw sample prefix modified to contain `0xFFF` ADTS syncword.
  - `bad-asc-sampling.m4a`: `samplingFrequencyIndex` set to reserved value 13 (`0x0D`).
  - `bad-ics-reserved.m4a`: `ics_reserved_bit` altered to 1.
  - `malformed-esds.m4a`: `esds` truncated or descriptor tags corrupted.
  - `truncated-sample.m4a`: audio sample size truncated in `stsz`.
  - `many-adts-failures.m4a`: 101 malformed sample headers for occurrence counting.

## Out of scope for this first runner

Full Huffman spectral decoding, scale factor decoding, PNS (Perceptual Noise Substitution), TNS (Temporal Noise Shaping), SBR QMF filtering, and AAC ELD / USAC syntax. These remain explicitly labeled coverage gaps.
