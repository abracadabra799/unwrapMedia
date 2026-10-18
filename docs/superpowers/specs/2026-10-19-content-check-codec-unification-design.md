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
- `codecCoverageGaps(type, root, plan, reports)` (from the whole-branch review): one bilingual reason per gap —
  video track without a checker, audio (`soun`) track without a checker (e.g. AC-3), a planned check that never
  produced a report (cancelled / failed between the two), partial sample coverage. Any gap turns a COMPLETED CODEC
  stage into PARTIAL with all reasons joined, makes the overview "partly unchecked", is listed on the codec tab and
  goes to the AI prompt as "코덱 바이트 검사 범위 밖" lines (rule 7 — never a silent pass).
- Each run clears the previous run's reports before checking (no duplicates on re-run); a late progress tick from
  the previous codec is dropped once the next one starts.
- `ContentCheckSnapshot.codecBitstreams: List<CodecBitstreamReport>`. Workflow JSON keeps `"codec"`
  (the first report) for older readers and adds `"codecs"` (every report, each with `"codecName"`).
  The AI prompt lists one "<codec> 바이트 검사" line per report.
- Window: one `codecBitstreams` state; overview verdict over all reports; codec tab shows one header per
  report and one combined findings table.

## Found while verifying

- `AacSampleScanner` read the SCE `ics_reserved_bit` from bit 7 (top bit of `global_gain`) instead of
  bit 15, so real mono AAC (global_gain ≥ 128) gave false FAILs. Fixed separately (`f2353e83`) with a real
  ffmpeg mono fixture.

- While there: the AAC scanner now also checks a CPE without `common_window` (reserved bit 16, after the first
  channel's `global_gain`) and LFE (SCE layout, bit 15); no false FAIL on real ffmpeg stereo / 5.1 output.

## Fixtures

`docs/validation/content-check-codec-fixtures/generate.sh` → `app/src/test/resources/content-check-codecs/`
`hevc-aac.mp4` (hev1+mp4a), `avc-aac.mp4` (avc1+mp4a), `mpeg4-aac.mp4` (mp4v+mp4a, unsupported video), `hevc-ac3.mp4` (hev1+ac-3, unsupported audio).
