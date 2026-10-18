# Content Check: check both video and audio codecs, one report type

## Problem (verified 2026-10-19 on real ffmpeg-default files)

- The CODEC stage picked one codec with a `when` whose first branch was AAC (added 2026-10-17, `2cdc348e`).
  For any MP4 with video + AAC audio — every phone/camera recording — `aacCodecAvailability` and
  `hevc/avcCodecAvailability` are both AVAILABLE, so only the AAC track was byte-checked and the video
  bitstream check never ran, while the stage still read as complete.
- All codec fixtures were single-track, so no test caught it (rule 2).
- Nine identical report types (`HevcBitstreamReport` … `FlacBitstreamReport`) and nine identical
  findings mappers forced 9-way copies in the window, snapshot JSON (9 identical blocks) and AI prompt.

User decision: check **both** the video and the audio codec.

## Design

- `parser/conformance/CodecBitstreamReport.kt`: `enum BitstreamCodec(label, isAudio)` +
  `data class CodecBitstreamReport(codec, findings, checkedSamples, indexedSamples, totalOccurrences,
  limitations, checkedTrack, complete)` + `analyzeCodecBitstream(codec, file, root, onProgress)` dispatcher.
  The nine analyzers keep their logic and return `CodecBitstreamReport`.
- `ui/CodecContentFindings.kt`: one `codecBitstreamFindings(report)` (the nine were identical).
- `codecBitstreamPlan(type, root)`: first AVAILABLE video codec (HEVC, AVC, AV1, VP9, APV, VVC), then the
  first AVAILABLE audio codec (AAC, Opus, FLAC). The stage runs them in order.
- `videoCodecUnchecked(type, plan)`: a VIDEO file with no supported video codec but a supported audio
  codec is checked audio-only and the stage shows PARTIAL with a reason (rule 7 — never a silent pass).
- `ContentCheckSnapshot.codecBitstreams: List<CodecBitstreamReport>`. Workflow JSON keeps `"codec"`
  (the first report) for older readers and adds `"codecs"` (every report, each with `"codecName"`).
  The AI prompt lists one "<codec> 바이트 검사" line per report.
- Window: one `codecBitstreams` state; overview verdict over all reports; codec tab shows one header per
  report and one combined findings table.

## Found while verifying

- `AacSampleScanner` read the SCE `ics_reserved_bit` from bit 7 (top bit of `global_gain`) instead of
  bit 15, so real mono AAC (global_gain ≥ 128) gave false FAILs. Fixed separately (`f2353e83`) with a real
  ffmpeg mono fixture.

## Fixtures

`docs/validation/content-check-codec-fixtures/generate.sh` → `app/src/test/resources/content-check-codecs/`
`hevc-aac.mp4` (hev1+mp4a), `avc-aac.mp4` (avc1+mp4a), `mpeg4-aac.mp4` (mp4v+mp4a, unsupported video).
